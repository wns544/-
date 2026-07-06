package com.example.screenlocktodo;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

final class AppSettings {
    private static final String PREFS = "todo_lock_settings";
    private static final String KEY_LOCK_SCREEN_ENABLED = "lock_screen_enabled";
    private static final String KEY_LOCK_RECOVERY_VERSION = "lock_screen_recovery_version";
    private static final String KEY_OVERLAY_OPACITY = "overlay_opacity";
    private static final String KEY_CURTAIN_UNLOCK_BOTH_DIRECTIONS = "curtain_unlock_both_directions";
    private static final String KEY_TODO_SWIPE_BOTH_DIRECTIONS_DELETE = "todo_swipe_both_directions_delete";
    private static final String KEY_DOUBLE_TAP_SCREEN_OFF_ENABLED = "double_tap_screen_off_enabled";
    private static final String KEY_RELEASE_LOCK_ON_SCREEN_OFF = "release_lock_on_screen_off";
    private static final String KEY_COMPACT_LOCK_CLOCK_LAYOUT = "compact_lock_clock_layout";
    private static final String KEY_TODOS_LOCKED = "todos_locked";
    private static final String KEY_BATTERY_GUIDE_SHOWN = "battery_guide_shown";
    private static final String KEY_LANGUAGE_TAG = "language_tag";
    private static final String KEY_SPEECH_LANGUAGE_TAG = "speech_language_tag";
    private static final String KEY_LOCK_BACKGROUND_IMAGE_URI = "lock_background_image_uri";
    private static final int DEFAULT_OVERLAY_OPACITY = 40;

    private AppSettings() {
    }

    static boolean lockScreenEnabled(Context context) {
        return prefs(context).getBoolean(KEY_LOCK_SCREEN_ENABLED, true);
    }

    static void setLockScreenEnabled(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_LOCK_SCREEN_ENABLED, value).apply();
    }

    static void applyLockScreenRecovery(Context context) {
        SharedPreferences prefs = prefs(context);
        int currentVersion = currentVersionCode(context);
        if (prefs.getInt(KEY_LOCK_RECOVERY_VERSION, 0) >= currentVersion) {
            return;
        }

        SharedPreferences.Editor editor = prefs.edit()
                .putInt(KEY_LOCK_RECOVERY_VERSION, currentVersion);
        if (!prefs.getBoolean(KEY_LOCK_SCREEN_ENABLED, true)) {
            editor.putBoolean(KEY_LOCK_SCREEN_ENABLED, true);
        }
        editor.apply();
    }

    static int overlayOpacity(Context context) {
        return prefs(context).getInt(KEY_OVERLAY_OPACITY, DEFAULT_OVERLAY_OPACITY);
    }

    static void setOverlayOpacity(Context context, int value) {
        int bounded = Math.max(0, Math.min(100, value));
        prefs(context).edit().putInt(KEY_OVERLAY_OPACITY, bounded).apply();
    }

    static boolean curtainUnlockBothDirections(Context context) {
        return prefs(context).getBoolean(KEY_CURTAIN_UNLOCK_BOTH_DIRECTIONS, true);
    }

    static void setCurtainUnlockBothDirections(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_CURTAIN_UNLOCK_BOTH_DIRECTIONS, value).apply();
    }

    static boolean todoSwipeBothDirectionsDelete(Context context) {
        return prefs(context).getBoolean(KEY_TODO_SWIPE_BOTH_DIRECTIONS_DELETE, false);
    }

    static void setTodoSwipeBothDirectionsDelete(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_TODO_SWIPE_BOTH_DIRECTIONS_DELETE, value).apply();
    }

    static boolean doubleTapScreenOffEnabled(Context context) {
        return prefs(context).getBoolean(KEY_DOUBLE_TAP_SCREEN_OFF_ENABLED, true);
    }

    static void setDoubleTapScreenOffEnabled(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_DOUBLE_TAP_SCREEN_OFF_ENABLED, value).apply();
    }

    static boolean releaseLockOnScreenOff(Context context) {
        return prefs(context).getBoolean(KEY_RELEASE_LOCK_ON_SCREEN_OFF, false);
    }

    static void setReleaseLockOnScreenOff(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_RELEASE_LOCK_ON_SCREEN_OFF, value).apply();
    }

    static boolean compactLockClockLayout(Context context) {
        return prefs(context).getBoolean(KEY_COMPACT_LOCK_CLOCK_LAYOUT, false);
    }

    static void setCompactLockClockLayout(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_COMPACT_LOCK_CLOCK_LAYOUT, value).apply();
    }

    static boolean todosLocked(Context context) {
        return prefs(context).getBoolean(KEY_TODOS_LOCKED, false);
    }

    static void setTodosLocked(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_TODOS_LOCKED, value).apply();
    }

    static boolean batteryGuideShown(Context context) {
        return prefs(context).getBoolean(KEY_BATTERY_GUIDE_SHOWN, false);
    }

    static void setBatteryGuideShown(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_BATTERY_GUIDE_SHOWN, value).apply();
    }

    static String languageTag(Context context) {
        return prefs(context).getString(KEY_LANGUAGE_TAG, "");
    }

    static void setLanguageTag(Context context, String value) {
        prefs(context).edit().putString(KEY_LANGUAGE_TAG, value == null ? "" : value).apply();
    }

    static String speechLanguageTag(Context context) {
        return prefs(context).getString(KEY_SPEECH_LANGUAGE_TAG, "");
    }

    static void setSpeechLanguageTag(Context context, String value) {
        prefs(context).edit().putString(KEY_SPEECH_LANGUAGE_TAG, value == null ? "" : value).apply();
    }

    static String lockBackgroundImageUri(Context context) {
        return prefs(context).getString(KEY_LOCK_BACKGROUND_IMAGE_URI, "");
    }

    static void setLockBackgroundImageUri(Context context, String value) {
        prefs(context).edit().putString(KEY_LOCK_BACKGROUND_IMAGE_URI, value == null ? "" : value).apply();
    }

    static void clearLockBackgroundImageUri(Context context) {
        prefs(context).edit().remove(KEY_LOCK_BACKGROUND_IMAGE_URI).apply();
    }

    private static SharedPreferences prefs(Context context) {
        Context storageContext = context;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            storageContext = context.createDeviceProtectedStorageContext();
        }
        return storageContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static int currentVersionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return (int) Math.min(Integer.MAX_VALUE, info.getLongVersionCode());
            }
            return info.versionCode;
        } catch (PackageManager.NameNotFoundException ignored) {
            return 0;
        }
    }
}
