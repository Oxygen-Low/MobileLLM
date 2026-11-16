package com.example

import com.example.video.GeneratedVideoReferences
import org.junit.Assert.assertEquals
import org.junit.Test

class GeneratedVideoReferencesTest {

    @Test
    fun `references are found in a reply`() {
        val text = "Here is the clip [a fox](vid:abc123) and [more](vid:def456)."
        assertEquals(listOf("abc123", "def456"), GeneratedVideoReferences.referencesIn(text))
    }

    @Test
    fun `a reply without a reference has none`() {
        assertEquals(emptyList<String>(), GeneratedVideoReferences.referencesIn("just a sentence"))
    }

    @Test
    fun `inline replaces the references that resolve`() {
        val html = """<video src="vid:abc123" controls></video><video src="vid:missing" controls></video>"""

        val result = GeneratedVideoReferences.inline(html) { id ->
            if (id == "abc123") "data:video/mp4;base64,AAAA" else null
        }

        assertEquals(
            """<video src="data:video/mp4;base64,AAAA" controls></video><video src="vid:missing" controls></video>""",
            result
        )
    }

    @Test
    fun `a forged path is not a reference`() {
        assertEquals(emptyList<String>(), GeneratedVideoReferences.referencesIn("vid:../../etc"))
    }
}