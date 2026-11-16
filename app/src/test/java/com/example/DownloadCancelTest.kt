package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.ModelDownloadManager
import com.example.model.ModelDownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Cancelling a download has to hand the UI back to the "not downloaded" card
 * while the partial file stays on disk for a later resume.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadCancelTest {

    private lateinit var context: Context
    private lateinit var manager: ModelDownloadManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = ModelDownloadManager(context)
    }

    @Test
    fun `a cancelled model download is no longer reported as running`() {
        manager.publishCancelled("qwen3-0.6b")

        val state = manager.downloadStates.value.getValue("qwen3-0.6b")
        assertEquals(ModelDownloadState.NOT_DOWNLOADED, state.state)
    }

    @Test
    fun `a cancelled speech download is no longer reported as running`() {
        manager.publishCancelled("piper-en-us")

        val state = manager.downloadStates.value.getValue("piper-en-us")
        assertEquals(ModelDownloadState.NOT_DOWNLOADED, state.state)
    }

    @Test
    fun `the partial file of a cancelled model download is kept`() {
        val part = File(manager.modelsDir, "qwen3-0.6b.gguf.part")
        part.parentFile?.mkdirs()
        part.writeBytes(ByteArray(2 * 1024 * 1024))

        manager.publishCancelled("qwen3-0.6b")

        assertTrue("the next attempt should resume the partial file", part.isFile)
        val state = manager.downloadStates.value.getValue("qwen3-0.6b")
        assertEquals(2f, state.downloadedMb, 0.1f)
    }

    @Test
    fun `deleting a model after cancelling removes the partial file`() {
        val part = File(manager.modelsDir, "qwen3-0.6b.gguf.part")
        part.parentFile?.mkdirs()
        part.writeBytes(ByteArray(1024))

        manager.publishCancelled("qwen3-0.6b")
        manager.deleteModel("qwen3-0.6b")

        assertFalse(part.exists())
        assertFalse(manager.downloadStates.value.containsKey("qwen3-0.6b"))
    }
}