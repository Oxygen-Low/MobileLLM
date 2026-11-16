package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.ModelPreferences
import com.example.model.ModelProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The model the user loaded has to survive a restart of the app, otherwise the
 * next launch loads the default model instead of the one that was in use.
 */
@RunWith(RobolectricTestRunner::class)
class ModelPreferenceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ModelPreferences(context).selectedModelId = ""
    }

    @Test
    fun `no model is remembered before the first launch`() {
        assertEquals("", ModelPreferences(context).selectedModelId)
    }

    @Test
    fun `the loaded model is remembered`() {
        val model = ModelProfile.DEFAULT_MODELS.first { it.id != ModelProfile.QWEN3_0_6B.id }

        ModelPreferences(context).selectedModelId = model.id

        // A fresh set of preferences stands for the next launch of the app.
        assertEquals(model.id, ModelPreferences(context).selectedModelId)
    }

    @Test
    fun `every model of the catalogue can be remembered`() {
        val preferences = ModelPreferences(context)

        ModelProfile.DEFAULT_MODELS.forEach { model ->
            preferences.selectedModelId = model.id
            assertEquals(model.id, ModelPreferences(context).selectedModelId)
        }
    }

    @Test
    fun `the remembered ids match the catalogue`() {
        assertTrue(
            "the catalogue should hold the models that can be remembered",
            ModelProfile.DEFAULT_MODELS.isNotEmpty()
        )
        assertTrue(
            "the default model should be part of the catalogue",
            ModelProfile.DEFAULT_MODELS.any { it.id == ModelProfile.QWEN3_0_6B.id }
        )
    }
}
