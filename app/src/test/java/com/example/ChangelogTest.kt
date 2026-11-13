package com.example

import com.example.changelog.ChangelogEntry
import com.example.changelog.ChangelogRange
import com.example.changelog.ChangelogRepository
import com.example.changelog.ChangelogUpdate
import com.example.changelog.ChangelogVersion
import com.example.changelog.Markdown
import com.example.changelog.MarkdownBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangelogTest {

    // ---------------------------------------------------------------------
    // Version helpers
    // ---------------------------------------------------------------------

    @Test
    fun `a changelog file name holds the version code`() {
        assertEquals(12, ChangelogVersion.versionFromFileName("V12.md"))
        assertEquals(1, ChangelogVersion.versionFromFileName("v1.md"))
        assertEquals("V12.md", ChangelogVersion.fileNameFor(12))
        assertEquals("V12", ChangelogVersion.label(12))
    }

    @Test
    fun `files that are not a changelog are ignored`() {
        assertNull(ChangelogVersion.versionFromFileName("README.md"))
        assertNull(ChangelogVersion.versionFromFileName("V12.txt"))
        assertNull(ChangelogVersion.versionFromFileName("V.md"))
        assertNull(ChangelogVersion.versionFromFileName("V12.md.bak"))
    }

    // ---------------------------------------------------------------------
    // Detecting an update
    // ---------------------------------------------------------------------

    @Test
    fun `an update shows the changelog of the new version`() {
        val range = ChangelogUpdate.detect(lastUsedVersion = 11, currentVersion = 12)

        assertEquals(ChangelogRange(11, 12), range)
        assertEquals(listOf(12), range?.versions?.toList())
    }

    @Test
    fun `skipping releases shows every version in between`() {
        val range = ChangelogUpdate.detect(lastUsedVersion = 9, currentVersion = 12)

        assertEquals(listOf(10, 11, 12), range?.versions?.toList())
    }

    @Test
    fun `a fresh install has nothing to catch up with`() {
        assertNull(ChangelogUpdate.detect(lastUsedVersion = null, currentVersion = 12))
    }

    @Test
    fun `the same version is not an update`() {
        assertNull(ChangelogUpdate.detect(lastUsedVersion = 12, currentVersion = 12))
    }

    @Test
    fun `a downgrade is not an update`() {
        assertNull(ChangelogUpdate.detect(lastUsedVersion = 12, currentVersion = 11))
    }

    // ---------------------------------------------------------------------
    // Markdown
    // ---------------------------------------------------------------------

    @Test
    fun `a changelog is split into headings and bullets`() {
        val blocks = Markdown.parse(
            """
            # V12

            - Added a changelog system.
            - The release notes come from the changelog file.

            ## Fixed

            - The update check no longer reports an error.
            """.trimIndent()
        )

        assertEquals(
            listOf(
                MarkdownBlock.Heading(1, "V12"),
                MarkdownBlock.Bullet("Added a changelog system."),
                MarkdownBlock.Bullet("The release notes come from the changelog file."),
                MarkdownBlock.Heading(2, "Fixed"),
                MarkdownBlock.Bullet("The update check no longer reports an error.")
            ),
            blocks
        )
    }

    @Test
    fun `lines that follow each other become one paragraph`() {
        val blocks = Markdown.parse(
            """
            The app asks whether you want to read
            the changelog after an update.
            """.trimIndent()
        )

        assertEquals(
            listOf(MarkdownBlock.Paragraph("The app asks whether you want to read the changelog after an update.")),
            blocks
        )
    }

    @Test
    fun `empty lines do not add empty paragraphs`() {
        val blocks = Markdown.parse("\n\n- One\n\n\n- Two\n")

        assertEquals(
            listOf(MarkdownBlock.Bullet("One"), MarkdownBlock.Bullet("Two")),
            blocks
        )
    }

    // ---------------------------------------------------------------------
    // Entries
    // ---------------------------------------------------------------------

    @Test
    fun `the title of an entry is the heading of its file`() {
        val entry = ChangelogEntry(
            versionCode = 12,
            versionName = "V12",
            body = "# V12\n\n- Added a changelog system."
        )

        assertEquals("V12", entry.title)
    }

    @Test
    fun `an entry without a heading falls back to the version name`() {
        val entry = ChangelogEntry(
            versionCode = 12,
            versionName = "V12",
            body = "- Added a changelog system."
        )

        assertEquals("V12", entry.title)
        assertTrue(entry.body.contains("Added a changelog system."))
    }

    // ---------------------------------------------------------------------
    // Versions without a changelog file
    // ---------------------------------------------------------------------

    @Test
    fun `a version without a changelog file says that there is nothing to read`() {
        val entry = ChangelogEntry.missing(13)

        assertEquals(13, entry.versionCode)
        assertEquals("V13", entry.versionName)
        assertEquals("No changelogs for this version.", entry.body)
        assertFalse(entry.hasNotes)
    }

    @Test
    fun `a version without a changelog file does not break the other versions`() {
        val repository = ChangelogRepository { fileName ->
            when (fileName) {
                "V12.md" -> "# V12\n\n- Added a changelog system."
                else -> null
            }
        }

        val entries = repository.entriesBetween(11, 13)

        assertEquals(2, entries.size)
        assertTrue(entries[0].hasNotes)
        assertEquals("V12", entries[0].versionName)
        assertFalse(entries[1].hasNotes)
        assertEquals("No changelogs for this version.", entries[1].body)
    }

    @Test
    fun `a version without a changelog file is still listed`() {
        val repository = ChangelogRepository { null }

        val entries = repository.entriesBetween(11, 13)

        assertEquals(listOf(12, 13), entries.map { it.versionCode })
    }
}
