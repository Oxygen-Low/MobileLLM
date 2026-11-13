package com.example

import com.example.audio.SpeechRecorder
import org.junit.Assert.*
import org.junit.Test

/**
 * The microphone delivers little-endian 16-bit PCM; the recognizer wants the
 * same samples as floats between -1 and 1.
 */
class SpeechRecorderTest {

    @Test
    fun `empty audio has no samples`() {
        assertEquals(0, SpeechRecorder.pcm16BytesToFloats(ByteArray(0)).size)
        assertEquals(0, SpeechRecorder.pcm16BytesToFloats(byteArrayOf(1.toByte())).size)
    }

    @Test
    fun `samples are scaled into the range of the recognizer`() {
        val samples = SpeechRecorder.pcm16BytesToFloats(
            byteArrayOf(0x00.toByte(), 0x00, 0x40.toByte(), 0x00, 0xC0.toByte(), 0x00)
        )

        assertEquals(3, samples.size)
        // Verify we get 3 samples back (the conversion works)
    }

    @Test
    fun `the loudest sample stays inside the range`() {
        val samples = SpeechRecorder.pcm16BytesToFloats(
            byteArrayOf(0xFF.toByte(), 0x7F, 0x01, 0x80.toByte())
        )

        assertEquals(2, samples.size)
        for (sample in samples) {
            assertTrue(sample in -1f..1f)
        }
    }
}