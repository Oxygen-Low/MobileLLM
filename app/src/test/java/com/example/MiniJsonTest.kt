package com.example

import com.example.tools.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniJsonTest {

    @Test
    fun `reads an escaped page`() {
        val json = """{"old_string": "", "new_string": "<h1 class=\"title\">Hi</h1>"}"""

        assertEquals("<h1 class=\"title\">Hi</h1>", MiniJson.getString(json, "new_string"))
    }

    @Test
    fun `keeps the unescaped quotes of a page`() {
        // Small models forget to escape the quotes of attributes, which used
        // to end the value at the first of them and leave a broken page.
        val page = """<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width"></head><body><h1>Hi</h1></body></html>"""
        val json = "{\"old_string\": \"\", \"new_string\": \"$page\"}"

        assertEquals(page, MiniJson.getString(json, "new_string"))
    }

    @Test
    fun `reads a value that contains a raw newline`() {
        val json = "{\"new_string\": \"<html>\n<body>\n<div>one</div>\n</body>\n</html>\"}"

        assertEquals("<html>\n<body>\n<div>one</div>\n</body>\n</html>", MiniJson.getString(json, "new_string"))
    }

    @Test
    fun `keeps the backslash of an escape it does not know`() {
        // A backslash in a regular expression of the page is not a JSON
        // escape, so it has to survive the trip into the sandbox.
        val json = "{\"new_string\": \"if (/\\d+/.test(x)) {}\"}"

        assertTrue(MiniJson.getString(json, "new_string").contains("\\d+"))
    }

    @Test
    fun `reads an empty value`() {
        val json = """{"old_string": "", "new_string": "<div></div>"}"""

        assertEquals("", MiniJson.getString(json, "old_string"))
        assertEquals("<div></div>", MiniJson.getString(json, "new_string"))
    }

    @Test
    fun `falls back to the alternative key`() {
        val json = """{"replacement": "<div></div>"}"""

        assertEquals("<div></div>", MiniJson.getString(json, "new_string", "replacement"))
    }

    @Test
    fun `returns an empty string when the key is missing`() {
        val json = """{"start_line": 3}"""

        assertEquals("", MiniJson.getString(json, "new_string"))
    }
}
