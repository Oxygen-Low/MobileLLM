package com.example

import com.example.video.VideoPromptParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoPromptParserTest {

    @Test
    fun `a json argument carries the prompt and the knobs`() {
        val request = VideoPromptParser.parse(
            """{"prompt": "a fox running through snow", "width": 640, "height": 480, "seconds": 2, "fps": 12, "steps": 10, "seed": 42}""",
            defaultSize = 384,
            defaultFps = 16,
            defaultSeconds = 2,
            defaultSteps = 20
        )

        assertEquals("a fox running through snow", request.prompt)
        assertEquals(640, request.width)
        assertEquals(480, request.height)
        assertEquals(24, request.frameCount)
        assertEquals(12, request.fps)
        assertEquals(10, request.steps)
        assertEquals(42, request.seed)
        assertEquals(2f, request.durationSeconds, 0.001f)
    }

    @Test
    fun `a bare description works too`() {
        val request = VideoPromptParser.parse(
            "a red fox",
            defaultSize = 384,
            defaultFps = 16,
            defaultSeconds = 2,
            defaultSteps = 20
        )

        assertEquals("a red fox", request.prompt)
        assertEquals(384, request.width)
        assertEquals(384, request.height)
        assertEquals(32, request.frameCount)
        assertEquals(16, request.fps)
        assertEquals(20, request.steps)
        assertNull(request.seed)
    }

    @Test
    fun `quoted descriptions lose their quotes`() {
        val request = VideoPromptParser.parse(
            "\"a red fox\"",
            defaultSize = 384,
            defaultFps = 16,
            defaultSeconds = 2,
            defaultSteps = 8
        )
        assertEquals("a red fox", request.prompt)
    }

    @Test
    fun `sizes and lengths are clamped to something a phone can render`() {
        val tooBig = VideoPromptParser.parse(
            """{"prompt": "x", "width": 99999, "height": 1, "seconds": 99, "fps": 99}""",
            defaultSize = 384,
            defaultFps = 16,
            defaultSeconds = 2,
            defaultSteps = 8
        )
        assertEquals(VideoPromptParser.MAX_SIZE, tooBig.width)
        assertEquals(VideoPromptParser.MIN_SIZE, tooBig.height)
        assertEquals(VideoPromptParser.MAX_SECONDS, tooBig.durationSeconds.toInt())
        assertEquals(VideoPromptParser.MAX_FPS, tooBig.fps)
    }

    @Test
    fun `an empty json object does not pretend to have a prompt`() {
        val request = VideoPromptParser.parse(
            """{"steps": 5}""",
            defaultSize = 384,
            defaultFps = 16,
            defaultSeconds = 2,
            defaultSteps = 8
        )
        assertEquals("", request.prompt)
        assertEquals(5, request.steps)
    }
}