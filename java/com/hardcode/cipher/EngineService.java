package com.hardcode.cipher;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.tukaani.xz.XZInputStream;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class EngineService extends Service {

    private static final String TAG               = "EngineService";
    private static final String CHANNEL_ID        = "cipher_engine";
    private static final String CHANNEL_ID_EVENTS = "cipher_events";
    private static final int    NOTIF_ID          = 1;
    private static final int    NOTIF_EVENT_ID    = 2;

    // ── Disguise ──────────────────────────────────────────────────────────────
    public enum Disguise { DEFAULT, FIREFOX, CANDYCRUSH }
    private volatile Disguise activeDisguise = Disguise.DEFAULT;

    public void setDisguise(Disguise d) {
        activeDisguise = d;
        updateNotification(disguiseStatus());
    }

    public void resetDisguise() {
        activeDisguise = Disguise.DEFAULT;
        updateNotification(disguiseStatus());
    }

    private String disguiseName() {
        switch (activeDisguise) {
            case FIREFOX:     return "Firefox";
            case CANDYCRUSH:  return "Candy Crush Saga";
            default:          return "Cipher Engine";
        }
    }

    private String disguiseStatus() {
        switch (activeDisguise) {
            case FIREFOX:     return "Firefox is active";
            case CANDYCRUSH:  return "Candy Crush Saga is active";
            default:          return "Engine running ✓";
        }
    }

    private static final String EVAL_MODEL_FILENAME  = "nn-46832cfbead3.nnue";
    private static final String EVAL_MODEL_ASSET     = "nn-46832cfbead3.nnue.xz";
    private static final long   EVAL_MODEL_MIN_BYTES = 45L * 1024 * 1024; // 45 MB

    // ── Log buffer ────────────────────────────────────────────────────────────

    private static final int MAX_LOG_BUFFER = 500;
    private final List<String> logBuffer = new ArrayList<>();

    private void bufferLog(String raw) {
        String ts   = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        String line = "[" + ts + "] " + raw;
        synchronized (logBuffer) { logBuffer.add(line); }
        Log.i(TAG, raw);
        mainHandler.post(() -> { if (listener != null) listener.onStatus(line); });
    }

    // ── Binder / listener ─────────────────────────────────────────────────────

    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        public EngineService getService() { return EngineService.this; }
    }

    public interface StatusListener {
        void onStatus(String message);
        void onStatusReplace(String message);
    }

    public void setStatusListener(StatusListener l) {
        listener = l;
        if (l != null) {
            synchronized (logBuffer) {
                for (String line : logBuffer) {
                    l.onStatus(line);
                }
            }
        }
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    // ── State ─────────────────────────────────────────────────────────────────

    private final ExecutorService          ioThread    = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scheduler   = Executors.newSingleThreadScheduledExecutor();
    private final Handler                  mainHandler = new Handler(Looper.getMainLooper());

    private ChessWebSocketServer server;
    private StatusListener       listener;

    private ScheduledFuture<?> dotFuture;
    private int                dotCount     = 0;
    private boolean            firstDotPost = true;

    // ── Public query ──────────────────────────────────────────────────────────

    public List<String> getLogSnapshot() {
        synchronized (logBuffer) { return new ArrayList<>(logBuffer); }
    }

    public void clearLog() {
        synchronized (logBuffer) { logBuffer.clear(); }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            boolean granted = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
            if (!granted) Log.w(TAG, "POST_NOTIFICATIONS not granted");
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ECLAIR) {
            startForeground(NOTIF_ID, buildNotification("Starting…"));
        }

        ioThread.submit(() -> {
            if (server != null) return; // already running — don't tear it down
            shutdownEngine();
            clearLog();
            setupAndStart();
        });

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopDots();
        scheduler.shutdownNow();
        shutdownEngine();
        ioThread.shutdownNow();
    }

    // ── Port cleanup ──────────────────────────────────────────────────────────

    private static final int ENGINE_PORT    = 8765;
    private static final int PORT_WAIT_MS   = 300;
    private static final int PORT_MAX_TRIES = 17;

    private boolean isPortInUse() {
        try (ServerSocket ss = new ServerSocket(ENGINE_PORT)) {
            ss.setReuseAddress(true);
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    private void waitForPortFree() {
        if (!isPortInUse()) return;
        Log.d(TAG, "Port " + ENGINE_PORT + " still occupied — waiting for OS to release…");
        for (int i = 0; i < PORT_MAX_TRIES; i++) {
            try { Thread.sleep(PORT_WAIT_MS); } catch (InterruptedException ignored) {}
            if (!isPortInUse()) {
                Log.d(TAG, "Port " + ENGINE_PORT + " free after " + ((i + 1) * PORT_WAIT_MS) + "ms");
                return;
            }
        }
        Log.w(TAG, "Port " + ENGINE_PORT + " still busy after 5s — attempting to start anyway");
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private void setupAndStart() {
        try {
            String enginePath = getApplicationInfo().nativeLibraryDir + "/libcipher.so";
            File   engineFile = new File(enginePath);

            if (!engineFile.exists()) {
                throw new IOException("Engine binary not found: " + enginePath);
            }

            File execDir       = getFilesDir();
            File evalModelFile = new File(execDir, EVAL_MODEL_FILENAME);

            if (evalModelFile.exists() && evalModelFile.length() < EVAL_MODEL_MIN_BYTES) {
                evalModelFile.delete();
            }

            if (!evalModelFile.exists()) {
                startDots();
                extractEvalModel(evalModelFile);
                stopDots();
                if (evalModelFile.length() < EVAL_MODEL_MIN_BYTES) {
                    evalModelFile.delete();
                    throw new IOException("Extracted eval model too small — asset may be corrupt.");
                }
            }

            // ── Read device data ──────────────────────────────────────────────
            String androidId  = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            String deviceData = "MODEL=" + Build.MODEL
                    + "|MANUFACTURER=" + Build.MANUFACTURER
                    + "|ANDROID_ID=" + (androidId != null ? androidId : "unknown");

            bufferLog("⏳ Starting…");
            waitForPortFree();

            server = new ChessWebSocketServer(
                    engineFile,
                    evalModelFile.getAbsolutePath(),
                    getFilesDir());

            server.setDeviceData(deviceData);

            server.setServerEventListener(new ChessWebSocketServer.ServerEventListener() {

                // ── Basic engine events ───────────────────────────────────────

                @Override
                public void onClientConnected() {
                    updateNotification("Cipher connected ✓");
                    bufferLog("✔ Client connected to engine");
                }

                @Override
                public void onMoveReceived(String moves) {
                    postEventNotification("♟ Move received", moves);
                    Log.d(TAG, "♟ Move received: " + moves);
                }

                @Override
                public void onBestMoveReturned(String bestMove) {
                    updateEventNotification("★ Best move", bestMove);
                    Log.d(TAG, "★ Best move: " + bestMove);
                }

                @Override
                public void onLog(String message) {
                    bufferLog(message);
                }

                @Override
                public void onNotPaid() {
                    resetDisguise();
                    mainHandler.post(() -> {
                        if (listener != null) listener.onStatus("notpaid");
                    });
                }

                // ── NNUE download events ──────────────────────────────────────

                @Override
                public void onNnueDownloadStart(String filename) {
                    startDownloadDots(filename);
                }

                @Override
                public void onNnueDownloadRetry(String filename, int attempt, int max) {
                    stopDots();
                    bufferLog("↺ Retrying (" + attempt + "/" + max + ")…");
                    startDownloadDots(filename);
                }

                @Override
                public void onNnueDownloadDone(String filename) {
                    stopDots();
                    bufferLog("✔ Ready");
                    mainHandler.post(() -> {
                        if (listener != null) listener.onStatus("✔ Ready");
                    });
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (listener != null) listener.onStatus("");
                    }, 2000);
                }

                @Override
                public void onNnueDownloadError(String filename, String message) {
                    stopDots();
                    bufferLog("✗ Download failed: " + message);
                }
            });

            server.start();

            bufferLog("✔ Running on ws://127.0.0.1:8765");
            updateNotification(disguiseStatus());

        } catch (Exception e) {
            stopDots();
            Log.e(TAG, "Setup failed", e);
            bufferLog("Error: " + e.getMessage());
            updateNotification("Error — see app");
        }
    }

    // ── Eval model extraction ─────────────────────────────────────────────────

    private void extractEvalModel(File dest) throws IOException {
        try (InputStream     raw = getAssets().open(EVAL_MODEL_ASSET);
             XZInputStream   xz  = new XZInputStream(raw);
             FileOutputStream fos = new FileOutputStream(dest)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = xz.read(buf)) != -1) {
                fos.write(buf, 0, n);
            }
        }
    }

    // ── Engine shutdown ───────────────────────────────────────────────────────

    private void shutdownEngine() {
        if (server != null) {
            server.shutdown();
            server = null;
        }
    }

    // ── Animated dots — bundled NNUE extraction ───────────────────────────────

    private void startDots() {
        stopDots();
        dotCount     = 0;
        firstDotPost = true;
        dotFuture = scheduler.scheduleAtFixedRate(() -> {
            dotCount = (dotCount % 3) + 1;
            String msg = "Initializing" + repeatChar('.', dotCount);
            mainHandler.post(() -> {
                if (listener == null) return;
                if (firstDotPost) {
                    listener.onStatus(msg);
                    firstDotPost = false;
                } else {
                    listener.onStatusReplace(msg);
                }
            });
        }, 0, 700, TimeUnit.MILLISECONDS);
    }

    // ── Animated dots — variant NNUE download ────────────────────────────────

    private void startDownloadDots(String filename) {
        stopDots();
        dotCount     = 0;
        firstDotPost = true;
        String prefix = "Updating";
        dotFuture = scheduler.scheduleAtFixedRate(() -> {
            int phase = dotCount % 4;
            int dots  = (phase <= 2) ? (phase + 1) : (5 - phase);
            dotCount++;
            String msg = prefix + repeatChar('.', dots);
            mainHandler.post(() -> {
                if (listener == null) return;
                if (firstDotPost) {
                    listener.onStatus(msg);
                    firstDotPost = false;
                } else {
                    listener.onStatusReplace(msg);
                }
            });
        }, 0, 500, TimeUnit.MILLISECONDS);
    }

    private void stopDots() {
        if (dotFuture != null && !dotFuture.isCancelled()) {
            dotFuture.cancel(false);
            dotFuture = null;
        }
        firstDotPost = true;
    }

    private static String repeatChar(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Cipher Engine", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Fairy-Stockfish WebSocket server");
            nm.createNotificationChannel(ch);

            NotificationChannel evCh = new NotificationChannel(
                    CHANNEL_ID_EVENTS, "Cipher Events", NotificationManager.IMPORTANCE_DEFAULT);
            evCh.setDescription("Move received and best move notifications");
            evCh.setSound(null, null);
            nm.createNotificationChannel(evCh);
        }
    }

    private void postEventNotification(String title, String text) {
        Intent tap = new Intent(this, MainActivity.class);
        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(this, 0, tap, piFlags);
        android.app.Notification notif = new NotificationCompat.Builder(this, CHANNEL_ID_EVENTS)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .build();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIF_EVENT_ID, notif);
    }

    private void updateEventNotification(String title, String text) {
        Intent tap = new Intent(this, MainActivity.class);
        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(this, 0, tap, piFlags);
        android.app.Notification notif = new NotificationCompat.Builder(this, CHANNEL_ID_EVENTS)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIF_EVENT_ID, notif);
    }

    private Notification buildNotification(String text) {
        Intent tap = new Intent(this, MainActivity.class);
        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(this, 0, tap, piFlags);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(disguiseName())
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIF_ID, buildNotification(text));
    }
}