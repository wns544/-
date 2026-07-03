package com.example.screenlocktodo;

import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class DiagnosticLog {
    private static final String FILE_NAME = "diagnostic-log.txt";
    private static final int MAX_BYTES = 64 * 1024;
    private static final long THROTTLE_MS = 1000L;
    private static final Object LOCK = new Object();
    private static String lastThrottledLine = "";
    private static long lastThrottledAt;

    private DiagnosticLog() {
    }

    static void record(Context context, String tag, String message) {
        Log.i(tag, message);
        String line = tag + ": " + message;
        if (!shouldThrottle(line)) {
            append(context, line);
        }
    }

    static void record(Context context, String tag, String message, Throwable throwable) {
        Log.w(tag, message, throwable);
        append(context, tag + ": " + message + " / " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
    }

    static String read(Context context) {
        synchronized (LOCK) {
            File file = logFile(context);
            if (!file.exists()) {
                return "";
            }
            StringBuilder builder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line).append('\n');
                }
            } catch (Exception e) {
                return "Unable to read diagnostic log: " + e.getMessage();
            }
            return builder.toString();
        }
    }

    static void clear(Context context) {
        synchronized (LOCK) {
            File file = logFile(context);
            if (file.exists()) {
                file.delete();
            }
            append(context, "DiagnosticLog: cleared");
        }
    }

    static String shareText(Context context) {
        return appSummary(context) + "\n\n" + read(context);
    }

    static void recordAppState(Context context, String reason) {
        append(context, "AppState: " + reason + "\n" + appSummary(context).replace("\n", "\n  "));
    }

    private static void append(Context context, String line) {
        synchronized (LOCK) {
            String stamped = timestamp() + "  " + line + "\n";
            File file = logFile(context);
            byte[] stampedBytes = stamped.getBytes(StandardCharsets.UTF_8);
            if (!file.exists() || file.length() + stampedBytes.length <= MAX_BYTES) {
                try (FileOutputStream output = new FileOutputStream(file, true)) {
                    output.write(stampedBytes);
                } catch (Exception ignored) {
                }
                return;
            }
            String existing = read(context);
            String combined = existing + stamped;
            byte[] bytes = trimToLimit(combined).getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream output = new FileOutputStream(file, false)) {
                output.write(bytes);
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean shouldThrottle(String line) {
        if (!isThrottleCandidate(line)) {
            return false;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            if (line.equals(lastThrottledLine) && now - lastThrottledAt < THROTTLE_MS) {
                return true;
            }
            lastThrottledLine = line;
            lastThrottledAt = now;
            return false;
        }
    }

    private static boolean isThrottleCandidate(String line) {
        return line.contains("display resting state=")
                || line.contains("pre-arm skipped")
                || line.contains("show lock skipped")
                || line.contains("lock visibility confirmed");
    }

    private static String trimToLimit(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_BYTES) {
            return value;
        }
        int start = Math.max(0, value.length() - MAX_BYTES);
        return "... older diagnostic log entries trimmed ...\n" + value.substring(start);
    }

    private static File logFile(Context context) {
        Context storageContext = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            storageContext = storageContext.createDeviceProtectedStorageContext();
        }
        return new File(storageContext.getFilesDir(), FILE_NAME);
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    private static String appSummary(Context context) {
        StringBuilder builder = new StringBuilder();
        builder.append("Nudge Screen diagnostic log").append('\n');
        builder.append("Version: ").append(versionName(context)).append(" (").append(versionCode(context)).append(")").append('\n');
        builder.append("Package: ").append(context.getPackageName()).append('\n');
        builder.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        builder.append("Android: ").append(Build.VERSION.RELEASE).append(" / SDK ").append(Build.VERSION.SDK_INT).append('\n');
        builder.append("Lock enabled: ").append(AppSettings.lockScreenEnabled(context)).append('\n');
        builder.append("Todos locked: ").append(AppSettings.todosLocked(context)).append('\n');
        builder.append("Overlay opacity: ").append(AppSettings.overlayOpacity(context)).append('%').append('\n');
        builder.append("Lock background mode: ").append(lockBackgroundMode(context)).append('\n');
        builder.append("Battery unrestricted: ").append(isBatteryUnrestricted(context)).append('\n');
        builder.append("Full screen intent: ").append(canUseFullScreenIntent(context)).append('\n');
        return builder.toString();
    }

    private static String lockBackgroundMode(Context context) {
        String uri = AppSettings.lockBackgroundImageUri(context);
        if (uri == null || uri.length() == 0) {
            return "system wallpaper layer";
        }
        return "selected image override";
    }

    private static String versionName(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            return "";
        }
    }

    private static long versionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return info.getLongVersionCode();
            }
            return info.versionCode;
        } catch (PackageManager.NameNotFoundException ignored) {
            return 0;
        }
    }

    private static boolean isBatteryUnrestricted(Context context) {
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return powerManager != null && powerManager.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    private static String canUseFullScreenIntent(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return "not required";
        }
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        return String.valueOf(manager != null && manager.canUseFullScreenIntent());
    }
}
