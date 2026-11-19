package com.example.horde

import org.json.JSONObject

/**
 * A decoded chunk of a Horde chat completion stream.
 *
 * Reasoning is kept apart from the answer: the OpenAI-compatible Horde front
 * sends it in `reasoning_content`, so there is no need to guess where thinking
 * ends and the reply begins.
 */
sealed class HordeChatEvent {
    data class Reasoning(val text: String) : HordeChatEvent()
    data class Content(val text: String) : HordeChatEvent()
    data class Finish(val reason: String?) : HordeChatEvent()
    data class Usage(val totalTokens: Int) : HordeChatEvent()
}

/** Pure parser for the server-sent events of `POST /chat/completions`. */
object HordeSseParser {

    const val DONE = "[DONE]"

    /** Parses one raw line, returning null for comments and empty lines. */
    fun parseLine(line: String): List<HordeChatEvent> {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(":")) return emptyList()
        val data = when {
            trimmed.startsWith("data:") -> trimmed.removePrefix("data:").trim()
            trimmed.startsWith("{") -> trimmed
            else -> return emptyList()
        }
        if (data == DONE) return listOf(HordeChatEvent.Finish("stop"))

        val root = runCatching { JSONObject(data) }.getOrNull() ?: return emptyList()
        val events = mutableListOf<HordeChatEvent>()

        root.optJSONObject("usage")?.let { usage ->
            val total = usage.optInt("total_tokens", 0)
            if (total > 0) events.add(HordeChatEvent.Usage(total))
        }

        val choices = root.optJSONArray("choices") ?: return events
        for (i in 0 until choices.length()) {
            val choice = choices.optJSONObject(i) ?: continue
            val delta = choice.optJSONObject("delta")
            val message = choice.optJSONObject("message")

            val reasoning = delta?.optString("reasoning_content").orEmpty()
                .takeIf { it.isNotEmpty() }
                ?: delta?.optString("reasoning").orEmpty().takeIf { it.isNotEmpty() }
                ?: message?.optString("reasoning_content").orEmpty().takeIf { it.isNotEmpty() }
            if (!reasoning.isNullOrEmpty()) events.add(HordeChatEvent.Reasoning(reasoning))

            val content = delta?.optString("content").orEmpty()
                .takeIf { it.isNotEmpty() }
                ?: message?.optString("content").orEmpty().takeIf { it.isNotEmpty() }
            if (!content.isNullOrEmpty()) events.add(HordeChatEvent.Content(content))

            val finish = choice.optString("finish_reason").takeIf { it.isNotBlank() }
            if (finish != null) events.add(HordeChatEvent.Finish(finish))
        }
        return events
    }

    /** Builds the JSON body of a chat request. */
    fun buildChatRequest(
        model: String,
        messages: List<HordeMessage>,
        maxTokens: Int,
        temperature: Double
    ): String = JSONObject().apply {
        put("model", model)
        put("stream", true)
        put("temperature", temperature)
        put("max_tokens", maxTokens)
        put("stream_options", JSONObject().put("include_usage", true))
        put("messages", org.json.JSONArray().apply {
            for (message in messages) {
                put(JSONObject().apply {
                    put("role", message.role)
                    put("content", message.content)
                })
            }
        })
    }.toString()

    data class HordeMessage(val role: String, val content: String)
}
