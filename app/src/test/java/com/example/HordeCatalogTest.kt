package com.example

import com.example.horde.HordeCatalog
import com.example.horde.HordeImageInfo
import com.example.horde.HordeTextInfo
import com.example.model.HORDE_ID_PREFIX
import com.example.model.ModelBackend
import com.example.model.ModelProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A Horde model is a normal profile that needs no downloaded weights. */
class HordeCatalogTest {

    @Test
    fun `a text model gets a remote profile that is ready to load`() {
        val profile = HordeCatalog.textProfile(
            HordeTextInfo("model-a", 8192, 1024, 2, 1, false)
        )
        assertEquals("${HORDE_ID_PREFIX}model-a", profile.id)
        assertEquals("model-a", profile.remoteModelId)
        assertEquals(ModelBackend.HORDE, profile.backend)
        assertTrue(profile.isReadyToLoad)

        val restored = HordeCatalog.rememberedTextProfile(profile.id)
        assertEquals(profile.id, restored.id)
        assertEquals("model-a", restored.remoteModelId)
        assertTrue(restored.isReadyToLoad)
    }

    @Test
    fun `an image model gets a remote profile that needs no weights`() {
        val profile = HordeCatalog.imageProfile(HordeImageInfo("model-b", 4, 0, 10))
        assertEquals("${HORDE_ID_PREFIX}model-b", profile.id)
        assertEquals(ModelBackend.HORDE, profile.backend)
        assertTrue(profile.isReady(java.io.File("unused")))
        assertEquals("model-b", HordeCatalog.rememberedImageProfile(profile.id).remoteModelId)
    }

    @Test
    fun `local profiles stay local`() {
        assertEquals(ModelBackend.LOCAL, ModelProfile.QWEN3_0_6B.backend)
        assertEquals(null, ModelProfile.QWEN3_0_6B.remoteModelId)
    }
}
