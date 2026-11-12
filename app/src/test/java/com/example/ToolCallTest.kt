package com.example

import com.example.model.PromptFormat
import com.example.model.ToolCallDetector
import com.example.model.ToolCallParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallDetectorTest {

    private val knownTools = setOf(
        "duckduckgo_search",
        "get_current_datetime",
        "calculate_expression"
    )

    private fun detector() = ToolCallDetector(knownTools)

    @Test
    fun `tool call is withheld from the displayed text`() {
        val detector = detector()

        val shown = detector.feed(
            "Let me look that up.\n<tool_call>\nduckduckgo_search: rust release date\n</tool_call>"
        )

        assertEquals("Let me look that up.\n", shown)
        assertTrue(detector.isComplete)
        assertEquals("duckduckgo_search", detector.toolCall()?.toolName)
        assertEquals("rust release date", detector.toolCall()?.argument)
    }

    @Test
    fun `tool call split across chunks is still detected`() {
        val detector = detector()

        assertEquals("Think", detector.feed("Think"))
        assertEquals("ing", detector.feed("ing<tool_"))
        assertEquals("", detector.feed("call>\ncalculate_express"))
        assertEquals("", detector.feed("ion: 128 * 1024\n</tool_"))
        detector.feed("call>")

        assertTrue(detector.isComplete)
        assertEquals("calculate_expression", detector.toolCall()?.toolName)
        assertEquals("128 * 1024", detector.toolCall()?.argument)
    }

    @Test
    fun `text around a tool call stays visible`() {
        val detector = detector()

        val first = detector.feed("before <tool_call>\nget_current_datetime:\n</tool_call>")
        val second = detector.feed("after")

        assertEquals("before ", first)
        assertEquals("after", second)
        assertEquals("get_current_datetime", detector.toolCall()?.toolName)
        assertEquals("", detector.toolCall()?.argument)
    }

    @Test
    fun `the labelled form the model emits is detected`() {
        val detector = detector()

        detector.feed(
            "<tool_call>\ntool_name: duckduckgo_search\n_argument: latest news\n</tool_call>"
        )

        assertTrue(detector.isComplete)
        assertEquals("duckduckgo_search", detector.toolCall()?.toolName)
        assertEquals("latest news", detector.toolCall()?.argument)
    }

    @Test
    fun `an unknown tool name is not treated as a call`() {
        val detector = detector()

        val shown = detector.feed("The format is <tool_call>\ntool_name: argument\n</tool_call>")

        assertFalse(detector.isComplete)
        assertNull(detector.toolCall())
        assertTrue(shown.contains("<tool_call>"))
        assertTrue(shown.contains("</tool_call>"))
    }

    @Test
    fun `an unfinished call is released on flush`() {
        val detector = detector()

        detector.feed("<tool_call>\nduckduckgo_search: partial")

        assertFalse(detector.isComplete)
        assertTrue(detector.flush().contains("duckduckgo_search"))
    }

    @Test
    fun `a think close inside the call does not break the argument`() {
        val detector = detector()

        detector.feed("<tool_call>\nduckduckgo_search: some query</think>\n</tool_call>")

        assertTrue(detector.isComplete)
        assertEquals("duckduckgo_search", detector.toolCall()?.toolName)
        assertEquals("some query", detector.toolCall()?.argument)
    }

    @Test
    fun `a mis-cased tool name resolves to the canonical one`() {
        val detector = detector()

        detector.feed("<tool_call>\nDuckDuckGo_Search: rust\n</tool_call>")

        assertTrue(detector.isComplete)
        assertEquals("duckduckgo_search", detector.toolCall()?.toolName)
    }
}

class ToolCallParserTest {

    private val knownTools = setOf(
        "duckduckgo_search",
        "get_current_datetime",
        "calculate_expression"
    )

    @Test
    fun `name and argument are parsed from the simple form`() {
        val call = ToolCallParser.parse("duckduckgo_search: who won the world cup", knownTools)

        assertEquals("duckduckgo_search", call?.toolName)
        assertEquals("who won the world cup", call?.argument)
    }

    @Test
    fun `a labelled name and argument are parsed`() {
        val call = ToolCallParser.parse(
            "tool_name: duckduckgo_search\n_argument: latest news",
            knownTools
        )

        assertEquals("duckduckgo_search", call?.toolName)
        assertEquals("latest news", call?.argument)
    }

    @Test
    fun `a labelled name with a name only tool has no argument`() {
        val call = ToolCallParser.parse("tool_name: get_current_datetime", knownTools)

        assertEquals("get_current_datetime", call?.toolName)
        assertEquals("", call?.argument)
    }

    @Test
    fun `a bare tool name is parsed without an argument`() {
        val call = ToolCallParser.parse("get_current_datetime", knownTools)

        assertEquals("get_current_datetime", call?.toolName)
        assertEquals("", call?.argument)
    }

    @Test
    fun `a bare tool name followed by the argument is parsed`() {
        val call = ToolCallParser.parse("duckduckgo_search\nlatest news", knownTools)

        assertEquals("duckduckgo_search", call?.toolName)
        assertEquals("latest news", call?.argument)
    }

    @Test
    fun `quoted arguments are unwrapped`() {
        val call = ToolCallParser.parse(
            "duckduckgo_search: \"rust 1.90 release date\"",
            knownTools
        )

        assertEquals("rust 1.90 release date", call?.argument)
    }

    @Test
    fun `a json body is parsed as well`() {
        val call = ToolCallParser.parse(
            "{\"name\": \"duckduckgo_search\", \"arguments\": {\"query\": \"kotlin 2.0\"}}",
            knownTools
        )

        assertEquals("duckduckgo_search", call?.toolName)
        assertEquals("kotlin 2.0", call?.argument)
    }

    @Test
    fun `an unknown tool name is rejected`() {
        assertNull(ToolCallParser.parse("tool_name: unknown_tool\n_argument: x", knownTools))
        assertNull(ToolCallParser.parse("prose about tools", knownTools))
        assertNull(ToolCallParser.parse("   ", knownTools))
    }
}

class ToolTurnPromptTest {

    @Test
    fun `tool results are rendered as their own turn`() {
        val prompt = PromptFormat.smolLM3(
            system = "Be nice",
            turns = listOf(
                PromptFormat.Turn("user", "Hi"),
                PromptFormat.Turn(
                    "assistant",
                    "<tool_call>\nduckduckgo_search: rust\n</tool_call>"
                ),
                PromptFormat.Turn("tool", "Rust 1.90 was released on 2025-09-18.")
            ),
            enableThinking = true,
            today = "11 November 2026"
        )

        assertTrue(prompt.contains("<|im_start|>assistant\n<tool_call>"))
        assertTrue(prompt.contains("<|im_start|>tool\nRust 1.90 was released"))
        assertTrue(prompt.contains("<|im_end|>\n<|im_start|>assistant\n<think>\n"))
    }

    @Test
    fun `tool results are rendered for qwen3 as well`() {
        val prompt = PromptFormat.qwen3(
            system = "Be helpful",
            turns = listOf(
                PromptFormat.Turn("user", "Hi"),
                PromptFormat.Turn("tool", "42")
            ),
            enableThinking = false
        )

        assertTrue(prompt.contains("<|im_start|>tool\n42<|im_end|>\n"))
    }
}
