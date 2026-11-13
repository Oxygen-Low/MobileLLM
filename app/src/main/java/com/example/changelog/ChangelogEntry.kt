package com.example.changelog

/**
 * One version of the changelog.
 *
 * The text is the raw markdown file that ships with the app, so the same
 * content is shown in the app, on the GitHub release page and in the
 * repository.
 */
data class ChangelogEntry(
    val versionCode: Int,
    val versionName: String,
    val body: String
) {
    /**
     * The heading of the file, or the version name when the file has no
     * heading.
     */
    val title: String
        get() = body.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("#") }
            ?.trimStart('#')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: versionName
}

/**
 * Naming rules for the changelog files.
 *
 * A changelog lives in `changelogs/V<version code>.md`, the version code is
 * the same number the release workflow puts into the git tag and into
 * `BuildConfig.VERSION_CODE`.
 */
object ChangelogVersion {

    private val FILE_PATTERN = Regex("^V(\\d+)\\.md$", RegexOption.IGNORE_CASE)

    /** The version code of a changelog file name, or null when it is not one. */
    fun versionFromFileName(fileName: String): Int? {
        val match = FILE_PATTERN.matchEntire(fileName.trim()) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    /** The file name that holds the changelog of a version. */
    fun fileNameFor(versionCode: Int): String = "V$versionCode.md"

    /** How a version is written in the user interface. */
    fun label(versionCode: Int): String = "V$versionCode"
}
