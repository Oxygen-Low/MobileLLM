package com.example.data

import android.content.Context

/**
 * Remembers which speech features the user enabled. The models themselves are
 * not kept in memory across launches; only the toggle is, so the autoload
 * logic can bring the right engine back when the app starts.
 */
class SpeechPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("speech", Context.MODE_PRIVATE)

    var speechToTextEnabled: Boolean
        get() = prefs.getBoolean(KEY_STT_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_STT_ENABLED, value).apply()

    var textToSpeechEnabled: Boolean
        get() = prefs.getBoolean(KEY_TTS_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_TTS_ENABLED, value).apply()

    private companion object {
        const val KEY_STT_ENABLED = "stt_enabled"
        const val KEY_TTS_ENABLED = "tts_enabled"
    }
}