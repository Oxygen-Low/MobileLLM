package com.example

import com.example.model.DownloadProgressInfo
import com.example.model.ModelDownloadState
import com.example.ui.shouldKeepScreenOn
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screen is only held on for a reply that is being written or a model that
 * is being downloaded, never for an idle chat.
 */
class KeepScreenOnTest {

    private fun download(state: ModelDownloadState) = DownloadProgressInfo(
        modelId = "model",
        progress = 0f,
        downloadedMb = 0f,
        totalMb = 0f,
        speedMbps = 0f,
        state = state
    )

    @Test
    fun `an idle chat lets the screen time out`() {
        assertFalse(shouldKeepScreenOn(generating = false, downloads = emptyList()))
    }

    @Test
    fun `writing a reply keeps the screen on`() {
        assertTrue(shouldKeepScreenOn(generating = true, downloads = emptyList()))
    }

    @Test
    fun `a running model download keeps the screen on`() {
        assertTrue(
            shouldKeepScreenOn(
                generating = false,
                downloads = listOf(download(ModelDownloadState.DOWNLOADING))
            )
        )
    }

    @Test
    fun `finished and failed downloads let the screen time out`() {
        val states = listOf(
            ModelDownloadState.NOT_DOWNLOADED,
            ModelDownloadState.DOWNLOADED,
            ModelDownloadState.ERROR
        )

        states.forEach { state ->
            assertFalse(
                "a download in $state must not hold the screen on",
                shouldKeepScreenOn(generating = false, downloads = listOf(download(state)))
            )
        }
    }

    @Test
    fun `one running download is enough next to downloads that are not running`() {
        assertTrue(
            shouldKeepScreenOn(
                generating = false,
                downloads = listOf(
                    download(ModelDownloadState.DOWNLOADED),
                    download(ModelDownloadState.DOWNLOADING),
                    download(ModelDownloadState.ERROR)
                )
            )
        )
    }
}
