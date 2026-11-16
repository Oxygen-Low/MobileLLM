package com.example

import com.example.video.GeneratedVideoStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GeneratedVideoStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: GeneratedVideoStore

    @Before
    fun setUp() {
        store = GeneratedVideoStore(tempFolder.newFolder("generated"))
    }

    @Test
    fun `saving writes the clip into the folder of the session`() {
        val clip = store.save("session-a", byteArrayOf(1, 2, 3), "a fox", 384, 384, 2000, true)

        assertTrue(clip.file.isFile)
        assertTrue(clip.file.parentFile!!.name == "session-a")
        assertEquals(GeneratedVideoStore.isSafeId(clip.id), true)
        assertEquals("vid:${clip.id}", clip.token)
        assertTrue(clip.hasAudio)
        assertEquals(2000L, clip.durationMs)
    }

    @Test
    fun `a clip is found by its reference`() {
        val clip = store.save("session-a", byteArrayOf(9), "a fox", 384, 384, 2000, true)

        val file = store.fileFor("session-a", clip.id)
        assertNotNull(file)
        assertEquals(byteArrayOf(9).toList(), file!!.readBytes().toList())
        assertEquals("a fox", store.promptFor("session-a", clip.id))
    }

    @Test
    fun `a reserved file becomes a clip once it is complete`() {
        val pending = store.newFile("session-a")
        assertFalse(pending.file.exists())

        pending.file.writeBytes(byteArrayOf(7, 8))
        val clip = store.complete("session-a", pending, "rain", 384, 384, 1500, true)

        assertEquals(pending.id, clip.id)
        assertEquals(pending.file, clip.file)
        assertEquals("rain", store.promptFor("session-a", clip.id))
        assertEquals(1, store.list("session-a").size)
    }

    @Test
    fun `another session cannot read the clip`() {
        val clip = store.save("session-a", byteArrayOf(9), "a fox", 384, 384, 2000, true)
        assertNull(store.fileFor("session-b", clip.id))
    }

    @Test
    fun `a forged reference is refused`() {
        assertNull(store.fileFor("session-a", "../../secret"))
        assertNull(store.fileFor("../..", "abc"))
    }

    @Test
    fun `deleting a session removes every clip of it`() {
        val clip = store.save("session-a", byteArrayOf(1), "one", 384, 384, 2000, true)
        store.save("session-b", byteArrayOf(2), "two", 384, 384, 2000, true)

        assertTrue(store.deleteSession("session-a"))

        assertNull(store.fileFor("session-a", clip.id))
        assertFalse(store.sessionDir("session-a").exists())
        assertTrue(store.sessionDir("session-b").isDirectory)
    }

    @Test
    fun `listing returns the clips of the session only`() {
        val first = store.save("session-a", byteArrayOf(1), "one", 384, 384, 2000, true)
        val second = store.save("session-a", byteArrayOf(2), "two", 384, 384, 2000, true)
        store.save("session-b", byteArrayOf(3), "three", 384, 384, 2000, true)

        val ids = store.list("session-a").map { it.id }.toSet()
        assertEquals(setOf(first.id, second.id), ids)
    }

    @Test
    fun `a data uri carries the bytes of the clip`() {
        val clip = store.save("session-a", byteArrayOf(0x41, 0x42), "a fox", 384, 384, 2000, true)

        val uri = store.dataUri("session-a", clip.id)
        assertNotNull(uri)
        assertTrue(uri!!.startsWith("data:video/mp4;base64,"))
        assertEquals("QUI=", uri.removePrefix("data:video/mp4;base64,"))
    }

    @Test
    fun `the root the clips live under is created up front`() {
        val root = File(tempFolder.root, "new-root")
        GeneratedVideoStore(root)
        assertTrue(root.isDirectory)
    }
}