package com.example.image

/**
 * Finds and rewrites the `gen:<id>` references the text model leaves behind.
 *
 * A reply or a sandbox carries the reference as plain text - a markdown image
 * (`![a fox](gen:1a2b)`) or an HTML tag (`<img src="gen:1a2b">`) - and the app
 * decides what to do with it. The model never sees a device path.
 */
object GeneratedImageReferences {

    private val TOKEN = Regex("gen:([A-Za-z0-9_-]{1,64})")

    /** The ids referenced by [text], in order and without duplicates. */
    fun referencesIn(text: String): List<String> =
        TOKEN.findAll(text).map { it.groupValues[1] }.distinct().toList()

    /**
     * Replaces every reference in [text] with whatever [resolve] returns, or
     * leaves it untouched when the picture is unknown. Used to hand the HTML
     * sandbox its pictures as `data:` URIs, which a WebView can show without
     * any file access.
     */
    fun inline(text: String, resolve: (String) -> String?): String =
        TOKEN.replace(text) { match -> resolve(match.groupValues[1]) ?: match.value }
}
