package com.example.video

/**
 * Converts the RGBA frames the native video engine returns into the planar
 * YUV420 layout the H.264 encoder wants.
 *
 * The conversion is BT.601 limited range, the colour space Android's encoders
 * expect by default. It is pure arithmetic so it can be checked without a
 * device, and it accepts an offset because all frames of a clip arrive in one
 * large array.
 */
object RgbaToYuv {

    /** Number of bytes one I420 frame of the given size occupies. */
    fun i420Size(width: Int, height: Int): Int {
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        return width * height + 2 * chromaWidth * chromaHeight
    }

    fun toI420(rgba: ByteArray, width: Int, height: Int): ByteArray =
        toI420(rgba, 0, width, height)

    /**
     * Converts one frame that starts at [offset] in [rgba].
     */
    fun toI420(rgba: ByteArray, offset: Int, width: Int, height: Int): ByteArray {
        require(width > 0 && height > 0) { "A frame needs a positive size" }
        require(offset >= 0) { "The frame offset cannot be negative" }
        require(rgba.size - offset >= width * height * 4) { "The frame is shorter than its size" }

        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val frameSize = width * height
        val chromaSize = chromaWidth * chromaHeight
        val output = ByteArray(frameSize + 2 * chromaSize)

        var yIndex = 0
        var uIndex = frameSize
        var vIndex = frameSize + chromaSize

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = offset + (y * width + x) * 4
                val r = rgba[pixel].toInt() and 0xFF
                val g = rgba[pixel + 1].toInt() and 0xFF
                val b = rgba[pixel + 2].toInt() and 0xFF

                val luma = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                output[yIndex++] = luma.coerceIn(0, 255).toByte()

                // One colour pair per 2x2 block, taken from its top-left
                // pixel. That is enough for the soft gradients the engine
                // draws and keeps the conversion cheap.
                if (x % 2 == 0 && y % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    output[uIndex++] = u.coerceIn(0, 255).toByte()
                    output[vIndex++] = v.coerceIn(0, 255).toByte()
                }
            }
        }
        return output
    }
}