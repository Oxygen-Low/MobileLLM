package com.example.video

/**
 * Finds and rewrites the `vid:<id>` references the text model leaves behind.
 *
 * A reply or a sandbox carries the reference as plain text - a markdown link
 * (`[a fox](vid:1a2b)`) or an HTML tag (`<video src="vid:1a2b">`) - and the app
 * decides what to do with it. The model never sees a device path.
 */
object GeneratedVideoReferences {

    private val TOKEN = Regex("vid:([A-Za-z0-9_-]{1,64})")

    /** The ids referenced by [text], in order and without duplicates. */
    fun referencesIn(text: String): List<String> =
        TOKEN.findAll(text).map { it.groupValues[1] }.distinct().toList()

    /**
     * Replaces every reference in [text] with whatever [resolve] returns, or
     * leaves it untouched when the clip is unknown. Used to hand the HTML
     * sandbox its clips as `data:` URIs, which a WebView can play without any
     * file access.
     */
    fun inline(text: String, resolve: (String) -> String?): String =
        TOKEN.replace(text) { match -> resolve(match.groupValues[1]) ?: match.value }
}