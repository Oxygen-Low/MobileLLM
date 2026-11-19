package com.example.horde

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps the AI Horde API key.
 *
 * The key is stored in the app's private preferences and never logged. The
 * anonymous Horde key is used until the user enters one of their own.
 */
class HordePreferences(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("horde", Context.MODE_PRIVATE)

    private val _apiKey = MutableStateFlow(
        prefs.getString(KEY_API_KEY, ANONYMOUS_KEY) ?: ANONYMOUS_KEY
    )
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    /** True when the user has supplied a key rather than using the anonymous one. */
    val hasCustomKey: Boolean get() = apiKey.value != ANONYMOUS_KEY

    fun saveApiKey(value: String) {
        val key = value.trim().ifEmpty { ANONYMOUS_KEY }
        prefs.edit().putString(KEY_API_KEY, key).apply()
        _apiKey.value = key
    }

    private companion object {
        const val KEY_API_KEY = "api_key"

        /** The key AI Horde reserves for anonymous requests. */
        const val ANONYMOUS_KEY = "0000000000"
    }
}
