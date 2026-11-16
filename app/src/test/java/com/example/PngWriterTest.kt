package com.example

import com.example.image.PngWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * The PNG the image engine output is wrapped into has to be a real file: the
 * WebView and the chat bubble both read it back with the platform decoder.
 */
class PngWriterTest {

    @Test
    fun `the signature is the one of a png`() {
        val png = PngWriter.encodeRgba(2, 2, ByteArray(2 * 2 * 4) { 0 })

        val expected = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertEquals(expected.toList(), png.take(expected.size))
    }

    @Test
    fun `a decoding round trip keeps the size and the pixels`() {
        // A 2x2 picture: red, green, blue, transparent.
        val pixels = byteArrayOf(
            255.toByte(), 0, 0, 255.toByte(),
            0, 255.toByte(), 0, 255.toByte(),
            0, 0, 255.toByte(), 255.toByte(),
            0, 0, 0, 0
        )

        val png = PngWriter.encodeRgba(2, 2, pixels)
        val image = ImageIO.read(ByteArrayInputStream(png))

        assertEquals(2, image.width)
        assertEquals(2, image.height)
        assertEquals(0xFFFF0000.toInt(), image.getRGB(0, 0))
        assertEquals(0xFF00FF00.toInt(), image.getRGB(1, 0))
        assertEquals(0xFF0000FF.toInt(), image.getRGB(0, 1))
        assertEquals(0x00000000, image.getRGB(1, 1))
    }

    @Test
    fun `a wrong buffer is refused`() {
        val thrown = runCatching { PngWriter.encodeRgba(4, 4, ByteArray(3)) }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
    }
}
