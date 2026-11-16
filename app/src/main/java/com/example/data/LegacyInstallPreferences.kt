package com.example.data

import android.content.Context
import androidx.core.content.edit

/**
 * Remembers the app version that already offered to remove the previous
 * package.
 *
 * Without it the system uninstall screen would open on every launch after the
 * user declined, which would be impossible to get rid of without removing the
 * old app after all.
 */
class LegacyInstallPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("legacy_install", Context.MODE_PRIVATE)

    /** The versionCode that last opened the uninstall screen, or [NEVER_ASKED]. */
    var askedVersionCode: Int
        get() = prefs.getInt(KEY_ASKED_VERSION, NEVER_ASKED)
        set(value) = prefs.edit { putInt(KEY_ASKED_VERSION, value) }

    companion object {
        const val NEVER_ASKED = -1

        private const val KEY_ASKED_VERSION = "asked_version_code"
    }
}
