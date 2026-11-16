package com.example

import com.example.model.VideoModelProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VideoModelProfileTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `the catalogue is not empty and the ids do not repeat`() {
        val models = VideoModelProfile.MODELS
        assertTrue(models.isNotEmpty())
        assertEquals(models.size, models.map { it.id }.toSet().size)
    }

    @Test
    fun `the default model is part of the catalogue`() {
        assertTrue(VideoModelProfile.MODELS.contains(VideoModelProfile.DEFAULT_VIDEO_MODEL))
    }

    @Test
    fun `every model generates sound together with the picture`() {
        VideoModelProfile.MODELS.forEach { model ->
            assertTrue("${model.id} has no soundtrack", model.hasAudio)
        }
    }

    @Test
    fun `every model has a Hugging Face source to download from`() {
        VideoModelProfile.MODELS.forEach { model ->
            assertTrue(model.hfRepo.isNotBlank())
            assertTrue(model.hfFile.endsWith(".gguf"))
            assertTrue(model.downloadSizeMb > 0)
            assertTrue(model.frameRate > 0)
            assertTrue(model.durationSeconds > 0)
        }
    }

    @Test
    fun `the weights live in the same folder as the chat models`() {
        val root = tempFolder.newFolder("models")
        assertEquals(
            File(root, "${VideoModelProfile.LTX_2_5_DISTILLED.id}.gguf"),
            VideoModelProfile.LTX_2_5_DISTILLED.fileIn(root)
        )
    }

    @Test
    fun `a missing or tiny file is not ready`() {
        val root = tempFolder.newFolder("models")
        assertFalse(VideoModelProfile.LTX_2_5_DISTILLED.isReady(root))

        VideoModelProfile.LTX_2_5_DISTILLED.fileIn(root).writeBytes(ByteArray(64))
        assertFalse(VideoModelProfile.LTX_2_5_DISTILLED.isReady(root))

        VideoModelProfile.LTX_2_5_DISTILLED.fileIn(root).writeBytes(ByteArray(2 * 1024 * 1024))
        assertTrue(VideoModelProfile.LTX_2_5_DISTILLED.isReady(root))
    }
}