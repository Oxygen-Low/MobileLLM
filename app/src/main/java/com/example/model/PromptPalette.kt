package com.example.model

import kotlin.math.floor

/**
 * The colour scheme the built in image and video samplers paint with.
 *
 * The engines that ship with the app are small field renderers: they cannot
 * read a prompt the way a diffusion checkpoint does. They can, however, honour
 * the colour words a person writes. The palette used to come straight out of a
 * random seed, which is why every picture got a fresh set of arbitrary colours
 * and a clip was stuck morphing the first random pattern. Deriving it from the
 * text instead makes a prompt paint with the colours it asks for, and makes the
 * same prompt produce the same colours every time.
 *
 * This is pure logic on purpose: it runs on the caller's thread before the
 * native sampler is entered and can be tested without a device.
 */
data class PromptPalette(
    /** Hue of the first anchor, in [0, 1). */
    val hueA: Float,
    /** Hue of the second anchor, in [0, 1). The picture blends between them. */
    val hueB: Float,
    /** Saturation shared by both anchors, in [0, 1). */
    val saturation: Float
) {
    companion object {
        /** HSV hue and saturation of a colour word. */
        private data class NamedColor(val hue: Float, val saturation: Float)

        private val NAMED_COLORS: Map<String, NamedColor> = buildMap {
            fun add(hue: Float, saturation: Float = 0.78f, vararg words: String) {
                for (word in words) put(word, NamedColor(hue, saturation))
            }
            add(0.00f, 0.82f, "red", "crimson", "scarlet", "ruby", "cherry")
            add(0.07f, 0.50f, "brown", "brownish")
            add(0.09f, 0.85f, "orange", "amber", "copper")
            add(0.15f, 0.85f, "yellow", "gold", "golden")
            add(0.33f, 0.75f, "green", "emerald", "lime", "forest")
            add(0.47f, 0.72f, "teal", "turquoise", "aqua")
            add(0.52f, 0.85f, "cyan", "sky")
            add(0.61f, 0.80f, "blue", "azure", "navy", "indigo")
            add(0.75f, 0.75f, "purple", "violet", "lavender")
            add(0.88f, 0.70f, "magenta", "pink", "rose")
            // Without a chromatic word the picture should stay readable, so
            // grey-ish words only lower saturation when they are all there is.
            add(0.00f, 0.04f, "black", "white", "gray", "grey", "silver", "grayscale", "monochrome")
        }

        /**
         * Builds the palette for [prompt].
         *
         * Colour words become the two anchors, in the order they appear. A
         * prompt without one still gets a stable scheme from a hash of the
         * text, so the colours depend on what was asked for and not on chance.
         */
        fun from(prompt: String): PromptPalette {
            val words = prompt.lowercase()
                .split(WORD_SEPARATOR)
                .filter { it.isNotEmpty() }

            val found = LinkedHashSet<NamedColor>()
            for (word in words) {
                NAMED_COLORS[word]?.let { found.add(it) }
            }

            if (found.isEmpty()) {
                val hash = hash(prompt)
                val hueA = ((hash ushr 8) and 0xFFFFL).toFloat() / 65535f
                val spread = 0.18f + (((hash ushr 24) and 0xFFL).toFloat() / 255f) * 0.28f
                val saturation = 0.50f + ((hash and 0x3FL).toFloat() / 63f) * 0.35f
                return PromptPalette(hueA, wrap(hueA + spread), saturation)
            }

            val colors = found.toList()
            val hueA = colors[0].hue
            val hueB = if (colors.size > 1) colors[1].hue else wrap(hueA + 0.12f)
            val saturation = (colors.map { it.saturation }.average()).toFloat().coerceIn(0f, 1f)
            return PromptPalette(hueA, hueB, saturation)
        }

        /**
         * Seed to use when a tool call does not carry one.
         *
         * A random default gave two identical prompts two different pictures.
         * Hashing the text keeps results reproducible while still letting a
         * caller pass an explicit seed when they want a variation.
         */
        fun seedFor(prompt: String): Int = hash(prompt).toInt()

        private val WORD_SEPARATOR = Regex("[^a-z]+")

        private fun wrap(hue: Float): Float {
            val wrapped = hue - floor(hue)
            return if (wrapped < 0f) wrapped + 1f else wrapped
        }

        /** FNV-1a over UTF-8 bytes: stable across devices and JVM versions. */
        private fun hash(text: String): Long {
            var value = 1469598103934665603L
            for (byte in text.toByteArray(Charsets.UTF_8)) {
                value = (value xor (byte.toLong() and 0xFFL)) * 1099511628211L
            }
            return value
        }
    }
}
