package com.example.image

/**
 * A tiny standard base64 encoder.
 *
 * The generated pictures are handed to the HTML sandbox as `data:` URIs, which
 * needs base64. This is written out by hand instead of using
 * [java.util.Base64] because the app still runs on Android 7.0, where that
 * class is not part of the platform yet.
 */
internal object Base64Codec {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var index = 0
        while (index + 2 < bytes.size) {
            val bits = (bytes[index].toInt() and 0xFF shl 16) or
                (bytes[index + 1].toInt() and 0xFF shl 8) or
                (bytes[index + 2].toInt() and 0xFF)
            out.append(ALPHABET[(bits ushr 18) and 0x3F])
            out.append(ALPHABET[(bits ushr 12) and 0x3F])
            out.append(ALPHABET[(bits ushr 6) and 0x3F])
            out.append(ALPHABET[bits and 0x3F])
            index += 3
        }
        when (bytes.size - index) {
            1 -> {
                val bits = bytes[index].toInt() and 0xFF shl 16
                out.append(ALPHABET[(bits ushr 18) and 0x3F])
                out.append(ALPHABET[(bits ushr 12) and 0x3F])
                out.append("==")
            }
            2 -> {
                val bits = (bytes[index].toInt() and 0xFF shl 16) or
                    (bytes[index + 1].toInt() and 0xFF shl 8)
                out.append(ALPHABET[(bits ushr 18) and 0x3F])
                out.append(ALPHABET[(bits ushr 12) and 0x3F])
                out.append(ALPHABET[(bits ushr 6) and 0x3F])
                out.append('=')
            }
        }
        return out.toString()
    }
}
