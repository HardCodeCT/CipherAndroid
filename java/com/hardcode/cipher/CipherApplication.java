package com.hardcode.cipher;

import android.app.Application;
import android.os.Process;
import android.widget.Toast;

public class CipherApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        /*if (!IntegrityCheck.isValid(this)) {
            Toast.makeText(this, "Integrity check failed", Toast.LENGTH_LONG).show();
            Process.killProcess(Process.myPid());
            return;
        }*/

        // your existing CipherApplication init code continues here...
    }
}