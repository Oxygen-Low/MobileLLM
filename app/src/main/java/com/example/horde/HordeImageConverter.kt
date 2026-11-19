package com.example.horde

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Converts a picture returned by AI Horde into the PNG the app stores.
 *
 * Horde usually answers with base64-encoded WebP even when a PNG was asked
 * for, and the generated-image store only handles PNG files, so the bytes are
 * decoded into a bitmap and re-encoded here.
 */
object HordeImageConverter {

    fun decodeBase64(value: String): ByteArray =
        Base64.decode(value, Base64.DEFAULT)

    fun toPng(imageBytes: ByteArray): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: throw IllegalArgumentException("AI Horde returned an image that could not be decoded")
        return try {
            ByteArrayOutputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw IllegalStateException("Could not encode the generated picture as PNG")
                }
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Horde requires each edge to be a multiple of 64, between 64 and 3072. */
    fun alignSize(size: Int): Int =
        (((size.coerceIn(64, 3072) + 63) / 64) * 64).coerceIn(64, 3072)
}
