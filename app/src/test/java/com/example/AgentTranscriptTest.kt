package com.example

import com.example.model.AgentTranscript
import com.example.model.StoredTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTranscriptTest {

    @Test
    fun `a reply is replayed with its reasoning`() {
        val turn = AgentTranscript.assistantTurn(
            content = "Rust 1.90 shipped in September.",
            reasoning = "The search said September 2025."
        )

        assertTrue(turn.contains("<think>"))
        assertTrue(turn.contains("The search said September 2025."))
        assertTrue(turn.contains("</think>"))
        assertTrue(turn.endsWith("Rust 1.90 shipped in September."))
    }

    @Test
    fun `a reply without reasoning is replayed as it is`() {
        val turn = AgentTranscript.assistantTurn(content = "42", reasoning = null)

        assertEquals("42", turn)
    }

    @Test
    fun `a tool step is replayed as the call the model made`() {
        val turn = AgentTranscript.toolCallTurn(
            toolName = "duckduckgo_search",
            argument = "rust release date",
            reasoning = "I need the release date."
        )

        assertTrue(turn!!.contains("<think>"))
        assertTrue(turn.contains("I need the release date."))
        assertTrue(turn.contains("</think>"))
        assertTrue(turn.contains("<tool_call>"))
        assertTrue(turn.contains("duckduckgo_search: rust release date"))
        assertTrue(turn.contains("</tool_call>"))
    }

    @Test
    fun `a tool step without reasoning is replayed as the bare call`() {
        val turn = AgentTranscript.toolCallTurn(
            toolName = "get_current_datetime",
            argument = "",
            reasoning = null
        )

        assertEquals("<tool_call>\nget_current_datetime: \n</tool_call>", turn)
    }

    @Test
    fun `a step without a tool name cannot be replayed`() {
        assertNull(AgentTranscript.toolCallTurn(toolName = null, argument = "x", reasoning = null))
        assertNull(AgentTranscript.toolCallTurn(toolName = "  ", argument = "x", reasoning = null))
    }

    @Test
    fun `very long reasoning is trimmed before it is replayed`() {
        val turn = AgentTranscript.assistantTurn(
            content = "done",
            reasoning = "x".repeat(5000)
        )

        assertTrue(turn.length < 1200)
        assertTrue(turn.endsWith("done"))
    }

    @Test
    fun `a tool step is replayed as the call and its result`() {
        val turns = AgentTranscript.turnsFor(
            StoredTurn(
                role = "tool",
                content = "Rust 1.90 was released in September 2025.",
                reasoning = "I need the release date.",
                toolName = "duckduckgo_search",
                toolQuery = "rust release date"
            )
        )

        assertEquals(2, turns.size)
        assertEquals("assistant", turns[0].role)
        assertTrue(turns[0].content.contains("duckduckgo_search: rust release date"))
        assertTrue(turns[0].content.contains("I need the release date."))
        assertEquals("tool", turns[1].role)
        assertEquals("Rust 1.90 was released in September 2025.", turns[1].content)
    }

    @Test
    fun `a stored run keeps its order when it is replayed`() {
        val turns = listOf(
            StoredTurn(role = "user", content = "when did rust 1.90 ship?"),
            StoredTurn(
                role = "tool",
                content = "September 18, 2025",
                reasoning = "Let me look it up.",
                toolName = "duckduckgo_search",
                toolQuery = "rust 1.90 release date"
            ),
            StoredTurn(
                role = "assistant",
                content = "Rust 1.90 shipped on September 18, 2025.",
                reasoning = "The search says September 18."
            )
        ).flatMap { AgentTranscript.turnsFor(it) }

        assertEquals(4, turns.size)
        assertEquals(listOf("user", "assistant", "tool", "assistant"), turns.map { it.role })
        // The model is handed the call it made, not just what came back.
        assertTrue(turns[1].content.contains("<tool_call>"))
        assertEquals("September 18, 2025", turns[2].content)
        assertTrue(turns[3].content.contains("The search says September 18."))
    }

    @Test
    fun `an unknown role is not replayed at all`() {
        val turns = AgentTranscript.turnsFor(
            StoredTurn(role = "system", content = "be nice")
        )

        assertTrue(turns.isEmpty())
    }
}
