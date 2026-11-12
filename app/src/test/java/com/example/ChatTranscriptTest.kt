package com.example

import com.example.data.ChatMessageEntity
import com.example.data.ChatTranscript
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTranscriptTest {

    private fun message(role: String, id: String) = ChatMessageEntity(
        id = id,
        sessionId = "session",
        role = role,
        content = id
    )

    private fun transcript(vararg roles: String): List<ChatMessageEntity> =
        roles.mapIndexed { index, role -> message(role, "m$index") }

    @Test
    fun `last user turn is the one nearest the end, not the first in the session`() {
        val messages = transcript("user", "assistant", "user", "assistant")

        // The reply at index 3 was produced by the turn at index 2.
        assertEquals(2, ChatTranscript.lastUserIndexAtOrBefore(messages, 3))
    }

    @Test
    fun `last user turn stays correct in a long transcript`() {
        val messages = transcript("user", "assistant", "user", "assistant", "user", "assistant")

        assertEquals(4, ChatTranscript.lastUserIndexAtOrBefore(messages, 5))
    }

    @Test
    fun `single exchange resolves to its own prompt`() {
        val messages = transcript("user", "assistant")

        assertEquals(0, ChatTranscript.lastUserIndexAtOrBefore(messages, 1))
    }

    @Test
    fun `there is no user turn to replay when the transcript starts with a reply`() {
        val messages = transcript("assistant")

        assertEquals(-1, ChatTranscript.lastUserIndexAtOrBefore(messages, 0))
    }

    @Test
    fun `rewind drops every message after the target`() {
        val messages = transcript("user", "assistant", "user", "assistant")

        val index = ChatTranscript.rewindAnchorIndex(messages, "m1")

        assertEquals(1, index)
        assertEquals(listOf("m2", "m3"), ChatTranscript.idsAfter(messages, index))
    }

    @Test
    fun `rewinding to the last message changes nothing`() {
        val messages = transcript("user", "assistant", "user", "assistant")

        assertEquals(-1, ChatTranscript.rewindAnchorIndex(messages, "m3"))
    }

    @Test
    fun `rewinding to an unknown message changes nothing`() {
        val messages = transcript("user", "assistant")

        assertEquals(-1, ChatTranscript.rewindAnchorIndex(messages, "nope"))
    }

    @Test
    fun `regenerate replays the prompt the final reply came from`() {
        val messages = transcript("user", "assistant", "user", "assistant")

        val target = ChatTranscript.regenerationTarget(messages)!!

        assertEquals(2, target.promptIndex)
        assertEquals(3, target.replyIndex)
        // "m2" is the second prompt - replaying "m0" would restart the whole
        // conversation from the top and leave the second turn orphaned.
        assertEquals("m2", messages[target.promptIndex].id)
    }

    @Test
    fun `regenerate in a long transcript still replays the newest prompt`() {
        val messages = transcript(
            "user", "assistant", "user", "assistant", "user", "assistant"
        )

        val target = ChatTranscript.regenerationTarget(messages)!!

        assertEquals(4, target.promptIndex)
        assertEquals(5, target.replyIndex)
    }

    @Test
    fun `regenerate replaces exactly the prompt and the reply`() {
        val messages = transcript("user", "assistant", "user", "assistant")

        val target = ChatTranscript.regenerationTarget(messages)!!

        assertEquals(
            listOf("m2", "m3"),
            listOf(messages[target.promptIndex].id, messages[target.replyIndex].id)
        )
    }

    @Test
    fun `regenerate does nothing when the transcript ends with a prompt`() {
        val messages = transcript("user", "assistant", "user")

        assertEquals(null, ChatTranscript.regenerationTarget(messages))
    }

    @Test
    fun `regenerate does nothing without a prompt behind the reply`() {
        val messages = transcript("assistant")

        assertEquals(null, ChatTranscript.regenerationTarget(messages))
    }
}
