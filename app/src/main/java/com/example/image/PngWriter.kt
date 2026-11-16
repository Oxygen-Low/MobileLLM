package com.example.image

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Encodes raw 8-bit RGBA pixels into a PNG.
 *
 * The native image engine hands back the pixels of a picture, not a file, so
 * the encoding lives here instead of in the engine: only the parts of the
 * format the app needs are written (8-bit RGBA, non-interlaced, one IDAT).
 */
object PngWriter {

    private val SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    /**
     * @param rgba [width] * [height] * 4 bytes, row major, red first.
     * @throws IllegalArgumentException when the buffer does not match the size.
     */
    fun encodeRgba(width: Int, height: Int, rgba: ByteArray): ByteArray {
        require(width > 0 && height > 0) { "The picture has to have a size" }
        require(rgba.size == width * height * 4) {
            "Expected ${width * height * 4} bytes of RGBA for ${width}x$height, got ${rgba.size}"
        }

        val raw = ByteArray(height * (1 + width * 4))
        var source = 0
        var target = 0
        repeat(height) {
            raw[target++] = 0 // filter type: None
            System.arraycopy(rgba, source, raw, target, width * 4)
            source += width * 4
            target += width * 4
        }

        val output = ByteArrayOutputStream(SIGNATURE.size + raw.size / 2 + 64)
        output.write(SIGNATURE)
        output.write(chunk("IHDR", ihdr(width, height)))
        output.write(chunk("IDAT", deflate(raw)))
        output.write(chunk("IEND", ByteArray(0)))
        return output.toByteArray()
    }

    private fun ihdr(width: Int, height: Int): ByteArray {
        val header = ByteArray(13)
        header[0] = (width ushr 24).toByte()
        header[1] = (width ushr 16).toByte()
        header[2] = (width ushr 8).toByte()
        header[3] = width.toByte()
        header[4] = (height ushr 24).toByte()
        header[5] = (height ushr 16).toByte()
        header[6] = (height ushr 8).toByte()
        header[7] = height.toByte()
        header[8] = 8  // bit depth
        header[9] = 6  // colour type: truecolour with alpha
        header[10] = 0 // compression: deflate
        header[11] = 0 // filter method
        header[12] = 0 // interlace: none
        return header
    }

    /** One PNG chunk: length, type, data and the CRC of type + data. */
    private fun chunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }
        val out = ByteArray(12 + data.size)
        writeInt(out, 0, data.size)
        System.arraycopy(typeBytes, 0, out, 4, 4)
        System.arraycopy(data, 0, out, 8, data.size)
        writeInt(out, 8 + data.size, crc.value.toInt())
        return out
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED)
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream(data.size / 2 + 64)
            val buffer = ByteArray(16 * 1024)
            while (!deflater.finished()) {
                val written = deflater.deflate(buffer)
                out.write(buffer, 0, written)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }
}
