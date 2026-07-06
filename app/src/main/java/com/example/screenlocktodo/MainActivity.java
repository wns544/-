package com.example.screenlocktodo;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.app.Dialog;
import android.app.LocaleManager;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
import android.os.PowerManager;
import android.os.CancellationSignal;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialException;

import com.google.android.libraries.identity.googleid.GetGoogleIdOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executor;

public class MainActivity extends Activity {
    private static final int REQUEST_NOTIFICATIONS = 40;
    private static final int REQUEST_LOCK_BACKGROUND_IMAGE = 41;
    private static final int REQUEST_GOOGLE_SIGN_IN = 42;
    private static final int REQUEST_RECORD_AUDIO = 43;
    private static final long TODO_DOUBLE_TAP_MS = ViewConfiguration.getDoubleTapTimeout();

    private static final int COLOR_BG = 0xFFF5F6F8;
    private static final int COLOR_INK = 0xFF191F28;
    private static final int COLOR_MUTED = 0xFF8B95A1;
    private static final int COLOR_PANEL = 0xFFFFFFFF;
    private static final int COLOR_LINE = 0xFFF2F4F6;
    private static final int COLOR_ACCENT = 0xFF5F8F73;
    private static final int COLOR_GREEN = 0xFF7FA88A;
    private static final int COLOR_DANGER = 0xFFD9796F;
    private static final int COLOR_FIELD = 0xFFF5F6F8;

