package com.example

import com.example.horde.HordeChatEvent
import com.example.horde.HordeModelParser
import com.example.horde.HordeSseParser
import com.example.horde.HordeSseParser.HordeMessage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Horde answers are parsed without a device or a network connection. */
@RunWith(RobolectricTestRunner::class)
class HordeParsingTest {

    @Test
    fun `text models are read from the OpenAI compatible catalogue`() {
        val body = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("id", "Qwen2.5-72B-Instruct")
                    put("context_length", 32768)
                    put("top_provider", JSONObject().put("max_completion_tokens", 4096))
                    put("supported_parameters", JSONArray().put("reasoning_effort"))
                    put("x_horde", JSONObject().put("workers", 3).put("queued", 2))
                }
            )
            put(
                JSONObject().apply {
                    put("id", "offline-model")
                    put("x_horde", JSONObject().put("workers", 0).put("queued", 0))
                }
            )
        }.toString()

        val models = HordeModelParser.parseTextModels(body)
        assertEquals(1, models.size)
        assertEquals("Qwen2.5-72B-Instruct", models[0].id)
        assertEquals(32768, models[0].contextLength)
        assertEquals(4096, models[0].maxTokens)
        assertEquals(3, models[0].workers)
        assertTrue(models[0].supportsReasoning)
    }

    @Test
    fun `image models are read from the native Horde status`() {
        val body = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("name", "FLUX.1 [schnell]")
                    put("count", 2)
                    put("queued", 4)
                    put("eta", 35)
                }
            )
        }.toString()

        val models = HordeModelParser.parseImageModels(body)
        assertEquals(1, models.size)
        assertEquals("FLUX.1 [schnell]", models[0].name)
        assertEquals(2, models[0].workers)
        assertEquals(4, models[0].queued)
    }

    @Test
    fun `a malformed catalogue is ignored`() {
        assertTrue(HordeModelParser.parseTextModels("not json").isEmpty())
        assertTrue(HordeModelParser.parseImageModels("{}").isEmpty())
        assertNull(HordeModelParser.parseJobId("{}"))
        assertFalse(HordeModelParser.parseCheck("{}").done)
    }

    @Test
    fun `stream lines yield reasoning content and finish reason`() {
        val reasoning = JSONObject().apply {
            put(
                "choices",
                JSONArray().put(
                    JSONObject().put(
                        "delta",
                        JSONObject().put("reasoning_content", "thinking")
                    )
                )
            )
        }
        val text = JSONObject().apply {
            put(
                "choices",
                JSONArray().put(
                    JSONObject()
                        .put("delta", JSONObject().put("content", "Hello"))
                        .put("finish_reason", "length")
                )
            )
        }
        val usage = JSONObject().put(
            "usage",
            JSONObject().put("total_tokens", 128)
        )

        val events = HordeSseParser.parseLine("data: $reasoning") +
            HordeSseParser.parseLine("data: $text") +
            HordeSseParser.parseLine("data: $usage") +
            HordeSseParser.parseLine("data: [DONE]") +
            HordeSseParser.parseLine(": keepalive")

        assertEquals(5, events.size)
        assertEquals("thinking", (events[0] as HordeChatEvent.Reasoning).text)
        assertEquals("Hello", (events[1] as HordeChatEvent.Content).text)
        assertEquals("length", (events[2] as HordeChatEvent.Finish).reason)
        assertEquals(128, (events[3] as HordeChatEvent.Usage).totalTokens)
        assertTrue(events[4] is HordeChatEvent.Finish)
    }

    @Test
    fun `the chat request carries the model and every message`() {
        val json = JSONObject(
            HordeSseParser.buildChatRequest(
                model = "test-model",
                messages = listOf(
                    HordeMessage("system", "rules"),
                    HordeMessage("user", "hello")
                ),
                maxTokens = 512,
                temperature = 0.6
            )
        )
        assertEquals("test-model", json.getString("model"))
        assertEquals(true, json.getBoolean("stream"))
        assertEquals(512, json.getInt("max_tokens"))
        assertEquals(2, json.getJSONArray("messages").length())
    }
}
