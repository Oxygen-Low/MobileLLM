package com.example

import com.example.image.GeneratedImageReferences
import org.junit.Assert.assertEquals
import org.junit.Test

class GeneratedImageReferencesTest {

    @Test
    fun `the references of a reply are found in order and without duplicates`() {
        val text = "Here: ![a fox](gen:abc123) and again gen:abc123, plus <img src=\"gen:def456\">"

        assertEquals(listOf("abc123", "def456"), GeneratedImageReferences.referencesIn(text))
    }

    @Test
    fun `text without a reference yields nothing`() {
        assertEquals(emptyList<String>(), GeneratedImageReferences.referencesIn("just a sentence"))
    }

    @Test
    fun `inlining replaces only the known references`() {
        val html = "<img src=\"gen:known\"><img src=\"gen:missing\">"

        val result = GeneratedImageReferences.inline(html) { id ->
            if (id == "known") "data:image/png;base64,AAAA" else null
        }

        assertEquals(
            "<img src=\"data:image/png;base64,AAAA\"><img src=\"gen:missing\">",
            result
        )
    }

    @Test
    fun `a reference with an unsafe character is not matched`() {
        assertEquals(emptyList<String>(), GeneratedImageReferences.referencesIn("gen:../../etc"))
    }
}