    private ScrollView mainScroll;
    private LinearLayout todoList;
    private EditText input;
    private Button undoDeleteButton;
    private final ArrayDeque<DeletedTodo> deletedTodos = new ArrayDeque<>();
    private TextView opacityValue;
    private View drawerScrim;
    private LinearLayout drawerPanel;
    private TextView cloudAccountText;
    private View drawerBackButton;
    private TextView drawerTitle;
    private TextView drawerSubtitle;
    private LinearLayout drawerMenuContent;
    private ScrollView drawerMenuScroll;
    private String drawerPage = "home";
    private boolean drawerOpen;
    private float drawerDownX;
    private float drawerDownY;
    private boolean drawerSwiping;
    private boolean drawerOpening;
    private long lastTodoTapId = -1L;
    private long lastTodoTapAt;
    private View draggingMainTodoRow;
    private int mainTodoDropIndex = -1;
    private boolean mainTodoDropCommitted;
    private String cloudSyncStatus = "signed_out";
    private int cloudServerItemCount = -1;
    private long cloudServerUpdatedAt;
    private final List<TodoItem> cloudServerItems = new ArrayList<>();
    private boolean cloudServerItemsLoaded;
    private final FirebaseTodoSync.Listener cloudSyncListener = new FirebaseTodoSync.Listener() {
        @Override
        public void onTodosUpdated() {
            runOnUiThread(() -> refreshTodos());
        }

        @Override
        public void onStatusChanged(String status) {
            runOnUiThread(() -> {
                cloudSyncStatus = status;
                if ("signed_out".equals(status)) {
                    cloudServerItems.clear();
                    cloudServerItemsLoaded = false;
                    cloudServerItemCount = -1;
                    cloudServerUpdatedAt = 0L;
                }
                refreshDrawerIfOpen();
            });
        }

        @Override
        public void onServerStateChanged(int itemCount, long updatedAt) {
            runOnUiThread(() -> {
                cloudServerItemCount = itemCount;
                cloudServerUpdatedAt = updatedAt;
                refreshDrawerIfOpen();
            });
        }

        @Override
        public void onServerItemsChanged(List<TodoItem> items, long updatedAt) {
            runOnUiThread(() -> {
                cloudServerItems.clear();
                cloudServerItems.addAll(items);
                cloudServerItemsLoaded = true;
                cloudServerItemCount = items.size();
                cloudServerUpdatedAt = updatedAt;
                refreshDrawerIfOpen();
            });
        }
    };

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        super.onCreate(savedInstanceState);
        configureMainWindow();
        if (!requestNotificationPermission()) {
            requestRecordAudioPermission();
        }
        AppSettings.applyLockScreenRecovery(this);
        DiagnosticLog.recordAppState(this, "main onCreate");
        syncLockMonitorService();
        registerBackHandler();
        setContentView(buildContent());
        refreshTodos();
        FirebaseTodoSync.start(this, cloudSyncListener);
        maybeShowBatteryGuideOnboarding();
    }

    @Override
    protected void onResume() {
        super.onResume();
        DiagnosticLog.recordAppState(this, "main onResume");
        syncLockMonitorService();
        refreshTodos();
        FirebaseTodoSync.start(this, cloudSyncListener);
    }

    @Override
    protected void onStop() {
        syncLockMonitorService();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        FirebaseTodoSync.stop();
        syncLockMonitorService();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        handleBack();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_LOCK_BACKGROUND_IMAGE && resultCode == RESULT_OK && data != null) {
            saveLockBackgroundImage(data);
        } else if (requestCode == REQUEST_GOOGLE_SIGN_IN) {
            handleLegacyGoogleSignInResult(data);
        }
    }

    private void registerBackHandler() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    this::handleBack
            );
        }
    }

    private void handleBack() {
        if (drawerOpen) {
            if (!"home".equals(drawerPage)) {
                showDrawerPage("home");
                return;
            }
            closeDrawer();
            return;
        }
        closeMainTask();
    }

    private void closeMainTask() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask();
        } else {
            finish();
        }
    }

    private void syncLockMonitorService() {
        if (AppSettings.lockScreenEnabled(this)) {
            DiagnosticLog.record(this, "NudgeMain", "sync monitor: start");
            LockMonitorService.start(getApplicationContext());
        } else {
            DiagnosticLog.record(this, "NudgeMain", "sync monitor: stop");
            LockMonitorService.stop(getApplicationContext());
        }
    }

    private void configureMainWindow() {
        Window window = getWindow();
        window.setStatusBarColor(COLOR_BG);
        window.setNavigationBarColor(COLOR_BG);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int flags = window.getDecorView().getSystemUiVisibility()
                    | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            window.getDecorView().setSystemUiVisibility(flags);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setNavigationBarContrastEnforced(false);
            window.setStatusBarContrastEnforced(false);
        }
    }

    private View buildContent() {
        drawerOpen = false;
        FrameLayout shell = new FrameLayout(this);

        mainScroll = new ScrollView(this);
        mainScroll.setFillViewport(true);
        mainScroll.setClipToPadding(false);
        mainScroll.setBackgroundColor(COLOR_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(38), dp(20), dp(28));
        mainScroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        root.addView(hero());
        root.addView(lockSettingsCard(), cardParams());
        root.addView(todoCard(), cardParams());

        shell.addView(mainScroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        shell.addView(drawerLayer(), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        return shell;
    }

    private View hero() {
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(8), dp(8), dp(8), dp(8));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        hero.addView(titleRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView title = text(getString(R.string.app_name), 28, COLOR_INK, true);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        titleRow.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        CircleIconButtonView preview = new CircleIconButtonView(this, CircleIconButtonView.ICON_PREVIEW);
        preview.setOnClickListener(v -> startActivity(new Intent(this, LockActivity.class)));
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(dp(36), dp(36));
        previewParams.rightMargin = dp(6);
        titleRow.addView(preview, previewParams);

        CircleIconButtonView menu = new CircleIconButtonView(this, CircleIconButtonView.ICON_SETTINGS);
        menu.setOnClickListener(v -> openDrawer());
        titleRow.addView(menu, new LinearLayout.LayoutParams(dp(36), dp(36)));

        return hero;
    }

    private View lockSettingsCard() {
        LinearLayout card = card();
        card.addView(sectionTitle(getString(R.string.section_lock_screen), null));

        LinearLayout enabledRow = new LinearLayout(this);
        enabledRow.setGravity(Gravity.CENTER_VERTICAL);
        enabledRow.setOrientation(LinearLayout.HORIZONTAL);
        enabledRow.setPadding(0, dp(14), 0, dp(8));

        LinearLayout enabledCopy = new LinearLayout(this);
        enabledCopy.setOrientation(LinearLayout.VERTICAL);
        TextView enabledTitle = text(getString(R.string.lock_screen_enabled), 16, COLOR_INK, false);
        TextView enabledSubtitle = text(getString(R.string.lock_screen_enabled_desc), 13, COLOR_MUTED, false);
        enabledSubtitle.setPadding(0, dp(3), dp(10), 0);
        enabledCopy.addView(enabledTitle);
        enabledCopy.addView(enabledSubtitle);
        enabledRow.addView(enabledCopy, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Switch enabledSwitch = new Switch(this);
        tintSwitch(enabledSwitch);
        enabledSwitch.setChecked(AppSettings.lockScreenEnabled(this));
        enabledSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            DiagnosticLog.record(MainActivity.this, "NudgeMain", "lock screen toggle=" + isChecked);
            AppSettings.setLockScreenEnabled(MainActivity.this, isChecked);
            syncLockMonitorService();
            setContentView(buildContent());
            refreshTodos();
        });
        enabledRow.addView(enabledSwitch, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        card.addView(enabledRow);

        card.addView(divider());

        LinearLayout opacityHeader = new LinearLayout(this);
        opacityHeader.setGravity(Gravity.CENTER_VERTICAL);
        opacityHeader.setOrientation(LinearLayout.HORIZONTAL);
        opacityHeader.setPadding(0, dp(12), 0, 0);
        opacityHeader.addView(text(getString(R.string.overlay_opacity), 16, COLOR_INK, false),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        opacityValue = text(AppSettings.overlayOpacity(this) + "%", 16, COLOR_ACCENT, false);
        opacityValue.setGravity(Gravity.RIGHT);
        opacityHeader.addView(opacityValue, new LinearLayout.LayoutParams(dp(70), LinearLayout.LayoutParams.WRAP_CONTENT));
        card.addView(opacityHeader);

        SeekBar opacity = new SeekBar(this);
        tintSeekBar(opacity);
        opacity.setMax(20);
        opacity.setProgress(Math.round(AppSettings.overlayOpacity(this) / 5f));
        card.addView(opacity, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
        ));
        opacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int snapped = progress * 5;
                AppSettings.setOverlayOpacity(MainActivity.this, snapped);
                opacityValue.setText(snapped + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        card.addView(divider());

        CheckBox curtainBothDirections = new CheckBox(this);
        tintCheckBox(curtainBothDirections);
        curtainBothDirections.setText(getString(R.string.unlock_both_directions));
        curtainBothDirections.setTextSize(15);
        curtainBothDirections.setTextColor(COLOR_INK);
        curtainBothDirections.setPadding(0, dp(10), 0, dp(2));
        curtainBothDirections.setChecked(AppSettings.curtainUnlockBothDirections(this));
        curtainBothDirections.setOnCheckedChangeListener((buttonView, isChecked) -> {
            AppSettings.setCurtainUnlockBothDirections(MainActivity.this, isChecked);
            setContentView(buildContent());
            refreshTodos();
        });
        card.addView(curtainBothDirections);

        CheckBox swipeBothDirectionsDelete = new CheckBox(this);
        tintCheckBox(swipeBothDirectionsDelete);
        swipeBothDirectionsDelete.setText(getString(R.string.swipe_both_directions_delete));
        swipeBothDirectionsDelete.setTextSize(15);
        swipeBothDirectionsDelete.setTextColor(COLOR_INK);
        swipeBothDirectionsDelete.setPadding(0, dp(6), 0, 0);
        swipeBothDirectionsDelete.setChecked(AppSettings.todoSwipeBothDirectionsDelete(this));
        swipeBothDirectionsDelete.setOnCheckedChangeListener((buttonView, isChecked) -> {
            AppSettings.setTodoSwipeBothDirectionsDelete(MainActivity.this, isChecked);
            DiagnosticLog.record(MainActivity.this, "NudgeMain", "todo swipe both directions delete=" + isChecked);
        });
        card.addView(swipeBothDirectionsDelete);

        CheckBox doubleTapScreenOff = new CheckBox(this);
        tintCheckBox(doubleTapScreenOff);
        doubleTapScreenOff.setText("\uB354\uBE14\uD0ED\uC73C\uB85C \uD654\uBA74 \uB044\uAE30");
        doubleTapScreenOff.setTextSize(15);
        doubleTapScreenOff.setTextColor(COLOR_INK);
        doubleTapScreenOff.setPadding(0, dp(6), 0, 0);
        doubleTapScreenOff.setChecked(AppSettings.doubleTapScreenOffEnabled(this));
        doubleTapScreenOff.setOnCheckedChangeListener((buttonView, isChecked) -> {
            AppSettings.setDoubleTapScreenOffEnabled(MainActivity.this, isChecked);
            DiagnosticLog.record(MainActivity.this, "NudgeMain", "double tap screen off=" + isChecked);
        });
        card.addView(doubleTapScreenOff);

        TextView doubleTapWarning = text(
                "\uC774 \uAE30\uB2A5\uC73C\uB85C \uD654\uBA74\uC744 \uB044\uBA74 \uB2E4\uC74C \uC7A0\uAE08 \uD574\uC81C \uC2DC \uC9C0\uBB38 \uB300\uC2E0 \uBE44\uBC00\uBC88\uD638\uAC00 \uD544\uC694\uD560 \uC218 \uC788\uC2B5\uB2C8\uB2E4.",
                13,
                COLOR_MUTED,
                false
        );
        doubleTapWarning.setPadding(dp(34), dp(2), 0, dp(2));
        card.addView(doubleTapWarning);

        return card;
    }

    private View todoCard() {
        LinearLayout card = card();
        LinearLayout todoHeader = new LinearLayout(this);
        todoHeader.setOrientation(LinearLayout.HORIZONTAL);
        todoHeader.setGravity(Gravity.CENTER_VERTICAL);
        todoHeader.addView(sectionTitle(getString(R.string.section_todos), getString(R.string.todo_gesture_hint)),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        CopyTodoButtonView copyButton = new CopyTodoButtonView(this);
        copyButton.setOnClickListener(v -> copyTodosToClipboard());
        todoHeader.addView(copyButton, new LinearLayout.LayoutParams(dp(44), dp(44)));
        card.addView(todoHeader);

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPadding(0, dp(14), 0, dp(8));
        card.addView(inputRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(getString(R.string.todo_input_hint));
        input.setTextColor(COLOR_INK);
        input.setHintTextColor(0x99667085);
        input.setTextSize(15);
        input.setPadding(dp(14), 0, dp(14), 0);
        input.setBackground(rounded(COLOR_FIELD, 16));
        input.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) {
                scrollTodoInputIntoView(inputRow);
            }
        });
        inputRow.addView(input, new LinearLayout.LayoutParams(0, dp(46), 1));

        AddTodoButtonView add = new AddTodoButtonView(this);
        add.setOnClickListener(v -> addTodo());
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(dp(46), dp(46));
        addParams.leftMargin = dp(8);
        inputRow.addView(add, addParams);

        undoDeleteButton = quietButton("\uC0AD\uC81C \uCDE8\uC18C", COLOR_ACCENT);
        undoDeleteButton.setOnClickListener(v -> undoDelete());
        LinearLayout.LayoutParams undoParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(42)
        );
        undoParams.gravity = Gravity.RIGHT;
        undoParams.bottomMargin = dp(8);
        card.addView(undoDeleteButton, undoParams);
        updateUndoDeleteButton();

        todoList = new LinearLayout(this);
        todoList.setOrientation(LinearLayout.VERTICAL);
        todoList.setPadding(0, dp(4), 0, 0);
        todoList.setOnDragListener((view, event) -> handleMainTodoDrag(event));
        card.addView(todoList);

        return card;
    }

    private void copyTodosToClipboard() {
        List<TodoItem> items = TodoStore.load(this);
        StringBuilder builder = new StringBuilder();
        for (TodoItem item : items) {
            String text = item.text == null ? "" : item.text.trim();
            if (text.length() == 0) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(text);
        }
        if (builder.length() == 0) {
            Toast.makeText(this, "복사할 할 일이 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Toast.makeText(this, "클립보드를 사용할 수 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("잠금메모", builder.toString()));
        Toast.makeText(this, "잠금메모를 복사했습니다.", Toast.LENGTH_SHORT).show();
    }

    private void copyTodoToClipboard(TodoItem item) {
        String text = item == null || item.text == null ? "" : item.text.trim();
        if (text.length() == 0) {
            Toast.makeText(this, "\uBCF5\uC0AC\uD560 \uD560 \uC77C\uC774 \uC5C6\uC2B5\uB2C8\uB2E4.", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Toast.makeText(this, "\uD074\uB9BD\uBCF4\uB4DC\uB97C \uC0AC\uC6A9\uD560 \uC218 \uC5C6\uC2B5\uB2C8\uB2E4.", Toast.LENGTH_SHORT).show();
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("\uC7A0\uAE08\uBA54\uBAA8", text));
        Toast.makeText(this, "\uD560 \uC77C\uC744 \uBCF5\uC0AC\uD588\uC2B5\uB2C8\uB2E4.", Toast.LENGTH_SHORT).show();
    }

    private View drawerLayer() {
        FrameLayout layer = new FrameLayout(this);
        layer.setClipChildren(false);

        drawerScrim = new View(this);
        drawerScrim.setBackgroundColor(0x52000000);
        drawerScrim.setAlpha(0f);
        drawerScrim.setVisibility(View.GONE);
        drawerScrim.setOnClickListener(v -> closeDrawer());
        layer.addView(drawerScrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        int panelHeight = Math.min(
                Math.round(getResources().getDisplayMetrics().heightPixels * 0.80f),
                dp(720)
        );
        drawerPanel = new DrawerPanelLayout(this);
        drawerPanel.setOrientation(LinearLayout.VERTICAL);
        drawerPanel.setPadding(dp(20), dp(10), dp(20), dp(16));
        drawerPanel.setBackground(topRounded(COLOR_PANEL, 24));
        drawerPanel.setClickable(true);
        drawerPanel.setFocusable(true);
        drawerPanel.setVisibility(View.GONE);
        drawerPanel.setTranslationY(panelHeight);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            drawerPanel.setElevation(dp(12));
        }

        TextView handle = new TextView(this);
        handle.setBackground(rounded(0xFFD1D6DB, 2));
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(40), dp(4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.bottomMargin = dp(12);
        drawerPanel.addView(handle, handleParams);

        LinearLayout sheetHeader = new LinearLayout(this);
        sheetHeader.setOrientation(LinearLayout.HORIZONTAL);
        sheetHeader.setGravity(Gravity.CENTER_VERTICAL);

        drawerBackButton = new CircleIconButtonView(this, CircleIconButtonView.ICON_BACK);
        drawerBackButton.setVisibility(View.GONE);
        drawerBackButton.setOnClickListener(v -> showDrawerPage("home"));
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(dp(32), dp(32));
        backParams.rightMargin = dp(8);
        sheetHeader.addView(drawerBackButton, backParams);

        LinearLayout headerCopy = new LinearLayout(this);
        headerCopy.setOrientation(LinearLayout.VERTICAL);
        drawerTitle = text(getString(R.string.settings), 19, COLOR_INK, true);
        drawerTitle.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        headerCopy.addView(drawerTitle);
        drawerSubtitle = text(getString(R.string.app_name), 12, COLOR_MUTED, false);
        drawerSubtitle.setPadding(0, dp(2), 0, dp(14));
        headerCopy.addView(drawerSubtitle);
        sheetHeader.addView(headerCopy, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        CircleIconButtonView close = new CircleIconButtonView(this, CircleIconButtonView.ICON_CLOSE);
        close.setOnClickListener(v -> closeDrawer());
        sheetHeader.addView(close, new LinearLayout.LayoutParams(dp(32), dp(32)));
        drawerPanel.addView(sheetHeader);

        drawerMenuContent = new LinearLayout(this);
        drawerMenuContent.setOrientation(LinearLayout.VERTICAL);
        showDrawerPage("home");

        drawerMenuScroll = new ScrollView(this);
        drawerMenuScroll.setFillViewport(false);
        drawerMenuScroll.setClipToPadding(false);
        drawerMenuScroll.addView(drawerMenuContent, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));
        drawerPanel.addView(drawerMenuScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1
        ));

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                panelHeight,
                Gravity.BOTTOM
        );
        layer.addView(drawerPanel, panelParams);
        return layer;
    }

    private void showDrawerPage(String page) {
        drawerPage = page;
        if (drawerMenuContent == null || drawerTitle == null || drawerSubtitle == null) {
            return;
        }
        drawerMenuContent.removeAllViews();
        cloudAccountText = null;
        boolean home = "home".equals(page);
        drawerBackButton.setVisibility(home ? View.GONE : View.VISIBLE);
        drawerTitle.setText(drawerTitleFor(page));
        drawerSubtitle.setText(drawerSubtitleFor(page));
        if ("lock".equals(page)) {
            populateLockDrawerPage(drawerMenuContent);
        } else if ("todo".equals(page)) {
            populateTodoDrawerPage(drawerMenuContent);
        } else if ("voice".equals(page)) {
            populateVoiceDrawerPage(drawerMenuContent);
        } else if ("sync".equals(page)) {
            populateSyncDrawerPage(drawerMenuContent);
        } else if ("permissions".equals(page)) {
            populatePermissionsDrawerPage(drawerMenuContent);
        } else if ("language".equals(page)) {
            populateLanguageDrawerPage(drawerMenuContent);
        } else if ("background".equals(page)) {
            populateBackgroundDrawerPage(drawerMenuContent);
        } else if ("trouble".equals(page)) {
            populateTroubleDrawerPage(drawerMenuContent);
        } else {
            populateDrawerHome(drawerMenuContent);
        }
    }

    private String drawerTitleFor(String page) {
        if ("voice".equals(page)) return "음성 입력";
        if ("sync".equals(page)) return "서버 동기화";
        if ("permissions".equals(page)) return "권한과 배터리";
        if ("language".equals(page)) return "언어와 표시";
        if ("background".equals(page)) return "잠금화면 배경";
        if ("trouble".equals(page)) return "문제 해결";
        return getString(R.string.settings);
    }

    private String drawerSubtitleFor(String page) {
        if ("voice".equals(page)) return currentSpeechLanguageName() + " · 마이크 버튼";
        if ("sync".equals(page)) return cloudStatusLabel();
        if ("permissions".equals(page)) return "알림, 전체 화면, 배터리";
        if ("language".equals(page)) return currentLanguageName() + " · 초록 포인트";
        if ("background".equals(page)) return "사진 선택과 시스템 배경";
        if ("trouble".equals(page)) return "진단 로그와 도움말";
        return getString(R.string.app_name);
    }

    private void populateDrawerHome(LinearLayout content) {
        content.addView(categoryRow("음성 입력", currentSpeechLanguageName() + " · 마이크 버튼", v -> showDrawerPage("voice")));
        content.addView(categoryRow("서버 동기화", syncHomeSummary(), v -> showDrawerPage("sync")));
        content.addView(categoryRow("권한과 배터리", "알림, 전체 화면, 배터리 제한", v -> showDrawerPage("permissions")));
        content.addView(categoryRow("언어와 표시", currentLanguageName() + " · 초록 포인트", v -> showDrawerPage("language")));
        content.addView(categoryRow("잠금화면 배경", "사진 선택, 삼성/시스템 배경 사용", v -> showDrawerPage("background")));
        content.addView(categoryRow("문제 해결", "진단 로그, 도움말, 닫기", v -> showDrawerPage("trouble")));
        content.addView(diagnosticsButton());
        content.addView(betaVersionLabel());
    }

    private void populateLockDrawerPage(LinearLayout content) {
        populateBackgroundDrawerPage(content);
    }

    private void populateTodoDrawerPage(LinearLayout content) {
        populateDrawerHome(content);
    }

    private void populateVoiceDrawerPage(LinearLayout content) {
        content.addView(detailRow("음성 입력 언어", currentSpeechLanguageName(), v -> showSpeechLanguageDialog(), false));
        content.addView(detailRow("마이크 권한", "잠금화면 마이크 버튼에 필요", v -> requestRecordAudioPermission(), false));
        content.addView(detailRow("잠금화면 마이크 테스트", "상단 미리보기 버튼으로 확인", v -> {
            closeDrawer();
            startActivity(new Intent(this, LockActivity.class));
        }, false));
    }

    private void populateSyncDrawerPage(LinearLayout content) {
        content.addView(detailRow("로그인", syncLoginSummary(), v -> handleCloudAccount(), true));
        content.addView(detailRow("서버 저장 상태", cloudServerStatusSummary(), v -> showServerTodosDialog(), false));
    }

    private void populatePermissionsDrawerPage(LinearLayout content) {
        content.addView(detailRow(getString(R.string.notification_permission), "알림 권한 설정", v -> {
            closeDrawer();
            openNotificationSettings();
        }, false));
        content.addView(detailRow(getString(R.string.full_screen_alert_action), "잠금화면 위에 앱을 띄우기", v -> showFullScreenIntentGuide(), false));
        content.addView(detailRow(getString(R.string.device_admin_action), "더블탭 화면 끄기에 필요", v -> {
            closeDrawer();
            openDeviceAdminSettings();
        }, false));
        content.addView(detailRow(getString(R.string.battery_unrestricted_action), "앱이 백그라운드에서 꺼지지 않게 설정", v -> {
            closeDrawer();
            showBatteryGuideDialog(false);
        }, false));
    }

    private void populateLanguageDrawerPage(LinearLayout content) {
        content.addView(detailRow(getString(R.string.language_setting), currentLanguageName(), v -> showLanguageDialog(), false));
        content.addView(detailRow("포인트 컬러", "초록 #5F8F73", v -> {}, false));
    }

    private void populateBackgroundDrawerPage(LinearLayout content) {
        content.addView(detailRow(getString(R.string.choose_lock_background), "직접 고른 사진을 잠금화면 배경으로 사용", v -> {
            closeDrawer();
            openLockBackgroundPicker();
        }, false));
        content.addView(detailRow(getString(R.string.remove_lock_background), "삼성/시스템 잠금화면 배경으로 되돌리기", v -> {
            clearLockBackgroundImage();
            showDrawerPage("background");
        }, false));
    }

    private void populateTroubleDrawerPage(LinearLayout content) {
        content.addView(detailRow(getString(R.string.battery_help_title), "할 일 커튼이 종료될 때 확인", v -> showBatteryGuideDialog(false), false));
        content.addView(detailRow(getString(R.string.diagnostics_title), "현재 상태 로그 보기", v -> showDiagnosticsDialog(), false));
        content.addView(detailRow(getString(R.string.close), "앱 화면 닫기", v -> closeDrawer(), false));
        content.addView(betaVersionLabel());
    }

    private final class DrawerRootLayout extends FrameLayout {
        DrawerRootLayout(Context context) {
            super(context);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            if (drawerOpen) {
                return super.onInterceptTouchEvent(event);
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    drawerDownX = event.getRawX();
                    drawerDownY = event.getRawY();
                    drawerSwiping = false;
                    drawerOpening = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - drawerDownX;
                    float dy = event.getRawY() - drawerDownY;
                    if (!drawerSwiping && dx < -dp(18) && Math.abs(dx) > Math.abs(dy) * 1.25f) {
                        beginDrawerOpenDrag();
                        drawerSwiping = true;
                        drawerOpening = true;
                        updateDrawerOpenDrag(dx);
                        return true;
                    }
                    break;
                default:
                    break;
            }
            return super.onInterceptTouchEvent(event);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (!drawerOpening) {
                return super.onTouchEvent(event);
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    updateDrawerOpenDrag(event.getRawX() - drawerDownX);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    finishDrawerOpenDrag(event.getRawX() - drawerDownX);
                    drawerOpening = false;
                    drawerSwiping = false;
                    return true;
                default:
                    return true;
            }
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (drawerOpening) {
                return onTouchEvent(event);
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    drawerSwiping = false;
                    drawerOpening = false;
                    break;
                default:
                    break;
            }
            return super.dispatchTouchEvent(event);
        }
    }

    private void beginDrawerOpenDrag() {
        if (drawerPanel == null || drawerScrim == null) {
            return;
        }
        drawerScrim.animate().cancel();
        drawerPanel.animate().cancel();
        drawerScrim.setVisibility(View.VISIBLE);
        drawerPanel.setVisibility(View.VISIBLE);
    }

    private void updateDrawerOpenDrag(float dx) {
        if (drawerPanel == null || drawerScrim == null) {
            return;
        }
        int panelWidth = drawerPanel.getWidth() > 0
                ? drawerPanel.getWidth()
                : Math.round(getResources().getDisplayMetrics().widthPixels * 5f / 6f);
        float drag = Math.min(panelWidth, Math.max(0f, -dx));
        float translation = panelWidth - drag;
        drawerPanel.setTranslationX(translation);
        drawerScrim.setAlpha(Math.min(1f, drag / Math.max(1, panelWidth)));
    }

    private void finishDrawerOpenDrag(float dx) {
        if (drawerPanel == null) {
            return;
        }
        int panelWidth = drawerPanel.getWidth() > 0
                ? drawerPanel.getWidth()
                : Math.round(getResources().getDisplayMetrics().widthPixels * 5f / 6f);
        float opened = Math.min(panelWidth, Math.max(0f, -dx));
        if (opened > Math.max(dp(90), panelWidth * 0.22f)) {
            openDrawer();
        } else {
            drawerOpen = true;
            closeDrawer();
        }
    }

    private boolean handleDrawerSwipe(MotionEvent event) {
        if (!drawerOpen || drawerPanel == null) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                drawerDownX = event.getRawX();
                drawerDownY = event.getRawY();
                drawerSwiping = false;
                drawerPanel.animate().cancel();
                return false;
            case MotionEvent.ACTION_MOVE:
                float dx = Math.max(0f, event.getRawX() - drawerDownX);
                float dy = event.getRawY() - drawerDownY;
                if (!drawerSwiping && dx > dp(14) && dx > Math.abs(dy) * 1.2f) {
                    drawerSwiping = true;
                }
                if (drawerSwiping) {
                    drawerPanel.setTranslationX(dx * 0.9f);
                    drawerScrim.setAlpha(Math.max(0f, 1f - dx / Math.max(1, drawerPanel.getWidth())));
                    return true;
                }
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!drawerSwiping) {
                    return false;
                }
                drawerSwiping = false;
                float releaseDx = event.getRawX() - drawerDownX;
                if (releaseDx > Math.max(dp(90), drawerPanel.getWidth() * 0.22f)) {
                    closeDrawer();
                } else {
                    drawerPanel.animate().translationX(0f).setDuration(140).start();
                    drawerScrim.animate().alpha(1f).setDuration(140).start();
                }
                return true;
            default:
                return true;
        }
    }

    private final class DrawerPanelLayout extends LinearLayout {
        DrawerPanelLayout(Context context) {
            super(context);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            if (!drawerOpen) {
                return super.onInterceptTouchEvent(event);
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    drawerDownX = event.getRawX();
                    drawerDownY = event.getRawY();
                    drawerSwiping = false;
                    animate().cancel();
                    if (drawerScrim != null) {
                        drawerScrim.animate().cancel();
                    }
                    return super.onInterceptTouchEvent(event);
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - drawerDownX;
                    float dy = event.getRawY() - drawerDownY;
                    boolean canPullSheet = drawerMenuScroll == null || !drawerMenuScroll.canScrollVertically(-1);
                    if (!drawerSwiping
                            && canPullSheet
                            && dy > dp(14)
                            && dy > Math.abs(dx) * 1.2f) {
                        drawerSwiping = true;
                    }
                    return drawerSwiping || super.onInterceptTouchEvent(event);
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    drawerSwiping = false;
                    return super.onInterceptTouchEvent(event);
                default:
                    return super.onInterceptTouchEvent(event);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (!drawerOpen) {
                return super.onTouchEvent(event);
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    float dy = Math.max(0f, event.getRawY() - drawerDownY);
                    setTranslationY(dy);
                    if (drawerScrim != null) {
                        drawerScrim.setAlpha(Math.max(0f, 1f - dy / Math.max(1, getHeight())));
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    drawerSwiping = false;
                    float releaseDy = event.getRawY() - drawerDownY;
                    if (releaseDy > Math.max(dp(90), getHeight() * 0.16f)) {
                        closeDrawer();
                    } else {
                        animate().translationY(0f).setDuration(140).start();
                        if (drawerScrim != null) {
                            drawerScrim.animate().alpha(1f).setDuration(140).start();
                        }
                    }
                    return true;
                default:
                    return true;
            }
        }
    }

    private void openDrawer() {
        if (drawerPanel == null || drawerScrim == null || drawerOpen) {
            return;
        }
        showDrawerPage("home");
        drawerOpen = true;
        drawerScrim.animate().cancel();
        drawerPanel.animate().cancel();
        drawerScrim.setVisibility(View.VISIBLE);
        drawerPanel.setVisibility(View.VISIBLE);
        drawerPanel.setTranslationX(0f);
        drawerScrim.animate().alpha(1f).setDuration(180).start();
        drawerPanel.animate()
                .translationY(0f)
                .setDuration(260)
                .start();
        drawerPanel.requestFocus();
    }

    private void closeDrawer() {
        if (drawerPanel == null || drawerScrim == null || !drawerOpen) {
            return;
        }
        drawerOpen = false;
        int panelHeight = drawerPanel.getHeight() > 0
                ? drawerPanel.getHeight()
                : Math.round(getResources().getDisplayMetrics().heightPixels * 0.80f);
        drawerScrim.animate().cancel();
        drawerPanel.animate().cancel();
        drawerScrim.animate()
                .alpha(0f)
                .setDuration(160)
                .withEndAction(() -> drawerScrim.setVisibility(View.GONE))
                .start();
        drawerPanel.animate()
                .translationY(panelHeight)
                .setDuration(220)
                .withEndAction(() -> drawerPanel.setVisibility(View.GONE))
                .start();
    }

    private String cloudAccountLabel() {
        FirebaseUser user = FirebaseTodoSync.currentUser(this);
        if (user == null) {
            return getString(R.string.google_sign_in);
        }
        String account = user.getEmail();
        if (account == null || account.trim().isEmpty()) {
            account = user.getDisplayName();
        }
        if (account == null || account.trim().isEmpty()) {
            account = getString(R.string.google_account);
        }
        return account + " \u00B7 " + cloudStatusLabel();
    }

    private String syncHomeSummary() {
        FirebaseUser user = FirebaseTodoSync.currentUser(this);
        return user == null ? getString(R.string.google_sign_in) : syncLoginSummary() + " · " + cloudServerStatusSummary();
    }

    private String syncLoginSummary() {
        FirebaseUser user = FirebaseTodoSync.currentUser(this);
        if (user == null) {
            return getString(R.string.google_sign_in);
        }
        String account = user.getEmail();
        if (account == null || account.trim().isEmpty()) {
            account = user.getDisplayName();
        }
        if (account == null || account.trim().isEmpty()) {
            account = getString(R.string.google_account);
        }
        return account + " · 로그인됨";
    }

    private void updateCloudAccountLabel() {
        if (cloudAccountText != null) {
            cloudAccountText.setText(cloudAccountLabel());
        }
    }

    private void refreshDrawerIfOpen() {
        if (drawerOpen && drawerMenuContent != null) {
            showDrawerPage(drawerPage);
        } else {
            updateCloudAccountLabel();
        }
    }

    private String cloudStatusLabel() {
        if ("syncing".equals(cloudSyncStatus)) {
            return getString(R.string.cloud_syncing);
        }
        if ("offline".equals(cloudSyncStatus)) {
            return cloudServerItemCount >= 0
                    ? getString(R.string.cloud_offline) + " \u00B7 " + cloudServerSummary()
                    : getString(R.string.cloud_offline);
        }
        if (cloudServerItemCount >= 0) {
            return cloudServerSummary();
        }
        return getString(R.string.cloud_synced);
    }

    private String cloudServerSummary() {
        String time = cloudServerUpdatedAt > 0
                ? android.text.format.DateFormat.getTimeFormat(this).format(new Date(cloudServerUpdatedAt))
                : "-";
        return getString(R.string.cloud_server_summary, cloudServerItemCount, time);
    }

    private String cloudServerStatusSummary() {
        if (!cloudServerItemsLoaded && cloudServerItemCount < 0) {
            return "아직 서버 목록을 불러오지 못했습니다";
        }
        String time = cloudServerUpdatedAt > 0
                ? android.text.format.DateFormat.getTimeFormat(this).format(new Date(cloudServerUpdatedAt))
                : "-";
        int count = cloudServerItemsLoaded ? cloudServerItems.size() : Math.max(0, cloudServerItemCount);
        return "서버에 저장된 메모 " + count + "개 · " + time + " 기준";
    }

    private void showServerTodosDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(18), dp(20), dp(14));
        box.setBackground(rounded(COLOR_PANEL, 16));

        TextView title = text("서버 저장 상태", 20, COLOR_INK, true);
        box.addView(title);

        TextView summary = text(cloudServerStatusSummary(), 13, COLOR_MUTED, false);
        summary.setPadding(0, dp(4), 0, dp(12));
        box.addView(summary);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        if (!cloudServerItemsLoaded) {
            TextView empty = text("아직 서버 목록을 불러오지 못했습니다.", 14, COLOR_MUTED, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(12), dp(22), dp(12), dp(22));
            list.addView(empty);
        } else if (cloudServerItems.isEmpty()) {
            TextView empty = text("서버에 저장된 메모가 없습니다.", 14, COLOR_MUTED, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(12), dp(22), dp(12), dp(22));
            list.addView(empty);
        } else {
            for (TodoItem item : cloudServerItems) {
                TextView row = text("· " + item.text, 14, COLOR_INK, false);
                row.setPadding(dp(12), dp(10), dp(12), dp(10));
                row.setBackground(rounded(COLOR_FIELD, 12));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );
                params.bottomMargin = dp(8);
                list.addView(row, params);
            }
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(list, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));
        box.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.min(dp(420), Math.round(getResources().getDisplayMetrics().heightPixels * 0.48f))
        ));

        Button close = filledButton(getString(R.string.close));
        close.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(dp(96), dp(44));
        closeParams.gravity = Gravity.RIGHT;
        closeParams.topMargin = dp(12);
        box.addView(close, closeParams);

        dialog.setContentView(box);
        dialog.show();

        Window dialogWindow = dialog.getWindow();
        if (dialogWindow != null) {
            dialogWindow.setBackgroundDrawable(new ColorDrawable(0x00000000));
            dialogWindow.setLayout(
                    Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(480)),
                    FrameLayout.LayoutParams.WRAP_CONTENT
            );
        }
    }

    private void handleCloudAccount() {
        FirebaseUser user = FirebaseTodoSync.currentUser(this);
        if (user == null) {
            beginGoogleSignIn();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.google_sign_out_title)
                .setMessage(user.getEmail() == null ? getString(R.string.google_account) : user.getEmail())
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.google_sign_out, (dialog, which) -> {
                    FirebaseTodoSync.signOut(this);
                    Toast.makeText(this, R.string.google_signed_out, Toast.LENGTH_SHORT).show();
                    setContentView(buildContent());
                    refreshTodos();
                })
                .show();
    }

    private void beginGoogleSignIn() {
        if (!FirebaseTodoSync.isConfigured(this)) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.firebase_setup_required_title)
                    .setMessage(R.string.firebase_setup_required_body)
                    .setPositiveButton(R.string.close, null)
                    .show();
            return;
        }

        beginLegacyGoogleSignIn();
    }

    private void beginCredentialManagerGoogleSignIn() {
        GetGoogleIdOption googleIdOption = new GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(FirebaseTodoSync.webClientId(this))
                .setAutoSelectEnabled(false)
                .build();
        GetCredentialRequest request = new GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build();
        CredentialManager manager = CredentialManager.create(this);
        Executor mainExecutor = command -> runOnUiThread(command);
        manager.getCredentialAsync(
                this,
                request,
                new CancellationSignal(),
                mainExecutor,
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse response) {
                        authenticateGoogleCredential(response.getCredential());
                    }

                    @Override
                    public void onError(GetCredentialException error) {
                        DiagnosticLog.record(MainActivity.this, "NudgeMain", "Google credential failed; falling back", error);
                        beginLegacyGoogleSignIn();
                    }
                }
        );
    }

    private void beginLegacyGoogleSignIn() {
        GoogleSignInOptions options = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(FirebaseTodoSync.webClientId(this))
                .requestEmail()
                .build();
        Intent signInIntent = GoogleSignIn.getClient(this, options).getSignInIntent();
        startActivityForResult(signInIntent, REQUEST_GOOGLE_SIGN_IN);
    }

    private void handleLegacyGoogleSignInResult(Intent data) {
        Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
        try {
            GoogleSignInAccount account = task.getResult(ApiException.class);
            if (account == null || account.getIdToken() == null) {
                Toast.makeText(this, R.string.google_sign_in_failed, Toast.LENGTH_SHORT).show();
                return;
            }
            authenticateFirebaseToken(account.getIdToken());
        } catch (ApiException error) {
            DiagnosticLog.record(this, "NudgeMain", "Legacy Google sign-in failed status=" + error.getStatusCode(), error);
            Toast.makeText(this, R.string.google_sign_in_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void authenticateGoogleCredential(Credential credential) {
        if (!(credential instanceof CustomCredential)
                || !GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(credential.getType())) {
            Toast.makeText(this, R.string.google_sign_in_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        GoogleIdTokenCredential googleCredential;
        try {
            googleCredential = GoogleIdTokenCredential.createFrom(credential.getData());
        } catch (RuntimeException error) {
            DiagnosticLog.record(this, "NudgeMain", "Google ID token parsing failed", error);
            Toast.makeText(this, R.string.google_sign_in_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        authenticateFirebaseToken(googleCredential.getIdToken());
    }

    private void authenticateFirebaseToken(String idToken) {
        AuthCredential firebaseCredential = GoogleAuthProvider.getCredential(idToken, null);
        FirebaseAuth.getInstance().signInWithCredential(firebaseCredential)
                .addOnCompleteListener(this, task -> {
                    if (!task.isSuccessful()) {
                        DiagnosticLog.record(
                                MainActivity.this,
                                "NudgeMain",
                                "Firebase sign-in failed",
                                task.getException()
                        );
                        Toast.makeText(this, R.string.google_sign_in_failed, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    cloudSyncStatus = "syncing";
                    FirebaseTodoSync.start(this, cloudSyncListener);
                    Toast.makeText(this, R.string.google_signed_in, Toast.LENGTH_SHORT).show();
                    setContentView(buildContent());
                    refreshTodos();
                });
    }

    private View sectionTitle(String title, String subtitle) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView titleView = text(title, 12, COLOR_MUTED, true);
        box.addView(titleView);

        if (subtitle != null && subtitle.length() > 0) {
            TextView subtitleView = text(subtitle, 12, 0xFFC9CDD2, false);
            subtitleView.setPadding(0, dp(4), 0, 0);
            box.addView(subtitleView);
        }
        return box;
    }

    private void addTodo() {
        String value = input.getText().toString().trim();
        if (value.length() == 0) {
            return;
        }
        TodoStore.add(this, value);
        input.setText("");
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        }
        refreshTodos();
    }

    private void scrollTodoInputIntoView(View anchor) {
        if (mainScroll == null || anchor == null) {
            return;
        }
        mainScroll.setPadding(0, 0, 0, dp(180));
        mainScroll.postDelayed(() -> {
            Rect anchorRect = new Rect();
            anchor.getDrawingRect(anchorRect);
            mainScroll.offsetDescendantRectToMyCoords(anchor, anchorRect);
            int targetY = Math.max(0, anchorRect.top - dp(18));
            mainScroll.smoothScrollTo(0, targetY);
        }, 180);
    }

    private void refreshTodos() {
        if (todoList == null) {
            return;
        }

        todoList.removeAllViews();
        List<TodoItem> items = TodoStore.load(this);
        if (items.isEmpty()) {
            TextView empty = text(getString(R.string.empty_todos_main), 15, COLOR_MUTED, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(24), 0, dp(18));
            empty.setBackground(rounded(COLOR_FIELD, 16));
            todoList.addView(empty, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(86)
            ));
            return;
        }

        for (int i = 0; i < items.size(); i++) {
            View row = todoRow(items.get(i), i);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.topMargin = i == 0 ? 0 : dp(8);
            todoList.addView(row, params);
        }
    }

    private View todoRow(TodoItem item, int index) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(6), dp(2), dp(6));
        row.setBackground(rounded(COLOR_PANEL, 16));
        row.setTag(item.id);

        TextView label = text(item.text, 16, COLOR_INK, false);
        label.setPadding(0, 0, dp(10), 0);
        label.setOnClickListener(v -> handleMainTodoTap(item));
        label.setOnLongClickListener(v -> startMainTodoDrag(row, item));
        row.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        CopyTodoButtonView copy = new CopyTodoButtonView(this);
        copy.setOnClickListener(v -> copyTodoToClipboard(item));
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(dp(40), dp(42));
        row.addView(copy, copyParams);

        DeleteTodoButtonView delete = new DeleteTodoButtonView(this);
        delete.setOnClickListener(v -> {
            deletedTodos.push(new DeletedTodo(item, index));
            TodoStore.remove(this, item.id);
            updateUndoDeleteButton();
            refreshTodos();
        });
        row.addView(delete, new LinearLayout.LayoutParams(dp(40), dp(42)));
        return row;
    }

    private void handleMainTodoTap(TodoItem item) {
        long now = SystemClock.uptimeMillis();
        if (lastTodoTapId == item.id && now - lastTodoTapAt <= TODO_DOUBLE_TAP_MS) {
            lastTodoTapId = -1L;
            lastTodoTapAt = 0L;
            showMainEditTodoDialog(item);
            return;
        }
        lastTodoTapId = item.id;
        lastTodoTapAt = now;
    }

    private boolean startMainTodoDrag(View row, TodoItem item) {
        draggingMainTodoRow = row;
        mainTodoDropIndex = indexOfMainTodoRow(row);
        mainTodoDropCommitted = false;
        row.setAlpha(0.35f);
        ClipData data = ClipData.newPlainText("todo", Long.toString(item.id));
        boolean started = row.startDragAndDrop(data, new View.DragShadowBuilder(row), item.id, 0);
        if (!started) {
            resetMainTodoDragVisuals();
        }
        return started;
    }

    private boolean handleMainTodoDrag(DragEvent event) {
        if (!(event.getLocalState() instanceof Long)) {
            return false;
        }
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return true;
            case DragEvent.ACTION_DRAG_LOCATION:
                mainTodoDropIndex = mainTodoIndexAt(event.getY());
                updateMainTodoDragPreview();
                return true;
            case DragEvent.ACTION_DROP:
                mainTodoDropIndex = mainTodoIndexAt(event.getY());
                TodoStore.move(this, (Long) event.getLocalState(), mainTodoDropIndex);
                mainTodoDropCommitted = true;
                return true;
            case DragEvent.ACTION_DRAG_ENDED:
                boolean refresh = mainTodoDropCommitted;
                resetMainTodoDragVisuals();
                if (refresh && todoList != null) {
                    todoList.post(this::refreshTodos);
                }
                return true;
            default:
                return true;
        }
    }

    private int indexOfMainTodoRow(View row) {
        return todoList == null ? -1 : todoList.indexOfChild(row);
    }

    private int mainTodoIndexAt(float localY) {
        if (todoList == null || todoList.getChildCount() == 0) {
            return 0;
        }
        for (int i = 0; i < todoList.getChildCount(); i++) {
            View child = todoList.getChildAt(i);
            if (localY < child.getTop() + child.getHeight() * 0.5f) {
                return i;
            }
        }
        return todoList.getChildCount() - 1;
    }

    private void updateMainTodoDragPreview() {
        if (todoList == null) {
            return;
        }
        for (int i = 0; i < todoList.getChildCount(); i++) {
            View child = todoList.getChildAt(i);
            if (child == draggingMainTodoRow) {
                child.setAlpha(0.35f);
            } else {
                child.setAlpha(i == mainTodoDropIndex ? 0.6f : 1f);
            }
        }
    }

    private void resetMainTodoDragVisuals() {
        if (todoList != null) {
            for (int i = 0; i < todoList.getChildCount(); i++) {
                todoList.getChildAt(i).setAlpha(1f);
            }
        }
        draggingMainTodoRow = null;
        mainTodoDropIndex = -1;
        mainTodoDropCommitted = false;
    }

    private void showMainEditTodoDialog(TodoItem item) {
        Dialog dialog = new Dialog(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(22), dp(20), dp(22), dp(16));
        panel.setBackground(rounded(COLOR_PANEL, 18));

        TextView title = text(getString(R.string.edit_todo), 17, COLOR_INK, true);
        title.setGravity(Gravity.CENTER);
        panel.addView(title);

        EditText editor = new EditText(this);
        editor.setSingleLine(true);
        editor.setText(item.text);
        editor.setSelection(editor.getText().length());
        editor.setTextColor(COLOR_INK);
        editor.setTextSize(16);
        editor.setGravity(Gravity.CENTER_VERTICAL);
        editor.setPadding(dp(12), 0, dp(12), 0);
        editor.setBackground(rounded(COLOR_FIELD, 10));
        LinearLayout.LayoutParams editorParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
        );
        editorParams.topMargin = dp(14);
        panel.addView(editor, editorParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
        );
        actionsParams.topMargin = dp(10);
        panel.addView(actions, actionsParams);

        Button cancel = quietButton(getString(R.string.cancel), COLOR_MUTED);
        cancel.setOnClickListener(v -> dialog.dismiss());
        actions.addView(cancel, new LinearLayout.LayoutParams(dp(76), dp(44)));

        Button save = filledButton(getString(R.string.save));
        save.setOnClickListener(v -> {
            TodoStore.setText(this, item.id, editor.getText().toString());
            dialog.dismiss();
            refreshTodos();
        });
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(dp(76), dp(44));
        saveParams.leftMargin = dp(8);
        actions.addView(save, saveParams);

        dialog.setContentView(panel);
        dialog.setOnShowListener(ignored -> {
            editor.requestFocus();
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(0x00000000));
                window.setDimAmount(0.32f);
                window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
                window.setLayout(
                        Math.round(getResources().getDisplayMetrics().widthPixels * 0.86f),
                        android.view.WindowManager.LayoutParams.WRAP_CONTENT
                );
            }
        });
        dialog.show();
    }

    private void undoDelete() {
        if (deletedTodos.isEmpty()) {
            return;
        }
        DeletedTodo deletedTodo = deletedTodos.pop();
        TodoStore.restore(this, deletedTodo.item, deletedTodo.index);
        updateUndoDeleteButton();
        refreshTodos();
    }

    private void updateUndoDeleteButton() {
        if (undoDeleteButton != null) {
            undoDeleteButton.setVisibility(deletedTodos.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    private static final class DeletedTodo {
        final TodoItem item;
        final int index;

        DeletedTodo(TodoItem item, int index) {
            this.item = item;
            this.index = index;
        }
    }

    private boolean requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            return true;
        }
        return false;
    }

    private void requestRecordAudioPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATIONS) {
            requestRecordAudioPermission();
        }
    }

    private void openLockBackgroundPicker() {
        DiagnosticLog.recordAppState(this, "open lock background picker");
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_LOCK_BACKGROUND_IMAGE);
        } catch (RuntimeException e) {
            DiagnosticLog.record(this, "NudgeMain", "lock background picker failed", e);
        }
    }

    private void saveLockBackgroundImage(Intent data) {
        Uri uri = data.getData();
        if (uri == null) {
            DiagnosticLog.record(this, "NudgeMain", "lock background picker returned no uri");
            return;
        }

        String previousUri = AppSettings.lockBackgroundImageUri(this);
        int flags = data.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(uri, flags & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (RuntimeException e) {
            DiagnosticLog.record(this, "NudgeMain", "lock background persist permission failed uri=" + uri, e);
        }

        if (previousUri != null && previousUri.length() > 0 && !previousUri.equals(uri.toString())) {
            releasePersistedLockBackgroundUri(previousUri);
        }
        AppSettings.setLockBackgroundImageUri(this, uri.toString());
        DiagnosticLog.record(this, "NudgeMain", "lock background image selected uri=" + uri);
    }

    private void clearLockBackgroundImage() {
        DiagnosticLog.record(this, "NudgeMain", "lock background image cleared");
        releasePersistedLockBackgroundUri(AppSettings.lockBackgroundImageUri(this));
        AppSettings.clearLockBackgroundImageUri(this);
    }

    private void releasePersistedLockBackgroundUri(String savedUri) {
        if (savedUri == null || savedUri.length() == 0) {
            return;
        }
        try {
            getContentResolver().releasePersistableUriPermission(
                    Uri.parse(savedUri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            );
        } catch (RuntimeException ignored) {
        }
    }

    private void openNotificationSettings() {
        DiagnosticLog.recordAppState(this, "open notification settings");
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && needsFullScreenIntentPermission()) {
            intent = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                    .setData(Uri.parse("package:" + getPackageName()));
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        } else {
            intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:" + getPackageName()));
        }
        startActivity(intent);
    }

    private void showFullScreenIntentGuide() {
        DiagnosticLog.recordAppState(this, "open full screen intent guide");
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(20), dp(20), dp(16));
        box.setBackground(rounded(COLOR_PANEL, 12));

        TextView title = text(getString(R.string.full_screen_alert_title), 20, COLOR_INK, true);
        box.addView(title);

        TextView body = text(getString(R.string.full_screen_alert_body), 15, COLOR_MUTED, false);
        body.setPadding(0, dp(10), 0, dp(18));
        box.addView(body);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        Button close = quietButton(getString(R.string.close), COLOR_MUTED);
        close.setOnClickListener(v -> dialog.dismiss());
        buttons.addView(close, new LinearLayout.LayoutParams(dp(88), dp(44)));

        Button settings = filledButton(getString(R.string.open_settings));
        settings.setOnClickListener(v -> {
            dialog.dismiss();
            openFullScreenIntentSettings();
        });
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(dp(132), dp(44));
        settingsParams.leftMargin = dp(8);
        buttons.addView(settings, settingsParams);
        box.addView(buttons);

        dialog.setContentView(box);
        dialog.show();

        Window dialogWindow = dialog.getWindow();
        if (dialogWindow != null) {
            dialogWindow.setBackgroundDrawable(new ColorDrawable(0x00000000));
            dialogWindow.setLayout(
                    Math.min(getResources().getDisplayMetrics().widthPixels - dp(40), dp(420)),
                    FrameLayout.LayoutParams.WRAP_CONTENT
            );
        }
    }

    private void openFullScreenIntentSettings() {
        DiagnosticLog.recordAppState(this, "open full screen intent settings");
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            intent = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                    .setData(Uri.parse("package:" + getPackageName()));
        } else {
            intent = appDetailsIntent();
        }
        try {
            startActivity(intent);
        } catch (RuntimeException ignored) {
            startActivity(appDetailsIntent());
        }
    }

    private void openBatterySettings() {
        DiagnosticLog.recordAppState(this, "open battery settings");
        Intent intent = batteryOptimizationIntent();
        try {
            startActivity(intent);
        } catch (Exception ignored) {
            startActivity(appDetailsIntent());
        }
    }

    private void openDeviceAdminSettings() {
        DiagnosticLog.recordAppState(this, "open device admin settings");
        ComponentName admin = new ComponentName(this, NudgeDeviceAdminReceiver.class);
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.device_admin_explanation));
        try {
            startActivity(intent);
        } catch (RuntimeException e) {
            DiagnosticLog.record(this, "NudgeMain", "device admin request failed", e);
            startActivity(appDetailsIntent());
        }
    }

    private void maybeShowBatteryGuideOnboarding() {
        if (AppSettings.batteryGuideShown(this)) {
            return;
        }
        if (isBatteryUnrestricted()) {
            AppSettings.setBatteryGuideShown(this, true);
            return;
        }
        getWindow().getDecorView().post(() -> showBatteryGuideDialog(true));
    }

    private void showBatteryGuideDialog(boolean firstRun) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(20), dp(20), dp(16));
        box.setBackground(rounded(COLOR_PANEL, 12));

        TextView title = text(getString(R.string.battery_guide_title), 20, COLOR_INK, true);
        box.addView(title);

        TextView body = text(
                getString(R.string.battery_guide_body),
                15,
                COLOR_MUTED,
                false
        );
        body.setPadding(0, dp(10), 0, dp(18));
        box.addView(body);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        Button later = quietButton(getString(R.string.later), COLOR_MUTED);
        later.setOnClickListener(v -> {
            if (firstRun) {
                AppSettings.setBatteryGuideShown(this, true);
            }
            dialog.dismiss();
        });
        buttons.addView(later, new LinearLayout.LayoutParams(dp(88), dp(44)));

        Button settings = filledButton(getString(R.string.open_settings));
        settings.setOnClickListener(v -> {
            if (firstRun) {
                AppSettings.setBatteryGuideShown(this, true);
            }
            dialog.dismiss();
            openBatterySettings();
        });
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(dp(132), dp(44));
        settingsParams.leftMargin = dp(8);
        buttons.addView(settings, settingsParams);
        box.addView(buttons);

        dialog.setContentView(box);
        dialog.setOnCancelListener(d -> {
            if (firstRun) {
                AppSettings.setBatteryGuideShown(this, true);
            }
        });
        dialog.show();

        Window dialogWindow = dialog.getWindow();
        if (dialogWindow != null) {
            dialogWindow.setBackgroundDrawable(new ColorDrawable(0x00000000));
            dialogWindow.setLayout(
                    Math.min(getResources().getDisplayMetrics().widthPixels - dp(40), dp(420)),
                    FrameLayout.LayoutParams.WRAP_CONTENT
            );
        }
    }

    private Intent batteryOptimizationIntent() {
        if (!isBatteryUnrestricted()) {
            return new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:" + getPackageName()));
        }
        return appDetailsIntent();
    }

    private Intent appDetailsIntent() {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:" + getPackageName()));
    }

    private boolean isBatteryUnrestricted() {
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        return powerManager != null && powerManager.isIgnoringBatteryOptimizations(getPackageName());
    }

    private boolean needsFullScreenIntentPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return false;
        }
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        return manager != null && !manager.canUseFullScreenIntent();
    }

    private void showLanguageDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(18), dp(20), dp(14));
        box.setBackground(rounded(COLOR_PANEL, 12));

        TextView title = text(getString(R.string.language_setting), 20, COLOR_INK, true);
        box.addView(title);

        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        group.setPadding(0, dp(12), 0, dp(8));

        addLanguageOption(group, "", getString(R.string.language_system));
        addLanguageOption(group, "ko", getString(R.string.language_korean));
        addLanguageOption(group, "en", getString(R.string.language_english));
        addLanguageOption(group, "zh-TW", getString(R.string.language_traditional_chinese));

        String current = AppSettings.languageTag(this);
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof RadioButton && current.equals(String.valueOf(child.getTag()))) {
                ((RadioButton) child).setChecked(true);
                break;
            }
        }

        group.setOnCheckedChangeListener((radioGroup, checkedId) -> {
            RadioButton checked = radioGroup.findViewById(checkedId);
            if (checked == null) {
                return;
            }
            String languageTag = String.valueOf(checked.getTag());
            applyLanguage(languageTag);
            dialog.dismiss();
        });
        box.addView(group);

        Button close = quietButton(getString(R.string.close), COLOR_MUTED);
        close.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(dp(96), dp(44));
        closeParams.gravity = Gravity.RIGHT;
        box.addView(close, closeParams);

        dialog.setContentView(box);
        dialog.show();

        Window dialogWindow = dialog.getWindow();
        if (dialogWindow != null) {
            dialogWindow.setBackgroundDrawable(new ColorDrawable(0x00000000));
            dialogWindow.setLayout(
                    Math.min(getResources().getDisplayMetrics().widthPixels - dp(40), dp(420)),
                    FrameLayout.LayoutParams.WRAP_CONTENT
            );
        }
    }

    private void showSpeechLanguageDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(18), dp(20), dp(14));
        box.setBackground(rounded(COLOR_PANEL, 12));

        TextView title = text("음성 입력 언어", 20, COLOR_INK, true);
        box.addView(title);

        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        group.setPadding(0, dp(12), 0, dp(8));

        addLanguageOption(group, "ko-KR", getString(R.string.language_korean));
        addLanguageOption(group, "en-US", getString(R.string.language_english));
        addLanguageOption(group, "ja-JP", "日本語");
        addLanguageOption(group, "zh-TW", getString(R.string.language_traditional_chinese));
        addLanguageOption(group, "", "앱 언어 따름");

        String current = AppSettings.speechLanguageTag(this);
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof RadioButton && current.equals(String.valueOf(child.getTag()))) {
                ((RadioButton) child).setChecked(true);
                break;
            }
        }

        group.setOnCheckedChangeListener((radioGroup, checkedId) -> {
            RadioButton checked = radioGroup.findViewById(checkedId);
            if (checked == null) {
                return;
            }
            AppSettings.setSpeechLanguageTag(this, String.valueOf(checked.getTag()));
            DiagnosticLog.recordAppState(this, "speech language changed tag=" + checked.getTag());
            dialog.dismiss();
            showDrawerPage(drawerPage);
        });
        box.addView(group);

        Button close = quietButton(getString(R.string.close), COLOR_MUTED);
        close.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(dp(96), dp(44));
        closeParams.gravity = Gravity.RIGHT;
        box.addView(close, closeParams);

        dialog.setContentView(box);
        dialog.show();

        Window dialogWindow = dialog.getWindow();
        if (dialogWindow != null) {
            dialogWindow.setBackgroundDrawable(new ColorDrawable(0x00000000));
            dialogWindow.setLayout(
                    Math.min(getResources().getDisplayMetrics().widthPixels - dp(40), dp(420)),
                    FrameLayout.LayoutParams.WRAP_CONTENT
            );
        }
    }

    private void addLanguageOption(RadioGroup group, String languageTag, String label) {
        RadioButton option = new RadioButton(this);
        option.setText(label);
        option.setTextSize(16);
        option.setTextColor(COLOR_INK);
        option.setTag(languageTag);
        option.setPadding(0, dp(8), 0, dp(8));
        group.addView(option, new RadioGroup.LayoutParams(
                RadioGroup.LayoutParams.MATCH_PARENT,
                dp(48)
        ));
    }

    private void applyLanguage(String languageTag) {
        AppSettings.setLanguageTag(this, languageTag);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            LocaleManager localeManager = getSystemService(LocaleManager.class);
            if (localeManager != null) {
                LocaleList locales = languageTag == null || languageTag.length() == 0
                        ? LocaleList.getEmptyLocaleList()
                        : LocaleList.forLanguageTags(languageTag);
                localeManager.setApplicationLocales(locales);
            }
        }
        DiagnosticLog.recordAppState(this, "language changed tag=" + languageTag);
        recreate();
    }

    private String currentLanguageName() {
        String languageTag = AppSettings.languageTag(this);
        if ("ko".equals(languageTag)) {
            return getString(R.string.language_korean);
        }
        if ("en".equals(languageTag)) {
            return getString(R.string.language_english);
        }
        if ("zh-TW".equals(languageTag)) {
            return getString(R.string.language_traditional_chinese);
        }
        return getString(R.string.language_system);
    }

    private String currentSpeechLanguageName() {
        String languageTag = AppSettings.speechLanguageTag(this);
        if ("ko-KR".equals(languageTag)) {
            return getString(R.string.language_korean);
        }
        if ("en-US".equals(languageTag)) {
            return getString(R.string.language_english);
        }
        if ("ja-JP".equals(languageTag)) {
            return "日本語";
        }
        if ("zh-TW".equals(languageTag)) {
            return getString(R.string.language_traditional_chinese);
        }
        return "앱 언어 따름";
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(18), dp(20), dp(16));
        card.setBackground(rounded(COLOR_PANEL, 24));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            card.setElevation(0);
        }
        return card;
    }

    private View divider() {
        return divider(4);
    }

    private View divider(int topMarginDp) {
        View view = new View(this);
        view.setBackgroundColor(COLOR_LINE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.max(1, dp(1))
        );
        params.topMargin = dp(topMarginDp);
        view.setLayoutParams(params);
        return view;
    }

    private View actionRow(String label, View.OnClickListener listener, boolean first) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(2), 0, dp(2));

        TextView icon = text("\u25CB", 18, COLOR_ACCENT, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(rounded(0xFFEFF6F1, 16));
        row.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32)));

        TextView textView = text(label, 15, COLOR_INK, false);
        textView.setGravity(Gravity.CENTER_VERTICAL);
        textView.setPadding(dp(12), 0, 0, 0);
        row.addView(textView, new LinearLayout.LayoutParams(0, dp(52), 1));
        if (first) {
            cloudAccountText = textView;
        }
        TextView chevron = text("\u203A", 22, 0xFFC9CDD2, false);
        chevron.setGravity(Gravity.CENTER);
        row.addView(chevron, new LinearLayout.LayoutParams(dp(24), dp(52)));
        row.setOnClickListener(listener);
        return row;
    }

    private View categoryRow(String title, String subtitle, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(8), dp(8), dp(8));
        row.setBackground(rounded(COLOR_FIELD, 18));
        row.setOnClickListener(listener);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = text(title, 16, COLOR_INK, true);
        TextView subtitleView = text(subtitle, 12, COLOR_MUTED, false);
        subtitleView.setPadding(0, dp(3), 0, 0);
        copy.addView(titleView);
        copy.addView(subtitleView);
        row.addView(copy, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView chevron = text("\u203A", 24, 0xFFC9CDD2, false);
        chevron.setGravity(Gravity.CENTER);
        row.addView(chevron, new LinearLayout.LayoutParams(dp(28), dp(54)));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(8);
        row.setLayoutParams(params);
        return row;
    }

    private View detailRow(String title, String subtitle, View.OnClickListener listener, boolean cloudRow) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));
        row.setOnClickListener(listener);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = text(title, 15, COLOR_INK, false);
        TextView subtitleView = text(subtitle == null ? "" : subtitle, 12, COLOR_MUTED, false);
        subtitleView.setPadding(0, dp(3), 0, 0);
        copy.addView(titleView);
        copy.addView(subtitleView);
        row.addView(copy, new LinearLayout.LayoutParams(0, dp(58), 1));
        if (cloudRow) {
            cloudAccountText = titleView;
        }

        TextView chevron = text("\u203A", 22, 0xFFC9CDD2, false);
        chevron.setGravity(Gravity.CENTER);
        row.addView(chevron, new LinearLayout.LayoutParams(dp(24), dp(58)));
        return row;
    }

    private View switchRow(String title, String subtitle, boolean checked, BooleanSetting listener) {
        LinearLayout row = settingBaseRow(title, subtitle);
        Switch switchView = new Switch(this);
        tintSwitch(switchView);
        switchView.setChecked(checked);
        switchView.setOnCheckedChangeListener((buttonView, isChecked) -> listener.set(isChecked));
        row.addView(switchView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        return row;
    }

    private View checkRow(String title, String subtitle, boolean checked, BooleanSetting listener) {
        LinearLayout row = settingBaseRow(title, subtitle);
        CheckBox checkBox = new CheckBox(this);
        tintCheckBox(checkBox);
        checkBox.setChecked(checked);
        checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> listener.set(isChecked));
        row.addView(checkBox, new LinearLayout.LayoutParams(dp(48), dp(48)));
        return row;
    }

    private View opacityRow() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(8), 0, dp(10));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        opacityValue = text(AppSettings.overlayOpacity(this) + "%", 15, COLOR_ACCENT, false);
        header.addView(text(getString(R.string.overlay_opacity), 15, COLOR_INK, false),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        opacityValue.setGravity(Gravity.RIGHT);
        header.addView(opacityValue, new LinearLayout.LayoutParams(dp(70), LinearLayout.LayoutParams.WRAP_CONTENT));
        box.addView(header);

        SeekBar opacity = new SeekBar(this);
        tintSeekBar(opacity);
        opacity.setMax(20);
        opacity.setProgress(Math.round(AppSettings.overlayOpacity(this) / 5f));
        opacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int snapped = progress * 5;
                AppSettings.setOverlayOpacity(MainActivity.this, snapped);
                opacityValue.setText(snapped + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        box.addView(opacity, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
        ));
        return box;
    }

    private LinearLayout settingBaseRow(String title, String subtitle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.addView(text(title, 15, COLOR_INK, false));
        if (subtitle != null && subtitle.length() > 0) {
            TextView subtitleView = text(subtitle, 12, COLOR_MUTED, false);
            subtitleView.setPadding(0, dp(3), dp(10), 0);
            copy.addView(subtitleView);
        }
        row.addView(copy, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    private interface BooleanSetting {
        void set(boolean value);
    }

    private View drawerSectionTitle(String label) {
        TextView title = text(label, 14, COLOR_MUTED, true);
        title.setPadding(0, dp(20), 0, dp(4));
        return title;
    }

    private View betaVersionLabel() {
        TextView label = text(getString(R.string.beta_version_label, appVersionName()), 13, COLOR_MUTED, false);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(18), 0, dp(2));
        return label;
    }

    private View diagnosticsButton() {
        TextView button = text(getString(R.string.diagnostics_button), 12, COLOR_MUTED, false);
        button.setGravity(Gravity.CENTER);
        button.setPadding(0, dp(10), 0, dp(4));
        button.setOnClickListener(v -> showDiagnosticsDialog());
        return button;
    }

    private void showDiagnosticsDialog() {
        DiagnosticLog.recordAppState(this, "open diagnostics");
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(18), dp(18), dp(14));
        box.setBackground(rounded(COLOR_PANEL, 12));

        TextView title = text(getString(R.string.diagnostics_title), 20, COLOR_INK, true);
        box.addView(title);

        TextView logView = text(diagnosticText(), 12, COLOR_INK, false);
        logView.setTextIsSelectable(true);
        logView.setPadding(dp(12), dp(10), dp(12), dp(10));
        logView.setBackground(rounded(COLOR_FIELD, 8));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(logView, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(340)
        );
        scrollParams.topMargin = dp(12);
        box.addView(scroll, scrollParams);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(12), 0, 0);

        Button clear = quietButton(getString(R.string.diagnostics_clear), COLOR_DANGER);
        clear.setOnClickListener(v -> {
            DiagnosticLog.clear(this);
            logView.setText(diagnosticText());
        });
        buttons.addView(clear, new LinearLayout.LayoutParams(dp(82), dp(44)));

        Button send = filledButton(getString(R.string.diagnostics_send));
        send.setOnClickListener(v -> shareDiagnostics());
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(dp(96), dp(44));
        sendParams.leftMargin = dp(8);
        buttons.addView(send, sendParams);

        Button close = quietButton(getString(R.string.close), COLOR_MUTED);
        close.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(dp(78), dp(44));
        closeParams.leftMargin = dp(8);
        buttons.addView(close, closeParams);

        box.addView(buttons);
        dialog.setContentView(box);
        dialog.show();

        Window dialogWindow = dialog.getWindow();
        if (dialogWindow != null) {
            dialogWindow.setBackgroundDrawable(new ColorDrawable(0x00000000));
            dialogWindow.setLayout(
                    Math.min(getResources().getDisplayMetrics().widthPixels - dp(28), dp(560)),
                    FrameLayout.LayoutParams.WRAP_CONTENT
            );
        }
    }

    private String diagnosticText() {
        String value = DiagnosticLog.shareText(this);
        return value.trim().length() == 0 ? getString(R.string.diagnostics_empty) : value;
    }

    private void shareDiagnostics() {
        DiagnosticLog.recordAppState(this, "share diagnostics");
        Intent intent = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.diagnostics_share_subject))
                .putExtra(Intent.EXTRA_TEXT, DiagnosticLog.shareText(this));
        startActivity(Intent.createChooser(intent, getString(R.string.diagnostics_send)));
    }

    private String appVersionName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            return "";
        }
    }

    private TextView chip(String value, int bgColor, int textColor) {
        TextView chip = text(value, 13, textColor, true);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(12), 0, dp(12), 0);
        chip.setBackground(rounded(bgColor, 17));
        chip.setMinHeight(dp(34));
        return chip;
    }

    private Button filledButton(String label) {
        Button button = baseButton(label);
        button.setTextColor(0xFFFFFFFF);
        button.setBackground(rounded(COLOR_ACCENT, 16));
        return button;
    }

    private Button quietButton(String label, int color) {
        Button button = baseButton(label);
        button.setTextColor(color);
        button.setBackground(rounded(0x00FFFFFF, 8));
        return button;
    }

    private final class AddTodoButtonView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        AddTodoButtonView(Context context) {
            super(context);
            setClickable(true);
            setFocusable(true);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float inset = dp(2);
            rect.set(inset, inset, getWidth() - inset, getHeight() - inset);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(COLOR_ACCENT);
            canvas.drawRoundRect(rect, dp(15), dp(15), paint);

            float centerX = getWidth() * 0.5f;
            float centerY = getHeight() * 0.5f;
            float half = dp(7.5f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2.2f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(0xFFFFFFFF);
            canvas.drawLine(centerX - half, centerY, centerX + half, centerY, paint);
            canvas.drawLine(centerX, centerY - half, centerX, centerY + half, paint);
        }
    }

    private final class CopyTodoButtonView extends View {
        private final Drawable icon;

        CopyTodoButtonView(Context context) {
            super(context);
            setClickable(true);
            setFocusable(true);
            icon = requireDrawable(R.drawable.ic_copy_lucide);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            drawCenteredDrawable(canvas, icon, this, dp(20));
        }
    }

    private final class DeleteTodoButtonView extends View {
        private final Drawable icon;

        DeleteTodoButtonView(Context context) {
            super(context);
            setClickable(true);
            setFocusable(true);
            icon = requireDrawable(R.drawable.ic_trash_lucide);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            drawCenteredDrawable(canvas, icon, this, dp(20));
        }
    }

    private Drawable requireDrawable(int resId) {
        Drawable drawable = getDrawable(resId);
        if (drawable == null) {
            throw new IllegalStateException("Missing drawable resource " + resId);
        }
        return drawable.mutate();
    }

    private void drawCenteredDrawable(Canvas canvas, Drawable drawable, View host, int sizePx) {
        int left = Math.round((host.getWidth() - sizePx) / 2f);
        int top = Math.round((host.getHeight() - sizePx) / 2f);
        drawable.setBounds(left, top, left + sizePx, top + sizePx);
        drawable.draw(canvas);
    }

    private void tintCheckBox(CheckBox checkBox) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            checkBox.setButtonTintList(controlTint(0xFFC9CDD2));
        }
    }

    private void tintSwitch(Switch switchView) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            int[][] states = {
                    new int[]{android.R.attr.state_checked},
                    new int[]{}
            };
            switchView.setThumbTintList(new ColorStateList(states, new int[]{COLOR_ACCENT, 0xFFFFFFFF}));
            switchView.setTrackTintList(new ColorStateList(states, new int[]{0x665F8F73, 0xFFE5E8EB}));
        }
    }

    private void tintSeekBar(SeekBar seekBar) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            seekBar.setThumbTintList(ColorStateList.valueOf(COLOR_ACCENT));
            seekBar.setProgressTintList(ColorStateList.valueOf(COLOR_ACCENT));
            seekBar.setProgressBackgroundTintList(ColorStateList.valueOf(0xFFE1E5E8));
        }
    }

    private ColorStateList controlTint(int uncheckedColor) {
        int[][] states = {
                new int[]{android.R.attr.state_checked},
                new int[]{}
        };
        return new ColorStateList(states, new int[]{COLOR_ACCENT, uncheckedColor});
    }

    private Button baseButton(String label) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(10), 0, dp(10), 0);
        return button;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setIncludeFontPadding(true);
        view.setLineSpacing(dp(1), 1.0f);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return view;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(12);
        return params;
    }

    private GradientDrawable gradient(int startColor, int endColor) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{startColor, endColor}
        );
        drawable.setCornerRadius(dp(8));
        return drawable;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private GradientDrawable topRounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        float radius = dp(radiusDp);
        drawable.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        return drawable;
    }

    private GradientDrawable roundedStroke(int fillColor, int strokeColor, int radiusDp) {
        GradientDrawable drawable = rounded(fillColor, radiusDp);
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private final class CircleIconButtonView extends View {
        static final int ICON_PREVIEW = 1;
        static final int ICON_SETTINGS = 2;
        static final int ICON_BACK = 3;
        static final int ICON_CLOSE = 4;

        private final int icon;
        private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint iconFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();

        CircleIconButtonView(Context context, int icon) {
            super(context);
            this.icon = icon;
            setClickable(true);
            setFocusable(true);
            setBackground(rounded(icon == ICON_BACK || icon == ICON_CLOSE ? COLOR_FIELD : COLOR_PANEL, 99));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setClipToOutline(true);
                setElevation(icon == ICON_BACK || icon == ICON_CLOSE ? 0f : dp(1));
            }
            iconPaint.setColor(0xFF6B7684);
            iconPaint.setStrokeWidth(icon == ICON_BACK || icon == ICON_CLOSE ? dp(2) : dp(1.6f));
            iconPaint.setStrokeCap(Paint.Cap.ROUND);
            iconPaint.setStrokeJoin(Paint.Join.ROUND);
            iconPaint.setStyle(Paint.Style.STROKE);
            iconFillPaint.setColor(0xFF6B7684);
            iconFillPaint.setStyle(Paint.Style.FILL);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;
            if (icon == ICON_PREVIEW) {
                path.reset();
                path.moveTo(w * 0.24f, cy);
                path.cubicTo(w * 0.34f, h * 0.34f, w * 0.66f, h * 0.34f, w * 0.76f, cy);
                path.cubicTo(w * 0.66f, h * 0.66f, w * 0.34f, h * 0.66f, w * 0.24f, cy);
                canvas.drawPath(path, iconPaint);
                canvas.drawCircle(cx, cy, Math.min(w, h) * 0.105f, iconPaint);
            } else if (icon == ICON_SETTINGS) {
                float size = Math.min(w, h);
                iconFillPaint.setColor(0xFF6B7684);
                iconPaint.setStrokeWidth(dp(1.9f));
                float left = w * 0.29f;
                float right = w * 0.71f;
                float topY = h * 0.36f;
                float midY = h * 0.50f;
                float bottomY = h * 0.64f;
                canvas.drawLine(left, topY, right, topY, iconPaint);
                canvas.drawLine(left, midY, right, midY, iconPaint);
                canvas.drawLine(left, bottomY, right, bottomY, iconPaint);
                canvas.drawCircle(w * 0.43f, topY, size * 0.045f, iconFillPaint);
                canvas.drawCircle(w * 0.58f, midY, size * 0.045f, iconFillPaint);
                canvas.drawCircle(w * 0.48f, bottomY, size * 0.045f, iconFillPaint);
            } else if (icon == ICON_BACK) {
                canvas.drawLine(w * 0.58f, h * 0.30f, w * 0.40f, cy, iconPaint);
                canvas.drawLine(w * 0.40f, cy, w * 0.58f, h * 0.70f, iconPaint);
            } else if (icon == ICON_CLOSE) {
                canvas.drawLine(w * 0.36f, h * 0.36f, w * 0.64f, h * 0.64f, iconPaint);
                canvas.drawLine(w * 0.64f, h * 0.36f, w * 0.36f, h * 0.64f, iconPaint);
            }
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
