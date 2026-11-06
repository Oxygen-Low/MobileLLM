package com.example

import com.example.model.GgufParser
import com.example.model.ModelProfile
import com.example.model.SafeTensorsParser
import com.example.tools.MCPToolExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ExampleRobolectricTest {

    @Test
    fun testGgufParserInvalidHeader() {
        val dummyBytes = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        val info = GgufParser.parse(ByteArrayInputStream(dummyBytes))
        assertFalse(info.isValid)
    }

    @Test
    fun testGgufParserValidHeaderMagic() {
        val buffer = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x46554747) // "GGUF" magic
        buffer.putInt(3)          // version 3
        buffer.putLong(128L)      // tensor count
        buffer.putLong(0L)        // metadata kv count
        val info = GgufParser.parse(ByteArrayInputStream(buffer.array()))
        assertTrue(info.isValid)
        assertEquals(3, info.version)
        assertEquals(128L, info.tensorCount)
    }

    @Test
    fun testSafeTensorsParserValidHeader() {
        val json = """{"__metadata__":{"format":"pt"},"weight":{"dtype":"F16","shape":[10,20],"data_offsets":[0,400]}}"""
        val jsonBytes = json.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(8 + jsonBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putLong(jsonBytes.size.toLong())
        buffer.put(jsonBytes)

        val info = SafeTensorsParser.parse(ByteArrayInputStream(buffer.array()))
        assertTrue(info.isValid)
        assertEquals(1, info.tensorCount)
        assertEquals("pt", info.metadata["format"])
    }

    @Test
    fun testDefaultModelProfiles() {
        assertEquals("deepsmollm3", ModelProfile.DEEP_SMOLLM3.id)
        assertEquals("3B", ModelProfile.DEEP_SMOLLM3.parameters)
        assertTrue(ModelProfile.DEEP_SMOLLM3.supportsReasoning)
        assertEquals("smollm3", ModelProfile.SMOLLM3.id)
        assertEquals("3B", ModelProfile.SMOLLM3.parameters)
        assertFalse(ModelProfile.SMOLLM3.supportsReasoning)
        assertEquals(2, ModelProfile.DEFAULT_MODELS.size)
    }

    @Test
    fun testMcpToolDateFormatting() {
        val executor = MCPToolExecutor()
        val date = executor.getCurrentFormattedDate()
        assertTrue(date.isNotEmpty())
    }
}
