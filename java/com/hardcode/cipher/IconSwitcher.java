package com.hardcode.cipher;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

public class IconSwitcher {

    public enum AppIcon {
        DEFAULT("com.hardcode.cipher.alias.IconDefault"),
        FIREFOX("com.hardcode.cipher.alias.IconFirefox"),
        CANDYCRUSH("com.hardcode.cipher.alias.IconCandyCrush");

        public final String aliasName;
        AppIcon(String aliasName) { this.aliasName = aliasName; }
    }

    private static final String PREFS_NAME = "cipher_prefs";
    private static final String KEY_ICON   = "active_icon";

    /**
     * Switch to the given icon/name alias.
     * Disables all other aliases, enables only the target.
     * Android updates the launcher icon after a short delay (~3–10s).
     */
    public static void switchTo(Context context, AppIcon target) {
        PackageManager pm = context.getPackageManager();
        for (AppIcon icon : AppIcon.values()) {
            pm.setComponentEnabledSetting(
                    new ComponentName(context, icon.aliasName),
                    icon == target
                            ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
            );
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ICON, target.name())
                .apply();
    }

    /**
     * Returns whichever alias is currently saved, defaulting to DEFAULT.
     */
    public static AppIcon getActive(Context context) {
        String saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_ICON, AppIcon.DEFAULT.name());
        try {
            return AppIcon.valueOf(saved);
        } catch (IllegalArgumentException e) {
            return AppIcon.DEFAULT;
        }
    }
}