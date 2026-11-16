package com.example.data

import android.content.Context

/**
 * Remembers which video model the user picked.
 *
 * Only the id is stored: the weights are not resident across launches and are
 * loaded on demand, right before a clip is generated.
 */
class VideoPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("videos", Context.MODE_PRIVATE)

    /** The id of the video model the user picked, or "" before the first pick. */
    var videoModelId: String
        get() = prefs.getString(KEY_VIDEO_MODEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_VIDEO_MODEL, value).apply()

    private companion object {
        const val KEY_VIDEO_MODEL = "video_model"
    }
}