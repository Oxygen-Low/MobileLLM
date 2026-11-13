package com.example

import com.example.changelog.ChangelogVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Checks the changelog files of the repository against the rules in
 * AGENTS.md.
 *
 * The files are the release notes of the GitHub release and the changelog the
 * app shows after an update, so their name and their heading have to agree
 * with the version they belong to.
 */
class ChangelogFilesTest {

    private val changelogFolder: File = findChangelogFolder()

    @Test
    fun `the changelog folder exists`() {
        assertTrue("changelogs/ is missing", changelogFolder.isDirectory)
    }

    @Test
    fun `every changelog file is named after its version`() {
        val files = changelogFolder.listFiles()?.filter { it.isFile }.orEmpty()
        assertTrue("there are no changelog files", files.isNotEmpty())

        files.forEach { file ->
            val version = ChangelogVersion.versionFromFileName(file.name)
            assertTrue("${file.name} is not named V<n>.md", version != null)
        }
    }

    @Test
    fun `every changelog file starts with the heading of its version`() {
        changelogFolder.listFiles()?.filter { it.isFile }.orEmpty().forEach { file ->
            val version = ChangelogVersion.versionFromFileName(file.name) ?: return@forEach
            val firstLine = file.readLines().firstOrNull()?.trim().orEmpty()

            assertEquals(
                "${file.name} has to start with '# ${ChangelogVersion.label(version)}'",
                "# ${ChangelogVersion.label(version)}",
                firstLine
            )
        }
    }

    @Test
    fun `the versions of the changelog files do not repeat`() {
        val versions = changelogFolder.listFiles()
            ?.filter { it.isFile }
            ?.mapNotNull { ChangelogVersion.versionFromFileName(it.name) }
            .orEmpty()

        assertEquals(versions.size, versions.toSet().size)
    }

    /** Walks up from the working directory until the changelog folder shows up. */
    private fun findChangelogFolder(): File {
        var directory: File? = File(".").absoluteFile
        while (directory != null) {
            val candidate = File(directory, "changelogs")
            if (candidate.isDirectory) return candidate
            directory = directory.parentFile
        }
        return File("changelogs")
    }
}
