package com.example.screenlocktodo;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.app.KeyguardManager;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.window.OnBackInvokedDispatcher;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.Manifest;
import android.content.pm.PackageManager;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;

import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class LockActivity extends Activity {
    static final String EXTRA_TURN_SCREEN_ON = "com.example.screenlocktodo.TURN_SCREEN_ON";
    private static volatile boolean showing;
    private static volatile boolean visible;
    private static volatile long lastVisibleAt;
    private static final long TODO_DOUBLE_TAP_MS = 420L;
    private static final long CURTAIN_DOUBLE_TAP_MS = 360L;
    private static final long INPUT_DRAFT_KEEP_MS = 60_000L;

    private LinearLayout todoList;
    private LinearLayout inputBlock;
    private LinearLayout inputRow;
    private TextView editModeLabel;
    private TextView inputDivider;
    private TextView editUndoBanner;
    private TextView topTodoDivider;
    private EditText input;
    private CheckSubmitButtonView inputSubmitButton;
    private PlusButtonView plusButton;
    private TextView timeText;
    private TextView meridiemText;
    private TextView dateText;
    private UndoButtonView undoButton;
    private TextView menuButton;
    private LinearLayout menuPanel;
    private LockToggleView lockButton;
    private ImageView wallpaperBackground;
    private View curtainBackground;
    private FrameLayout curtainContent;
    private final ArrayDeque<DeletedTodo> deletedTodos = new ArrayDeque<>();
    private boolean todosLocked;
    private String lastRenderedTodoKey;
    private long pendingTapItemId = -1L;
    private long draggingTodoId = -1L;
    private int draggingTodoIndex = -1;
    private int dragPreviewIndex = -1;
    private View draggingTodoRow;
    private float draggingTodoStartCenterY;
    private float draggingTouchToCenterOffset;
    private int draggingTodoMoveOffset;
    private View draggingPreviousDivider;
    private View draggingNextDivider;
    private boolean firstTodoRender = true;
    private boolean clockReceiverRegistered;
    private boolean keyboardVisible;
    private String pendingInputDraft = "";
    private long pendingInputDraftSavedAt;
    private long editingTodoId = -1L;
    private String editingOriginalText = "";
    private EditedTodo lastEditedTodo;
    private String lastBackgroundKey = "";
    private ValueAnimator inputBlockHeightAnimator;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    
    private SpeechRecognizer speechRecognizer;
    private ImageView micButton;
    private boolean isListening = false;
    private int speechInsertStart = -1;
    private static final int REQUEST_RECORD_AUDIO_PERMISSION = 200;
    private final BroadcastReceiver clockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateClock();
        }
    };

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        showing = true;
        configureLockWindow();
        super.onCreate(savedInstanceState);
        todosLocked = AppSettings.todosLocked(this);
        DiagnosticLog.recordAppState(this, "lock activity onCreate turnScreenOn=" + getIntent().getBooleanExtra(EXTRA_TURN_SCREEN_ON, true));
        LockMonitorService.cancelLockNotification(this);
        registerBackHandler();
        setContentView(buildContent());
        refreshTodos();
    }

    @Override
    public void onBackPressed() {
        handleSystemBack();
    }

    private void registerBackHandler() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    this::handleSystemBack
            );
        }
    }

    private void handleSystemBack() {
        if (inputBlock != null && inputBlock.getVisibility() == View.VISIBLE) {
            if (keyboardVisible) {
                hideKeyboard();
                if (input != null) {
                    input.clearFocus();
                }
                return;
            }
            if (isEditingTodo()) {
                clearEditingTodo();
                if (input != null) {
                    input.setText("");
                }
                refreshTodos();
            } else {
                saveInputDraft();
            }
            hideInput(false);
        }
        // The curtain itself is dismissed only by the unlock swipe, not by system back.
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        showing = true;
        super.onNewIntent(intent);
        setIntent(intent);
        DiagnosticLog.recordAppState(this, "lock activity onNewIntent turnScreenOn=" + intent.getBooleanExtra(EXTRA_TURN_SCREEN_ON, true));
        todosLocked = AppSettings.todosLocked(this);
        configureLockWindow();
        LockMonitorService.cancelLockNotification(this);
        updateWallpaperBackground(true);
        updateClock();
        refreshTodos();
    }

    @Override
    protected void onResume() {
        showing = true;
        visible = true;
        lastVisibleAt = SystemClock.elapsedRealtime();
        super.onResume();
        DiagnosticLog.recordAppState(this, "lock activity onResume");
        LockMonitorService.cancelLockNotification(this);
        updateWallpaperBackground(false);
        updateClock();
        registerClockReceiver();
        refreshTodos();
    }

    @Override
    protected void onPause() {
        DiagnosticLog.record(this, "NudgeLockActivity", "onPause");
        visible = false;
        if (isListening) {
            stopSpeechRecognition();
        }
        unregisterClockReceiver();
        super.onPause();
    }

    @Override
    protected void onStop() {
        DiagnosticLog.record(this, "NudgeLockActivity", "onStop finishing=" + isFinishing());
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        DiagnosticLog.record(this, "NudgeLockActivity", "onDestroy");
        showing = false;
        unregisterClockReceiver();
        uiHandler.removeCallbacksAndMessages(null);
        if (inputBlockHeightAnimator != null) {
            inputBlockHeightAnimator.cancel();
            inputBlockHeightAnimator = null;
        }
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        super.onDestroy();
    }

    private void toggleSpeechRecognition() {
        if (isListening) {
            stopSpeechRecognition();
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO_PERMISSION);
                    return;
                }
            }
            startSpeechRecognition();
        }
    }

    private void startSpeechRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "이 기기에서 음성 인식을 사용할 수 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override
                public void onReadyForSpeech(Bundle params) {
                    isListening = true;
                    speechInsertStart = input == null ? -1 : input.getSelectionStart();
                    updateMicListening(true);
                    Toast.makeText(LockActivity.this, "말하면 할 일에 입력됩니다.", Toast.LENGTH_SHORT).show();
                }
                @Override
                public void onBeginningOfSpeech() {
                    isListening = true;
                    updateMicListening(true);
                }
                @Override
                public void onRmsChanged(float rmsdB) {}
                @Override
                public void onBufferReceived(byte[] buffer) {}
                @Override
                public void onEndOfSpeech() {
                    updateMicListening(false);
                }
                @Override
                public void onError(int error) {
                    isListening = false;
                    speechInsertStart = -1;
                    updateMicListening(false);
                    Toast.makeText(LockActivity.this, "음성 입력 실패: " + speechErrorMessage(error), Toast.LENGTH_SHORT).show();
                }
                @Override
                public void onResults(Bundle results) {
                    isListening = false;
                    updateMicListening(false);
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        insertSpeechText(matches.get(0), true);
                    }
                    speechInsertStart = -1;
                }
                @Override
                public void onPartialResults(Bundle partialResults) {
                    ArrayList<String> matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        insertSpeechText(matches.get(0), false);
                    }
                }
                @Override
                public void onEvent(int eventType, Bundle params) {}
            });
        }
        
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechLanguageTag());
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, speechLanguageTag());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 8000L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 8000L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 30000L);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "할 일을 말하세요");
        try {
            if (input != null) {
                input.requestFocus();
            }
            speechRecognizer.startListening(intent);
        } catch (Exception e) {
            DiagnosticLog.record(this, "NudgeLockActivity", "speech recognizer start failed", e);
            Toast.makeText(this, "음성 인식을 시작할 수 없습니다.", Toast.LENGTH_SHORT).show();
        }
    }

    private String speechLanguageTag() {
        String speechLanguage = AppSettings.speechLanguageTag(this);
        if (speechLanguage != null && speechLanguage.trim().length() > 0) {
            return speechLanguage;
        }
        String appLanguage = AppSettings.languageTag(this);
        if (appLanguage != null) {
            if (appLanguage.startsWith("en")) {
                return "en-US";
            }
            if (appLanguage.startsWith("zh")) {
                return "zh-TW";
            }
        }
        return "ko-KR";
    }

    private void stopSpeechRecognition() {
        if (speechRecognizer != null) {
            speechRecognizer.stopListening();
        }
        isListening = false;
        speechInsertStart = -1;
        updateMicListening(false);
    }

    private void updateMicListening(boolean listening) {
        if (micButton == null) {
            return;
        }
        micButton.setAlpha(listening ? 1.0f : 0.6f);
        if (listening) {
            micButton.setColorFilter(0xFF5F8F73);
        } else {
            micButton.clearColorFilter();
        }
    }

    private void insertSpeechText(String text, boolean finalResult) {
        if (input == null || text == null) {
            return;
        }
        String spokenText = text.trim();
        if (spokenText.isEmpty()) {
            return;
        }
        int start = speechInsertStart >= 0 ? speechInsertStart : input.getText().length();
        start = Math.max(0, Math.min(start, input.getText().length()));
        input.getText().replace(start, input.getText().length(), spokenText);
        input.setSelection(input.getText().length());
        if (finalResult) {
            speechInsertStart = -1;
        }
    }

    private String speechErrorMessage(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "마이크 오디오 오류";
            case SpeechRecognizer.ERROR_CLIENT:
                return "음성 인식 클라이언트 오류";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "마이크 권한이 필요합니다";
            case SpeechRecognizer.ERROR_NETWORK:
                return "네트워크 오류";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "네트워크 시간 초과";
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "인식된 말이 없습니다";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "음성 인식기가 사용 중입니다";
            case SpeechRecognizer.ERROR_SERVER:
                return "음성 인식 서버 오류";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "말소리가 감지되지 않았습니다";
            default:
                return "알 수 없는 오류 " + error;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startSpeechRecognition();
            } else {
                Toast.makeText(this, "잠금화면 음성 입력에는 마이크 권한이 필요합니다.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    static boolean isShowing() {
        return showing;
    }

    static boolean isVisible() {
        return visible;
    }

    static long lastVisibleAt() {
        return lastVisibleAt;
    }

    private void configureLockWindow() {
        boolean turnScreenOn = getIntent().getBooleanExtra(EXTRA_TURN_SCREEN_ON, true);
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(0f);
        window.setFormat(PixelFormat.TRANSLUCENT);
        window.setBackgroundDrawable(new ColorDrawable(0x00000000));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(turnScreenOn);
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
            if (turnScreenOn) {
                window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
            }
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setNavigationBarColor(0x00000000);
        getWindow().setStatusBarColor(overlayColor());
        int systemUiFlags = getWindow().getDecorView().getSystemUiVisibility();
        systemUiFlags |= View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            systemUiFlags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            systemUiFlags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(systemUiFlags);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            if (getWindow().getInsetsController() != null) {
                getWindow().getInsetsController().hide(android.view.WindowInsets.Type.navigationBars());
                getWindow().getInsetsController().setSystemBarsBehavior(
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setNavigationBarContrastEnforced(false);
            getWindow().setStatusBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && getWindow().getInsetsController() != null) {
            getWindow().getInsetsController().setSystemBarsAppearance(0,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        }
    }

    private void updateWallpaperBackground(boolean force) {
        if (wallpaperBackground == null) {
            return;
        }

        String imageUri = AppSettings.lockBackgroundImageUri(this);
        if (imageUri != null && imageUri.length() > 0) {
            DiagnosticLog.record(this, "NudgeLockActivity", "selected background configured; system wallpaper layer will be covered");
            if (applySelectedWallpaperImage(imageUri, force)) {
                return;
            }
            DiagnosticLog.record(this, "NudgeLockActivity", "selected background unavailable; falling back to system wallpaper layer");
        }

        wallpaperBackground.setImageDrawable(null);
        wallpaperBackground.setBackgroundColor(0x00000000);
        wallpaperBackground.setVisibility(View.GONE);
        lastBackgroundKey = "system-wallpaper";
        DiagnosticLog.record(this, "NudgeLockActivity", "using system wallpaper background");
        recordSystemWallpaperState();
    }

    private void recordSystemWallpaperState() {
        try {
            WallpaperManager wallpaperManager = WallpaperManager.getInstance(this);
            StringBuilder message = new StringBuilder("system wallpaper state");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                message.append(" lockId=").append(wallpaperManager.getWallpaperId(WallpaperManager.FLAG_LOCK));
                message.append(" systemId=").append(wallpaperManager.getWallpaperId(WallpaperManager.FLAG_SYSTEM));
                WallpaperInfo lockInfo = wallpaperManager.getWallpaperInfo(WallpaperManager.FLAG_LOCK);
                WallpaperInfo systemInfo = wallpaperManager.getWallpaperInfo(WallpaperManager.FLAG_SYSTEM);
                message.append(" lockInfo=").append(wallpaperInfoLabel(lockInfo));
                message.append(" systemInfo=").append(wallpaperInfoLabel(systemInfo));
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                message.append(" lockColors=").append(wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_LOCK) != null);
                message.append(" systemColors=").append(wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM) != null);
            }
            boolean showWallpaper = (getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER) != 0;
            message.append(" showWallpaperFlag=").append(showWallpaper);
            DiagnosticLog.record(this, "NudgeLockActivity", message.toString());
        } catch (RuntimeException e) {
            DiagnosticLog.record(this, "NudgeLockActivity", "system wallpaper state failed", e);
        }
    }

    private String wallpaperInfoLabel(WallpaperInfo info) {
        if (info == null) {
            return "null";
        }
        return info.getPackageName() + "/" + info.getServiceName();
    }

    private boolean applySelectedWallpaperImage(String imageUri, boolean force) {
        String backgroundKey = "image:" + imageUri;
        if (!force && backgroundKey.equals(lastBackgroundKey) && wallpaperBackground.getDrawable() != null) {
            return true;
        }

        Uri uri;
        try {
            uri = Uri.parse(imageUri);
        } catch (RuntimeException e) {
            DiagnosticLog.record(this, "NudgeLockActivity", "selected background uri parse failed uri=" + imageUri, e);
            return false;
        }

        try (InputStream ignored = getContentResolver().openInputStream(uri)) {
            if (ignored == null) {
                DiagnosticLog.record(this, "NudgeLockActivity", "selected background open returned null uri=" + uri);
                return false;
            }
        } catch (IOException | RuntimeException e) {
            DiagnosticLog.record(this, "NudgeLockActivity", "selected background open failed uri=" + uri, e);
            return false;
        }

        try {
            wallpaperBackground.setVisibility(View.VISIBLE);
            wallpaperBackground.setBackgroundColor(0x00000000);
            wallpaperBackground.setScaleType(ImageView.ScaleType.CENTER_CROP);
            wallpaperBackground.setImageURI(uri);
            if (wallpaperBackground.getDrawable() == null) {
                wallpaperBackground.setVisibility(View.GONE);
                DiagnosticLog.record(this, "NudgeLockActivity", "selected background decode failed uri=" + uri);
                return false;
            }
            lastBackgroundKey = backgroundKey;
            DiagnosticLog.record(this, "NudgeLockActivity", "selected background image applied uri=" + uri);
            return true;
        } catch (RuntimeException e) {
            wallpaperBackground.setImageDrawable(null);
            wallpaperBackground.setVisibility(View.GONE);
            DiagnosticLog.record(this, "NudgeLockActivity", "selected background apply failed uri=" + uri, e);
            return false;
        }
    }

    private View buildContent() {
        FrameLayout shell = new CurtainFrameLayout(this);

        wallpaperBackground = new ImageView(this);
        wallpaperBackground.setBackgroundColor(0x00000000);
        wallpaperBackground.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallpaperBackground.setVisibility(View.GONE);
        shell.addView(wallpaperBackground, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        updateWallpaperBackground(true);

        curtainBackground = new View(this);
        curtainBackground.setBackgroundColor(overlayColor());
        shell.addView(curtainBackground, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        curtainContent = new FrameLayout(this);
        shell.addView(curtainContent, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        menuButton = text("\u22ee", 28, 0xDFFFFFFF, false);
        menuButton.setGravity(Gravity.CENTER);
        menuButton.setOnClickListener(v -> toggleMenu());
        FrameLayout.LayoutParams menuParams = new FrameLayout.LayoutParams(
                dp(48),
                dp(48),
                Gravity.TOP | Gravity.RIGHT
        );
        menuParams.topMargin = dp(34);
        menuParams.rightMargin = dp(12);

        menuPanel = new LinearLayout(this);
        menuPanel.setOrientation(LinearLayout.HORIZONTAL);
        menuPanel.setGravity(Gravity.CENTER);
        menuPanel.setVisibility(View.GONE);
        menuPanel.setAlpha(0f);
        menuPanel.setTranslationY(-dp(6));
        menuPanel.setPadding(0, 0, 0, 0);
        menuPanel.setBackgroundColor(0x00000000);

        lockButton = new LockToggleView(this);
        lockButton.setOnClickListener(v -> toggleTodoLock());
        menuPanel.addView(lockButton, new LinearLayout.LayoutParams(dp(44), dp(44)));

        undoButton = new UndoButtonView(this);
        undoButton.setOnClickListener(v -> undoDelete());
        menuPanel.addView(undoButton, new LinearLayout.LayoutParams(dp(44), dp(44)));

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                dp(44),
                Gravity.TOP | Gravity.RIGHT
        );
        panelParams.topMargin = dp(82);
        panelParams.rightMargin = dp(12);

        updateMenuButtons();

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0x00000000);
        curtainContent.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(18), dp(34), dp(18), dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setGravity(Gravity.CENTER);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(timeRow, fullWidthWrap());

        timeText = text("", 52, 0xEFFFFFFF, false);
        timeText.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        timeRow.addView(timeText);

        meridiemText = text("", 13, 0xDFFFFFFF, false);
        meridiemText.setPadding(dp(5), 0, 0, dp(12));
        timeRow.addView(meridiemText);

        dateText = text("", 16, 0xCCFFFFFF, false);
        dateText.setGravity(Gravity.CENTER);
        root.addView(dateText, fullWidthWrap());
        updateClock();

        FrameLayout plusTouchRow = new FrameLayout(this);
        plusTouchRow.setClickable(false);
        plusTouchRow.setFocusable(false);
        plusTouchRow.setClipChildren(false);
        plusTouchRow.setClipToPadding(false);
        plusTouchRow.setTranslationY(dp(2));
        plusButton = new PlusButtonView(this);
        plusButton.setOnClickListener(v -> toggleInput());
        FrameLayout.LayoutParams plusButtonParams = new FrameLayout.LayoutParams(
                dp(44),
                dp(44),
                Gravity.CENTER
        );
        plusTouchRow.addView(plusButton, plusButtonParams);

        micButton = new ImageView(this);
        micButton.setImageResource(R.drawable.ic_mic);
        micButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        micButton.setAlpha(0f);
        micButton.setVisibility(View.GONE);
        micButton.setTranslationX(0f);
        micButton.setScaleX(0.9f);
        micButton.setScaleY(0.9f);
        micButton.setOnClickListener(v -> toggleSpeechRecognition());
        FrameLayout.LayoutParams plusMicParams = new FrameLayout.LayoutParams(
                dp(44),
                dp(44),
                Gravity.CENTER
        );
        plusTouchRow.addView(micButton, plusMicParams);
        root.addView(plusTouchRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(70)
        ));

        inputBlock = new LinearLayout(this);
        inputBlock.setOrientation(LinearLayout.VERTICAL);
        inputBlock.setVisibility(View.GONE);
        inputBlock.setAlpha(0f);
        inputBlock.setTranslationY(-dp(8));
        LinearLayout.LayoutParams inputBlockParams = fullWidthWrap();
        inputBlockParams.height = 0;
        root.addView(inputBlock, inputBlockParams);

        inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);

        editModeLabel = text("", 13, 0xCCFFFFFF, true);
        editModeLabel.setGravity(Gravity.CENTER);
        editModeLabel.setSingleLine(true);
        editModeLabel.setVisibility(View.GONE);
        inputBlock.addView(editModeLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(24)
        ));

        inputBlock.addView(inputRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(72)
        ));

        View inputLeftSpacer = new View(this);
        inputRow.addView(inputLeftSpacer, new LinearLayout.LayoutParams(dp(44), dp(72)));

        input = new EditText(this);
        input.setSingleLine(false);
        input.setMinLines(1);
        input.setMaxLines(3);
        input.setHorizontallyScrolling(false);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setHint(getString(R.string.new_todo_hint));
        input.setGravity(Gravity.CENTER);
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0x99FFFFFF);
        input.setTextSize(16);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_ENTER_ACTION);
        input.setBackgroundColor(0x00000000);
        input.setPadding(dp(12), 0, dp(12), 0);
        input.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitInputTodo();
                return true;
            }
            return false;
        });
        inputRow.addView(input, new LinearLayout.LayoutParams(0, dp(72), 1));

        inputSubmitButton = new CheckSubmitButtonView(this);
        inputSubmitButton.setOnClickListener(v -> submitInputTodo());
        inputRow.addView(inputSubmitButton, new LinearLayout.LayoutParams(dp(44), dp(72)));

        inputDivider = text("\u2013", 16, 0x66FFFFFF, false);
        inputDivider.setGravity(Gravity.CENTER);
        inputBlock.addView(inputDivider, compactDividerParams());

        topTodoDivider = text("\u2013", 16, 0x66FFFFFF, false);
        topTodoDivider.setGravity(Gravity.CENTER);
        topTodoDivider.setVisibility(View.GONE);
        root.addView(topTodoDivider, topTodoDividerParams());

        editUndoBanner = text("", 14, 0xEFFFFFFF, true);
        editUndoBanner.setGravity(Gravity.CENTER);
        editUndoBanner.setSingleLine(true);
        editUndoBanner.setVisibility(View.GONE);
        editUndoBanner.setBackground(rounded(0x225F8F73, dp(12)));
        editUndoBanner.setOnClickListener(v -> undoLastEdit());
        LinearLayout.LayoutParams editUndoParams = narrowParams();
        editUndoParams.height = dp(32);
        editUndoParams.topMargin = dp(4);
        root.addView(editUndoBanner, editUndoParams);

        todoList = new LinearLayout(this);
        todoList.setOrientation(LinearLayout.VERTICAL);
        todoList.setGravity(Gravity.CENTER_HORIZONTAL);
        todoList.setPadding(0, 0, 0, dp(18));
        todoList.setTranslationY(-dp(1));
        root.addView(todoList, narrowParams());

        curtainContent.addView(menuButton, menuParams);
        curtainContent.addView(menuPanel, panelParams);
        registerKeyboardVisibilityWatcher(shell);
        return shell;
    }

    private void updateClock() {
        if (timeText == null || meridiemText == null || dateText == null) {
            return;
        }
        Date now = new Date();
        timeText.setText(new SimpleDateFormat("h:mm", Locale.getDefault()).format(now));
        meridiemText.setText(new SimpleDateFormat("a", Locale.ENGLISH).format(now));
        dateText.setText(new SimpleDateFormat("EEEE, MMMM d", Locale.ENGLISH).format(now));
    }

    private void registerClockReceiver() {
        if (clockReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_TIME_TICK);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_DATE_CHANGED);
        registerReceiver(clockReceiver, filter);
        clockReceiverRegistered = true;
    }

    private void unregisterClockReceiver() {
        if (!clockReceiverRegistered) {
            return;
        }
        unregisterReceiver(clockReceiver);
        clockReceiverRegistered = false;
    }

    private void toggleMenu() {
        if (menuPanel.getVisibility() == View.VISIBLE) {
            menuPanel.animate()
                    .alpha(0f)
                    .translationY(-dp(6))
                    .setDuration(140)
                    .withEndAction(() -> menuPanel.setVisibility(View.GONE))
                    .start();
            return;
        }
        updateMenuButtons();
        menuPanel.animate().cancel();
        menuPanel.setVisibility(View.VISIBLE);
        menuPanel.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(160)
                .start();
    }

    private void toggleTodoLock() {
        todosLocked = !todosLocked;
        AppSettings.setTodosLocked(this, todosLocked);
        DiagnosticLog.record(this, "NudgeLockActivity", "todos locked=" + todosLocked);
        updateMenuButtons();
        refreshTodos();
    }

    private void updateMenuButtons() {
        if (lockButton != null) {
            lockButton.setLocked(todosLocked);
        }
        if (undoButton != null) {
            boolean canUndo = !deletedTodos.isEmpty();
            undoButton.setActive(canUndo);
        }
    }

    private void submitInputTodo() {
        if (isEditingTodo()) {
            saveEditedTodo();
            return;
        }
        addTodo();
    }

    private void addTodo() {
        if (input.getText().toString().trim().length() == 0) {
            clearInputDraft();
            input.setText("");
            hideInput(true);
            return;
        }
        TodoStore.add(this, input.getText().toString());
        clearInputDraft();
        input.setText("");
        hideInput(true);
        refreshTodos();
    }

    private void refreshTodos() {
        if (todoList == null) {
            return;
        }

        List<TodoItem> items = TodoStore.load(this);
        String renderKey = TodoCodec.encode(items) + "|" + todosLocked + "|" + editingTodoId;
        updateTopTodoDividerVisibility(!items.isEmpty() && inputBlock.getVisibility() != View.VISIBLE);
        if (renderKey.equals(lastRenderedTodoKey) && todoList.getChildCount() > 0) {
            return;
        }

        todoList.removeAllViews();
        lastRenderedTodoKey = renderKey;
        if (items.isEmpty()) {
            TextView empty = text(getString(R.string.empty_todos_lock), 16, 0xBFFFFFFF, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(28), 0, dp(24));
            todoList.addView(empty);
            if (!firstTodoRender) {
                animateIn(empty, 0);
            }
            firstTodoRender = false;
            return;
        }

        boolean animateRows = !firstTodoRender;
        for (int i = 0; i < items.size(); i++) {
            View row = todoRow(items.get(i), i);
            todoList.addView(row);
            if (animateRows) {
                animateIn(row, i * 35);
            }
            TextView divider = text("\u2013", 16, 0x66FFFFFF, false);
            divider.setGravity(Gravity.CENTER);
            if (!todosLocked) {
                divider.setOnTouchListener(new DividerTouchListener(i));
            }
            todoList.addView(divider, compactDividerParams());
            if (animateRows) {
                animateIn(divider, i * 35 + 20);
            }
        }
        firstTodoRender = false;
    }

    private void updateTopTodoDividerVisibility(boolean visible) {
        if (topTodoDivider == null) {
            return;
        }
        topTodoDivider.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private View todoRow(TodoItem item, int index) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, 0, 0, 0);
        row.setTag(item.id);
        if (item.id == editingTodoId) {
            row.setBackground(rounded(0x185F8F73, dp(14)));
        }

        row.addView(text("", 1, 0x00FFFFFF, false), new LinearLayout.LayoutParams(dp(58), dp(38)));

        TextView label = text(item.text, 16, 0xFFFFFFFF, false);
        label.setGravity(Gravity.CENTER);
        row.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView hint = text("", 14, 0x00FFFFFF, false);
        hint.setGravity(Gravity.CENTER);
        row.addView(hint, new LinearLayout.LayoutParams(dp(58), dp(38)));

        if (todosLocked) {
            return row;
        }

        SwipeActionListener swipeActionListener = new SwipeActionListener(item, index, row, hint);
        label.setOnTouchListener(swipeActionListener);
        label.setOnClickListener(v -> handleTodoTap(item));
        return row;
    }

    private void beginTodoDrag(View row, TodoItem item, float rawY) {
        draggingTodoId = item.id;
        draggingTodoIndex = indexOfTodoRow(row);
        dragPreviewIndex = draggingTodoIndex;
        draggingTodoRow = row;
        int[] location = new int[2];
        row.getLocationOnScreen(location);
        draggingTodoStartCenterY = location[1] + row.getHeight() * 0.5f;
        draggingTouchToCenterOffset = draggingTodoStartCenterY - rawY;
        draggingTodoMoveOffset = todoMoveOffsetForIndex(draggingTodoIndex);
        hideDraggedTodoDividers(row);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            row.setElevation(dp(6));
        }
        row.animate().scaleX(1.03f).scaleY(1.03f).alpha(0.9f).setDuration(120).start();
    }

    private void updateTodoDrag(float rawY) {
        if (draggingTodoRow == null || draggingTodoIndex < 0) {
            return;
        }
        float draggedCenterY = rawY + draggingTouchToCenterOffset;
        float translationY = draggedCenterY - draggingTodoStartCenterY;
        draggingTodoRow.setTranslationY(translationY);

        int targetIndex = dragTargetIndex(draggedCenterY);
        updateReorderPreview(targetIndex);
    }

    private void finishTodoDrag(TodoItem item) {
        if (draggingTodoRow == null || draggingTodoIndex < 0) {
            resetTodoDragState();
            return;
        }
        int targetIndex = Math.max(0, dragPreviewIndex);
        View row = draggingTodoRow;
        row.animate().cancel();
        int dropDuration = targetIndex == draggingTodoIndex ? 180 : 150;
        float targetTranslation = dragTranslationToIndex(targetIndex);
        if (targetIndex == draggingTodoIndex) {
            animateReorderPreviewToZero(dropDuration);
        }
        row.animate()
                .translationY(targetTranslation)
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(dropDuration)
                .withEndAction(() -> {
                    if (targetIndex != draggingTodoIndex) {
                        TodoStore.move(LockActivity.this, item.id, targetIndex);
                        moveTodoViews(draggingTodoIndex, targetIndex);
                    }
                    clearReorderPreviewImmediately();
                    restoreDraggedTodoDividers();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        row.setElevation(0f);
                    }
                    resetTodoDragState();
                })
                .start();
    }

    private void animateReorderPreviewToZero(int duration) {
        for (int i = 0; i < todoList.getChildCount(); i++) {
            View child = todoList.getChildAt(i);
            if (child == draggingTodoRow) {
                continue;
            }
            child.animate().cancel();
            child.animate().translationY(0f).setDuration(duration).start();
        }
    }

    private void cancelTodoDrag() {
        if (draggingTodoRow != null) {
            draggingTodoRow.animate()
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(140)
                    .start();
        }
        clearReorderPreview();
        restoreDraggedTodoDividers();
        resetTodoDragState();
    }

    private void resetTodoDragState() {
        draggingTodoId = -1L;
        draggingTodoIndex = -1;
        dragPreviewIndex = -1;
        draggingTodoRow = null;
        draggingTodoStartCenterY = 0f;
        draggingTouchToCenterOffset = 0f;
        draggingTodoMoveOffset = 0;
        draggingPreviousDivider = null;
        draggingNextDivider = null;
    }

    private void hideDraggedTodoDividers(View row) {
        int rowPosition = todoList.indexOfChild(row);
        draggingPreviousDivider = rowPosition > 0 ? todoList.getChildAt(rowPosition - 1) : null;
        draggingNextDivider = rowPosition + 1 < todoList.getChildCount()
                ? todoList.getChildAt(rowPosition + 1)
                : null;
        setDividerVisibilityDuringDrag(draggingPreviousDivider, 0f);
        setDividerVisibilityDuringDrag(draggingNextDivider, 0f);
    }

    private void restoreDraggedTodoDividers() {
        setDividerVisibilityDuringDrag(draggingPreviousDivider, 1f);
        setDividerVisibilityDuringDrag(draggingNextDivider, 1f);
    }

    private void setDividerVisibilityDuringDrag(View divider, float alpha) {
        if (divider == null || divider == draggingTodoRow) {
            return;
        }
        divider.animate().cancel();
        divider.setAlpha(alpha);
    }

    private int dragTargetIndex(float rawY) {
        int itemCount = Math.max(0, (todoList.getChildCount() + 1) / 2);
        int nearestIndex = Math.max(0, Math.min(draggingTodoIndex, itemCount - 1));
        float nearestDistance = Float.MAX_VALUE;
        for (int i = 0; i < todoList.getChildCount(); i += 2) {
            View row = todoList.getChildAt(i);
            int[] location = new int[2];
            row.getLocationOnScreen(location);
            int rowIndex = i / 2;
            float middle = row == draggingTodoRow
                    ? draggingTodoStartCenterY
                    : location[1] + row.getHeight() * 0.5f - row.getTranslationY();
            float distance = Math.abs(rawY - middle);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearestIndex = rowIndex;
            }
        }
        return nearestIndex;
    }

    private int indexOfTodoRow(View row) {
        int rowPosition = todoList.indexOfChild(row);
        return rowPosition < 0 ? -1 : rowPosition / 2;
    }

    private void updateReorderPreview(int targetIndex) {
        if (draggingTodoIndex < 0 || targetIndex == dragPreviewIndex) {
            return;
        }
        dragPreviewIndex = targetIndex;
        int offset = draggingTodoMoveOffset > 0 ? draggingTodoMoveOffset : todoMoveOffsetForIndex(draggingTodoIndex);
        for (int i = 0; i < todoList.getChildCount(); i += 2) {
            View row = todoList.getChildAt(i);
            Object tag = row.getTag();
            if (tag instanceof Long && (Long) tag == draggingTodoId) {
                continue;
            }
            int rowIndex = i / 2;
            float translation = 0f;
            if (targetIndex > draggingTodoIndex && rowIndex > draggingTodoIndex && rowIndex <= targetIndex) {
                translation = -offset;
            } else if (targetIndex < draggingTodoIndex && rowIndex >= targetIndex && rowIndex < draggingTodoIndex) {
                translation = offset;
            }
            row.animate().translationY(translation).setDuration(150).start();
            if (i + 1 < todoList.getChildCount()) {
                todoList.getChildAt(i + 1).animate().translationY(translation).setDuration(150).start();
            }
        }
    }

    private float dragTranslationToIndex(int targetIndex) {
        if (targetIndex == draggingTodoIndex) {
            return 0f;
        }
        int from = Math.min(draggingTodoIndex, targetIndex);
        int to = Math.max(draggingTodoIndex, targetIndex);
        int distance = 0;
        for (int index = from; index <= to; index++) {
            if (index == draggingTodoIndex) {
                continue;
            }
            distance += todoMoveOffsetForIndex(index);
        }
        return targetIndex > draggingTodoIndex ? distance : -distance;
    }

    private void clearReorderPreview() {
        for (int i = 0; i < todoList.getChildCount(); i++) {
            todoList.getChildAt(i).animate().translationY(0f).setDuration(120).start();
        }
    }

    private void clearReorderPreviewImmediately() {
        for (int i = 0; i < todoList.getChildCount(); i++) {
            View child = todoList.getChildAt(i);
            child.animate().cancel();
            child.setTranslationY(0f);
            child.setScaleX(1f);
            child.setScaleY(1f);
            child.setAlpha(1f);
        }
    }

    private void moveTodoViews(int fromIndex, int toIndex) {
        int fromChildIndex = fromIndex * 2;
        if (fromChildIndex < 0 || fromChildIndex >= todoList.getChildCount()) {
            return;
        }
        View row = todoList.getChildAt(fromChildIndex);
        View divider = fromChildIndex + 1 < todoList.getChildCount()
                ? todoList.getChildAt(fromChildIndex + 1)
                : null;
        android.animation.LayoutTransition transition = todoList.getLayoutTransition();
        todoList.setLayoutTransition(null);
        todoList.removeView(row);
        if (divider != null) {
            todoList.removeView(divider);
        }

        int safeIndex = Math.max(0, Math.min(toIndex, todoList.getChildCount() / 2));
        int insertChildIndex = safeIndex * 2;
        todoList.addView(row, insertChildIndex);
        if (divider != null) {
            todoList.addView(divider, insertChildIndex + 1);
        }
        todoList.setLayoutTransition(transition);
    }

    private int todoMoveOffsetForIndex(int index) {
        int rowHeight = dp(54);
        int dividerHeight = dp(24);
        int rowChildIndex = index * 2;
        if (rowChildIndex >= 0 && rowChildIndex < todoList.getChildCount()) {
            View row = todoList.getChildAt(rowChildIndex);
            if (row.getHeight() > 0) {
                rowHeight = row.getHeight();
            }
        }
        int dividerChildIndex = rowChildIndex + 1;
        if (dividerChildIndex >= 0 && dividerChildIndex < todoList.getChildCount()) {
            View divider = todoList.getChildAt(dividerChildIndex);
            if (divider.getHeight() > 0) {
                dividerHeight = divider.getHeight();
            }
        }
        return rowHeight + dividerHeight;
    }

    private void handleTodoTap(TodoItem item) {
        long now = SystemClock.uptimeMillis();
        if (pendingTapItemId == item.id) {
            pendingTapItemId = -1L;
            uiHandler.removeCallbacksAndMessages(item.id);
            beginEditTodo(item);
            return;
        }

        pendingTapItemId = item.id;
        uiHandler.postAtTime(() -> {
            if (pendingTapItemId == item.id) {
                pendingTapItemId = -1L;
            }
        }, item.id, now + TODO_DOUBLE_TAP_MS);
    }

    private void beginEditTodo(TodoItem item) {
        if (item == null || input == null) {
            return;
        }
        if (inputBlock.getVisibility() == View.VISIBLE && !isEditingTodo()) {
            saveInputDraft();
        }
        editingTodoId = item.id;
        editingOriginalText = item.text == null ? "" : item.text;
        hideEditUndoBanner();
        updateEditModeLabel();
        input.setHint(getString(R.string.edit_todo));
        input.setText(item.text);
        input.setSelection(input.getText().length());
        showInputBlock(false);
        refreshTodos();
    }

    private void saveEditedTodo() {
        long editedId = editingTodoId;
        String oldText = editingOriginalText;
        String newText = input.getText().toString().trim();
        if (!newText.isEmpty() && !newText.equals(oldText)) {
            TodoStore.setText(this, editedId, newText);
            lastEditedTodo = new EditedTodo(editedId, oldText);
            showEditUndoBanner();
        } else {
            hideEditUndoBanner();
        }
        clearEditingTodo();
        input.setText("");
        hideInput(true);
        refreshTodos();
    }

    private boolean isEditingTodo() {
        return editingTodoId >= 0L;
    }

    private void clearEditingTodo() {
        editingTodoId = -1L;
        editingOriginalText = "";
        if (input != null) {
            input.setHint(getString(R.string.new_todo_hint));
        }
        if (editModeLabel != null) {
            editModeLabel.setText("");
            editModeLabel.setVisibility(View.GONE);
        }
    }

    private void updateEditModeLabel() {
        if (editModeLabel == null) {
            return;
        }
        if (!isEditingTodo()) {
            editModeLabel.setText("");
            editModeLabel.setVisibility(View.GONE);
            return;
        }
        String preview = editingOriginalText == null ? "" : editingOriginalText.trim();
        if (preview.length() > 18) {
            preview = preview.substring(0, 18) + "...";
        }
        editModeLabel.setText("수정 중 · " + preview);
        editModeLabel.setVisibility(View.VISIBLE);
    }

    private void showEditUndoBanner() {
        if (editUndoBanner == null) {
            return;
        }
        editUndoBanner.setText("수정됨 · 되돌리기");
        editUndoBanner.setVisibility(View.VISIBLE);
        uiHandler.removeCallbacksAndMessages(editUndoBanner);
        uiHandler.postAtTime(this::hideEditUndoBanner, editUndoBanner, SystemClock.uptimeMillis() + 5000L);
    }

    private void hideEditUndoBanner() {
        if (editUndoBanner == null) {
            return;
        }
        uiHandler.removeCallbacksAndMessages(editUndoBanner);
        editUndoBanner.setVisibility(View.GONE);
        editUndoBanner.setText("");
    }

    private void undoLastEdit() {
        if (lastEditedTodo == null) {
            hideEditUndoBanner();
            return;
        }
        TodoStore.setText(this, lastEditedTodo.id, lastEditedTodo.oldText);
        lastEditedTodo = null;
        hideEditUndoBanner();
        refreshTodos();
    }

    private void showEditTodoDialog(TodoItem item) {
        Dialog dialog = new Dialog(this);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(22), dp(20), dp(22), dp(16));
        panel.setBackground(rounded(0xEE171717, dp(18)));

        TextView title = text(getString(R.string.edit_todo), 17, 0xF2FFFFFF, true);
        title.setGravity(Gravity.CENTER);
        panel.addView(title, fullWidthWrap());

        EditText editor = new EditText(this);
        editor.setSingleLine(false);
        editor.setText(item.text);
        editor.setSelectAllOnFocus(false);
        editor.setTextColor(0xFFFFFFFF);
        editor.setHintTextColor(0x88FFFFFF);
        editor.setTextSize(16);
        editor.setGravity(Gravity.CENTER);
        editor.setPadding(dp(12), dp(10), dp(12), dp(10));
        editor.setBackground(rounded(0x22FFFFFF, dp(10)));
        LinearLayout.LayoutParams editorParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(62)
        );
        editorParams.topMargin = dp(14);
        panel.addView(editor, editorParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
        );
        actionsParams.topMargin = dp(10);
        panel.addView(actions, actionsParams);

        TextView cancel = text(getString(R.string.cancel), 15, 0xAAFFFFFF, false);
        cancel.setGravity(Gravity.CENTER);
        cancel.setOnClickListener(v -> dialog.dismiss());
        actions.addView(cancel, new LinearLayout.LayoutParams(dp(70), dp(44)));

        TextView save = text(getString(R.string.save), 15, 0xF2FFFFFF, true);
        save.setGravity(Gravity.CENTER);
        save.setOnClickListener(v -> {
            TodoStore.setText(this, item.id, editor.getText().toString());
            dialog.dismiss();
            refreshTodos();
        });
        actions.addView(save, new LinearLayout.LayoutParams(dp(70), dp(44)));

        editor.setOnEditorActionListener((view, actionId, event) -> {
            boolean enterPressed = event != null
                    && event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
            if (actionId == EditorInfo.IME_ACTION_DONE || enterPressed) {
                TodoStore.setText(this, item.id, editor.getText().toString());
                dialog.dismiss();
                refreshTodos();
                return true;
            }
            return false;
        });

        dialog.setContentView(panel);
        dialog.setOnShowListener(d -> {
            editor.requestFocus();
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(0x00000000));
                dialog.getWindow().setDimAmount(0.42f);
                dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
                dialog.getWindow().setLayout(
                        Math.round(getResources().getDisplayMetrics().widthPixels * 0.84f),
                        WindowManager.LayoutParams.WRAP_CONTENT
                );
            }
        });
        dialog.show();
    }

    private void deleteTodo(TodoItem item, int index) {
        deletedTodos.push(new DeletedTodo(item, index));
        updateMenuButtons();
        TodoStore.remove(this, item.id);
        refreshTodos();
    }

    private void animateDeleteTodo(TodoItem item, int index, View rowView) {
        rowView.setEnabled(false);
        int rowPosition = todoList.indexOfChild(rowView);
        View dividerView = rowPosition >= 0 && rowPosition + 1 < todoList.getChildCount()
                ? todoList.getChildAt(rowPosition + 1)
                : null;
        int rowHeight = rowView.getHeight() > 0 ? rowView.getHeight() : dp(54);
        int dividerHeight = dividerView != null && dividerView.getHeight() > 0 ? dividerView.getHeight() : dp(24);

        rowView.animate()
                .translationX(rowView.getWidth() * 0.8f)
                .alpha(0f)
                .setDuration(150)
                .start();
        if (dividerView != null) {
            dividerView.animate()
                    .alpha(0f)
                    .translationY(-dp(4))
                    .setDuration(150)
                    .start();
        }

        ValueAnimator collapse = ValueAnimator.ofFloat(1f, 0f);
        collapse.setStartDelay(70);
        collapse.setDuration(190);
        collapse.addUpdateListener(animation -> {
            float value = (Float) animation.getAnimatedValue();
            setViewHeight(rowView, Math.round(rowHeight * value));
            if (dividerView != null) {
                setViewHeight(dividerView, Math.round(dividerHeight * value));
            }
        });
        collapse.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                deleteTodo(item, index);
            }
        });
        collapse.start();
    }

    private void undoDelete() {
        if (deletedTodos.isEmpty()) {
            return;
        }
        DeletedTodo deletedTodo = deletedTodos.pop();
        TodoStore.restore(this, deletedTodo.item, deletedTodo.index);
        updateMenuButtons();
        refreshTodos();
    }

    private static final class DeletedTodo {
        final TodoItem item;
        final int index;

        DeletedTodo(TodoItem item, int index) {
            this.item = item;
            this.index = index;
        }
    }

    private static final class EditedTodo {
        final long id;
        final String oldText;

        EditedTodo(long id, String oldText) {
            this.id = id;
            this.oldText = oldText;
        }
    }

    private void toggleInput() {
        if (inputBlock.getVisibility() == View.VISIBLE) {
            closeInputFromButton();
            return;
        }
        clearEditingTodo();
        restoreInputDraft();
        showInputBlock(true);
    }

    private void showInputBlock(boolean animate) {
        animatePlusOpen();
        updateTopTodoDividerVisibility(false);
        if (inputBlock.getVisibility() != View.VISIBLE) {
            inputBlock.animate().cancel();
            cancelInputBlockHeightAnimation();
            inputBlock.setVisibility(View.VISIBLE);
            if (animate) {
                inputBlock.setAlpha(0f);
                inputBlock.setTranslationY(-dp(8));
                setInputBlockHeight(0);
                animateInputBlockHeight(0, inputBlockTargetHeight(), 190, null);
                inputBlock.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(190)
                        .start();
            } else {
                inputBlock.setAlpha(1f);
                inputBlock.setTranslationY(0f);
                setInputBlockHeight(inputBlockTargetHeight());
            }
        }
        setMicButtonVisible(true);
        input.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void hideInput() {
        hideInput(false);
    }

    private void hideInput(boolean hideKeyboard) {
        if (hideKeyboard) {
            hideKeyboard();
            if (input != null) {
                input.clearFocus();
            }
        }
        setMicButtonVisible(false);
        stopSpeechRecognition();
        animatePlusClosed();
        inputBlock.animate().cancel();
        cancelInputBlockHeightAnimation();
        animateInputBlockHeight(inputBlock.getHeight(), 0, 170, () -> {
            inputBlock.setVisibility(View.GONE);
            updateTopTodoDividerVisibility(!TodoStore.load(this).isEmpty());
        });
        inputBlock.animate()
                .alpha(0f)
                .translationY(-dp(8))
                .setDuration(170)
                .start();
    }

    private void closeInputFromButton() {
        clearInputDraft();
        boolean wasEditing = isEditingTodo();
        clearEditingTodo();
        if (input != null) {
            input.setText("");
        }
        hideInput(true);
        if (wasEditing) {
            refreshTodos();
        }
    }

    private void saveInputDraft() {
        if (isEditingTodo()) {
            return;
        }
        pendingInputDraft = input == null ? "" : input.getText().toString();
        pendingInputDraftSavedAt = pendingInputDraft.length() == 0 ? 0L : SystemClock.elapsedRealtime();
    }

    private void restoreInputDraft() {
        if (input == null || pendingInputDraft.length() == 0) {
            return;
        }
        if (SystemClock.elapsedRealtime() - pendingInputDraftSavedAt > INPUT_DRAFT_KEEP_MS) {
            clearInputDraft();
            return;
        }
        input.setText(pendingInputDraft);
        input.setSelection(input.getText().length());
    }

    private void clearInputDraft() {
        pendingInputDraft = "";
        pendingInputDraftSavedAt = 0L;
    }

    private void hideKeyboard() {
        if (input == null) {
            return;
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        }
    }

    private void registerKeyboardVisibilityWatcher(View root) {
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            Rect visibleFrame = new Rect();
            root.getWindowVisibleDisplayFrame(visibleFrame);
            int hiddenHeight = root.getRootView().getHeight() - visibleFrame.height();
            keyboardVisible = hiddenHeight > dp(140);
        });
    }

    private int inputBlockTargetHeight() {
        int height = dp(98);
        if (editModeLabel != null && editModeLabel.getVisibility() == View.VISIBLE) {
            height += dp(24);
        }
        return height;
    }

    private void setInputBlockHeight(int height) {
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) inputBlock.getLayoutParams();
        params.height = height;
        inputBlock.setLayoutParams(params);
    }

    private void setViewHeight(View view, int height) {
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) view.getLayoutParams();
        params.height = height;
        view.setLayoutParams(params);
    }

    private void animateInputBlockHeight(int fromHeight, int toHeight, long duration, Runnable endAction) {
        inputBlockHeightAnimator = ValueAnimator.ofInt(fromHeight, toHeight);
        inputBlockHeightAnimator.setDuration(duration);
        inputBlockHeightAnimator.addUpdateListener(animation -> setInputBlockHeight((Integer) animation.getAnimatedValue()));
        if (endAction != null) {
            inputBlockHeightAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
                private boolean canceled;

                @Override
                public void onAnimationCancel(android.animation.Animator animation) {
                    canceled = true;
                }

                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    inputBlockHeightAnimator = null;
                    if (!canceled) {
                        endAction.run();
                    }
                }
            });
        } else {
            inputBlockHeightAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    inputBlockHeightAnimator = null;
                }
            });
        }
        inputBlockHeightAnimator.start();
    }

    private void cancelInputBlockHeightAnimation() {
        if (inputBlockHeightAnimator != null) {
            inputBlockHeightAnimator.cancel();
            inputBlockHeightAnimator = null;
        }
    }

    private void animatePlusOpen() {
        if (plusButton == null) {
            return;
        }
        plusButton.animate().cancel();
        plusButton.animateOpen();
        plusButton.animate()
                .alpha(0.9f)
                .translationX(-dp(26))
                .translationY(-dp(2))
                .setDuration(220)
                .start();
    }

    private void animatePlusClosed() {
        if (plusButton == null) {
            return;
        }
        plusButton.animate().cancel();
        plusButton.animateClosed();
        plusButton.animate()
                .alpha(1f)
                .translationX(0f)
                .translationY(0f)
                .setDuration(190)
                .start();
    }

    private void setMicButtonVisible(boolean visible) {
        if (micButton == null) {
            return;
        }
        if (visible) {
            micButton.setVisibility(View.VISIBLE);
            micButton.animate().cancel();
            micButton.setAlpha(0f);
            micButton.setTranslationX(dp(10));
            micButton.setScaleX(0.88f);
            micButton.setScaleY(0.88f);
            micButton.animate()
                    .alpha(isListening ? 1.0f : 0.6f)
                    .translationX(dp(30))
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(220)
                    .start();
        } else {
            micButton.animate().cancel();
            micButton.animate()
                    .alpha(0f)
                    .translationX(dp(10))
                    .scaleX(0.88f)
                    .scaleY(0.88f)
                    .setDuration(160)
                    .withEndAction(() -> micButton.setVisibility(View.GONE))
                    .start();
        }
    }

    private final class PlusButtonView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private ValueAnimator iconAnimator;
        private float openProgress;

        PlusButtonView(Context context) {
            super(context);
            setClickable(true);
            setFocusable(true);
            paint.setColor(0xBFFFFFFF);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(dp(2));
        }

        @Override
        protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            setPivotX(width * 0.5f);
            setPivotY(height * 0.5f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float centerX = getWidth() * 0.5f;
            float centerY = getHeight() * 0.5f;
            float halfLength = dp(9) * (1f + 0.4142f * openProgress);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(0xBFFFFFFF);
            canvas.save();
            canvas.rotate(45f * openProgress, centerX, centerY);
            canvas.drawLine(centerX - halfLength, centerY, centerX + halfLength, centerY, paint);
            canvas.drawLine(centerX, centerY - halfLength, centerX, centerY + halfLength, paint);
            canvas.restore();
        }

        void animateOpen() {
            animateIconTo(1f, 160);
        }

        void animateClosed() {
            animateIconTo(0f, 150);
        }

        private void animateIconTo(float target, long duration) {
            if (iconAnimator != null) {
                iconAnimator.cancel();
            }
            iconAnimator = ValueAnimator.ofFloat(openProgress, target);
            iconAnimator.setDuration(duration);
            iconAnimator.addUpdateListener(animation -> {
                openProgress = (Float) animation.getAnimatedValue();
                invalidate();
            });
            iconAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    iconAnimator = null;
                }
            });
            iconAnimator.start();
        }
    }

    private final class LockToggleView extends View {
        private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF backgroundRect = new RectF();
        private final Drawable lockedIcon;
        private final Drawable unlockedIcon;
        private boolean locked;

        LockToggleView(Context context) {
            super(context);
            setClickable(true);
            setFocusable(true);
            lockedIcon = requireDrawable(R.drawable.ic_menu_lock_closed);
            unlockedIcon = requireDrawable(R.drawable.ic_menu_lock_open);
        }

        void setLocked(boolean locked) {
            this.locked = locked;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (locked) {
                drawLockedStateBackground(canvas);
            }
            drawCenteredIcon(canvas, locked ? lockedIcon : unlockedIcon, 0xFF);
        }

        private void drawLockedStateBackground(Canvas canvas) {
            float margin = dp(4);
            backgroundRect.set(margin, margin, getWidth() - margin, getHeight() - margin);
            backgroundPaint.setStyle(Paint.Style.FILL);
            backgroundPaint.setColor(0x22FFFFFF);
            canvas.drawRoundRect(backgroundRect, dp(12), dp(12), backgroundPaint);
            backgroundPaint.setStyle(Paint.Style.STROKE);
            backgroundPaint.setStrokeWidth(dp(1));
            backgroundPaint.setColor(0x26FFFFFF);
            canvas.drawRoundRect(backgroundRect, dp(12), dp(12), backgroundPaint);
        }
    }

    private final class CheckSubmitButtonView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        CheckSubmitButtonView(Context context) {
            super(context);
            setClickable(true);
            setFocusable(true);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float centerX = getWidth() * 0.5f;
            float centerY = getHeight() * 0.5f;

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2.4f));
            paint.setColor(0xDFFFFFFF);
            float visualOffsetX = -dp(1.5f);
            float startX = centerX + visualOffsetX - dp(7);
            float startY = centerY + dp(1);
            float midX = centerX + visualOffsetX - dp(1);
            float midY = centerY + dp(5);
            float endX = centerX + visualOffsetX + dp(8);
            float endY = centerY - dp(6);
            canvas.drawLine(startX, startY, midX, midY, paint);
            canvas.drawLine(midX, midY, endX, endY, paint);
        }
    }

    private final class UndoButtonView extends View {
        private final Drawable undoIcon;
        private boolean active;

        UndoButtonView(Context context) {
            super(context);
            setClickable(true);
            setFocusable(true);
            undoIcon = requireDrawable(R.drawable.ic_menu_undo_uturn);
        }

        void setActive(boolean active) {
            this.active = active;
            setEnabled(active);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            drawCenteredIcon(canvas, undoIcon, active ? 0xEF : 0xAF);
        }
    }

    private Drawable requireDrawable(int drawableId) {
        Drawable drawable;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            drawable = getResources().getDrawable(drawableId, getTheme());
        } else {
            drawable = getResources().getDrawable(drawableId);
        }
        if (drawable == null) {
            throw new IllegalStateException("Missing drawable resource: " + drawableId);
        }
        return drawable.mutate();
    }

    private void drawCenteredIcon(Canvas canvas, Drawable drawable, int alpha) {
        int side = Math.round(Math.min(canvas.getWidth(), canvas.getHeight()) * (24f / 44f));
        int left = Math.round((canvas.getWidth() - side) * 0.5f);
        int top = Math.round((canvas.getHeight() - side) * 0.5f);
        drawable.setBounds(left, top, left + side, top + side);
        drawable.setAlpha(alpha);
        drawable.draw(canvas);
    }

    private void continueSystemUnlock() {
        DiagnosticLog.record(this, "NudgeLockActivity", "continue system unlock");
        KeyguardManager keyguardManager = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && keyguardManager != null && keyguardManager.isKeyguardLocked()) {
            keyguardManager.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override
                public void onDismissSucceeded() {
                    DiagnosticLog.record(LockActivity.this, "NudgeLockActivity", "dismiss keyguard succeeded");
                    closeLockTask();
                }
            });
        } else {
            closeLockTask();
        }
    }

    private void lockNowFromCurtainDoubleTap() {
        if (!AppSettings.doubleTapScreenOffEnabled(this)) {
            DiagnosticLog.record(this, "NudgeLockActivity", "curtain double tap lock skipped; disabled");
            return;
        }
        DiagnosticLog.record(this, "NudgeLockActivity", "curtain double tap lock requested");
        DevicePolicyManager devicePolicyManager = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, NudgeDeviceAdminReceiver.class);
        if (devicePolicyManager != null && devicePolicyManager.isAdminActive(admin)) {
            devicePolicyManager.lockNow();
            return;
        }

        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.device_admin_explanation));
        try {
            startActivity(intent);
        } catch (RuntimeException e) {
            DiagnosticLog.record(this, "NudgeLockActivity", "device admin request failed", e);
        }
    }

    private void closeLockTask() {
        DiagnosticLog.record(this, "NudgeLockActivity", "close lock task");
        sendBroadcast(new Intent(LockMonitorService.ACTION_LOCK_DISMISSED_BY_USER).setPackage(getPackageName()));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask();
        } else {
            finish();
        }
        overridePendingTransition(0, 0);
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setIncludeFontPadding(true);
        if (bold) {
            view.setTypeface(view.getTypeface(), Typeface.BOLD);
        }
        return view;
    }

    private LinearLayout.LayoutParams fullWidthWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams compactDividerParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(20)
        );
    }

    private LinearLayout.LayoutParams topTodoDividerParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0
        );
    }

    private LinearLayout.LayoutParams narrowParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.leftMargin = dp(14);
        params.rightMargin = dp(14);
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private int overlayColor() {
        int alpha = Math.round(AppSettings.overlayOpacity(this) * 255f / 100f);
        return (alpha << 24);
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private void animateIn(View view, long delay) {
        view.setAlpha(0f);
        view.setTranslationY(dp(8));
        view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(delay)
                .setDuration(180)
                .start();
    }

    private final class CurtainFrameLayout extends FrameLayout {
        private float downX;
        private float downY;
        private long lastTapAt;
        private boolean curtainSwiping;
        private boolean curtainBlocked;

        CurtainFrameLayout(Context context) {
            super(context);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    curtainSwiping = false;
                    curtainBlocked = (!todosLocked && isInsideTodoGestureTarget(event))
                            || isInside(inputBlock, event)
                            || isInside(plusButton, event)
                            || isInside(menuButton, event)
                            || isInside(menuPanel, event);
                    animate().cancel();
                    if (curtainBackground != null) {
                        curtainBackground.animate().cancel();
                    }
                    if (curtainContent != null) {
                        curtainContent.animate().cancel();
                    }
                    if (!curtainBlocked) {
                        setCurtainContentTranslationX(0f);
                        setAlpha(1f);
                        setCurtainBackgroundAlpha(1f);
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (curtainBlocked) {
                        break;
                    }
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    if (!curtainSwiping && Math.abs(dx) > dp(22) && Math.abs(dx) > Math.abs(dy) * 1.25f) {
                        curtainSwiping = true;
                    }
                    if (curtainSwiping) {
                        boolean bothDirections = AppSettings.curtainUnlockBothDirections(LockActivity.this);
                        float resistedDx = (!bothDirections && dx < 0) ? dx * 0.18f : dx * 0.92f;
                        setCurtainContentTranslationX(resistedDx);
                        updateCurtainOpacity(resistedDx, bothDirections || dx > 0);
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (curtainSwiping) {
                        releaseCurtain(event.getRawX() - downX);
                        curtainSwiping = false;
                        return true;
                    }
                    if (event.getActionMasked() == MotionEvent.ACTION_UP
                            && !curtainBlocked
                            && isCurtainTap(event)) {
                        long now = SystemClock.elapsedRealtime();
                        if (now - lastTapAt <= CURTAIN_DOUBLE_TAP_MS) {
                            lastTapAt = 0L;
                            lockNowFromCurtainDoubleTap();
                            return true;
                        }
                        lastTapAt = now;
                    }
                    break;
                default:
                    break;
            }
            return super.dispatchTouchEvent(event);
        }

        private boolean isCurtainTap(MotionEvent event) {
            return Math.abs(event.getRawX() - downX) <= dp(10)
                    && Math.abs(event.getRawY() - downY) <= dp(10);
        }

        private void releaseCurtain(float dx) {
            boolean bothDirections = AppSettings.curtainUnlockBothDirections(LockActivity.this);
            boolean allowedDirection = bothDirections || dx > 0;
            float threshold = Math.max(dp(150), getWidth() * 0.32f);
            if (allowedDirection && Math.abs(dx) >= threshold) {
                float targetX = dx < 0 ? -getWidth() : getWidth();
                if (curtainContent != null) {
                    curtainContent.animate()
                            .translationX(targetX)
                            .setDuration(240)
                            .withEndAction(() -> closeLockTask())
                            .start();
                } else {
                    closeLockTask();
                }
                if (curtainBackground != null) {
                    curtainBackground.animate()
                            .alpha(0f)
                            .setDuration(240)
                            .start();
                }
                return;
            }

            if (curtainContent != null) {
                curtainContent.animate()
                        .translationX(0f)
                        .setDuration(170)
                        .start();
            }
            if (curtainBackground != null) {
                curtainBackground.animate()
                        .alpha(1f)
                        .setDuration(170)
                        .start();
            }
        }

        private void updateCurtainOpacity(float dx, boolean allowedDirection) {
            float threshold = Math.max(dp(150), getWidth() * 0.32f);
            float progress = Math.min(1f, Math.abs(dx) / threshold);
            float fadeStrength = allowedDirection ? 0.82f : 0.24f;
            setCurtainBackgroundAlpha(Math.max(0.18f, 1f - progress * fadeStrength));
        }

        private void setCurtainBackgroundAlpha(float alpha) {
            if (curtainBackground != null) {
                curtainBackground.setAlpha(alpha);
            }
        }

        private void setCurtainContentTranslationX(float translationX) {
            if (curtainContent != null) {
                curtainContent.setTranslationX(translationX);
            }
        }

        private boolean isInside(View child, MotionEvent event) {
            if (child == null || child.getVisibility() != View.VISIBLE || child.getWidth() <= 0 || child.getHeight() <= 0) {
                return false;
            }
            int[] location = new int[2];
            child.getLocationOnScreen(location);
            float rawX = event.getRawX();
            float rawY = event.getRawY();
            return rawX >= location[0]
                    && rawX <= location[0] + child.getWidth()
                    && rawY >= location[1]
                    && rawY <= location[1] + child.getHeight();
        }

        private boolean isInsideTodoGestureTarget(MotionEvent event) {
            if (todoList == null || todoList.getVisibility() != View.VISIBLE) {
                return false;
            }
            for (int i = 0; i < todoList.getChildCount(); i += 2) {
                View row = todoList.getChildAt(i);
                if (row instanceof LinearLayout && ((LinearLayout) row).getChildCount() > 1) {
                    View label = ((LinearLayout) row).getChildAt(1);
                    if (isInside(label, event)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private final class DividerTouchListener implements View.OnTouchListener {
        private final int dividerIndex;
        private SwipeActionListener activeDelegate;

        DividerTouchListener(int dividerIndex) {
            this.dividerIndex = dividerIndex;
        }

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN || activeDelegate == null) {
                activeDelegate = delegateForDividerTouch(view, event);
            }
            if (activeDelegate == null) {
                return false;
            }
            boolean handled = activeDelegate.onTouch(view, event);
            if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                activeDelegate = null;
            }
            return handled;
        }

        private SwipeActionListener delegateForDividerTouch(View divider, MotionEvent event) {
            List<TodoItem> items = TodoStore.load(LockActivity.this);
            if (items.isEmpty()) {
                return null;
            }
            int[] location = new int[2];
            divider.getLocationOnScreen(location);
            float middleY = location[1] + divider.getHeight() * 0.5f;
            int targetIndex = event.getRawY() < middleY ? dividerIndex : dividerIndex + 1;
            targetIndex = Math.max(0, Math.min(targetIndex, items.size() - 1));
            int rowChildIndex = targetIndex * 2;
            if (rowChildIndex < 0 || rowChildIndex >= todoList.getChildCount()) {
                return null;
            }
            View row = todoList.getChildAt(rowChildIndex);
            if (!(row instanceof LinearLayout) || ((LinearLayout) row).getChildCount() <= 2) {
                return null;
            }
            View hintView = ((LinearLayout) row).getChildAt(2);
            if (!(hintView instanceof TextView)) {
                return null;
            }
            return new SwipeActionListener(items.get(targetIndex), targetIndex, row, (TextView) hintView);
        }
    }

    private final class SwipeActionListener implements View.OnTouchListener {
        private final TodoItem item;
        private final int index;
        private final View rowView;
        private final TextView actionHint;
        private float downX;
        private float downY;
        private boolean swiping;
        private boolean longPressTriggered;
        private boolean parentInterceptReleased;
        private Runnable longPressRunnable;

        SwipeActionListener(TodoItem item, int index, View rowView, TextView actionHint) {
            this.item = item;
            this.index = index;
            this.rowView = rowView;
            this.actionHint = actionHint;
        }

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    swiping = false;
                    longPressTriggered = false;
                    parentInterceptReleased = false;
                    view.getParent().requestDisallowInterceptTouchEvent(true);
                    rowView.animate().cancel();
                    rowView.setScaleX(1f);
                    rowView.setScaleY(1f);
                    actionHint.setText("");
                    actionHint.setTextColor(0x00FFFFFF);
                    rowView.setBackgroundColor(0x00000000);
                    longPressRunnable = () -> {
                        longPressTriggered = true;
                        beginTodoDrag(rowView, item, downY);
                    };
                    uiHandler.postDelayed(longPressRunnable, 520);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float moveDx = event.getRawX() - downX;
                    float moveDy = event.getRawY() - downY;
                    if (!swiping
                            && !longPressTriggered
                            && Math.abs(moveDy) > dp(14)
                            && Math.abs(moveDy) > Math.abs(moveDx) * 1.25f) {
                        cancelLongPress();
                        parentInterceptReleased = true;
                        view.getParent().requestDisallowInterceptTouchEvent(false);
                        return false;
                    }
                    if (parentInterceptReleased) {
                        return false;
                    }
                    if (Math.abs(moveDx) > dp(8) || Math.abs(moveDy) > dp(8)) {
                        cancelLongPress();
                    }
                    if (longPressTriggered) {
                        updateTodoDrag(event.getRawY());
                        return true;
                    }
                    if (!swiping && Math.abs(moveDx) > dp(14) && Math.abs(moveDx) > Math.abs(moveDy)) {
                        swiping = true;
                    }
                    if (swiping) {
                        rowView.setTranslationX(moveDx * 0.82f);
                        rowView.setAlpha(1f);
                        actionHint.setText(getString(R.string.delete));
                        actionHint.setTextColor(0xFFFFB3A8);
                        rowView.setBackgroundColor(0x22C24132);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    view.getParent().requestDisallowInterceptTouchEvent(false);
                    cancelLongPress();
                    if (longPressTriggered) {
                        finishTodoDrag(item);
                        return true;
                    }
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    if (Math.abs(dx) < dp(72) || Math.abs(dx) < Math.abs(dy)) {
                        rowView.animate()
                                .translationX(0f)
                                .scaleX(1f)
                                .scaleY(1f)
                                .alpha(1f)
                                .setDuration(140)
                                .withEndAction(() -> {
                                    actionHint.setText("");
                                    actionHint.setTextColor(0x00FFFFFF);
                                    rowView.setBackgroundColor(0x00000000);
                                })
                                .start();
                        if (!swiping) {
                            view.performClick();
                        }
                        return true;
                    }
                    actionHint.setText(getString(R.string.delete));
                    actionHint.setTextColor(0xFFFFB3A8);
                    animateDeleteTodo(item, index, rowView);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    view.getParent().requestDisallowInterceptTouchEvent(false);
                    cancelLongPress();
                    if (longPressTriggered) {
                        cancelTodoDrag();
                        longPressTriggered = false;
                        return true;
                    }
                    rowView.animate()
                            .translationX(0f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .alpha(1f)
                            .setDuration(140)
                            .withEndAction(() -> {
                                actionHint.setText("");
                                actionHint.setTextColor(0x00FFFFFF);
                                rowView.setBackgroundColor(0x00000000);
                            })
                            .start();
                    return true;
                default:
                    return true;
            }
        }

        private void animateThen(View view, float targetX, Runnable action) {
            view.animate()
                    .translationX(targetX)
                    .scaleX(0.92f)
                    .scaleY(0.92f)
                    .alpha(0f)
                    .setDuration(210)
                    .withEndAction(action)
                    .start();
        }

        private void cancelLongPress() {
            if (longPressRunnable != null) {
                uiHandler.removeCallbacks(longPressRunnable);
                longPressRunnable = null;
            }
        }
    }
}
