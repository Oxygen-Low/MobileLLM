package com.example

import com.example.model.ToolCallDetector
import com.example.model.ToolCallParser
import com.example.tools.HtmlSandboxManager
import com.example.tools.MCPToolExecutor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HtmlSandboxTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var sandboxDir: File
    private lateinit var manager: HtmlSandboxManager
    private lateinit var executor: MCPToolExecutor

    @Before
    fun setUp() {
        sandboxDir = tempFolder.newFolder("sandboxes")
        manager = HtmlSandboxManager(sandboxDir)
        executor = MCPToolExecutor(htmlSandboxManager = manager)
    }

    @Test
    fun `ensureSandboxExists creates a 0 byte file`() {
        val sessionId = "session-123"
        val file = manager.ensureSandboxExists(sessionId)

        assertTrue(file.exists())
        assertEquals(0L, file.length())
        assertEquals("", manager.getSandboxContent(sessionId))
        assertFalse(manager.hasSandboxContent(sessionId))
    }

    @Test
    fun `deleteSandbox removes the sandbox file`() {
        val sessionId = "session-456"
        val file = manager.ensureSandboxExists(sessionId)
        assertTrue(file.exists())

        val deleted = manager.deleteSandbox(sessionId)
        assertTrue(deleted)
        assertFalse(file.exists())
    }

    @Test
    fun `editSandbox initializes empty file with empty old_string`() {
        val sessionId = "session-init"
        manager.ensureSandboxExists(sessionId)

        val html = "<!DOCTYPE html><html><body><h1>Hello Mobile</h1></body></html>"
        val (success, msg) = manager.editSandbox(sessionId, oldString = "", newString = html)

        assertTrue(success)
        assertTrue(msg.contains("Successfully initialized"))
        assertEquals(html, manager.getSandboxContent(sessionId))
        assertTrue(manager.hasSandboxContent(sessionId))
    }

    @Test
    fun `editSandbox fails on empty file when old_string is not empty`() {
        val sessionId = "session-fail-init"
        manager.ensureSandboxExists(sessionId)

        val (success, msg) = manager.editSandbox(sessionId, oldString = "non-empty", newString = "<html></html>")

        assertFalse(success)
        assertTrue(msg.contains("currently empty"))
    }

    @Test
    fun `editSandbox replaces exact unique string in non-empty file`() {
        val sessionId = "session-replace"
        manager.ensureSandboxExists(sessionId)
        val initialHtml = """
            <!DOCTYPE html>
            <html>
            <head><title>App</title></head>
            <body>
              <button id="btn">Click Me</button>
            </body>
            </html>
        """.trimIndent()
        manager.editSandbox(sessionId, "", initialHtml)

        val target = """<button id="btn">Click Me</button>"""
        val replacement = """<button id="btn" style="background: red;">Tap Me</button>"""

        val (success, msg) = manager.editSandbox(sessionId, target, replacement)

        assertTrue(success)
        assertTrue(msg.contains("Successfully updated"))
        val updated = manager.getSandboxContent(sessionId)
        assertTrue(updated.contains("""<button id="btn" style="background: red;">Tap Me</button>"""))
        assertFalse(updated.contains("""<button id="btn">Click Me</button>"""))
    }

    @Test
    fun `editSandbox fails when old_string is empty on non-empty file`() {
        val sessionId = "session-empty-target"
        manager.ensureSandboxExists(sessionId)
        manager.editSandbox(sessionId, "", "<div>Test</div>")

        val (success, msg) = manager.editSandbox(sessionId, "", "<div>New</div>")
        assertFalse(success)
        assertTrue(msg.contains("already contains code"))
    }

    @Test
    fun `editSandbox fails when old_string is not found`() {
        val sessionId = "session-not-found"
        manager.ensureSandboxExists(sessionId)
        manager.editSandbox(sessionId, "", "<div>Test</div>")

        val (success, msg) = manager.editSandbox(sessionId, "<span>NotFound</span>", "<span>Found</span>")
        assertFalse(success)
        assertTrue(msg.contains("not found"))
    }

    @Test
    fun `editSandbox fails when old_string matches multiple times`() {
        val sessionId = "session-multi"
        manager.ensureSandboxExists(sessionId)
        val html = "<div>Item</div><div>Item</div>"
        manager.editSandbox(sessionId, "", html)

        val (success, msg) = manager.editSandbox(sessionId, "<div>Item</div>", "<div>Updated</div>")
        assertFalse(success)
        assertTrue(msg.contains("found 2 times"))
    }

    @Test
    fun `readSandbox on empty file informs caller`() {
        val sessionId = "session-read-empty"
        manager.ensureSandboxExists(sessionId)

        val (success, msg) = manager.readSandbox(sessionId, startLine = 1, limit = 50)
        assertTrue(success)
        assertTrue(msg.contains("empty (0 bytes)"))
    }

    @Test
    fun `readSandbox formats output with line numbers and limits`() {
        val sessionId = "session-read-lines"
        manager.ensureSandboxExists(sessionId)
        val content = (1..10).joinToString("\n") { "line $it" }
        manager.editSandbox(sessionId, "", content)

        val (success, msg) = manager.readSandbox(sessionId, startLine = 3, limit = 4)
        assertTrue(success)
        assertTrue(msg.contains("Showing lines 3-6 of 10"))
        assertTrue(msg.contains("   3 | line 3"))
        assertTrue(msg.contains("   6 | line 6"))
        assertFalse(msg.contains("line 2"))
        assertFalse(msg.contains("line 7"))
    }

    @Test
    fun `readSandbox rejects out of bounds start line`() {
        val sessionId = "session-read-oob"
        manager.ensureSandboxExists(sessionId)
        manager.editSandbox(sessionId, "", "line 1\nline 2")

        val (success, msg) = manager.readSandbox(sessionId, startLine = 10, limit = 10)
        assertFalse(success)
        assertTrue(msg.contains("beyond the end of file"))
    }

    @Test
    fun `MCPToolExecutor executes edit_html_sandbox with JSON argument`() = runBlocking {
        val sessionId = "session-exec"
        executor.activeSessionId = sessionId
        manager.ensureSandboxExists(sessionId)

        val jsonArg = """{"old_string": "", "new_string": "<h1>Mobile Canvas</h1>"}"""
        val response = executor.executeTool("edit_html_sandbox", jsonArg)

        assertTrue(response.success)
        assertEquals("edit_html_sandbox", response.toolName)
        assertEquals("<h1>Mobile Canvas</h1>", manager.getSandboxContent(sessionId))
    }

    @Test
    fun `MCPToolExecutor executes read_html_sandbox with JSON argument`() = runBlocking {
        val sessionId = "session-exec-read"
        executor.activeSessionId = sessionId
        manager.ensureSandboxExists(sessionId)
        manager.editSandbox(sessionId, "", "<div>Hello</div>\n<div>World</div>")

        val jsonArg = """{"start_line": 1, "limit": 2}"""
        val response = executor.executeTool("read_html_sandbox", jsonArg)

        assertTrue(response.success)
        assertEquals("read_html_sandbox", response.toolName)
        assertTrue(response.result.contains("   1 | <div>Hello</div>"))
        assertTrue(response.result.contains("   2 | <div>World</div>"))
    }

    @Test
    fun `ToolCallParser parses edit_html_sandbox with multi-line JSON payload`() {
        val knownTools = setOf("edit_html_sandbox", "read_html_sandbox", "duckduckgo_search")
        val callText = """
            edit_html_sandbox:
            {
              "old_string": "",
              "new_string": "<!DOCTYPE html>\n<html>\n<body>Mobile App</body>\n</html>"
            }
        """.trimIndent()

        val parsed = ToolCallParser.parse(callText, knownTools)
        assertEquals("edit_html_sandbox", parsed?.toolName)
        assertTrue(parsed?.argument?.contains("\"old_string\"") == true)
        assertTrue(parsed?.argument?.contains("Mobile App") == true)
    }

    @Test
    fun `ToolCallParser parses read_html_sandbox with JSON object envelope`() {
        val knownTools = setOf("edit_html_sandbox", "read_html_sandbox")
        val callText = """
            {"name": "read_html_sandbox", "arguments": {"start_line": 5, "limit": 20}}
        """.trimIndent()

        val parsed = ToolCallParser.parse(callText, knownTools)
        assertEquals("read_html_sandbox", parsed?.toolName)
        assertTrue(parsed?.argument?.contains("\"start_line\":5") == true || parsed?.argument?.contains("\"start_line\": 5") == true)
    }
}
