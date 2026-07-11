package com.hardcode.cipher;

import android.os.Build;
import android.util.Log;
import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshakeBuilder;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ChessWebSocketServer extends WebSocketServer {

    private static final String TAG              = "ChessWSS";
    private static final int    DEFAULT_MOVETIME = 100;

    // The standard bundled NNUE — never downloaded, always extracted from assets
    private static final String BUNDLED_NNUE     = "nn-46832cfbead3.nnue";
    private static final long   MIN_NNUE_BYTES   = 1024 * 1024L; // 1 MB sanity floor

    private final File         engineBinary;
    private final File         engineDir;

    // Where variant NNUEs are persisted (getFilesDir(), NOT getCacheDir())
    private final File         nnueStorageDir;

    private Process            engineProcess;
    private BufferedWriter     engineIn;
    private BufferedReader     engineOut;

    private String currentVariant  = "standard";
    private String currentEvalModel;

    // ── Device data to send to client ────────────────────────────────────────
    private String deviceData = "";

    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();

    // ── Event callbacks to EngineService ─────────────────────────────────────

    public interface ServerEventListener {
        void onClientConnected();
        void onMoveReceived(String moves);
        void onBestMoveReturned(String bestMove);
        void onLog(String message);
        void onNotPaid();
        // ── NNUE download events (for UI animation in EngineService) ─────────
        void onNnueDownloadStart(String filename);
        void onNnueDownloadRetry(String filename, int attempt, int max);
        void onNnueDownloadDone(String filename);
        void onNnueDownloadError(String filename, String message);
    }

    private ServerEventListener eventListener;

    public void setServerEventListener(ServerEventListener l) { eventListener = l; }

    public void setDeviceData(String data) { this.deviceData = data; }

    private void uiLog(String msg) {
        Log.i(TAG, msg);
        if (eventListener != null) eventListener.onLog(msg);
    }

    // ── Constructor ───────────────────────────────────────────────────────────

    public ChessWebSocketServer(File engineBinary, String evalModelFullPath, File nnueStorageDir) {
        super(new InetSocketAddress("127.0.0.1", 8765));
        this.engineBinary    = engineBinary;
        this.engineDir       = engineBinary.getParentFile();
        this.currentEvalModel = evalModelFullPath;
        this.nnueStorageDir  = nnueStorageDir;
        setReuseAddr(true);
        setConnectionLostTimeout(60);
    }

    @Override
    public void onStart() {
        Log.d(TAG, "[Server] Listening on ws://127.0.0.1:8765");
        startEngine();
    }

    @Override
    public ServerHandshakeBuilder onWebsocketHandshakeReceivedAsServer(
            WebSocket conn, Draft d, ClientHandshake request) throws InvalidDataException {
        ServerHandshakeBuilder builder = super.onWebsocketHandshakeReceivedAsServer(conn, d, request);
        builder.put("Access-Control-Allow-Origin",          "*");
        builder.put("Access-Control-Allow-Private-Network", "true");
        return builder;
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        Log.d(TAG, "[Server] Client connected: " + conn.getRemoteSocketAddress());
        if (eventListener != null) eventListener.onClientConnected();

        // ── Send device data immediately ────────────────────────────────────
        if (!deviceData.isEmpty()) {
            try {
                JSONObject devMsg = new JSONObject();
                devMsg.put("type", "devicedata");
                devMsg.put("data", deviceData);
                conn.send(devMsg.toString());
                Log.d(TAG, "[Server] Sent device data");
            } catch (JSONException e) {
                Log.e(TAG, "[Server] Failed to send device data: " + e.getMessage());
            }
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        Log.d(TAG, "[Server] Client disconnected (code=" + code + " reason=" + reason + " remote=" + remote + ")");
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        Log.e(TAG, "[Server] WebSocket error", ex);
    }

    @Override
    public void onMessage(WebSocket conn, String raw) {
        try {
            JSONObject msg  = new JSONObject(raw);
            String     kind = msg.optString("type", "");

            switch (kind) {

                // ── Prefetch / verify a variant NNUE without running analysis ─
                // Mirrors C++ "ensure_nnue" handler in HandleClient().
                // Sent by the extension when the user selects a variant so the
                // file is ready by the time the first analyze request arrives.
                case "ensure_nnue": {
                    String nnueFilename = msg.optString("nnue",    "");
                    String gdriveId     = msg.optString("gdrive",  "");
                    if (!nnueFilename.isEmpty() && !gdriveId.isEmpty()) {
                        Log.d(TAG, "[ensure_nnue] Prefetching " + nnueFilename);
                        analysisExecutor.submit(() -> ensureNNUE(conn, nnueFilename, gdriveId));
                    }
                    break;
                }

                case "configure": {
                    String variant      = msg.optString("variant", currentVariant);
                    String nnueFilename = msg.optString("nnue",    "");
                    String gdriveId     = msg.optString("gdrive",  "");
                    Log.d(TAG, "[configure] variant=" + variant + " nnue=" + nnueFilename);
                    analysisExecutor.submit(() -> {
                        String evalModel = resolveEvalModel(conn, nnueFilename, gdriveId);
                        Log.d(TAG, "[configure] eval_model=" + evalModel);
                        reconfigure(variant, evalModel);
                    });
                    break;
                }

                case "analyze": {
                    String variant      = msg.optString("variant", currentVariant);
                    String nnueFilename = msg.optString("nnue",    "");
                    String gdriveId     = msg.optString("gdrive",  "");
                    final int requestedMovetime = msg.optInt("movetime", DEFAULT_MOVETIME);
                    final int elo          = msg.optInt("elo", 2200);   // no clamp here — keep full range 500–3400
                    List<String> moves  = parseMoves(msg);

                    String lastMove = moves.isEmpty() ? "(none)" : moves.get(moves.size() - 1);
                    Log.d(TAG, "[analyze] Received — moves=" + moves.size()
                            + " lastMove=" + lastMove + " movetime=" + requestedMovetime + "ms elo=" + elo);

                    if (eventListener != null && !moves.isEmpty()) {
                        eventListener.onMoveReceived(lastMove + " (" + moves.size() + " moves)");
                    }

                    analysisExecutor.submit(() -> {
                        try {
                            // Resolve eval model — may trigger NNUE download
                            // for variant files not yet on disk.
                            String evalModel;
                            if (!nnueFilename.isEmpty() && !gdriveId.isEmpty()) {
                                if (!ensureNNUE(conn, nnueFilename, gdriveId)) {
                                    Log.d(TAG, "[analyze] ✗ NNUE not available — skipping analysis");
                                    return;
                                }
                                evalModel = new File(nnueStorageDir, nnueFilename).getAbsolutePath();
                            } else {
                                evalModel = resolveEvalModel(conn, nnueFilename, gdriveId);
                            }
                            Log.d(TAG, "[analyze] Using eval_model=" + evalModel);

                            Log.d(TAG, "[analyze] Reconfiguring engine if needed...");
                            reconfigure(variant, evalModel);

                            // ── NEW: dual-axis Elo / movetime scaling ──────────
                            final int searchMovetime;
                            if (elo <= 2850) {
                                // Strength-limited mode: use UCI_Elo handicap, fixed fast movetime
                                send("setoption name UCI_LimitStrength value true");
                                send("setoption name UCI_Elo value " + elo);
                                searchMovetime = 1000;   // constant quick search for all handicap levels
                            } else {
                                // Full strength mode: disable limit, scale movetime with rating
                                send("setoption name UCI_LimitStrength value false");
                                // UCI_Elo is irrelevant when LimitStrength is false
                                final int minTime = 500;
                                final int maxTime = 3000;
                                searchMovetime = minTime + (int)((elo - 2850) * (maxTime - minTime) / (3400.0 - 2850.0));
                            }

                            Log.d(TAG, "[analyze] Calling bestMove() — movetime=" + searchMovetime + "ms");
                            String[] best = bestMove(moves, searchMovetime);

                            if (best == null) {
                                Log.d(TAG, "[analyze] ✗ Engine returned no move (null)");
                                sendError(conn, "Engine returned no move");
                                return;
                            }

                            Log.d(TAG, "[analyze] ✓ Best move = " + best[0] + best[1]);

                            JSONObject reply = new JSONObject();
                            reply.put("type", "bestmove");
                            reply.put("from", best[0]);
                            reply.put("to",   best[1]);
                            reply.put("move", best[0] + best[1]);

                            if (conn.isOpen()) {
                                conn.send(reply.toString());
                                Log.d(TAG, "[analyze] Reply sent to client ✓");
                            } else {
                                Log.d(TAG, "[analyze] ✗ Connection closed before reply — best move was: "
                                        + best[0] + best[1]);
                            }

                            if (eventListener != null) {
                                String display = best[0].toUpperCase() + " → " + best[1].toUpperCase();
                                eventListener.onBestMoveReturned(display);
                            }

                        } catch (Exception e) {
                            Log.e(TAG, "Analysis error", e);
                            sendError(conn, e.getMessage());
                        }
                    });
                    break;
                }

                case "ping": {
                    try {
                        JSONObject pong = new JSONObject();
                        pong.put("type", "pong");
                        if (conn.isOpen()) conn.send(pong.toString());
                    } catch (JSONException ignored) {}
                    break;
                }

                case "notpaid": {
                    Log.d(TAG, "[Server] notpaid received — reverting disguise");
                    if (eventListener != null) eventListener.onNotPaid();
                    break;
                }

                default:
                    Log.d(TAG, "[Server] Unknown message type: " + kind);
            }

        } catch (JSONException e) {
            Log.w(TAG, "[Server] Bad JSON from client: " + raw);
        }
    }

    // ── Resolve eval model path ───────────────────────────────────────────────
    // - Full path (starts with '/'):  use as-is (backward compat)
    // - Filename + gdrive:            handled by caller via ensureNNUE
    // - Empty or unrecognized:        keep currentEvalModel
    private String resolveEvalModel(WebSocket conn, String nnueFilename, String gdriveId) {
        if (!nnueFilename.isEmpty() && !gdriveId.isEmpty()) {
            // Caller should have used ensureNNUE first; resolve to storage path
            return new File(nnueStorageDir, nnueFilename).getAbsolutePath();
        }
        if (nnueFilename.startsWith("/")) {
            return nnueFilename;
        }
        return currentEvalModel;
    }

    // ── Ensure a variant NNUE is present, downloading if necessary ────────────
    // Mirrors C++ EnsureNNUE().
    // Returns true if the file is ready to use, false if it could not be obtained.
    // Sends nnue_download_* JSON messages to the WebSocket client so the Chrome
    // extension can show progress in its own UI (same protocol as Windows).
    // Also fires ServerEventListener callbacks so EngineService can animate dots.
    private boolean ensureNNUE(WebSocket conn, String nnueFilename, String gdriveId) {
        // The standard bundled NNUE is always extracted from assets — never download it
        if (BUNDLED_NNUE.equals(nnueFilename)) return true;
        if (nnueFilename.isEmpty() || gdriveId.isEmpty()) return true;

        File destFile = new File(nnueStorageDir, nnueFilename);

        // Already present and large enough — notify client and return
        if (destFile.exists() && destFile.length() >= MIN_NNUE_BYTES) {
            // ── FIX: send done message so UI clears any pending download status ─
            if (eventListener != null) eventListener.onNnueDownloadDone(nnueFilename);
            wsSend(conn, nnueJson("nnue_download_done", nnueFilename, null, 0, 0, null));
            return true;
        }

        // ── Notify: download starting ─────────────────────────────────────────
        if (eventListener != null) eventListener.onNnueDownloadStart(nnueFilename);
        wsSend(conn, nnueJson("nnue_download_start", nnueFilename, null, 0, 0, null));

        final int    MAX_ATTEMPTS      = 3;
        final long[] RETRY_DELAYS_MS   = { 0L, 5_000L, 15_000L };

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {

            if (RETRY_DELAYS_MS[attempt - 1] > 0) {
                // Notify: retrying
                if (eventListener != null)
                    eventListener.onNnueDownloadRetry(nnueFilename, attempt, MAX_ATTEMPTS);
                wsSend(conn, nnueJson("nnue_download_retry", nnueFilename, null,
                        attempt, MAX_ATTEMPTS, null));
                try {
                    Thread.sleep(RETRY_DELAYS_MS[attempt - 1]);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    // Service is shutting down — clean up and bail
                    if (destFile.exists()) destFile.delete();
                    return false;
                }
            }

            Log.d(TAG, "[NNUE] Download attempt " + attempt + "/" + MAX_ATTEMPTS
                    + ": " + nnueFilename);

            try {
                if (downloadNNUE(nnueFilename, gdriveId)) {
                    // ── Notify: done ─────────────────────────────────────────
                    if (eventListener != null)
                        eventListener.onNnueDownloadDone(nnueFilename);
                    wsSend(conn, nnueJson("nnue_download_done", nnueFilename,
                            null, 0, 0, null));
                    return true;
                }
            } catch (IOException e) {
                Log.w(TAG, "[NNUE] Attempt " + attempt + " threw: " + e.getMessage());
            }

            // Clean up any partial file before retrying
            if (destFile.exists()) destFile.delete();
        }

        // ── Notify: all attempts failed ───────────────────────────────────────
        String errorMsg = "Could not download " + nnueFilename + " after "
                + MAX_ATTEMPTS + " attempts. Check your network connection. "
                + "The engine will retry next time you select this variant.";
        if (eventListener != null)
            eventListener.onNnueDownloadError(nnueFilename, errorMsg);
        wsSend(conn, nnueJson("nnue_download_error", nnueFilename, errorMsg, 0, 0, null));
        return false;
    }

    // ── Download a single NNUE file from Google Drive ─────────────────────────
    // Mirrors C++ DownloadNNUE().
    // Uses HttpURLConnection with manual redirect following so cross-host
    // HTTPS redirects (drive.google.com → googleusercontent.com) are handled.
    // Returns true iff the file is on disk and > MIN_NNUE_BYTES.
    private boolean downloadNNUE(String filename, String gdriveId) throws IOException {
        File destFile = new File(nnueStorageDir, filename);
        Log.d(TAG, "[NNUE] Downloading " + filename + " from Google Drive…");

        // Google Drive direct-download URL — &confirm=t bypasses the virus-scan
        // warning page that Google shows for large files.
        URL url = new URL("https://drive.google.com/uc?id=" + gdriveId
                + "&export=download&confirm=t");

        // Follow redirects manually: Android's HttpURLConnection follows
        // same-host redirects automatically but may not follow cross-host ones.
        for (int redirects = 0; redirects < 10; redirects++) {

            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(false); // we handle manually
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(60_000);
            conn.setRequestProperty("User-Agent", "CipherEngine/2.0");

            int code;
            try {
                code = conn.getResponseCode();
            } catch (IOException e) {
                conn.disconnect();
                throw e;
            }

            // Redirect — follow it
            if (code == 301 || code == 302 || code == 303
                    || code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null || location.isEmpty()) {
                    Log.w(TAG, "[NNUE] Redirect with no Location header");
                    return false;
                }
                url = new URL(location);
                continue;
            }

            if (code != 200) {
                conn.disconnect();
                Log.w(TAG, "[NNUE] HTTP " + code + " — download failed");
                return false;
            }

            // Stream response body to disk
            try (InputStream in = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(destFile)) {
                byte[] buf = new byte[65_536];
                long   total = 0;
                int    n;
                while ((n = in.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                    total += n;
                }
                Log.d(TAG, "[NNUE] Received " + total + " bytes → " + filename);
            } finally {
                conn.disconnect();
            }

            long size = destFile.length();
            if (size < MIN_NNUE_BYTES) {
                Log.w(TAG, "[NNUE] File too small (" + size + " bytes) — discarding");
                destFile.delete();
                return false;
            }

            return true;
        }

        Log.w(TAG, "[NNUE] Too many redirects");
        return false;
    }

    // ── JSON helpers for NNUE status messages ─────────────────────────────────

    private static String nnueJson(String type, String nnue, String message,
                                   int attempt, int of, String unused) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", type);
            o.put("nnue", nnue);
            if (message != null) o.put("message", message);
            if (attempt > 0)     o.put("attempt", attempt);
            if (of > 0)          o.put("of", of);
            return o.toString();
        } catch (JSONException e) {
            return "{\"type\":\"" + type + "\"}";
        }
    }

    private static void wsSend(WebSocket conn, String json) {
        try {
            if (conn != null && conn.isOpen()) conn.send(json);
        } catch (Exception ignored) {}
    }

    // ──────────── Engine control methods (with ucinewgame fixes) ─────────────

    private synchronized void startEngine() {
        try {
            Log.d(TAG, "[Engine] Starting binary: " + engineBinary.getAbsolutePath());

            ProcessBuilder pb = new ProcessBuilder(engineBinary.getAbsolutePath());
            pb.directory(engineDir);
            pb.redirectErrorStream(false);

            engineProcess = pb.start();
            engineIn  = new BufferedWriter(new OutputStreamWriter(engineProcess.getOutputStream()));
            engineOut = new BufferedReader(new InputStreamReader(engineProcess.getInputStream()));

            drainStderr();
            configure();

            uiLog("✔ Engine ready");

        } catch (IOException e) {
            uiLog("[Engine] ✗ Failed to start: " + e.getMessage());
            Log.e(TAG, "[Engine] Failed to start", e);
        }
    }

    private void restartEngine() {
        try {
            if (engineProcess != null) {
                try { if (engineIn != null) engineIn.close(); } catch (IOException ignored) {}
                engineProcess.destroy();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    engineProcess.waitFor(3, TimeUnit.SECONDS);
                }
                engineProcess = null;
                engineIn      = null;
                engineOut     = null;
            }
        } catch (Exception ignored) {}
        startEngine();
    }

    private boolean isAlive() {
        if (engineProcess == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return engineProcess.isAlive();
        }
        try {
            engineProcess.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    private void configure() throws IOException {
        Log.d(TAG, "[Engine] Sending UCI handshake...");
        send("uci");
        awaitToken("uciok");
        Log.d(TAG, "[Engine] uciok received");
        applyOptions();
        send("isready");
        awaitToken("readyok");
        // ── FIX: ensure a fresh game state after startup ─────────────────────
        send("ucinewgame");
        Log.d(TAG, "[Engine] readyok received — engine is ready");
    }

    /**
     * Compute the optimal UCI Threads count for this device.
     *
     * Strategy (mirrors the C++ PoolConfig logic, single-engine edition):
     *   - Android is memory- and thermal-constrained, so we cap at 4 threads
     *     (Fairy-SF gains almost nothing past that on mobile hardware).
     *   - We leave at least half the cores free for the OS, UI, and WebSocket
     *     threads, then clamp the result to [1, 4].
     *
     *   cores  →  threads
     *     1    →  1
     *     2    →  1
     *     3-4  →  2
     *     5-6  →  3
     *     7+   →  4
     */
    private static int computeEngineThreads() {
        int cores = Runtime.getRuntime().availableProcessors();
        // Give half to the engine, keep the rest for the system.
        int threads = Math.max(1, cores / 2);
        // Hard cap — Fairy-SF on mobile rarely benefits past 4, and thermal
        // throttling kicks in quickly when all cores are pegged.
        return Math.min(threads, 4);
    }

    private void applyOptions() throws IOException {
        int threads = computeEngineThreads();
        send("setoption name Use NNUE value true");
        send("setoption name EvalFile value " + currentEvalModel);
        send("setoption name UCI_Variant value " + currentVariant);
        send("setoption name Threads value " + threads);
        send("setoption name UCI_LimitStrength value true");
        send("setoption name UCI_Elo value 2200");
        Log.d(TAG, "[Engine] Options applied: variant=" + currentVariant
                + " eval_model=" + currentEvalModel
                + " threads=" + threads
                + " (device cores=" + Runtime.getRuntime().availableProcessors() + ")");
    }

    private synchronized void reconfigure(String variant, String evalModel) {
        if (variant.equals(currentVariant) && evalModel.equals(currentEvalModel)) return;

        Log.d(TAG, "[Engine] Variant change: " + currentVariant + " → " + variant
                + " | NNUE: " + currentEvalModel + " → " + evalModel);

        currentVariant   = variant;
        currentEvalModel = evalModel;

        restartEngine();
    }

    private void send(String cmd) throws IOException {
        if (!isAlive()) throw new IOException("Engine process is dead");
        engineIn.write(cmd + "\n");
        engineIn.flush();
    }

    private String readLine() throws IOException {
        if (!isAlive()) throw new IOException("Engine process is dead");
        String line = engineOut.readLine();
        if (line == null) throw new IOException("Engine stdout closed unexpectedly");
        return line.trim();
    }

    private void awaitToken(String token) throws IOException {
        while (true) {
            String line = readLine();
            if (line.equals(token)) return;
        }
    }

    private String[] bestMove(List<String> moves, int movetime) throws IOException {
        if (!isAlive()) {
            Log.d(TAG, "[bestMove] Engine was dead — restarting before analysis");
            restartEngine();
        }

        // ── FIX: always start a fresh game before setting the position ────────
        send("ucinewgame");

        String movesStr = moves.isEmpty() ? "" : String.join(" ", moves);
        Log.d(TAG, "[bestMove] Sending position: startpos moves " + (movesStr.isEmpty() ? "(none)" : movesStr));
        send("position startpos moves " + movesStr);

        Log.d(TAG, "[bestMove] Sending: go movetime " + movetime);
        send("go movetime " + movetime);

        while (true) {
            String line = readLine();
            if (line.startsWith("bestmove")) {
                String[] parts = line.split("\\s+");
                if (parts.length < 2 || parts[1].equals("(none)")) {
                    Log.d(TAG, "[bestMove] Engine said (none) — no legal moves in this position");
                    return null;
                }
                String move = parts[1];
                Log.d(TAG, "[bestMove] Engine returned: " + move);
                return new String[]{ move.substring(0, 2), move.substring(2, 4) };
            }
        }
    }

    private static List<String> parseMoves(JSONObject msg) {
        List<String> result = new ArrayList<>();
        try {
            org.json.JSONArray arr = msg.optJSONArray("moves");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) result.add(arr.getString(i));
            }
        } catch (JSONException ignored) {}
        return result;
    }

    private static void sendError(WebSocket conn, String message) {
        try {
            JSONObject err = new JSONObject();
            err.put("type", "error");
            err.put("message", message == null ? "unknown error" : message);
            if (conn.isOpen()) conn.send(err.toString());
        } catch (JSONException ignored) {}
    }

    private void drainStderr() {
        BufferedReader stderr = new BufferedReader(
                new InputStreamReader(engineProcess.getErrorStream()));
        Thread t = new Thread(() -> {
            try {
                String line;
                while ((line = stderr.readLine()) != null) {
                    Log.d(TAG, "[Engine stderr] " + line);
                }
            } catch (IOException ignored) {}
        });
        t.setDaemon(true);
        t.setName("engine-stderr");
        t.start();
    }

    public void shutdown() {
        Log.d(TAG, "[Server] Shutting down...");
        analysisExecutor.shutdownNow();
        try {
            if (isAlive()) {
                try { send("quit"); } catch (IOException ignored) {}
                engineProcess.destroy();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    engineProcess.waitFor(3, TimeUnit.SECONDS);
                }
            }
        } catch (InterruptedException ignored) {}
        try { stop(); } catch (Exception ignored) {}
        Log.d(TAG, "[Server] Stopped.");
    }
}