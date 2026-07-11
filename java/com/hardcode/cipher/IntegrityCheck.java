package com.hardcode.cipher;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import java.security.MessageDigest;

public class IntegrityCheck {

    private static final String EXPECTED_SHA256 =
            "2E3FA2A8FCF9AAAFF8085C72443D2DE7C8CC3B04CCC84A8EBFBFAF116C82E668";

    public static boolean isValid(Context ctx) {
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(
                    ctx.getPackageName(),
                    PackageManager.GET_SIGNING_CERTIFICATES
            );
            Signature[] sigs = new Signature[0];
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                sigs = info.signingInfo.getApkContentsSigners();
            }
            for (Signature sig : sigs) {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] digest = md.digest(sig.toByteArray());
                String hex = toHex(digest).toUpperCase();
                if (hex.equals(EXPECTED_SHA256)) return true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}