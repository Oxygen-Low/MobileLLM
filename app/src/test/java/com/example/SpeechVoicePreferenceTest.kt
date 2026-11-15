package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.SpeechPreferences
import com.example.model.SpeechModelProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The voice the user picked has to survive a restart of the app, otherwise
 * the replies are read with a different voice every time the app is opened.
 */
@RunWith(RobolectricTestRunner::class)
class SpeechVoicePreferenceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SpeechPreferences(context).textToSpeechVoiceId = ""
    }

    @Test
    fun `no voice is picked at first`() {
        assertEquals("", SpeechPreferences(context).textToSpeechVoiceId)
    }

    @Test
    fun `the picked voice is remembered`() {
        val preferences = SpeechPreferences(context)
        val voice = SpeechModelProfile.TEXT_TO_SPEECH_MODELS[2]

        preferences.textToSpeechVoiceId = voice.id

        // A fresh set of preferences stands for the next launch of the app.
        assertEquals(voice.id, SpeechPreferences(context).textToSpeechVoiceId)
    }

    @Test
    fun `every voice of the catalogue can be picked`() {
        val preferences = SpeechPreferences(context)

        SpeechModelProfile.TEXT_TO_SPEECH_MODELS.forEach { voice ->
            preferences.textToSpeechVoiceId = voice.id
            assertEquals(voice.id, SpeechPreferences(context).textToSpeechVoiceId)
        }
    }

    @Test
    fun `the catalogue holds more than one voice`() {
        assertTrue(
            "the user should be able to choose between voices",
            SpeechModelProfile.TEXT_TO_SPEECH_MODELS.size > 1
        )
    }

    @Test
    fun `the voices cover more than one language`() {
        val languages = SpeechModelProfile.TEXT_TO_SPEECH_MODELS.map { it.language }
        assertTrue(
            "the voices should speak more than one language",
            languages.toSet().size > 1
        )
    }
}
