package com.example.model

import java.io.ByteArrayOutputStream

/**
 * Decodes a token stream into text without splitting multi-byte UTF-8
 * sequences: llama.cpp emits raw bytes, and a single glyph can span tokens.
 */
internal class Utf8StreamDecoder {
    private val pending = ByteArrayOutputStream(8)

    fun decode(bytes: ByteArray): String {
        pending.write(bytes)
        val buffer = pending.toByteArray()
        if (buffer.isEmpty()) return ""

        // Walk back over continuation bytes to the start of the last sequence.
        var start = buffer.size - 1
        var continuation = 0
        while (start >= 0 && continuation < 4 && (buffer[start].toInt() and 0xC0) == 0x80) {
            start--
            continuation++
        }

        var cut = buffer.size
        if (start >= 0) {
            val lead = buffer[start].toInt() and 0xFF
            val needed = when {
                lead < 0x80 -> 1
                lead and 0xE0 == 0xC0 -> 2
                lead and 0xF0 == 0xE0 -> 3
                lead and 0xF8 == 0xF0 -> 4
                else -> 1
            }
            if (buffer.size - start < needed) cut = start
        }

        pending.reset()
        if (cut < buffer.size) pending.write(buffer, cut, buffer.size - cut)
        return String(buffer, 0, cut, Charsets.UTF_8)
    }

    fun flush(): String {
        val text = String(pending.toByteArray(), Charsets.UTF_8)
        pending.reset()
        return text
    }
}

/**
 * Splits the model's `<think>…</think>` output into reasoning and answer
 * segments while keeping the stream incremental.
 */
internal class ThinkStreamSplitter {

    data class Chunk(val thinking: Boolean, val text: String)

    private val buffer = StringBuilder()
    private var insideThinking = false

    fun feed(text: String): List<Chunk> {
        buffer.append(text)
        return drain(final = false)
    }

    fun flush(): List<Chunk> = drain(final = true)

    private fun drain(final: Boolean): List<Chunk> {
        val chunks = mutableListOf<Chunk>()

        while (true) {
            if (!insideThinking) {
                val start = buffer.indexOf(OPEN)
                if (start >= 0) {
                    if (start > 0) chunks.add(Chunk(false, buffer.substring(0, start)))
                    buffer.delete(0, start + OPEN.length)
                    insideThinking = true
                    continue
                }
                val keep = partialSuffix(buffer, OPEN)
                val emit = buffer.length - keep
                if (emit > 0) {
                    chunks.add(Chunk(false, buffer.substring(0, emit)))
                    buffer.delete(0, emit)
                }
                break
            } else {
                val end = buffer.indexOf(CLOSE)
                if (end >= 0) {
                    if (end > 0) chunks.add(Chunk(true, buffer.substring(0, end)))
                    buffer.delete(0, end + CLOSE.length)
                    insideThinking = false
                    continue
                }
                val keep = partialSuffix(buffer, CLOSE)
                val emit = buffer.length - keep
                if (emit > 0) {
                    chunks.add(Chunk(true, buffer.substring(0, emit)))
                    buffer.delete(0, emit)
                }
                break
            }
        }

        if (final && buffer.isNotEmpty()) {
            chunks.add(Chunk(insideThinking, buffer.toString()))
            buffer.setLength(0)
        }

        return chunks.filter { it.text.isNotEmpty() }
    }

    private fun partialSuffix(text: CharSequence, tag: String): Int {
        for (length in (tag.length - 1) downTo 1) {
            if (text.length >= length && text.endsWith(tag.substring(0, length))) {
                return length
            }
        }
        return 0
    }

    private companion object {
        const val OPEN = "<think>"
        const val CLOSE = "</think>"
    }
}
