package com.example

import com.example.model.SpeechTextNormalizer
import org.junit.Assert.*
import org.junit.Test

/**
 * A reply that is read aloud has to sound like a sentence, not like markdown.
 */
class SpeechTextNormalizerTest {

    @Test
    fun `plain text stays the same`() {
        assertEquals(
            "Hello there.",
            SpeechTextNormalizer.clean("Hello there.")
        )
    }

    @Test
    fun `code blocks are replaced by a short notice`() {
        val spoken = SpeechTextNormalizer.clean("Look:\n```kotlin\nval x = 1\n```\nDone.")

        assertTrue(spoken.contains("Code block"))
        assertTrue(!spoken.contains("val x = 1"))
        assertTrue(spoken.endsWith("Done."))
    }

    @Test
    fun `inline code keeps its content`() {
        assertEquals(
            "Call print now.",
            SpeechTextNormalizer.clean("Call `print` now.")
        )
    }

    @Test
    fun `links keep their label and images disappear`() {
        assertEquals(
            "See the docs and the",
            SpeechTextNormalizer.clean("See [the docs](https://example.com) and the ![logo](logo.png)")
        )
    }

    @Test
    fun `emphasis and headings lose their markers`() {
        assertEquals(
            "A bold claim",
            SpeechTextNormalizer.clean("**A bold** claim")
        )
        assertEquals(
            "A heading",
            SpeechTextNormalizer.clean("## A heading")
        )
    }

    @Test
    fun `long replies are cut off`() {
        val spoken = SpeechTextNormalizer.clean("a".repeat(5_000), maxChars = 100)

        assertEquals(100, spoken.length)
    }
}