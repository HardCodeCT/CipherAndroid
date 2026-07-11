package com.hardcode.cipher;

import android.content.Context;

public class SecurityCheck {

    static {
        System.loadLibrary("cipher_security");
    }

    public static native boolean nativeIsSafe(Context ctx);

    public static boolean isSafe(Context ctx) {
        return nativeIsSafe(ctx);
    }
}