package com.example.screenlocktodo;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

public class QuickMemoActivity extends Activity {
    static final String ACTION_QUICK_MEMO = "com.example.screenlocktodo.QUICK_MEMO";
    static final String ACTION_QUICK_MEMO_CANONICAL = "com.wns544.nudgescreen.QUICK_MEMO";
    static final String EXTRA_MEMO_TEXT = "com.wns544.nudgescreen.extra.MEMO_TEXT";
    static final String EXTRA_AUTO_SAVE = "com.wns544.nudgescreen.extra.AUTO_SAVE";
    static final String EXTRA_START_VOICE = "com.wns544.nudgescreen.extra.START_VOICE";
    private static final int REQUEST_RECORD_AUDIO_PERMISSION = 310;
    private static final int COLOR_BG = 0xFFF4F5F7;
    private static final int COLOR_INK = 0xFF191F28;
    private static final int COLOR_MUTED = 0xFF8B95A1;
    private static final int COLOR_ACCENT = 0xFF5F8F73;
    private static final int COLOR_RECORDING = 0xFFF04452;

    private EditText input;
    private ImageButton micButton;
    private SpeechRecognizer speechRecognizer;
    private boolean listening;
    private int speechInsertStart = -1;
    private boolean pendingStartVoice;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(false);
        }
        configureWindow();
        setContentView(buildContent());
        handleLaunchIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLaunchIntent(intent);
    }

    @Override
    protected void onPause() {
        stopSpeechRecognition();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        super.onDestroy();
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(0x00000000));
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.setStatusBarColor(COLOR_BG);
            window.setNavigationBarColor(COLOR_BG);
        }
    }

    private LinearLayout buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(18));
        root.setBackgroundColor(COLOR_BG);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout titleColumn = new LinearLayout(this);
        titleColumn.setOrientation(LinearLayout.VERTICAL);
        header.addView(titleColumn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(this);
        title.setText(getString(R.string.quick_memo_short_label));
        title.setTextSize(24);
        title.setTextColor(COLOR_INK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleColumn.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText(getString(R.string.quick_memo_widget_description));
        subtitle.setTextSize(13);
        subtitle.setTextColor(COLOR_MUTED);
        subtitle.setPadding(0, dp(3), dp(8), 0);
        titleColumn.addView(subtitle);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        header.addView(actions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        micButton = new ImageButton(this);
        micButton.setImageResource(R.drawable.ic_mic);
        micButton.setBackground(rounded(0xFFE9EEE9, 16));
        micButton.setColorFilter(COLOR_ACCENT);
        micButton.setOnClickListener(v -> toggleSpeechRecognition());
        LinearLayout.LayoutParams micParams = new LinearLayout.LayoutParams(dp(42), dp(42));
        micParams.rightMargin = dp(6);
        actions.addView(micButton, micParams);

        Button save = compactButton(getString(R.string.save), 0xFFFFFFFF, COLOR_ACCENT, true);
        save.setOnClickListener(v -> saveMemo());
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(dp(64), dp(42));
        saveParams.rightMargin = dp(4);
        actions.addView(save, saveParams);

        Button close = compactButton(getString(R.string.close), COLOR_MUTED, 0x00FFFFFF, false);
        close.setOnClickListener(v -> finish());
        actions.addView(close, new LinearLayout.LayoutParams(dp(58), dp(42)));

        input = new EditText(this);
        input.setMinLines(4);
        input.setMaxLines(8);
        input.setTextSize(17);
        input.setTextColor(COLOR_INK);
        input.setHintTextColor(0x888B95A1);
        input.setHint(getString(R.string.quick_memo_hint));
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        input.setBackground(rounded(0xFFFFFFFF, 18));
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        );
        inputParams.topMargin = dp(16);
        root.addView(input, inputParams);
        return root;
    }

    private Button compactButton(String label, int textColor, int backgroundColor, boolean bold) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13);
        button.setTextColor(textColor);
        button.setAllCaps(false);
        button.setPadding(0, 0, 0, 0);
        if (bold) {
            button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        button.setBackground(rounded(backgroundColor, 15));
        return button;
    }

    private void handleLaunchIntent(Intent intent) {
        if (input == null || intent == null) {
            return;
        }
        QuickMemoRequest request = requestFrom(intent);
        pendingStartVoice = request.startVoice;
        prefillFromIntent(request.text);
        if (request.autoSave && request.text.length() > 0) {
            saveMemo(false);
            return;
        }
        focusInput();
        if (pendingStartVoice) {
            input.postDelayed(() -> {
                pendingStartVoice = false;
                if (!isFinishing()) {
                    toggleSpeechRecognition();
                }
            }, 260L);
        }
    }

    private void prefillFromIntent(String value) {
        String safeValue = value == null ? "" : value.trim();
        if (safeValue.length() > 0) {
            input.setText(safeValue);
            input.setSelection(input.getText().length());
        }
    }

    private QuickMemoRequest requestFrom(Intent intent) {
        String text = sharedTextFrom(intent);
        boolean autoSave = false;
        boolean startVoice = false;
        Uri data = intent.getData();
        if (data != null) {
            String uriText = data.getQueryParameter("text");
            if (uriText != null && uriText.trim().length() > 0) {
                text = uriText.trim();
            }
            autoSave = truthy(data.getQueryParameter("save"));
            startVoice = truthy(data.getQueryParameter("voice"));
        }
        String extraText = intent.getStringExtra(EXTRA_MEMO_TEXT);
        if (extraText != null && extraText.trim().length() > 0) {
            text = extraText.trim();
        }
        autoSave = autoSave || intent.getBooleanExtra(EXTRA_AUTO_SAVE, false);
        startVoice = startVoice || intent.getBooleanExtra(EXTRA_START_VOICE, false);
        return new QuickMemoRequest(text.trim(), autoSave, startVoice);
    }

    private boolean truthy(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase();
        return "1".equals(normalized) || "true".equals(normalized) || "yes".equals(normalized);
    }

    private String sharedTextFrom(Intent intent) {
        CharSequence processText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        if (processText != null && processText.toString().trim().length() > 0) {
            return processText.toString().trim();
        }
        StringBuilder builder = new StringBuilder();
        appendExtra(builder, intent, Intent.EXTRA_TITLE);
        appendExtra(builder, intent, Intent.EXTRA_SUBJECT);
        appendExtra(builder, intent, Intent.EXTRA_TEXT);
        return builder.toString().trim();
    }

    private void appendExtra(StringBuilder builder, Intent intent, String key) {
        CharSequence value = intent.getCharSequenceExtra(key);
        if (value == null || value.toString().trim().length() == 0) {
            return;
        }
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append(value.toString().trim());
    }

    private void focusInput() {
        input.requestFocus();
        input.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 180L);
    }

    private void saveMemo() {
        saveMemo(true);
    }

    private void saveMemo(boolean showToast) {
        String text = input.getText().toString().trim();
        if (text.length() == 0) {
            if (showToast) {
                Toast.makeText(this, R.string.quick_memo_empty, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        stopSpeechRecognition();
        TodoStore.add(this, text);
        if (showToast) {
            Toast.makeText(this, R.string.quick_memo_added, Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    private void toggleSpeechRecognition() {
        if (listening) {
            stopSpeechRecognition();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO_PERMISSION);
            return;
        }
        startSpeechRecognition();
    }

    private void startSpeechRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, R.string.speech_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override
                public void onReadyForSpeech(Bundle params) {
                    listening = true;
                    speechInsertStart = input.getSelectionStart();
                    updateMicState();
                }

                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() {}

                @Override
                public void onError(int error) {
                    listening = false;
                    speechInsertStart = -1;
                    updateMicState();
                }

                @Override
                public void onResults(Bundle results) {
                    listening = false;
                    insertSpeech(results, true);
                    updateMicState();
                }

                @Override
                public void onPartialResults(Bundle partialResults) {
                    insertSpeech(partialResults, false);
                }

                @Override public void onEvent(int eventType, Bundle params) {}
            });
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechLanguageTag());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 8000L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 8000L);
        speechRecognizer.startListening(intent);
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

    private void insertSpeech(Bundle results, boolean finalResult) {
        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null || matches.isEmpty()) {
            return;
        }
        String text = matches.get(0).trim();
        if (text.length() == 0) {
            return;
        }
        int start = speechInsertStart >= 0 ? speechInsertStart : input.getText().length();
        start = Math.max(0, Math.min(start, input.getText().length()));
        input.getText().replace(start, input.getText().length(), text);
        input.setSelection(input.getText().length());
        if (finalResult) {
            speechInsertStart = -1;
        }
    }

    private void stopSpeechRecognition() {
        if (speechRecognizer != null) {
            speechRecognizer.cancel();
        }
        listening = false;
        speechInsertStart = -1;
        updateMicState();
    }

    private void updateMicState() {
        if (micButton == null) {
            return;
        }
        micButton.setColorFilter(listening ? COLOR_RECORDING : COLOR_ACCENT);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startSpeechRecognition();
        }
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class QuickMemoRequest {
        final String text;
        final boolean autoSave;
        final boolean startVoice;

        QuickMemoRequest(String text, boolean autoSave, boolean startVoice) {
            this.text = text == null ? "" : text;
            this.autoSave = autoSave;
            this.startVoice = startVoice;
        }
    }
}
