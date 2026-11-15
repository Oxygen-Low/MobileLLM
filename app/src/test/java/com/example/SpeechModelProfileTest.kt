package com.example

import com.example.model.SpeechModelProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * The speech models are directories with a handful of files in them. The app
 * only loads an engine when every one of those files is there, so the check
 * decides whether a model is used at all.
 */
class SpeechModelProfileTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `a model without its files is not ready`() {
        val root = folder.newFolder("speech")

        assertFalse(SpeechModelProfile.WHISPER_TINY.isReady(root))
    }

    @Test
    fun `a model with all of its files is ready`() {
        val root = folder.newFolder("speech")
        val model = SpeechModelProfile.WHISPER_TINY
        val dir = model.modelDir(root)
        dir.mkdirs()
        model.requiredFiles.forEach { File(dir, it).writeText("weights") }

        assertTrue(model.isReady(root))
    }

    @Test
    fun `one missing file is enough to hold the model back`() {
        val root = folder.newFolder("speech")
        val model = SpeechModelProfile.WHISPER_TINY
        val dir = model.modelDir(root)
        dir.mkdirs()
        model.requiredFiles.dropLast(1).forEach { File(dir, it).writeText("weights") }

        assertFalse(model.isReady(root))
    }

    @Test
    fun `a voice without the shared espeak data is not ready`() {
        val root = folder.newFolder("speech")
        val model = SpeechModelProfile.PIPER_AMY_LOW
        val dir = model.modelDir(root)
        dir.mkdirs()
        File(dir, "en_US-amy-low.onnx").writeText("weights")
        File(dir, "tokens.txt").writeText("tokens")

        assertFalse(model.isReady(root))

        writeEspeakData(root)
        assertTrue(model.isReady(root))
    }

    @Test
    fun `a voice shares the espeak data with the other voices`() {
        val root = folder.newFolder("speech")
        val amy = SpeechModelProfile.PIPER_AMY_LOW
        val ryan = SpeechModelProfile.TEXT_TO_SPEECH_MODELS
            .first { it.id != amy.id && it.locale == "en_US" }

        listOf(amy, ryan).forEach { model ->
            val dir = model.modelDir(root)
            dir.mkdirs()
            File(dir, model.voiceFile!!).writeText("weights")
            File(dir, "tokens.txt").writeText("tokens")
        }

        // Neither voice is ready before the shared data is there.
        assertFalse(amy.isReady(root))
        assertFalse(ryan.isReady(root))

        writeEspeakData(root)

        // One copy of the data is enough for every voice.
        assertTrue(amy.isReady(root))
        assertTrue(ryan.isReady(root))
        assertTrue(SpeechModelProfile.espeakDir(root).isDirectory)
    }

    /** Writes the tables of the espeak-ng data that every voice needs. */
    private fun writeEspeakData(root: File) {
        val espeak = SpeechModelProfile.espeakDir(root)
        espeak.mkdirs()
        SpeechModelProfile.ESPEAK_CORE_FILES.forEach { File(espeak, it).writeText("data") }
    }
}
