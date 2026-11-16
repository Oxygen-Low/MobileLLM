package com.example.video

/**
 * Turns the float samples returned by the TTS model into the 16-bit PCM the
 * MP4 muxer accepts. Piper voices run at a lower rate than AAC audio, so the
 * samples are linearly resampled and the track is padded with silence to the
 * length of the clip.
 */
object Pcm16Converter {

    fun convert(samples: FloatArray, sourceRate: Int, targetRate: Int, targetCount: Int): ByteArray {
        if (targetCount <= 0 || sourceRate <= 0 || targetRate <= 0) return ByteArray(0)
        val output = ByteArray(targetCount * 2)
        for (i in 0 until targetCount) {
            val position = i.toDouble() * sourceRate / targetRate
            val index = position.toInt()
            val fraction = (position - index).toFloat()
            val first = samples.getOrElse(index) { 0f }
            val second = samples.getOrElse(index + 1) { first }
            val value = (first + (second - first) * fraction).coerceIn(-1f, 1f)
            val pcm = (value * 32767f).toInt()
            output[i * 2] = (pcm and 0xFF).toByte()
            output[i * 2 + 1] = ((pcm shr 8) and 0xFF).toByte()
        }
        return output
    }
}
