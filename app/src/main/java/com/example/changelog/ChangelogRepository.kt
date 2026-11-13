package com.example.changelog

import android.content.Context
import java.io.IOException

/**
 * Reads the changelog files that are bundled with the app.
 *
 * Every file in the `changelogs/` folder of the repository is packaged as an
 * asset, so the notes are available offline and do not need a network call.
 */
class ChangelogRepository(private val readFile: (String) -> String?) {

    /** Reads the files from the assets of the app. */
    constructor(context: Context) : this(assetReader(context))

    /** The changelog of a single version, or null when there is none. */
    fun read(versionCode: Int): ChangelogEntry? {
        val body = readFile(ChangelogVersion.fileNameFor(versionCode)) ?: return null
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
     *
     * A version that was released without a changelog file gets an entry that
     * says there is nothing to read, so a release without notes does not
     * break the changelog of the other versions.
     */
    fun entriesBetween(fromExclusive: Int, toInclusive: Int): List<ChangelogEntry> =
        (fromExclusive + 1..toInclusive).map { version ->
            read(version) ?: ChangelogEntry.missing(version)
        }

    private companion object {
        /** Reads a file from the assets of the app. */
        fun assetReader(context: Context): (String) -> String? = { fileName ->
            try {
                context.assets.open(fileName).use { input ->
                    input.bufferedReader().readText()
                }
            } catch (e: IOException) {
                null
            }
        }
    }
}
