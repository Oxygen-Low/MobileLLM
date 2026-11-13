package com.example.changelog

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import com.example.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the app shows about the changes of the last update.
 */
sealed interface ChangelogState {
    /** Nothing to show. */
    data object Idle : ChangelogState

    /**
     * The app was updated and the user is asked whether they want to read
     * what changed.
     */
    data class Prompt(val range: ChangelogRange) : ChangelogState

    /** The changelog of every version between the old one and the new one. */
    data class Showing(val entries: List<ChangelogEntry>) : ChangelogState
}

/**
 * Keeps track of the version the user was running and offers the changelog of
 * the versions they skipped over when the app was updated.
 *
 * The version of the last run is stored in the shared preferences, which
 * survive an update of the app because the package and the signing key stay
 * the same. That is what makes it possible to show the notes of every version
 * between the one the user was using and the one they are on now, even when
 * more than one release was missed.
 */
class ChangelogManager(
    private val app: Application,
    private val repository: ChangelogRepository = ChangelogRepository(app),
    private val currentVersionCode: Int = BuildConfig.VERSION_CODE
) {

    private val preferences = app.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<ChangelogState>(ChangelogState.Idle)
    val state: StateFlow<ChangelogState> = _state.asStateFlow()

    /**
     * Called when the app comes to the foreground.
     *
     * Compares the version of this build with the version the user ran last
     * and asks for the changelog when the app was updated in the meantime.
     */
    fun onAppStart() {
        // A pending prompt survives a restart of the app, so the question is
        // not lost when the process is killed while the dialog is open.
        val pending = pendingRange()
        if (pending != null) {
            _state.value = ChangelogState.Prompt(pending)
            return
        }

        val lastUsed = lastUsedVersion()
        val updated = ChangelogUpdate.detect(lastUsed, currentVersionCode)
        if (updated == null) {
            // A fresh install, the same version or a downgrade: there is
            // nothing to catch up with.
            rememberVersion(currentVersionCode)
            return
        }

        rememberVersion(currentVersionCode)
        savePendingRange(updated)
        _state.value = ChangelogState.Prompt(updated)
    }

    /** The user said yes: show the notes of every version they skipped. */
    fun showChanges() {
        val pending = pendingRange() ?: return
        val entries = repository.entriesBetween(pending.fromVersion, pending.toVersion)
        clearPendingRange()
        _state.value = if (entries.isEmpty()) {
            ChangelogState.Idle
        } else {
            ChangelogState.Showing(entries)
        }
    }

    /** The user does not want to read the notes right now. */
    fun dismiss() {
        clearPendingRange()
        _state.value = ChangelogState.Idle
    }

    /** The version the app was on the last time it was used. */
    fun lastUsedVersion(): Int? {
        val stored = preferences.getInt(KEY_LAST_VERSION, NO_VERSION)
        return stored.takeIf { it != NO_VERSION }
    }

    private fun rememberVersion(versionCode: Int) {
        preferences.edit { putInt(KEY_LAST_VERSION, versionCode) }
    }

    private fun pendingRange(): ChangelogRange? {
        val from = preferences.getInt(KEY_PENDING_FROM, NO_VERSION)
        val to = preferences.getInt(KEY_PENDING_TO, NO_VERSION)
        if (from == NO_VERSION || to == NO_VERSION || to <= from) return null
        return ChangelogRange(fromVersion = from, toVersion = to)
    }

    private fun savePendingRange(range: ChangelogRange) {
        preferences.edit {
            putInt(KEY_PENDING_FROM, range.fromVersion)
            putInt(KEY_PENDING_TO, range.toVersion)
        }
    }

    private fun clearPendingRange() {
        preferences.edit {
            remove(KEY_PENDING_FROM)
            remove(KEY_PENDING_TO)
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "changelog_state"
        const val KEY_LAST_VERSION = "last_version_code"
        const val KEY_PENDING_FROM = "pending_from_version"
        const val KEY_PENDING_TO = "pending_to_version"
        const val NO_VERSION = -1
    }
}
