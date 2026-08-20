package com.example.screenlocktodo;

import android.content.Context;
import android.media.AudioManager;

final class CallStateGuard {
    private CallStateGuard() {
    }

    static boolean shouldSuppressLockScreen(Context context) {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        return audioManager != null && shouldSuppressForAudioMode(audioManager.getMode());
    }

    static boolean shouldSuppressForAudioMode(int mode) {
        return mode != AudioManager.MODE_NORMAL;
    }

    static String audioModeSummary(Context context) {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        return audioManager == null ? "unavailable" : Integer.toString(audioManager.getMode());
    }
}
