package com.example

import com.example.video.Pcm16Converter
import org.junit.Assert.assertEquals
import org.junit.Test

class Pcm16ConverterTest {

    @Test
    fun `silence in gives silence out at the new rate`() {
        val pcm = Pcm16Converter.convert(FloatArray(10), 22050, 44100, 10)
        assertEquals(20, pcm.size)
        assertEquals(0.toByte(), pcm[0])
        assertEquals(0.toByte(), pcm[1])
    }

    @Test
    fun `full scale samples become full scale 16 bit little endian`() {
        val pcm = Pcm16Converter.convert(floatArrayOf(1f, -1f), 44100, 44100, 2)
        assertEquals(0xFF.toByte(), pcm[0])
        assertEquals(0x7F.toByte(), pcm[1])
        assertEquals(0x01.toByte(), pcm[2])
        assertEquals(0x80.toByte(), pcm[3])
    }

    @Test
    fun `resampling repeats samples when the source rate is lower`() {
        val pcm = Pcm16Converter.convert(floatArrayOf(1f, -1f), 22050, 44100, 4)
        // Doubling the rate: +1, the midpoint (silence), then -1 twice.
        assertEquals(0xFF.toByte(), pcm[0])
        assertEquals(0x7F.toByte(), pcm[1])
        assertEquals(0.toByte(), pcm[2])
        assertEquals(0.toByte(), pcm[3])
        assertEquals(0x01.toByte(), pcm[4])
        assertEquals(0x80.toByte(), pcm[5])
    }

    @Test
    fun `a short narration is padded with silence to fill the clip`() {
        val pcm = Pcm16Converter.convert(floatArrayOf(1f), 22050, 44100, 4)
        assertEquals(8, pcm.size)
        assertEquals(0.toByte(), pcm[6])
        assertEquals(0.toByte(), pcm[7])
    }
}
