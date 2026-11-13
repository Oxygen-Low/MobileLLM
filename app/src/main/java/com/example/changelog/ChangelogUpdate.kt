package com.example.changelog

/**
 * The span of versions between the version the user was using and the version
 * they are on now.
 */
data class ChangelogRange(
    /** The version the user was using. Its own changelog is not shown again. */
    val fromVersion: Int,

    /** The version the app was updated to. */
    val toVersion: Int
) {
    /** The versions whose changelog has to be shown, oldest first. */
    val versions: IntRange
        get() = (fromVersion + 1)..toVersion
}

/**
 * Decides when the app has to ask the user whether they want to read the
 * changelog.
 *
 * The logic is kept apart from the storage so it can be tested without an
 * Android device.
 */
object ChangelogUpdate {

    /**
     * The range of versions to show the changelog of, or null when there is
     * nothing to show.
     *
     * Nothing is shown for a fresh install (there is no version the user came
     * from), for the same version (the app was not updated) or for a downgrade
     * (the user moved back, so there is no new work to describe).
     */
    fun detect(lastUsedVersion: Int?, currentVersion: Int): ChangelogRange? {
        val lastUsed = lastUsedVersion ?: return null
        if (currentVersion <= lastUsed) return null
        return ChangelogRange(fromVersion = lastUsed, toVersion = currentVersion)
    }
}
