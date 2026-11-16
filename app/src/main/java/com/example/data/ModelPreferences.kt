package com.example.data

import android.content.Context

/**
 * Remembers which chat model the user loaded last. The weights are not kept in
 * memory across launches; only the id is, so the autoload logic can bring the
 * same model back when the app starts instead of falling back to the default.
 */
class ModelPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("models", Context.MODE_PRIVATE)

    /** The id of the model that was loaded last, or "" before the first load. */
    var selectedModelId: String
        get() = prefs.getString(KEY_SELECTED_MODEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SELECTED_MODEL, value).apply()

    private companion object {
        const val KEY_SELECTED_MODEL = "selected_model"
    }
}
