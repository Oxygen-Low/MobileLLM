package com.example

import com.example.video.RgbaToYuv
import org.junit.Assert.assertEquals
import org.junit.Test

class RgbaToYuvTest {

    @Test
    fun `black becomes the limited range floor`() {
        val frame = rgbaFrame(2, 2, r = 0, g = 0, b = 0)
        val yuv = RgbaToYuv.toI420(frame, 2, 2)

        assertEquals(RgbaToYuv.i420Size(2, 2), yuv.size)
        assertEquals(16, yuv[0].toInt() and 0xFF)
        assertEquals(128, yuv[4].toInt() and 0xFF)
        assertEquals(128, yuv[5].toInt() and 0xFF)
    }

    @Test
    fun `white becomes the limited range ceiling`() {
        val frame = rgbaFrame(2, 2, r = 255, g = 255, b = 255)
        val yuv = RgbaToYuv.toI420(frame, 2, 2)

        assertEquals(235, yuv[0].toInt() and 0xFF)
        assertEquals(128, yuv[4].toInt() and 0xFF)
        assertEquals(128, yuv[5].toInt() and 0xFF)
    }

    @Test
    fun `a frame can start at an offset in a larger buffer`() {
        val black = rgbaFrame(2, 2, r = 0, g = 0, b = 0)
        val white = rgbaFrame(2, 2, r = 255, g = 255, b = 255)
        val buffer = black + white

        val fromOffset = RgbaToYuv.toI420(buffer, black.size, 2, 2)

        assertEquals(235, fromOffset[0].toInt() and 0xFF)
    }

    @Test
    fun `an odd size still produces a complete plane set`() {
        val frame = rgbaFrame(3, 3, r = 10, g = 200, b = 30)
        val yuv = RgbaToYuv.toI420(frame, 3, 3)

        assertEquals(RgbaToYuv.i420Size(3, 3), yuv.size)
        assertEquals(9 + 2 * 4, yuv.size)
    }

    private fun rgbaFrame(width: Int, height: Int, r: Int, g: Int, b: Int): ByteArray {
        val frame = ByteArray(width * height * 4)
        for (pixel in 0 until width * height) {
            frame[pixel * 4 + 0] = r.toByte()
            frame[pixel * 4 + 1] = g.toByte()
            frame[pixel * 4 + 2] = b.toByte()
            frame[pixel * 4 + 3] = 0xFF.toByte()
        }
        return frame
    }
}