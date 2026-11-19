package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.example.model.ModelHubTab
import com.example.model.SpeechModelKind
import com.example.ui.components.ModelHubTabRow
import com.example.ui.components.WorkInProgressPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Model Hub is split into one tab per kind of model.
 */
@RunWith(RobolectricTestRunner::class)
class ModelHubTabTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `the hub has a tab for every kind of model`() {
        assertEquals(
            listOf("Text", "Image", "AI Horde", "Music", "Video", "TTS", "STT"),
            ModelHubTab.entries.map { it.title }
        )
    }

    @Test
    fun `the tabs without models yet say so`() {
        assertFalse(ModelHubTab.IMAGE.isWorkInProgress)
        assertFalse(ModelHubTab.HORDE.isWorkInProgress)
        assertTrue(ModelHubTab.MUSIC.isWorkInProgress)
        assertFalse(ModelHubTab.VIDEO.isWorkInProgress)
        assertFalse(ModelHubTab.TEXT.isWorkInProgress)
        assertFalse(ModelHubTab.TTS.isWorkInProgress)
        assertFalse(ModelHubTab.STT.isWorkInProgress)
    }

    @Test
    fun `a speech model belongs to the tab of its kind`() {
        assertEquals(ModelHubTab.STT, ModelHubTab.forSpeech(SpeechModelKind.SPEECH_TO_TEXT))
        assertEquals(ModelHubTab.TTS, ModelHubTab.forSpeech(SpeechModelKind.TEXT_TO_SPEECH))
    }

    @Test
    fun `every tab of the hub can be picked`() {
        var selected: ModelHubTab? = null
        composeTestRule.setContent {
            ModelHubTabRow(
                selectedTab = ModelHubTab.TEXT,
                onSelectTab = { selected = it }
            )
        }

        ModelHubTab.entries.forEach { tab ->
            // The tabs are in a row that scrolls, so a tab that is out of
            // sight has to be scrolled to before it can be picked.
            composeTestRule.onNodeWithTag("model_hub_tabs")
                .performScrollToNode(hasTestTag("hub_tab_${tab.name}"))
            composeTestRule.onNodeWithTag("hub_tab_${tab.name}").performClick()
            composeTestRule.runOnIdle { assertEquals(tab, selected) }
        }
    }

    @Test
    fun `a tab without models yet tells the user it is not ready`() {
        composeTestRule.setContent {
            WorkInProgressPanel(title = "Image")
        }

        composeTestRule.onNodeWithTag("hub_work_in_progress").assertIsDisplayed()
        composeTestRule.onNodeWithText("Work In Progress!").assertIsDisplayed()
        composeTestRule.onNodeWithText("Image").assertIsDisplayed()
    }
}
