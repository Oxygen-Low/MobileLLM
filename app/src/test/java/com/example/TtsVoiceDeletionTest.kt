package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.ModelDownloadManager
import com.example.model.SpeechModelProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * A voice that is on the device can be removed again. The weights of the
 * voice go away, but the espeak-ng data that all of the voices share stays,
 * and the app speaks with another voice when the chosen one is deleted.
 */
@RunWith(RobolectricTestRunner::class)
class TtsVoiceDeletionTest {

    private lateinit var context: Context
    private lateinit var downloads: ModelDownloadManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        downloads = ModelDownloadManager(context)
    }

    @Test
    fun `a deleted voice is gone from the device`() {
        val amy = SpeechModelProfile.PIPER_AMY_LOW
        installVoice(amy)

        assertTrue(downloads.deleteSpeechModel(amy.id))

        assertFalse(amy.isReady(downloads.speechDir))
        assertFalse(downloads.getSpeechModelDir(amy.id).exists())
    }

    @Test
    fun `deleting a voice keeps the data that all of the voices share`() {
        val amy = SpeechModelProfile.PIPER_AMY_LOW
        val ryan = secondVoice()
        installVoice(amy)
        installVoice(ryan)

        downloads.deleteSpeechModel(amy.id)

        // The phoneme tables serve every voice, so they have to survive.
        assertTrue(SpeechModelProfile.espeakIsReady(downloads.speechDir))
        assertTrue(ryan.isReady(downloads.speechDir))
    }

    @Test
    fun `the app speaks with another voice when the chosen one is deleted`() {
        val amy = SpeechModelProfile.PIPER_AMY_LOW
        val ryan = secondVoice()

        val replacement = SpeechModelProfile.fallbackVoice(amy, listOf(ryan))

        assertEquals(ryan.id, replacement.id)
    }

    @Test
    fun `a deleted voice never becomes the voice of the app`() {
        val amy = SpeechModelProfile.PIPER_AMY_LOW

        val replacement = SpeechModelProfile.fallbackVoice(amy, listOf(amy))

        assertFalse(replacement.id == amy.id)
    }

    @Test
    fun `a voice of the catalogue takes over when nothing is left`() {
        val amy = SpeechModelProfile.PIPER_AMY_LOW

        val replacement = SpeechModelProfile.fallbackVoice(amy, emptyList())

        assertTrue(replacement.id in SpeechModelProfile.TEXT_TO_SPEECH_MODELS.map { it.id })
        assertFalse(replacement.id == amy.id)
    }

    @Test
    fun `the voice of the app survives when another voice is deleted`() {
        val amy = SpeechModelProfile.PIPER_AMY_LOW
        val ryan = secondVoice()

        val replacement = SpeechModelProfile.fallbackVoice(ryan, listOf(amy))

        assertEquals(amy.id, replacement.id)
    }

    /** A voice of the catalogue that is not the default one. */
    private fun secondVoice(): SpeechModelProfile = SpeechModelProfile.TEXT_TO_SPEECH_MODELS
        .first { it.id != SpeechModelProfile.PIPER_AMY_LOW.id }

    /** Writes the files of a voice, so it looks installed. */
    private fun installVoice(voice: SpeechModelProfile) {
        val dir = downloads.getSpeechModelDir(voice.id)
        dir.mkdirs()
        voice.requiredFiles.forEach { File(dir, it).writeText("weights") }

        val espeak = SpeechModelProfile.espeakDir(downloads.speechDir)
        espeak.mkdirs()
        SpeechModelProfile.ESPEAK_CORE_FILES.forEach { File(espeak, it).writeText("data") }
    }
}
