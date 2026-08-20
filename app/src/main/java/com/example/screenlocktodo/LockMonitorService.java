package com.example.screenlocktodo;

import android.app.AlarmManager;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;
import android.hardware.display.DisplayManager;

public class LockMonitorService extends Service {
    private static final String TAG = "NudgeLockMonitor";
    static final String ACTION_RESTART_MONITOR = "com.example.screenlocktodo.RESTART_MONITOR";
    static final String ACTION_LOCK_DISMISSED_BY_USER = "com.example.screenlocktodo.LOCK_DISMISSED_BY_USER";
    private static final String SERVICE_CHANNEL_ID = "todo_lock_service_quiet_v1";
    private static final String LOCK_CHANNEL_ID = "todo_lock_fullscreen_v1";
    private static final int SERVICE_NOTIFICATION_ID = 1001;
    private static final int LOCK_NOTIFICATION_ID = 1002;
    static final long QUICK_RESTART_DELAY_MS = 5000L;
    static final long KEEP_ALIVE_DELAY_MS = 5 * 60 * 1000L;
    private static final long LOCK_NOTIFICATION_COOLDOWN_MS = 15000L;
    private static final long UNLOCK_FROM_SCREEN_OFF_WINDOW_MS = 8000L;
    private static final long LOCK_VISIBILITY_CHECK_DELAY_MS = 650L;
    private static final long RECENT_VISIBLE_SKIP_MS = 1500L;
    private static final long POCKET_CHECK_TIMEOUT_MS = 450L;
    private static final long USER_DISMISS_SUPPRESS_MS = 3000L;
    private static final long PROXIMITY_CHECK_DEDUPE_MS = 1000L;
    private static final long[] SCREEN_ON_RETRY_DELAYS_MS = {250L, 900L};
    private static final long[] USER_PRESENT_RETRY_DELAYS_MS = {250L, 1000L};
    private static final long PRE_ARM_COOLDOWN_MS = 2500L;
    private static final long PRE_ARM_READY_CHECK_MS = 500L;
    private static final long PRE_ARM_ACTIVATION_DEDUPE_MS = 1500L;
    private long nextLockAttemptId;
    private long lastLockNotificationAt;
    private long lastPreArmAt;
    private long lastPreArmActivationAt;
    private long lastScreenOffAt;
    private long lastUserDismissedLockAt;
    private boolean waitingForScreenOffUnlock;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Object lockRetryToken = new Object();
    private final Object proximityToken = new Object();
    private final Object visibilityToken = new Object();
    private long lastProximityCheckAt;
    private SensorManager activeProximityManager;
    private SensorEventListener activeProximityListener;
    private DisplayManager displayManager;
    private AudioManager audioManager;
    private Object callModeListener;
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
        }

        @Override
        public void onDisplayRemoved(int displayId) {
        }

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                handleDefaultDisplayState();
            }
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!AppSettings.lockScreenEnabled(context)) {
                LockMonitorService.stop(context);
                return;
            }
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                DiagnosticLog.record(context, TAG, "screen event off " + displayStateSummary(context));
                waitingForScreenOffUnlock = true;
                lastScreenOffAt = SystemClock.elapsedRealtime();
                lastPreArmActivationAt = 0L;
                lastUserDismissedLockAt = 0L;
                cancelLockNotification(context);
                TodoStore.warm(context);
                closeLockActivityForScreenOffIfNeeded(context);
                preArmLockScreen(context);
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                long screenOnAt = SystemClock.elapsedRealtime();
                lastPreArmAt = 0L;
                DiagnosticLog.record(context, TAG, "screen event on " + displayStateSummary(context));
                if (activatePreparedLockScreen(context, screenOnAt, "screen_on")) {
                    return;
                }
                showLockScreenAfterPocketCheck(context, false, true, true, "screen_on");
                scheduleLockScreenRetries(false, true, true, SCREEN_ON_RETRY_DELAYS_MS, "screen_on_retry");
            } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                DiagnosticLog.record(context, TAG, "screen event user_present recentScreenOff=" + wasRecentlyScreenOff()
                        + " " + displayStateSummary(context));
                if (wasRecentlyScreenOff()) {
                    waitingForScreenOffUnlock = false;
                    showLockScreenAfterPocketCheck(context, false, true, false, "user_present");
                    scheduleLockScreenRetries(false, true, false, USER_PRESENT_RETRY_DELAYS_MS, "user_present_retry");
                }
            } else if (ACTION_LOCK_DISMISSED_BY_USER.equals(action)) {
                lastUserDismissedLockAt = SystemClock.elapsedRealtime();
                cancelLockScreenRetries();
                cancelActiveProximityCheck();
                cancelLockVisibilityChecks();
                cancelLockNotification(context);
                DiagnosticLog.record(context, TAG, "lock dismissed by user");
                DiagnosticLog.record(context, TAG, "lock retries canceled after user dismiss");
            }
        }
    };

    private boolean registered;

    private void closeLockActivityForScreenOffIfNeeded(Context context) {
        if (!AppSettings.releaseLockOnScreenOff(context)) {
            return;
        }
        Intent closeIntent = new Intent(LockActivity.ACTION_CLOSE_FOR_SCREEN_OFF).setPackage(context.getPackageName());
        context.sendBroadcast(closeIntent);
        DiagnosticLog.record(context, TAG, "close lock activity for AOD friendly mode");
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    public static void start(Context context) {
        if (!AppSettings.lockScreenEnabled(context)) {
            DiagnosticLog.record(context, TAG, "start skipped because lock screen is disabled");
            stop(context);
            return;
        }

        Intent intent = new Intent(context, LockMonitorService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            DiagnosticLog.record(context, TAG, "monitor service start requested");
        } catch (RuntimeException e) {
            DiagnosticLog.record(context, TAG, "monitor service start failed; scheduling restart", e);
            scheduleRestart(context, QUICK_RESTART_DELAY_MS);
        }
    }

    public static void stop(Context context) {
        DiagnosticLog.record(context, TAG, "monitor service stop requested");
        cancelScheduledRestarts(context);
        cancelLockNotification(context);
        cancelServiceNotification(context);
        context.stopService(new Intent(context, LockMonitorService.class));
    }

    static void scheduleRestart(Context context, long delayMillis) {
        if (!AppSettings.lockScreenEnabled(context)) {
            DiagnosticLog.record(context, TAG, "schedule restart skipped; disabled delay=" + delayMillis);
            return;
        }

        AlarmManager alarmManager = (AlarmManager) context.getSystemService(ALARM_SERVICE);
        if (alarmManager == null) {
            DiagnosticLog.record(context, TAG, "schedule restart skipped; alarm manager unavailable delay=" + delayMillis);
            return;
        }

        Intent restartIntent = new Intent(context, BootReceiver.class)
                .setAction(ACTION_RESTART_MONITOR)
                .setPackage(context.getPackageName());
        PendingIntent restartPendingIntent = PendingIntent.getBroadcast(
                context,
                (int) Math.max(1, Math.min(100000, delayMillis)),
                restartIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        alarmManager.cancel(restartPendingIntent);
        long triggerAt = SystemClock.elapsedRealtime() + delayMillis;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    restartPendingIntent
            );
        } else {
            alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    restartPendingIntent
            );
        }
        DiagnosticLog.record(context, TAG, "restart scheduled delayMs=" + delayMillis);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (!AppSettings.lockScreenEnabled(this)) {
            DiagnosticLog.record(this, TAG, "onCreate stopSelf; disabled");
            stopSelf();
            return;
        }
        DiagnosticLog.recordAppState(this, "service onCreate");
        createNotificationChannels();
        registerScreenReceiver();
        registerDisplayListener();
        registerCallModeListener();
        startForeground(SERVICE_NOTIFICATION_ID, buildServiceNotification());
        TodoStore.warm(this);
        scheduleKeepAlive();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!AppSettings.lockScreenEnabled(this)) {
            DiagnosticLog.record(this, TAG, "onStartCommand stopSelf; disabled");
            cancelLockNotification(this);
            cancelServiceNotification(this);
            stopSelf();
            return START_NOT_STICKY;
        }
        DiagnosticLog.recordAppState(this, "service onStartCommand startId=" + startId);
        createNotificationChannels();
        registerScreenReceiver();
        registerDisplayListener();
        registerCallModeListener();
        startForeground(SERVICE_NOTIFICATION_ID, buildServiceNotification());
        TodoStore.warm(this);
        scheduleKeepAlive();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        DiagnosticLog.record(this, TAG, "service onDestroy");
        if (registered) {
            unregisterReceiver(screenReceiver);
            registered = false;
        }
        cancelActiveProximityCheck();
        if (displayManager != null) {
            displayManager.unregisterDisplayListener(displayListener);
            displayManager = null;
        }
        unregisterCallModeListener();
        handler.removeCallbacksAndMessages(null);
        if (AppSettings.lockScreenEnabled(this)) {
            scheduleRestart(1200);
            scheduleKeepAlive();
        }
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        DiagnosticLog.record(this, TAG, "service onTaskRemoved");
        if (AppSettings.lockScreenEnabled(this)) {
            scheduleRestart(700);
            scheduleRestart(QUICK_RESTART_DELAY_MS);
            scheduleKeepAlive();
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void registerScreenReceiver() {
        if (registered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(ACTION_LOCK_DISMISSED_BY_USER);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, filter);
        }
        registered = true;
    }

    private void registerDisplayListener() {
        if (displayManager != null) {
            return;
        }
        displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        if (displayManager != null) {
            displayManager.registerDisplayListener(displayListener, handler);
            handleDefaultDisplayState();
        }
    }

    private void registerCallModeListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || audioManager != null) {
            return;
        }
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager != null) {
            AudioManager.OnModeChangedListener listener = mode -> {
                if (CallStateGuard.shouldSuppressForAudioMode(mode)) {
                    suppressLockScreenForCall(this, "audio_mode_changed_" + mode);
                }
            };
            callModeListener = listener;
            audioManager.addOnModeChangedListener(getMainExecutor(), listener);
            if (CallStateGuard.shouldSuppressForAudioMode(audioManager.getMode())) {
                suppressLockScreenForCall(this, "listener_registered");
            }
        }
    }

    private void unregisterCallModeListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && audioManager != null
                && callModeListener instanceof AudioManager.OnModeChangedListener) {
            audioManager.removeOnModeChangedListener((AudioManager.OnModeChangedListener) callModeListener);
        }
        audioManager = null;
        callModeListener = null;
    }

    private void handleDefaultDisplayState() {
        if (displayManager == null) {
            return;
        }
        Display display = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) {
            return;
        }

        int state = display.getState();
        if (state == Display.STATE_OFF || state == Display.STATE_DOZE || state == Display.STATE_DOZE_SUSPEND) {
            DiagnosticLog.record(this, TAG, "display resting state=" + state + " " + displayStateSummary(this));
            waitingForScreenOffUnlock = true;
            lastScreenOffAt = SystemClock.elapsedRealtime();
            lastPreArmActivationAt = 0L;
            cancelLockNotification(this);
            TodoStore.warm(this);
            closeLockActivityForScreenOffIfNeeded(this);
            preArmLockScreen(this);
        } else if (state == Display.STATE_ON && wasRecentlyScreenOff()) {
            long screenOnAt = SystemClock.elapsedRealtime();
            lastPreArmAt = 0L;
            DiagnosticLog.record(this, TAG, "display on after resting state " + displayStateSummary(this));
            waitingForScreenOffUnlock = false;
            if (activatePreparedLockScreen(this, screenOnAt, "display_on_after_rest")) {
                return;
            }
            showLockScreenAfterPocketCheck(this, false, true, true, "display_on_after_rest");
            scheduleLockScreenRetries(false, true, true, SCREEN_ON_RETRY_DELAYS_MS, "display_on_retry");
        }
    }

    private boolean isKeyguardLocked(Context context) {
        KeyguardManager keyguardManager = (KeyguardManager) context.getSystemService(KEYGUARD_SERVICE);
        return keyguardManager != null && keyguardManager.isKeyguardLocked();
    }

    private boolean wasRecentlyScreenOff() {
        return waitingForScreenOffUnlock
                && SystemClock.elapsedRealtime() - lastScreenOffAt <= UNLOCK_FROM_SCREEN_OFF_WINDOW_MS;
    }

    private boolean wasRecentlyDismissedByUser() {
        return lastUserDismissedLockAt > 0
                && SystemClock.elapsedRealtime() - lastUserDismissedLockAt <= USER_DISMISS_SUPPRESS_MS;
    }

    private void scheduleLockScreenRetries(
            boolean wakeDisplay,
            boolean allowBeforeKeyguard,
            boolean allowNotificationFallback,
            long[] delaysMillis,
            String source
    ) {
        if (suppressLockScreenForCall(this, source + "_schedule")) {
            return;
        }
        Context appContext = getApplicationContext();
        for (long delayMillis : delaysMillis) {
            handler.postDelayed(
                    () -> showLockScreenAfterPocketCheck(appContext, wakeDisplay, allowBeforeKeyguard, allowNotificationFallback, source + "_" + delayMillis + "ms"),
                    lockRetryToken,
                    delayMillis
            );
        }
    }

    private void cancelLockScreenRetries() {
        handler.removeCallbacksAndMessages(lockRetryToken);
    }

    private void cancelLockVisibilityChecks() {
        handler.removeCallbacksAndMessages(visibilityToken);
    }

    private void scheduleRestart(long delayMillis) {
        scheduleRestart(this, delayMillis);
    }

    private void scheduleKeepAlive() {
        scheduleRestart(KEEP_ALIVE_DELAY_MS);
    }

    private void preArmLockScreen(Context context) {
        if (suppressLockScreenForCall(context, "pre_arm")) {
            return;
        }
        if (AppSettings.lockDisplayMode(context) != LockDisplayMode.FAST_PREARM) {
            DiagnosticLog.record(context, TAG, "pre-arm skipped; AOD priority mode");
            return;
        }
        if (!canPreArmLockScreen(context)) {
            DiagnosticLog.record(context, TAG, "pre-arm blocked; appear-on-top permission missing");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastPreArmAt < PRE_ARM_COOLDOWN_MS) {
            DiagnosticLog.record(context, TAG, "pre-arm skipped by cooldown");
            return;
        }
        lastPreArmAt = now;
        if (LockActivity.isVisible() && LockActivity.markPreparedForWake()) {
            DiagnosticLog.record(context, TAG, "pre-arm ready; existing lock activity reused");
            return;
        }
        LockActivity.clearPreparedForWake();

        Intent lockIntent = new Intent(context, LockActivity.class)
                .putExtra(LockActivity.EXTRA_TURN_SCREEN_ON, false)
                .putExtra(LockActivity.EXTRA_IDLE_SCREEN_OFF, false)
                .putExtra(LockActivity.EXTRA_PRE_ARMED, true)
                .putExtra(LockActivity.EXTRA_PRE_ARM_REQUESTED_AT, now)
                .putExtra(LockActivity.EXTRA_MONITOR_LAUNCH, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        DiagnosticLog.record(context, TAG, "pre-arm requested " + displayStateSummary(context));
        try {
            context.startActivity(lockIntent);
        } catch (RuntimeException e) {
            DiagnosticLog.record(context, TAG, "pre-arm direct activity launch failed", e);
            return;
        }

        Context appContext = context.getApplicationContext();
        handler.postDelayed(() -> {
            if (LockActivity.isPreArmReady()) {
                DiagnosticLog.record(appContext, TAG, "pre-arm success");
            } else {
                DiagnosticLog.record(appContext, TAG, "pre-arm blocked or not ready after " + PRE_ARM_READY_CHECK_MS + "ms");
            }
        }, PRE_ARM_READY_CHECK_MS);
    }

    private boolean canPreArmLockScreen(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context);
    }

    private boolean activatePreparedLockScreen(Context context, long screenOnAt, String source) {
        if (suppressLockScreenForCall(context, source + "_activate_prepared")) {
            return false;
        }
        if (AppSettings.lockDisplayMode(context) != LockDisplayMode.FAST_PREARM || !LockActivity.isPreArmReady()) {
            return false;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastPreArmActivationAt <= PRE_ARM_ACTIVATION_DEDUPE_MS) {
            DiagnosticLog.record(context, TAG, "prepared lock already activated source=" + source);
            return true;
        }
        lastPreArmActivationAt = now;
        waitingForScreenOffUnlock = false;
        cancelLockScreenRetries();
        cancelLockVisibilityChecks();
        cancelLockNotification(context);
        Intent activation = new Intent(LockActivity.ACTION_PRE_ARM_SCREEN_ON)
                .setPackage(context.getPackageName())
                .putExtra(LockActivity.EXTRA_SCREEN_ON_AT, screenOnAt);
        context.sendBroadcast(activation);
        DiagnosticLog.record(context, TAG, "prepared lock activated source=" + source);
        checkPocketAfterPreparedWake(context, source);
        return true;
    }

    private void checkPocketAfterPreparedWake(Context context, String source) {
        cancelActiveProximityCheck();
        SensorManager sensorManager = (SensorManager) context.getSystemService(SENSOR_SERVICE);
        if (sensorManager == null) {
            DiagnosticLog.record(context, TAG, "prepared wake pocket check unavailable; no sensor manager");
            return;
        }
        Sensor proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        if (proximitySensor == null) {
            DiagnosticLog.record(context, TAG, "prepared wake pocket check unavailable; no proximity sensor");
            return;
        }

        Context appContext = context.getApplicationContext();
        final boolean[] completed = {false};
        SensorEventListener listener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                if (completed[0]) {
                    return;
                }
                completed[0] = true;
                clearActiveProximityCheck(sensorManager, this);
                boolean near = event.values.length > 0
                        && event.values[0] < Math.min(proximitySensor.getMaximumRange(), 5f);
                if (near) {
                    DiagnosticLog.record(appContext, TAG, "prepared wake dismissed; proximity near source=" + source);
                    dismissPreparedLockForPocket(appContext);
                } else {
                    DiagnosticLog.record(appContext, TAG, "prepared wake pocket check far source=" + source);
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
            }
        };

        boolean registered = sensorManager.registerListener(listener, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL, handler);
        if (!registered) {
            DiagnosticLog.record(context, TAG, "prepared wake pocket check registration failed");
            return;
        }
        activeProximityManager = sensorManager;
        activeProximityListener = listener;
        lastProximityCheckAt = SystemClock.elapsedRealtime();
        handler.postDelayed(() -> {
            if (completed[0]) {
                return;
            }
            completed[0] = true;
            clearActiveProximityCheck(sensorManager, listener);
            DiagnosticLog.record(appContext, TAG, "prepared wake pocket check timed out source=" + source);
        }, POCKET_CHECK_TIMEOUT_MS);
    }

    private void dismissPreparedLockForPocket(Context context) {
        lastUserDismissedLockAt = SystemClock.elapsedRealtime();
        Intent closeIntent = new Intent(LockActivity.ACTION_CLOSE_FOR_SCREEN_OFF)
                .setPackage(context.getPackageName());
        context.sendBroadcast(closeIntent);
    }

    static void cancelLockNotification(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(LOCK_NOTIFICATION_ID);
        }
    }

    private static void cancelServiceNotification(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(SERVICE_NOTIFICATION_ID);
        }
    }

    private static void cancelScheduledRestarts(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }

        cancelScheduledRestart(context, alarmManager, 700);
        cancelScheduledRestart(context, alarmManager, 1200);
        cancelScheduledRestart(context, alarmManager, QUICK_RESTART_DELAY_MS);
        cancelScheduledRestart(context, alarmManager, 30000);
        cancelScheduledRestart(context, alarmManager, KEEP_ALIVE_DELAY_MS);
    }

    private static void cancelScheduledRestart(Context context, AlarmManager alarmManager, long delayMillis) {
        Intent restartIntent = new Intent(context, BootReceiver.class)
                .setAction(ACTION_RESTART_MONITOR)
                .setPackage(context.getPackageName());
        PendingIntent restartPendingIntent = PendingIntent.getBroadcast(
                context,
                (int) Math.max(1, Math.min(100000, delayMillis)),
                restartIntent,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE
        );
        if (restartPendingIntent != null) {
            alarmManager.cancel(restartPendingIntent);
            restartPendingIntent.cancel();
        }
    }

    private Notification buildServiceNotification() {
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new Notification.Builder(this, SERVICE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_lock_todo)
                .setContentTitle(getString(R.string.service_notification_title))
                .setContentText(getString(R.string.service_notification_text))
                .setContentIntent(contentIntent)
                .setDefaults(0)
                .setSound(null)
                .setVibrate(null)
                .setOngoing(true)
                .build();
    }

    private void showLockScreen(Context context, boolean wakeDisplay, boolean allowBeforeKeyguard, boolean allowNotificationFallback, String source) {
        long attemptId = ++nextLockAttemptId;
        long attemptAt = SystemClock.elapsedRealtime();
        if (suppressLockScreenForCall(context, source + "_show")) {
            return;
        }
        if (!AppSettings.lockScreenEnabled(context)) {
            DiagnosticLog.record(context, TAG, "show lock skipped id=" + attemptId + " source=" + source + "; disabled");
            LockMonitorService.stop(context);
            return;
        }

        if (wasRecentlyDismissedByUser()) {
            DiagnosticLog.record(context, TAG, "show lock skipped id=" + attemptId
                    + " source=" + source
                    + "; recently dismissed by user ageMs=" + ageMs(lastUserDismissedLockAt));
            return;
        }

        if (!allowBeforeKeyguard && !isKeyguardLocked(context)) {
            DiagnosticLog.record(context, TAG, "show lock skipped id=" + attemptId + " source=" + source + "; keyguard not locked "
                    + displayStateSummary(context));
            return;
        }

        if (!wakeDisplay && !isUserAwakeDisplay(context)) {
            DiagnosticLog.record(context, TAG, "show lock skipped id=" + attemptId
                    + " source=" + source
                    + "; display not user-awake " + displayStateSummary(context));
            return;
        }

        DiagnosticLog.recordAppState(context, "show lock id=" + attemptId
                + " source=" + source
                + " wake=" + wakeDisplay
                + " beforeKeyguard=" + allowBeforeKeyguard
                + " fallback=" + allowNotificationFallback
                + " " + displayStateSummary(context));

        long lastVisibleAgeMs = ageMs(LockActivity.lastVisibleAt());
        if (LockActivity.isShowing() && (LockActivity.isVisible()
                || (lastVisibleAgeMs >= 0 && lastVisibleAgeMs < RECENT_VISIBLE_SKIP_MS))) {
            DiagnosticLog.record(context, TAG, "show lock skipped id=" + attemptId + " source=" + source
                    + "; lock activity already showing visible=" + LockActivity.isVisible()
                    + " lastVisibleAgeMs=" + lastVisibleAgeMs);
            cancelLockNotification(context);
            return;
        }
        if (LockActivity.isShowing()) {
            DiagnosticLog.record(context, TAG, "stale lock activity state ignored id=" + attemptId
                    + " source=" + source
                    + " visible=" + LockActivity.isVisible()
                    + " lastVisibleAgeMs=" + lastVisibleAgeMs);
        }

        Intent lockIntent = new Intent(context, LockActivity.class)
                .putExtra(LockActivity.EXTRA_TURN_SCREEN_ON, wakeDisplay)
                .putExtra(LockActivity.EXTRA_IDLE_SCREEN_OFF, wakeDisplay)
                .putExtra(LockActivity.EXTRA_MONITOR_LAUNCH, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);

        PowerManager powerManager = (PowerManager) context.getSystemService(POWER_SERVICE);
        if (wakeDisplay && powerManager != null && !powerManager.isInteractive()) {
            PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "ScreenLockTodo:showLock"
            );
            wakeLock.acquire(3000);
        }

        boolean useNotificationAsPrimaryLaunch = wakeDisplay && allowNotificationFallback;
        if (useNotificationAsPrimaryLaunch && canUseFullScreenIntent(context)) {
            DiagnosticLog.record(context, TAG, "using full-screen notification as primary launch id=" + attemptId);
            postFullScreenLockNotification(context, lockIntent, attemptId);
            scheduleLockVisibilityCheck(context.getApplicationContext(), attemptId, source, attemptAt, lockIntent, false);
            return;
        }

        launchLockActivity(context, lockIntent);
        scheduleLockVisibilityCheck(context.getApplicationContext(), attemptId, source, attemptAt, lockIntent, allowNotificationFallback);
    }

    private void postFullScreenLockNotification(Context context, Intent lockIntent, long attemptId) {
        if (suppressLockScreenForCall(context, "full_screen_notification_" + attemptId)) {
            return;
        }
        PendingIntent fullScreenIntent = PendingIntent.getActivity(
                context,
                1,
                lockIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                pendingIntentCreatorOptions()
        );

        Notification notification = new Notification.Builder(context, LOCK_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_lock_todo)
                .setContentTitle(context.getString(R.string.lock_notification_title))
                .setContentText(context.getString(R.string.lock_notification_text))
                .setPriority(Notification.PRIORITY_HIGH)
                .setCategory(Notification.CATEGORY_ALARM)
                .setFullScreenIntent(fullScreenIntent, true)
                .setDefaults(0)
                .setSound(null)
                .setVibrate(null)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .build();

        NotificationManager manager = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            DiagnosticLog.record(context, TAG, "posting full-screen notification id=" + attemptId);
            manager.notify(LOCK_NOTIFICATION_ID, notification);
        }
    }

    private void showLockScreenAfterPocketCheck(Context context, boolean wakeDisplay, boolean allowBeforeKeyguard, boolean allowNotificationFallback, String source) {
        if (suppressLockScreenForCall(context, source + "_pocket_check")) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (activeProximityListener != null && now - lastProximityCheckAt < PROXIMITY_CHECK_DEDUPE_MS) {
            DiagnosticLog.record(context, TAG, "show lock skipped source=" + source + "; proximity check already active");
            return;
        }
        SensorManager sensorManager = (SensorManager) context.getSystemService(SENSOR_SERVICE);
        if (sensorManager == null) {
            if (isPassiveScreenWakeSource(source)) {
                DiagnosticLog.record(context, TAG, "show lock skipped source=" + source + "; no sensor manager for passive wake");
                return;
            }
            showLockScreen(context, wakeDisplay, allowBeforeKeyguard, allowNotificationFallback, source);
            return;
        }

        Sensor proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        if (proximitySensor == null) {
            if (isPassiveScreenWakeSource(source)) {
                DiagnosticLog.record(context, TAG, "show lock skipped source=" + source + "; no proximity sensor for passive wake");
                return;
            }
            showLockScreen(context, wakeDisplay, allowBeforeKeyguard, allowNotificationFallback, source);
            return;
        }

        Context appContext = context.getApplicationContext();
        final boolean[] completed = {false};
        SensorEventListener listener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                if (completed[0]) {
                    return;
                }
                completed[0] = true;
                clearActiveProximityCheck(sensorManager, this);

                boolean near = event.values.length > 0
                        && event.values[0] < Math.min(proximitySensor.getMaximumRange(), 5f);
                if (near) {
                    DiagnosticLog.record(appContext, TAG, "show lock skipped source=" + source + "; proximity near");
                    return;
                }
                showLockScreen(appContext, wakeDisplay, allowBeforeKeyguard, allowNotificationFallback, source);
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
            }
        };

        boolean registered = sensorManager.registerListener(listener, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL, handler);
        if (!registered) {
            showLockScreen(context, wakeDisplay, allowBeforeKeyguard, allowNotificationFallback, source);
            return;
        }
        activeProximityManager = sensorManager;
        activeProximityListener = listener;
        lastProximityCheckAt = now;

        handler.postDelayed(() -> {
            if (completed[0]) {
                return;
            }
            completed[0] = true;
            clearActiveProximityCheck(sensorManager, listener);
            DiagnosticLog.record(appContext, TAG, "proximity check timed out source=" + source);
            if (isPassiveScreenWakeSource(source)) {
                DiagnosticLog.record(appContext, TAG, "show lock skipped source=" + source + "; proximity timeout on passive wake");
                return;
            }
            showLockScreen(appContext, wakeDisplay, allowBeforeKeyguard, allowNotificationFallback, source);
        }, proximityToken, POCKET_CHECK_TIMEOUT_MS);
    }

    private boolean isPassiveScreenWakeSource(String source) {
        return source != null
                && (source.startsWith("screen_on") || source.startsWith("display_on"));
    }

    private void clearActiveProximityCheck(SensorManager sensorManager, SensorEventListener listener) {
        handler.removeCallbacksAndMessages(proximityToken);
        sensorManager.unregisterListener(listener);
        if (activeProximityListener == listener) {
            activeProximityListener = null;
            activeProximityManager = null;
        }
    }

    private void cancelActiveProximityCheck() {
        handler.removeCallbacksAndMessages(proximityToken);
        if (activeProximityManager != null && activeProximityListener != null) {
            activeProximityManager.unregisterListener(activeProximityListener);
        }
        activeProximityListener = null;
        activeProximityManager = null;
    }

    private void scheduleLockVisibilityCheck(
            Context context,
            long attemptId,
            String source,
            long attemptAt,
            Intent lockIntent,
            boolean allowNotificationFallback
    ) {
        handler.postDelayed(() -> {
            if (suppressLockScreenForCall(context, source + "_visibility_check")) {
                return;
            }
            long lastVisibleAt = LockActivity.lastVisibleAt();
            boolean becameVisible = lastVisibleAt >= attemptAt;
            if (becameVisible) {
                DiagnosticLog.record(context, TAG, "lock visibility confirmed id=" + attemptId
                        + " source=" + source
                        + " delayMs=" + (lastVisibleAt - attemptAt)
                        + " visible=" + LockActivity.isVisible()
                        + " showing=" + LockActivity.isShowing());
                return;
            }
            DiagnosticLog.record(context, TAG, "LOCK VISIBILITY MISS id=" + attemptId
                    + " source=" + source
                    + " waitedMs=" + LOCK_VISIBILITY_CHECK_DELAY_MS
                    + " visible=" + LockActivity.isVisible()
                    + " showing=" + LockActivity.isShowing()
                    + " lastVisibleAgeMs=" + ageMs(lastVisibleAt)
                    + " " + displayStateSummary(context));

            if (!allowNotificationFallback) {
                DiagnosticLog.record(context, TAG, "notification fallback skipped id=" + attemptId);
                return;
            }
            if (!canUseFullScreenIntent(context)) {
                DiagnosticLog.record(context, TAG, "notification fallback skipped id=" + attemptId + "; full-screen intent denied");
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (now - lastLockNotificationAt < LOCK_NOTIFICATION_COOLDOWN_MS) {
                DiagnosticLog.record(context, TAG, "notification fallback skipped id=" + attemptId + " by cooldown");
                return;
            }
            lastLockNotificationAt = now;
            postFullScreenLockNotification(context, lockIntent, attemptId);
        }, visibilityToken, LOCK_VISIBILITY_CHECK_DELAY_MS);
    }

    private void launchLockActivity(Context context, Intent lockIntent) {
        if (suppressLockScreenForCall(context, "launch_activity")) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                PendingIntent pendingIntent = PendingIntent.getActivity(
                        context,
                        2,
                        lockIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                        pendingIntentCreatorOptions()
                );
                ActivityOptions options = pendingIntentSenderOptions();
                pendingIntent.send(context, 0, null, null, null, null, options.toBundle());
                DiagnosticLog.record(context, TAG, "sent lock activity pending intent with BAL allowed");
                cancelLockNotification(context);
                return;
            } catch (PendingIntent.CanceledException | RuntimeException e) {
                DiagnosticLog.record(context, TAG, "pending intent lock launch failed", e);
            }
        }

        try {
            context.startActivity(lockIntent);
            DiagnosticLog.record(context, TAG, "started lock activity directly");
            cancelLockNotification(context);
        } catch (RuntimeException e) {
            DiagnosticLog.record(context, TAG, "direct lock launch failed", e);
        }
    }

    private boolean suppressLockScreenForCall(Context context, String source) {
        if (!CallStateGuard.shouldSuppressLockScreen(context)) {
            return false;
        }
        waitingForScreenOffUnlock = false;
        cancelLockScreenRetries();
        cancelActiveProximityCheck();
        cancelLockVisibilityChecks();
        cancelLockNotification(context);
        LockActivity.clearPreparedForWake();
        context.sendBroadcast(new Intent(LockActivity.ACTION_CLOSE_PREPARED_FOR_CALL)
                .setPackage(context.getPackageName()));
        DiagnosticLog.record(context, TAG, "lock suppressed during call source=" + source
                + " audioMode=" + CallStateGuard.audioModeSummary(context));
        return true;
    }

    private boolean canUseFullScreenIntent(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return true;
        }
        NotificationManager manager = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        return manager != null && manager.canUseFullScreenIntent();
    }

    private boolean isUserAwakeDisplay(Context context) {
        PowerManager powerManager = (PowerManager) context.getSystemService(POWER_SERVICE);
        return powerManager != null
                && powerManager.isInteractive()
                && defaultDisplayState(context) == Display.STATE_ON;
    }

    private String displayStateSummary(Context context) {
        PowerManager powerManager = (PowerManager) context.getSystemService(POWER_SERVICE);
        return "interactive=" + (powerManager != null && powerManager.isInteractive())
                + " keyguardLocked=" + isKeyguardLocked(context)
                + " fullScreenIntent=" + canUseFullScreenIntent(context)
                + " displayState=" + defaultDisplayState(context)
                + " screenOffAgeMs=" + ageMs(lastScreenOffAt);
    }

    private int defaultDisplayState(Context context) {
        DisplayManager manager = displayManager;
        if (manager == null) {
            manager = (DisplayManager) context.getSystemService(DISPLAY_SERVICE);
        }
        if (manager == null) {
            return -1;
        }
        Display display = manager.getDisplay(Display.DEFAULT_DISPLAY);
        return display == null ? -1 : display.getState();
    }

    private long ageMs(long timestamp) {
        if (timestamp <= 0) {
            return -1;
        }
        return SystemClock.elapsedRealtime() - timestamp;
    }

    private Bundle pendingIntentCreatorOptions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return null;
        }

        ActivityOptions options = ActivityOptions.makeBasic();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            options.setPendingIntentCreatorBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
            );
        } else {
            options.setPendingIntentBackgroundActivityLaunchAllowed(true);
        }
        return options.toBundle();
    }

    private ActivityOptions pendingIntentSenderOptions() {
        ActivityOptions options = ActivityOptions.makeBasic();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            options.setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
            );
        } else {
            options.setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            );
        }
        return options;
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationChannel serviceChannel = new NotificationChannel(
                SERVICE_CHANNEL_ID,
                getString(R.string.service_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        serviceChannel.setDescription(getString(R.string.service_channel_description));
        serviceChannel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        serviceChannel.setSound(null, null);
        serviceChannel.enableVibration(false);

        NotificationChannel lockChannel = new NotificationChannel(
                LOCK_CHANNEL_ID,
                getString(R.string.lock_channel_name),
                NotificationManager.IMPORTANCE_HIGH
        );
        lockChannel.setDescription(getString(R.string.lock_channel_description));
        lockChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(serviceChannel);
            manager.createNotificationChannel(lockChannel);
        }
    }
}
