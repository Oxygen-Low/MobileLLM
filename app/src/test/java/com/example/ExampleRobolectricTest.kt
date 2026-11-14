package com.example

import com.example.model.GgufParser
import com.example.model.Llama2Engine
import com.example.model.LlamaTokenizer
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
        assertEquals("Q4_K_M", ModelProfile.DEEP_SMOLLM3.quantization)
        assertEquals("DeepSmolLM3-3B.Q4_K_M.gguf", ModelProfile.DEEP_SMOLLM3.modelScopeFile)
        assertEquals("DeepSmolLM3-3B.Q4_K_M.gguf", ModelProfile.DEEP_SMOLLM3.hfFile)
        assertTrue(ModelProfile.DEEP_SMOLLM3.supportsReasoning)
        assertEquals("Oxygen-Low/DeepSmolLM3-3B", ModelProfile.DEEP_SMOLLM3.modelScopeRepo)
        assertEquals("qwen3-0.6b", ModelProfile.QWEN3_0_6B.id)
        assertEquals("Q8_0", ModelProfile.QWEN3_0_6B.quantization)
        assertEquals("Qwen3-0.6B-Q8_0.gguf", ModelProfile.QWEN3_0_6B.hfFile)
        assertEquals("Qwen/Qwen3-0.6B-GGUF", ModelProfile.QWEN3_0_6B.hfRepo)
        assertTrue(ModelProfile.QWEN3_0_6B.supportsReasoning)
        assertEquals(2, ModelProfile.DEFAULT_MODELS.size)
        assertEquals("qwen3-0.6b", ModelProfile.DEFAULT_MODELS.first().id)
    }

    @Test
    fun testLlamaTokenizerEncoding() {
        val tokenizer = LlamaTokenizer()
        val tokens = tokenizer.encode("Hello world")
        assertTrue(tokens.isNotEmpty())
        assertEquals(1, tokens[0]) // BOS token
    }

    @Test
    fun testMcpToolDateFormatting() {
        val executor = MCPToolExecutor()
        val date = executor.getCurrentFormattedDate()
        assertTrue(date.isNotEmpty())
    }
}
