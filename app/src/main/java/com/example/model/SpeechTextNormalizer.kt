package com.example.model

/**
 * Turns a chat reply into something that is pleasant to hear.
 *
 * Reading markdown punctuation out loud is worse than skipping it, so code
 * blocks become a short notice, links and images keep only their label, and
 * emphasis markers disappear.
 */
internal object SpeechTextNormalizer {

    private val codeBlock = Regex("```[\\s\\S]*?```")
    private val inlineCode = Regex("`([^`]*)`")
    private val image = Regex("!\\[[^]]*]\\([^)]*\\)")
    private val link = Regex("\\[([^]]*)]\\([^)]*\\)")
    private val decoration = Regex("[*_#>]+")
    private val whitespace = Regex("\\s+")

    /**
     * @param raw the reply as it is shown in the transcript.
     * @param maxChars how much text to keep; longer replies are cut so one
     * sentence cannot tie up the synthesizer for minutes.
     */
    fun clean(raw: String, maxChars: Int = MAX_CHARS): String {
        val spoken = raw
            .replace(codeBlock, " Code block. ")
            .replace(inlineCode, "$1")
            .replace(image, "")
            .replace(link, "$1")
            .replace(decoration, " ")
            .replace(whitespace, " ")
            .trim()
        return if (spoken.length > maxChars) spoken.take(maxChars) else spoken
    }

    private const val MAX_CHARS = 2_000
}
