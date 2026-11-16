package com.example

import com.example.image.GeneratedImageStore
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

class GeneratedImageStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: GeneratedImageStore

    @Before
    fun setUp() {
        store = GeneratedImageStore(tempFolder.newFolder("generated"))
    }

    @Test
    fun `saving writes the picture into the folder of the session`() {
        val image = store.save("session-a", byteArrayOf(1, 2, 3), "a fox", 512, 512)

        assertTrue(image.file.isFile)
        assertTrue(image.file.parentFile!!.name == "session-a")
        assertEquals(GeneratedImageStore.isSafeId(image.id), true)
        assertEquals("gen:${image.id}", image.token)
    }

    @Test
    fun `a picture is found by its reference`() {
        val image = store.save("session-a", byteArrayOf(9), "a fox", 512, 512)

        val file = store.fileFor("session-a", image.id)
        assertNotNull(file)
        assertEquals(byteArrayOf(9).toList(), file!!.readBytes().toList())
        assertEquals("a fox", store.promptFor("session-a", image.id))
    }

    @Test
    fun `another session cannot read the picture`() {
        val image = store.save("session-a", byteArrayOf(9), "a fox", 512, 512)
        assertNull(store.fileFor("session-b", image.id))
    }

    @Test
    fun `a forged reference is refused`() {
        assertNull(store.fileFor("session-a", "../../secret"))
        assertNull(store.fileFor("../..", "abc"))
    }

    @Test
    fun `deleting a session removes every picture of it`() {
        val image = store.save("session-a", byteArrayOf(1), "one", 512, 512)
        store.save("session-b", byteArrayOf(2), "two", 512, 512)

        assertTrue(store.deleteSession("session-a"))

        assertNull(store.fileFor("session-a", image.id))
        assertTrue(store.sessionDir("session-a").exists().not())
        assertTrue(store.sessionDir("session-b").isDirectory)
    }

    @Test
    fun `listing returns the pictures of the session only`() {
        val first = store.save("session-a", byteArrayOf(1), "one", 512, 512)
        val second = store.save("session-a", byteArrayOf(2), "two", 512, 512)
        store.save("session-b", byteArrayOf(3), "three", 512, 512)

        val ids = store.list("session-a").map { it.id }.toSet()
        assertEquals(setOf(first.id, second.id), ids)
    }

    @Test
    fun `a data uri carries the bytes of the picture`() {
        val image = store.save("session-a", byteArrayOf(0x41, 0x42), "a fox", 512, 512)

        val uri = store.dataUri("session-a", image.id)
        assertNotNull(uri)
        assertTrue(uri!!.startsWith("data:image/png;base64,"))
        assertEquals("QUI=", uri.removePrefix("data:image/png;base64,"))
    }

    @Test
    fun `the root the pictures live under is created up front`() {
        val root = File(tempFolder.root, "new-root")
        GeneratedImageStore(root)
        assertTrue(root.isDirectory)
    }
}
