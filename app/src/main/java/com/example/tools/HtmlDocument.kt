package com.example.tools

/**
 * Cleans the HTML a model hands to the sandbox before it is stored or shown.
 *
 * Two accidents turn a page into text on screen:
 *
 * - the model wraps the whole file in a markdown code fence, so the page
 *   starts with ```` ```html ```` and the WebView shows that line as text;
 * - the model escapes every angle bracket as `&lt;`, so the WebView shows the
 *   source of the page instead of rendering it.
 *
 * The document belongs to the user once it is written, so the cleaning
 * happens while it is still coming from the model and before every read: a
 * sandbox saved by an older build gets repaired the same way.
 */
internal object HtmlDocument {

    /** The content of [raw] as the WebView should receive it. */
    fun normalize(raw: String): String {
        var text = raw.removePrefix(BOM).trim()
        text = stripFence(text)
        if (looksEscaped(text)) text = decodeEntities(text)
        return text.trim()
    }

    /**
     * Removes a fence that wraps the whole document. A fence that is part of
     * the page (or one that is never closed) is left alone.
     */
    private fun stripFence(text: String): String {
        var ticks = 0
        while (ticks < text.length && text[ticks] == '`') ticks++
        if (ticks < MIN_FENCE) return text

        val fence = "`".repeat(ticks)
        val rest = text.substring(ticks)
        val firstLineEnd = rest.indexOf('\n')
        if (firstLineEnd < 0) return text

        // The opening line is ```` ```html ````, or ```` ``` ```` on its own.
        val tag = rest.substring(0, firstLineEnd).trim()
        if (tag.any { !it.isLetterOrDigit() && it !in FENCE_TAG_PUNCTUATION }) return text

        val body = rest.substring(firstLineEnd + 1)
        val close = body.lastIndexOf(fence)
        if (close < 0 || body.substring(close + fence.length).isNotBlank()) return text
        return body.substring(0, close)
    }

    /**
     * True when the text is a page whose every tag was escaped, which is the
     * only case where decoding the entities is safe: a page that teaches HTML
     * starts with a real tag and keeps its examples escaped.
     */
    private fun looksEscaped(text: String): Boolean =
        ESCAPED_START.any { text.startsWith(it, ignoreCase = true) }

    private fun decodeEntities(text: String): String =
        text.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&amp;", "&")

    private const val BOM = "\uFEFF"

    /** The shortest markdown fence that wraps a code block. */
    private const val MIN_FENCE = 3
    private const val FENCE_TAG_PUNCTUATION = "#+._-"

    private val ESCAPED_START = listOf("&lt;!doctype", "&lt;html", "&lt;head", "&lt;body")
}
