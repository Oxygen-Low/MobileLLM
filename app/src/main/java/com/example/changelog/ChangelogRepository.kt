package com.example.changelog

import android.content.Context
import java.io.IOException

/**
 * Reads the changelog files that are bundled with the app.
 *
 * Every file in the `changelogs/` folder of the repository is packaged as an
 * asset, so the notes are available offline and do not need a network call.
 */
class ChangelogRepository(private val context: Context) {

    /** All versions that ship a changelog file, oldest first. */
    fun availableVersions(): List<Int> =
        context.assets.list("").orEmpty()
            .mapNotNull { ChangelogVersion.versionFromFileName(it) }
            .distinct()
            .sorted()

    /** The changelog of a single version, or null when there is none. */
    fun read(versionCode: Int): ChangelogEntry? {
        val body = readAsset(ChangelogVersion.fileNameFor(versionCode)) ?: return null
        return ChangelogEntry(
            versionCode = versionCode,
            versionName = ChangelogVersion.label(versionCode),
            body = body
        )
    }

    /**
     * The changelogs of every version between the two versions.
     *
     * The lower bound is exclusive: the changelog of the version the user was
     * using already describes what changed before it. The upper bound is
     * inclusive, so the version the user just updated to is included.
     */
    fun entriesBetween(fromExclusive: Int, toInclusive: Int): List<ChangelogEntry> =
        availableVersions()
            .filter { it > fromExclusive && it <= toInclusive }
            .mapNotNull { read(it) }

    private fun readAsset(fileName: String): String? = try {
        context.assets.open(fileName).use { input ->
            input.bufferedReader().readText()
        }
    } catch (e: IOException) {
        null
    }
}
