package com.example.data

import android.content.Context

/**
 * Remembers which image model the user picked.
 *
 * Only the id is stored: the weights are not resident across launches and are
 * loaded on demand, right before a picture is generated.
 */
class ImagePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("images", Context.MODE_PRIVATE)

    /** The id of the image model the user picked, or "" before the first pick. */
    var imageModelId: String
        get() = prefs.getString(KEY_IMAGE_MODEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_IMAGE_MODEL, value).apply()

    private companion object {
        const val KEY_IMAGE_MODEL = "image_model"
    }
}
