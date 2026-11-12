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
 * Pulls `<tool_call>…</tool_call>` blocks out of the token stream.
 *
 * The model is instructed to emit tool calls in a small, unambiguous format.
 * Everything inside a complete block is withheld from the displayed answer (the
 * UI shows a dedicated tool banner instead) and handed to the caller so the
 * agent loop can execute the call and generate the next round.
 *
 * Only the tools in [knownTools] are accepted. A block naming an unknown tool
 * is put back into the visible text, so the model quoting the syntax (or
 * hallucinating a tool) does not trigger a call.
 */
internal class ToolCallDetector(knownTools: Set<String>) {

    /** A parsed tool invocation. */
    data class ParsedToolCall(val toolName: String, val argument: String)

    private val knownTools = knownTools
    private val buffer = StringBuilder()
    private var insideToolCall = false
    private var parsed: ParsedToolCall? = null

    /** True once a syntactically valid tool call has been seen. */
    val isComplete: Boolean get() = parsed != null

    /** The parsed call, or null when none has been detected yet. */
    fun toolCall(): ParsedToolCall? = parsed

    /**
     * Feeds a raw text piece and returns the part that is safe to display.
     * A trailing partial tag is held back until the rest arrives.
     */
    fun feed(text: String): String {
        buffer.append(text)
        return drain()
    }

    /**
     * Releases anything still held when the stream ends without a complete
     * tool call, so a truncated block is not silently dropped from the answer.
     */
    fun flush(): String {
        if (!insideToolCall) return ""
        val held = buffer.toString()
        buffer.setLength(0)
        insideToolCall = false
        return held
    }

    private fun drain(): String {
        val out = StringBuilder()
        while (true) {
            if (!insideToolCall) {
                val start = buffer.indexOf(OPEN)
                if (start >= 0) {
                    out.append(buffer.substring(0, start))
                    buffer.delete(0, start + OPEN.length)
                    insideToolCall = true
                    continue
                }
                val keep = partialTagSuffix(buffer, OPEN)
                val emit = buffer.length - keep
                if (emit > 0) {
                    out.append(buffer.substring(0, emit))
                    buffer.delete(0, emit)
                }
                break
            } else {
                val end = buffer.indexOf(CLOSE)
                if (end < 0) break
                val body = buffer.substring(0, end)
                buffer.delete(0, end + CLOSE.length)
                insideToolCall = false
                // A `</think>` injected by the reasoning budget can land in
                // the middle of a call; drop it before parsing the body.
                val cleaned = body.replace("<think>", "").replace("</think>", "")
                val call = ToolCallParser.parse(cleaned)
                val canonical = call?.let { resolveToolName(it.toolName) }
                if (call != null && canonical != null) {
                    parsed = ParsedToolCall(canonical, call.argument)
                    break
                }
                // Not a real call (the model was quoting the syntax, or named a
                // tool that does not exist); put the tags back so the text
                // stays visible.
                out.append(OPEN).append(body).append(CLOSE)
            }
        }
        return out.toString()
    }

    /** Maps a (possibly mis-cased) tool name onto the canonical one. */
    private fun resolveToolName(name: String): String? =
        knownTools.firstOrNull { it.equals(name, ignoreCase = true) }

    private companion object {
        const val OPEN = "<tool_call>"
        const val CLOSE = "</tool_call>"
    }
}

/**
 * Parses the body of a `<tool_call>` block.
 *
 * Two shapes are accepted: the simple `tool_name: argument` form the system
 * prompt asks for, and a JSON object for models that prefer to emit one.
 */
internal object ToolCallParser {

    fun parse(body: String): ToolCallDetector.ParsedToolCall? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null

        if (trimmed.startsWith("{")) {
            parseJson(trimmed)?.let { return it }
        }

        val colon = trimmed.indexOf(':')
        if (colon > 0) {
            val name = trimmed.substring(0, colon).trim().stripQuotes()
            val argument = trimmed.substring(colon + 1).trim().stripQuotes()
            return if (name.isEmpty()) null
            else ToolCallDetector.ParsedToolCall(name, argument)
        }

        // A bare tool name (no parameter).
        val name = trimmed.lineSequence().firstOrNull()?.trim().orEmpty()
        return if (name.isNotEmpty() && !name.contains(' ')) {
            ToolCallDetector.ParsedToolCall(name.stripQuotes(), "")
        } else {
            null
        }
    }

    private fun parseJson(text: String): ToolCallDetector.ParsedToolCall? {
        val name = Regex("\"name\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1)
            ?: return null
        if (name.isEmpty()) return null
        val argument = Regex("\"(?:arguments?|query|expression)\"\\s*:\\s*\"([^\"]*)\"")
            .find(text)?.groupValues?.get(1).orEmpty()
        return ToolCallDetector.ParsedToolCall(name, argument.stripQuotes())
    }

    private fun String.stripQuotes(): String =
        trim().removeSurrounding("\"").removeSurrounding("'")
}

/**
 * Splits the model's `<think>…</think>` output into reasoning and answer
 * segments while keeping the stream incremental.
 */
internal class ThinkStreamSplitter(startInsideThinking: Boolean = false) {

    data class Chunk(val thinking: Boolean, val text: String)

    private val buffer = StringBuilder()
    private var insideThinking = startInsideThinking

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
                val keep = partialTagSuffix(buffer, OPEN)
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
                val keep = partialTagSuffix(buffer, CLOSE)
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

    private companion object {
        const val OPEN = "<think>"
        const val CLOSE = "</think>"
    }
}

/**
 * Length of the longest suffix of [text] that could still grow into [tag].
 * Used to hold back partial tags at chunk boundaries.
 */
internal fun partialTagSuffix(text: CharSequence, tag: String): Int {
    for (length in (tag.length - 1) downTo 1) {
        if (text.length >= length && text.endsWith(tag.substring(0, length))) {
            return length
        }
    }
    return 0
}
