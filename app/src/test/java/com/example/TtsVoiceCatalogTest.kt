package com.example

import com.example.model.SpeechModelKind
import com.example.model.SpeechModelProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Piper voices are downloaded by name, so every one of them has to point
 * at a release archive that actually exists and name its weights the same way
 * the archive does.
 */
class TtsVoiceCatalogTest {

    @Test
    fun `every voice is a text to speech model`() {
        SpeechModelProfile.TEXT_TO_SPEECH_MODELS.forEach { voice ->
            assertEquals(SpeechModelKind.TEXT_TO_SPEECH, voice.kind)
        }
    }

    @Test
    fun `every voice has its own id`() {
        val ids = SpeechModelProfile.TEXT_TO_SPEECH_MODELS.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `every voice has its own archive`() {
        val urls = SpeechModelProfile.TEXT_TO_SPEECH_MODELS.map { it.archiveUrl }
        assertEquals(urls.size, urls.toSet().size)
    }

    @Test
    fun `every voice points at an int8 release archive`() {
        SpeechModelProfile.TEXT_TO_SPEECH_MODELS.forEach { voice ->
            assertTrue(
                "${voice.name} does not point at an int8 archive",
                voice.archiveUrl.contains("-int8.tar.bz2")
            )
            assertTrue(
                "${voice.name} does not point at the tts-models release",
                voice.archiveUrl.contains("/releases/download/tts-models/")
            )
        }
    }

    @Test
    fun `every voice names its weights after the archive`() {
        SpeechModelProfile.TEXT_TO_SPEECH_MODELS.forEach { voice ->
            val weights = voice.voiceFile
            assertNotNull("${voice.name} has no weights file", weights)
            assertTrue(
                "${voice.name} does not name its weights after the locale",
                weights!!.startsWith("${voice.locale}-")
            )
            assertTrue(
                "${voice.name} does not name its weights after the quality",
                weights.endsWith("-${voice.quality}.onnx")
            )
            assertTrue(
                "${voice.name} does not ship its weights",
                voice.requiredFiles.contains(weights)
            )
        }
    }

    @Test
    fun `every voice needs the espeak data and the token table`() {
        SpeechModelProfile.TEXT_TO_SPEECH_MODELS.forEach { voice ->
            assertTrue(
                "${voice.name} does not need the token table",
                voice.requiredFiles.contains("tokens.txt")
            )
            assertTrue(
                "${voice.name} does not need the espeak data",
                voice.needsEspeakData
            )
        }
    }

    @Test
    fun `every voice has a sample sentence to preview`() {
        SpeechModelProfile.TEXT_TO_SPEECH_MODELS.forEach { voice ->
            assertTrue("${voice.name} has no preview text", voice.previewText.isNotBlank())
        }
    }

    @Test
    fun `the default voice is part of the catalogue`() {
        assertTrue(
            SpeechModelProfile.TEXT_TO_SPEECH_MODELS.any {
                it.id == SpeechModelProfile.DEFAULT_TTS_VOICE.id
            }
        )
    }

    @Test
    fun `every voice knows the exact size of its archive`() {
        SpeechModelProfile.ALL_MODELS.forEach { model ->
            assertTrue(
                "${model.name} does not know the size of its archive",
                model.archiveSizeBytes > 0
            )
            // The size shown to the user has to match the real one.
            val megabytes = (model.archiveSizeBytes + 1024L * 1024L - 1) / (1024L * 1024L)
            assertEquals(
                "${model.name} shows the wrong download size",
                megabytes.toInt(),
                model.downloadSizeMb
            )
        }
    }

    @Test
    fun `every voice has its own archive size`() {
        // The download tells a complete file from a truncated one by size, so
        // the sizes have to be the real ones. One rounded guess reused for
        // every voice would let a short download pass as finished.
        val sizes = SpeechModelProfile.TEXT_TO_SPEECH_MODELS.map { it.archiveSizeBytes }
        assertTrue(
            "all voices would report the same archive size, which is not real",
            sizes.toSet().size > 1
        )
    }
}
