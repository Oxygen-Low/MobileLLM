package com.example

import com.example.tools.HtmlDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlDocumentTest {

    private val page = "<!DOCTYPE html>\n<html>\n<body><h1>Hi</h1></body>\n</html>"

    @Test
    fun `leaves a normal page alone`() {
        assertEquals(page, HtmlDocument.normalize(page))
    }

    @Test
    fun `removes a fence with a language tag`() {
        val fenced = "```html\n$page\n```"

        assertEquals(page, HtmlDocument.normalize(fenced))
    }

    @Test
    fun `removes a fence without a language tag`() {
        val fenced = "```\n$page\n```"

        assertEquals(page, HtmlDocument.normalize(fenced))
    }

    @Test
    fun `removes a fence that is longer than three backticks`() {
        val fenced = "````html\n$page\n````"

        assertEquals(page, HtmlDocument.normalize(fenced))
    }

    @Test
    fun `keeps an unclosed fence`() {
        val fenced = "```html\n$page"

        assertEquals(fenced, HtmlDocument.normalize(fenced))
    }

    @Test
    fun `keeps a fence that is followed by other text`() {
        val text = "```html\n$page\n```\nDone."

        assertEquals(text, HtmlDocument.normalize(text))
    }

    @Test
    fun `decodes a page whose tags were all escaped`() {
        val escaped = "&lt;!DOCTYPE html&gt;\n&lt;html&gt;\n&lt;body&gt;&lt;h1&gt;Hi&lt;/h1&gt;&lt;/body&gt;\n&lt;/html&gt;"

        assertEquals(page, HtmlDocument.normalize(escaped))
    }

    @Test
    fun `keeps the escaped examples of a page that teaches html`() {
        val teaching = "<!DOCTYPE html>\n<html>\n<body><p>&lt;div&gt; is a box</p></body>\n</html>"

        assertEquals(teaching, HtmlDocument.normalize(teaching))
    }

    @Test
    fun `removes a byte order mark and the surrounding whitespace`() {
        assertEquals(page, HtmlDocument.normalize("\uFEFF\n$page\n\n"))
    }

    @Test
    fun `a fence around an escaped page is removed as well`() {
        val fenced = "```html\n&lt;!DOCTYPE html&gt;\n&lt;html&gt;\n&lt;body&gt;&lt;h1&gt;Hi&lt;/h1&gt;&lt;/body&gt;\n&lt;/html&gt;\n```"

        assertEquals(page, HtmlDocument.normalize(fenced))
    }

    @Test
    fun `a blank document stays blank`() {
        assertTrue(HtmlDocument.normalize("  \n\t ").isEmpty())
    }
}
