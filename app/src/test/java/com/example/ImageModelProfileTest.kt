package com.example

import com.example.model.ImageModelProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ImageModelProfileTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `the catalogue is not empty and the ids do not repeat`() {
        val models = ImageModelProfile.MODELS
        assertTrue(models.isNotEmpty())
        assertEquals(models.size, models.map { it.id }.toSet().size)
    }

    @Test
    fun `the default model is part of the catalogue`() {
        assertTrue(ImageModelProfile.MODELS.contains(ImageModelProfile.DEFAULT_IMAGE_MODEL))
    }

    @Test
    fun `every model has a Hugging Face source to download from`() {
        ImageModelProfile.MODELS.forEach { model ->
            assertTrue(model.hfRepo.isNotBlank())
            assertTrue(model.hfFile.endsWith(".gguf"))
            assertTrue(model.downloadSizeMb > 0)
        }
    }

    @Test
    fun `the weights live in the same folder as the chat models`() {
        val root = tempFolder.newFolder("models")
        assertEquals(File(root, "${ImageModelProfile.SD_TURBO.id}.gguf"), ImageModelProfile.SD_TURBO.fileIn(root))
    }

    @Test
    fun `a missing or tiny file is not ready`() {
        val root = tempFolder.newFolder("models")
        assertTrue(ImageModelProfile.SD_TURBO.isReady(root).not())

        ImageModelProfile.SD_TURBO.fileIn(root).writeBytes(ByteArray(64))
        assertTrue(ImageModelProfile.SD_TURBO.isReady(root).not())

        ImageModelProfile.SD_TURBO.fileIn(root).writeBytes(ByteArray(2 * 1024 * 1024))
        assertTrue(ImageModelProfile.SD_TURBO.isReady(root))
    }
}
