package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.example.model.ModelHubTab
import com.example.model.ImageEngineState
import com.example.model.ImageModelProfile
import com.example.model.ModelLoadState
import com.example.model.ModelProfile
import com.example.model.SpeechEngineState
import com.example.model.SpeechModelProfile
import com.example.model.VideoEngineState
import com.example.model.VideoModelProfile
import com.example.ui.components.ModelsHubSheet
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Every kind of model has its own tab in the Model Hub.
 */
@RunWith(RobolectricTestRunner::class)
class ModelsHubSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `the text tab lists the chat models`() {
        showHub()

        scrollTo("model_card_${ModelProfile.QWEN3_0_6B.id}")
        composeTestRule.onNodeWithTag("model_card_${ModelProfile.QWEN3_0_6B.id}").assertIsDisplayed()
        scrollTo("model_card_${ModelProfile.LFM2_5_1_2B_THINKING.id}")
        composeTestRule.onNodeWithTag("model_card_${ModelProfile.LFM2_5_1_2B_THINKING.id}").assertIsDisplayed()
    }

    @Test
    fun `the text tab holds no speech model`() {
        showHub()

        composeTestRule.onNodeWithTag("speech_card_${SpeechModelProfile.WHISPER_TINY.id}")
            .assertDoesNotExist()
    }

    @Test
    fun `the tabs without models yet show a placeholder`() {
        showHub()

        selectTab(ModelHubTab.MUSIC)
        composeTestRule.onNodeWithTag("hub_work_in_progress").assertIsDisplayed()
        composeTestRule.onNodeWithText("Work In Progress!").assertIsDisplayed()
    }

    @Test
    fun `the image tab holds the image models`() {
        showHub()

        selectTab(ModelHubTab.IMAGE)
        scrollTo("image_model_${ImageModelProfile.SD_TURBO.id}")
        composeTestRule.onNodeWithTag("image_model_${ImageModelProfile.SD_TURBO.id}").assertIsDisplayed()
        composeTestRule.onNodeWithTag("image_model_${ImageModelProfile.SD_V1_5.id}").assertIsDisplayed()
        // Image generation is implemented, so the tab is not a placeholder.
        composeTestRule.onNodeWithTag("hub_work_in_progress").assertDoesNotExist()
    }

    @Test
    fun `the video tab holds the video models and marks the ones with sound`() {
        showHub()

        selectTab(ModelHubTab.VIDEO)
        val model = VideoModelProfile.DEFAULT_VIDEO_MODEL
        scrollTo("video_model_${model.id}")
        composeTestRule.onNodeWithTag("video_model_${model.id}")
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(
            "video_sound_${model.id}",
            useUnmergedTree = true
        ).assertIsDisplayed()
        // Video generation is implemented, so the tab is not a placeholder.
        composeTestRule.onNodeWithTag("hub_work_in_progress").assertDoesNotExist()
    }

    @Test
    fun `the speech tabs hold the speech models`() {
        showHub()

        selectTab(ModelHubTab.STT)
        scrollTo("speech_card_${SpeechModelProfile.WHISPER_TINY.id}")
        composeTestRule.onNodeWithTag("speech_card_${SpeechModelProfile.WHISPER_TINY.id}").assertIsDisplayed()

        selectTab(ModelHubTab.TTS)
        scrollTo("tts_voice_list")
        composeTestRule.onNodeWithTag("tts_voice_list").assertIsDisplayed()
        // The voices are part of the tab itself now: no toggle is needed to
        // see them.
        composeTestRule.onNodeWithTag("tts_voice_picker_toggle").assertDoesNotExist()
    }

    /** Picks a tab of the hub, scrolling the tab row to it first. */
    private fun selectTab(tab: ModelHubTab) {
        composeTestRule.onNodeWithTag("model_hub_tabs")
            .performScrollToNode(hasTestTag("hub_tab_${tab.name}"))
        composeTestRule.onNodeWithTag("hub_tab_${tab.name}").performClick()
    }

    /** Brings the row with the given tag into view. */
    private fun scrollTo(tag: String) {
        composeTestRule.onNodeWithTag("model_hub_list")
            .performScrollToNode(hasTestTag(tag))
    }

    /** Opens the hub with the models the app ships with. */
    private fun showHub() {
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
                ttsEnabled = false,
                sttState = SpeechEngineState.DISABLED,
                ttsState = SpeechEngineState.DISABLED,
                sttError = null,
                ttsError = null,
                ttsVoices = SpeechModelProfile.TEXT_TO_SPEECH_MODELS,
                selectedTtsVoice = SpeechModelProfile.DEFAULT_TTS_VOICE,
                downloadedTtsVoices = emptySet(),
                previewingTtsVoiceId = null,
                imageModels = ImageModelProfile.MODELS,
                selectedImageModel = ImageModelProfile.DEFAULT_IMAGE_MODEL,
                downloadedImageModels = emptySet(),
                imageState = ImageEngineState.NOT_DOWNLOADED,
                imageError = null,
                videoModels = VideoModelProfile.MODELS,
                selectedVideoModel = VideoModelProfile.DEFAULT_VIDEO_MODEL,
                downloadedVideoModels = emptySet(),
                videoState = VideoEngineState.NOT_DOWNLOADED,
                videoError = null,
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
    }
}
