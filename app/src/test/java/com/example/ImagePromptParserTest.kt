package com.example

import com.example.image.ImagePromptParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImagePromptParserTest {

    @Test
    fun `a json argument carries the prompt and the knobs`() {
        val request = ImagePromptParser.parse(
            """{"prompt": "a red fox in a snowy forest", "width": 768, "height": 640, "steps": 12, "seed": 42}""",
            defaultSize = 512,
            defaultSteps = 20
        )

        assertEquals("a red fox in a snowy forest", request.prompt)
        assertEquals(768, request.width)
        assertEquals(640, request.height)
        assertEquals(12, request.steps)
        assertEquals(42, request.seed)
    }

    @Test
    fun `a bare description works too`() {
        val request = ImagePromptParser.parse("a red fox", defaultSize = 512, defaultSteps = 20)

        assertEquals("a red fox", request.prompt)
        assertEquals(512, request.width)
        assertEquals(512, request.height)
        assertEquals(20, request.steps)
        assertNull(request.seed)
    }

    @Test
    fun `quoted descriptions lose their quotes`() {
        val request = ImagePromptParser.parse("\"a red fox\"", defaultSize = 512, defaultSteps = 4)
        assertEquals("a red fox", request.prompt)
    }

    @Test
    fun `sizes are clamped to something a phone can draw`() {
        val tooBig = ImagePromptParser.parse(
            """{"prompt": "x", "width": 99999, "height": 1}""",
            defaultSize = 512,
            defaultSteps = 4
        )
        assertEquals(ImagePromptParser.MAX_SIZE, tooBig.width)
        assertEquals(ImagePromptParser.MIN_SIZE, tooBig.height)
    }

    @Test
    fun `an empty json object does not pretend to have a prompt`() {
        val request = ImagePromptParser.parse("""{"steps": 5}""", defaultSize = 512, defaultSteps = 20)
        assertEquals("", request.prompt)
        assertEquals(5, request.steps)
    }
}
