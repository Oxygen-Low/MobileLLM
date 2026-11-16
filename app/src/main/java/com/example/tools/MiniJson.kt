package com.example.tools

/**
 * A forgiving reader for the small JSON objects a model passes to a tool.
 *
 * The arguments of the tools are JSON, but the values are often whole files
 * of HTML: small models forget to escape the quotes of an attribute, or they
 * wrap the payload in a markdown code fence. A strict parser stops at the
 * first of those quotes and hands back the first line of the page, which the
 * sandbox then shows as a blank screen with a stray fragment of text on it.
 *
 * The reader below therefore ends a string value only at a quote that is
 * followed by a separator or the end of the object, and it decodes escape
 * sequences on the way through. An escape it does not know (`\d` in a regular
 * expression, for example) keeps its backslash instead of being eaten.
 */
internal object MiniJson {

    fun getString(json: String, vararg keys: String): String {
        for (key in keys) {
            valueOf(json, key)?.let { return it }
        }
        return ""
    }

    fun getInt(json: String, vararg keys: String, default: Int): Int {
        for (key in keys) {
            val pattern = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(\\d+)")
            val match = pattern.find(json)
            if (match != null) {
                return match.groupValues[1].toIntOrNull() ?: default
            }
        }
        return default
    }

    /** The string value of [key], or null when the object does not have it. */
    private fun valueOf(json: String, key: String): String? {
        val token = "\"$key\""
        var from = 0
        while (true) {
            val keyIndex = json.indexOf(token, from)
            if (keyIndex < 0) return null
            var index = skipWhitespace(json, keyIndex + token.length)
            if (index < json.length && json[index] == ':') {
                index = skipWhitespace(json, index + 1)
                if (index < json.length && json[index] == '"') {
                    return readString(json, index + 1)
                }
            }
            // The key belongs to a different, nested object: keep looking.
            from = keyIndex + token.length
        }
    }

    private fun readString(json: String, start: Int): String {
        val out = StringBuilder()
        var index = start
        while (index < json.length) {
            val c = json[index]
            if (c == '\\' && index + 1 < json.length) {
                val escape = decodeEscape(json, index)
                out.append(escape.text)
                index = escape.next
                continue
            }
            if (c == '"') {
                if (endsValue(json, index)) return out.toString()
                // An unescaped quote inside the value, which is what an
                // attribute of the page looks like when the model forgot to
                // escape it. It is part of the value, not its end.
                out.append(c)
                index++
                continue
            }
            out.append(c)
            index++
        }
        return out.toString()
    }

    /**
     * True when the quote at [quoteIndex] closes the value: the next
     * meaningful character is the one that ends an entry.
     */
    private fun endsValue(json: String, quoteIndex: Int): Boolean {
        val next = skipWhitespace(json, quoteIndex + 1)
        return next >= json.length || json[next] == ',' || json[next] == '}'
    }

    private fun skipWhitespace(text: String, from: Int): Int {
        var index = from
        while (index < text.length && text[index].isWhitespace()) index++
        return index
    }

    private data class Escape(val text: String, val next: Int)

    private fun decodeEscape(json: String, index: Int): Escape {
        val next = json[index + 1]
        return when (next) {
            '"' -> Escape("\"", index + 2)
            '\\' -> Escape("\\", index + 2)
            '/' -> Escape("/", index + 2)
            'b' -> Escape("\b", index + 2)
            'f' -> Escape("\u000C", index + 2)
            'n' -> Escape("\n", index + 2)
            'r' -> Escape("\r", index + 2)
            't' -> Escape("\t", index + 2)
            'u' -> {
                val hex = json.substring(index + 2, minOf(index + 6, json.length))
                val code = if (hex.length == 4) hex.toIntOrNull(16) else null
                if (code != null) Escape(code.toChar().toString(), index + 6)
                else Escape("\\u", index + 2)
            }
            else -> Escape("\\$next", index + 2)
        }
    }
}
