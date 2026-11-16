package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import com.example.model.ModelHubTab
import com.example.model.ModelLoadState
import com.example.model.ModelProfile
import com.example.model.SpeechEngineState
import com.example.model.SpeechModelProfile
import com.example.ui.components.ModelsHubSheet
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HubScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `screenshot of the hub`() {
        val models = ModelProfile.DEFAULT_MODELS
        composeTestRule.setContent {
            ModelsHubSheet(
                isOpen = true,
                onDismiss = {},
                models = models,
                currentModel = models.first(),
                loadState = ModelLoadState.UNLOADED,
                downloadStates = emptyMap(),
                sttEnabled = false,
                ttsEnabled = true,
                sttState = SpeechEngineState.NOT_DOWNLOADED,
                ttsState = SpeechEngineState.READY,
                sttError = null,
                ttsError = null,
                ttsVoices = SpeechModelProfile.TEXT_TO_SPEECH_MODELS,
                selectedTtsVoice = SpeechModelProfile.DEFAULT_TTS_VOICE,
                downloadedTtsVoices = setOf(SpeechModelProfile.DEFAULT_TTS_VOICE.id),
                previewingTtsVoiceId = null,
                onToggleStt = {},
                onToggleTts = {},
                onSelectTtsVoice = {},
                onDownloadTtsVoice = {},
                onPreviewTtsVoice = {},
                onDeleteTtsVoice = {},
                onSelectAndLoadModel = {},
                onUnloadModel = {},
                onDownloadModel = {},
                onReinstallModel = {},
                onDeleteModel = {},
                onCancelDownload = {}
            )
        }

        composeTestRule.waitForIdle()
        dump("hub_text")

        selectTab(ModelHubTab.TTS)
        composeTestRule.waitForIdle()
        dump("hub_tts")

        selectTab(ModelHubTab.STT)
        composeTestRule.waitForIdle()
        dump("hub_stt")

        selectTab(ModelHubTab.VIDEO)
        composeTestRule.waitForIdle()
        dump("hub_video")
    }

    private fun selectTab(tab: ModelHubTab) {
        composeTestRule.onNodeWithTag("model_hub_tabs")
            .performScrollToNode(hasTestTag("hub_tab_${tab.name}"))
        composeTestRule.onNodeWithTag("hub_tab_${tab.name}").performClick()
    }

    private fun dump(name: String) {
        val dialog = ShadowDialog.getLatestDialog()
        val view = dialog?.window?.decorView ?: return
        val bitmap = Bitmap.createBitmap(
            view.width.coerceAtLeast(1),
            view.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        view.draw(Canvas(bitmap))
        val out = File("C:/Users/Daniel/AppData/Local/Temp/opencode/$name.png")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("saved ${out.absolutePath} ${bitmap.width}x${bitmap.height}")
    }
}
