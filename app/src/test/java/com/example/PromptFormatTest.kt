package com.example

import com.example.model.PromptFormat
import com.example.model.ThinkStreamSplitter
import com.example.model.Utf8StreamDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptFormatTest {

    @Test
    fun `smollm3 thinking prompt matches the gguf template layout`() {
        val prompt = PromptFormat.smolLM3(
            system = "",
            turns = listOf(PromptFormat.Turn("user", "Hello")),
            enableThinking = true,
            today = "11 November 2026"
        )

        assertTrue(prompt.startsWith("<|im_start|>system\n## Metadata\n\n"))
        assertTrue(prompt.contains("Knowledge Cutoff Date: June 2025\n"))
        assertTrue(prompt.contains("Today Date: 11 November 2026\n"))
        assertTrue(prompt.contains("Reasoning Mode: /think\n\n"))
        assertTrue(prompt.contains("## Custom Instructions\n\nYou are a helpful AI assistant named SmolLM"))
        // The template does not close the system block with <|im_end|> when no tools are used.
        assertTrue(prompt.contains("</think> Solution section. In the Thought"))
        assertTrue(
            prompt.endsWith(
                "\n\n<|im_start|>user\nHello<|im_end|>\n<|im_start|>assistant\n"
            )
        )
    }

    @Test
    fun `no think mode injects an empty think block`() {
        val prompt = PromptFormat.smolLM3(
            system = "Answer briefly /no_think",
            turns = listOf(PromptFormat.Turn("user", "Hi")),
            enableThinking = true,
            today = "11 November 2026"
        )

        assertTrue(prompt.contains("Reasoning Mode: /no_think"))
        assertTrue(prompt.contains("## Custom Instructions\n\nAnswer briefly\n\n"))
        assertTrue(prompt.endsWith("<|im_start|>assistant\n<think>\n\n</think>\n"))
    }

    @Test
    fun `assistant turns are closed and trimmed`() {
        val prompt = PromptFormat.smolLM3(
            system = "Be nice",
            turns = listOf(
                PromptFormat.Turn("user", "Hi"),
                PromptFormat.Turn("assistant", "\n\nHello!"),
                PromptFormat.Turn("user", "Again")
            ),
            enableThinking = false,
            today = "11 November 2026"
        )

        assertTrue(prompt.contains("<|im_start|>user\nHi<|im_end|>\n"))
        assertTrue(prompt.contains("<|im_start|>assistant\nHello!<|im_end|>\n"))
        assertTrue(prompt.contains("<|im_start|>user\nAgain<|im_end|>\n"))
    }

    @Test
    fun `system override skips metadata`() {
        val prompt = PromptFormat.smolLM3(
            system = "Custom prompt only /system_override",
            turns = listOf(PromptFormat.Turn("user", "Hi")),
            enableThinking = true,
            today = "11 November 2026"
        )

        assertTrue(prompt.startsWith("<|im_start|>system\nCustom prompt only<|im_end|>\n"))
        assertTrue(!prompt.contains("## Metadata"))
    }
}

class StreamTextTest {

    @Test
    fun `utf8 decoder keeps split multi-byte sequences`() {
        val bytes = "héllo 🙂 ok".toByteArray(Charsets.UTF_8)
        val decoder = Utf8StreamDecoder()
        val out = StringBuilder()

        // Feed one byte at a time: every glyph boundary is exercised.
        for (byte in bytes) {
            out.append(decoder.decode(byteArrayOf(byte)))
        }
        out.append(decoder.flush())

        assertEquals("héllo 🙂 ok", out.toString())
    }

    @Test
    fun `think splitter separates reasoning from answer`() {
        val splitter = ThinkStreamSplitter()
        val chunks = mutableListOf<ThinkStreamSplitter.Chunk>()

        for (piece in listOf("<thi", "nk>", "1 + 1 = 2", "</th", "ink>", "\nThe answer is 2")) {
            chunks += splitter.feed(piece)
        }
        chunks += splitter.flush()

        val thinking = chunks.filter { it.thinking }.joinToString("") { it.text }
        val answer = chunks.filter { !it.thinking }.joinToString("") { it.text }

        assertEquals("1 + 1 = 2", thinking)
        assertEquals("\nThe answer is 2", answer)
    }

    @Test
    fun `think splitter keeps unterminated reasoning at flush`() {
        val splitter = ThinkStreamSplitter()
        val chunks = splitter.feed("<think>partial") + splitter.flush()

        assertEquals("partial", chunks.filter { it.thinking }.joinToString("") { it.text })
    }
}
