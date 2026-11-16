package com.example

import com.example.model.PromptPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptPaletteTest {

    @Test
    fun `a colour word becomes the first hue anchor`() {
        val palette = PromptPalette.from("a red fox")

        assertEquals(0.00f, palette.hueA, 0.001f)
        assertTrue(palette.saturation > 0.6f)
    }

    @Test
    fun `two colour words become the two anchors in order`() {
        val palette = PromptPalette.from("a BLUE car and green hills")

        assertEquals(0.61f, palette.hueA, 0.001f)
        assertEquals(0.33f, palette.hueB, 0.001f)
    }

    @Test
    fun `a colour word is only matched as a whole word`() {
        // "blueberry" contains the letters "blue" but does not ask for blue.
        assertEquals(0.61f, PromptPalette.from("blue").hueA, 0.001f)
        assertTrue(kotlin.math.abs(PromptPalette.from("blueberry").hueA - 0.61f) > 0.01f)
    }

    @Test
    fun `grey words keep a monochrome picture unsaturated`() {
        val palette = PromptPalette.from("a black and white photograph")

        assertTrue(palette.saturation < 0.1f)
    }

    @Test
    fun `a prompt without a colour word still gets a stable palette`() {
        val first = PromptPalette.from("a quiet cabin in the rain")
        val again = PromptPalette.from("a quiet cabin in the rain")
        val different = PromptPalette.from("a busy market at noon")

        assertEquals(first, again)
        assertNotEquals(first, different)
        assertTrue(first.hueA in 0f..1f)
        assertTrue(first.hueB in 0f..1f)
        assertTrue(first.saturation in 0f..1f)
    }

    @Test
    fun `the default seed is derived from the prompt`() {
        assertEquals(PromptPalette.seedFor("a red fox"), PromptPalette.seedFor("a red fox"))
        assertNotEquals(PromptPalette.seedFor("a red fox"), PromptPalette.seedFor("a blue fox"))
    }

    @Test
    fun `punctuation and case do not hide a colour word`() {
        assertEquals(PromptPalette.from("RED!").hueA, PromptPalette.from("a red car").hueA, 0.001f)
    }
}
