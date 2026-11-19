package com.example.horde

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses the two AI Horde model catalogues.
 *
 * This is pure JSON-to-data work so it can be tested without a device. The
 * Horde fronts are free to omit fields, so every value has a safe fallback.
 */
object HordeModelParser {

    /** Only the busiest models are shown; the catalogues hold hundreds. */
    private const val MAX_TEXT_MODELS = 40
    private const val MAX_IMAGE_MODELS = 30

    fun parseTextModels(body: String): List<HordeTextInfo> {
        val root = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        val models = mutableListOf<HordeTextInfo>()
        for (i in 0 until root.length()) {
            val item = root.optJSONObject(i) ?: continue
            val id = item.optString("id").trim()
            if (id.isEmpty()) continue
            val horde = item.optJSONObject("x_horde") ?: JSONObject()
            models.add(
                HordeTextInfo(
                    id = id,
                    contextLength = intOf(item.opt("context_length"), 8192),
                    maxTokens = intOf(
                        item.optJSONObject("top_provider")?.opt("max_completion_tokens"),
                        2048
                    ),
                    workers = maxOf(
                        intOf(horde.opt("workers"), 0),
                        intOf(item.opt("workers"), 0)
                    ),
                    queued = maxOf(
                        intOf(horde.opt("queued"), 0),
                        intOf(item.opt("queued"), 0)
                    ),
                    supportsReasoning = item.optJSONArray("supported_parameters")
                        ?.let { params ->
                            (0 until params.length()).any {
                                params.optString(it).contains("reason", ignoreCase = true)
                            }
                        } ?: false
                )
            )
        }
        val available = models.filter { it.workers > 0 || it.queued > 0 }
        return (available.ifEmpty { models })
            .sortedWith(compareByDescending<HordeTextInfo> { it.workers }.thenBy { it.queued })
            .take(MAX_TEXT_MODELS)
    }

    fun parseImageModels(body: String): List<HordeImageInfo> {
        val root = runCatching { JSONObject(body) }
            .getOrNull()?.optJSONArray("models")
            ?: runCatching { JSONArray(body) }.getOrNull()
            ?: return emptyList()
        val models = mutableListOf<HordeImageInfo>()
        for (i in 0 until root.length()) {
            val item = root.optJSONObject(i) ?: continue
            val name = item.optString("name").trim()
            if (name.isEmpty()) continue
            models.add(
                HordeImageInfo(
                    name = name,
                    workers = maxOf(
                        intOf(item.opt("count"), 0),
                        intOf(item.opt("workers"), 0)
                    ),
                    queued = intOf(item.opt("queued"), 0),
                    eta = intOf(item.opt("eta"), 0)
                )
            )
        }
        val available = models.filter { it.workers > 0 || it.queued > 0 }
        return (available.ifEmpty { models })
            .sortedWith(compareByDescending<HordeImageInfo> { it.workers }.thenBy { it.queued })
            .take(MAX_IMAGE_MODELS)
    }

    /** Extracts the job id returned by `POST /generate/async`. */
    fun parseJobId(body: String): String? {
        val id = runCatching { JSONObject(body) }.getOrNull()?.optString("id").orEmpty()
        return id.takeIf { it.isNotBlank() }
    }

    /** Reads a `POST /generate/check` reply. */
    fun parseCheck(body: String): HordeCheck {
        val obj = runCatching { JSONObject(body) }.getOrNull() ?: return HordeCheck()
        return HordeCheck(
            done = obj.optBoolean("done", false),
            faulted = obj.optBoolean("faulted", false),
            queuePosition = intOf(obj.opt("queue_position"), 0),
            waitTime = intOf(obj.opt("wait_time"), 0)
        )
    }

    /**
     * Pulls the first generated picture out of `GET /generate/status`.
     *
     * Requests set `r2: false`, so the image is a base64 string in `img`. A URL
     * is accepted as well in case a proxy changes the answer.
     */
    fun parseImageResult(body: String): HordeImageResult {
        val obj = runCatching { JSONObject(body) }.getOrNull() ?: return HordeImageResult()
        val generations = obj.optJSONArray("generations") ?: return HordeImageResult()
        val first = generations.optJSONObject(0) ?: return HordeImageResult()
        val faulted = first.optBoolean("faulted", false)
        val img = first.optString("img").trim()
        val url = first.optString("url").trim()
        return HordeImageResult(
            base64 = img.takeIf { it.isNotBlank() && !it.startsWith("http") },
            url = url.takeIf { it.isNotBlank() },
            faulted = faulted || obj.optBoolean("faulted", false)
        )
    }

    private fun intOf(value: Any?, fallback: Int): Int = when (value) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull() ?: fallback
        else -> fallback
    }
}

data class HordeCheck(
    val done: Boolean = false,
    val faulted: Boolean = false,
    val queuePosition: Int = 0,
    val waitTime: Int = 0
)

data class HordeImageResult(
    val base64: String? = null,
    val url: String? = null,
    val faulted: Boolean = false
)
