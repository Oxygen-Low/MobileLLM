package com.example.horde

import com.example.model.ImageModelProfile
import com.example.model.ModelProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Talks to AI Horde.
 *
 * Text goes through the OpenAI-compatible front (native chat templates and
 * streaming); images go through the native Horde API, which is polled until the
 * job is done. The API key is read from [preferences] at the time of the call,
 * so saving a new key takes effect immediately, and it is never logged.
 */
class HordeClient(private val preferences: HordePreferences) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val imageClient = client.newBuilder()
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Fetches the text models currently available on the Horde. */
    suspend fun fetchTextModels(): List<ModelProfile> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$OAI_BASE/models")
            .header("X-Title", APP_TITLE)
            .header("Authorization", "Bearer ${preferences.apiKey.value}")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("AI Horde could not list text models (${response.code})")
            }
            HordeModelParser.parseTextModels(response.body?.string().orEmpty())
                .map { HordeCatalog.textProfile(it) }
        }
    }

    /** Fetches the image models currently available on the Horde. */
    suspend fun fetchImageModels(): List<ImageModelProfile> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$HORDE_BASE/status/models?type=image")
            .header("apikey", preferences.apiKey.value)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("AI Horde could not list image models (${response.code})")
            }
            HordeModelParser.parseImageModels(response.body?.string().orEmpty())
                .map { HordeCatalog.imageProfile(it) }
        }
    }

    /**
     * Streams one chat completion.
     *
     * The free Horde allowance can be smaller than the requested budget. A 403
     * that asks for more kudos is retried once with a 512-token budget, which
     * is the smallest allowance a worker accepts.
     */
    fun streamChat(
        model: String,
        messages: List<HordeSseParser.HordeMessage>,
        maxTokens: Int = 1024,
        temperature: Double = 0.6
    ): Flow<HordeChatEvent> = flow {
        val budgets = if (maxTokens > MIN_BUDGET) listOf(maxTokens, MIN_BUDGET) else listOf(maxTokens)
        for (budget in budgets) {
            if (!currentCoroutineContext().isActive) return@flow
            val body = HordeSseParser.buildChatRequest(model, messages, budget, temperature)
                .toRequestBody(JSON)
            val request = Request.Builder()
                .url("$OAI_BASE/chat/completions")
                .header("Authorization", "Bearer ${preferences.apiKey.value}")
                .header("X-Title", APP_TITLE)
                .post(body)
                .build()

            val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
            if (response.code == 403) {
                val message = response.body?.string().orEmpty()
                response.close()
                if (message.contains("kudos", ignoreCase = true) && budget != MIN_BUDGET) continue
                throw IOException("AI Horde needs more kudos for this model: ${message.take(160)}")
            }
            if (!response.isSuccessful) {
                val message = response.body?.string().orEmpty().take(160)
                response.close()
                throw IOException("AI Horde chat failed (${response.code}): $message")
            }

            response.use { active ->
                val source = active.body?.source() ?: throw IOException("AI Horde sent an empty stream")
                while (currentCoroutineContext().isActive) {
                    val line = withContext(Dispatchers.IO) { source.readUtf8Line() } ?: break
                    for (event in HordeSseParser.parseLine(line)) emit(event)
                    if (eventIsDone(line)) break
                }
            }
            break
        }
    }

    /**
     * Generates one image and returns its raw bytes.
     *
     * The job is submitted asynchronously and polled for completion. `r2` is
     * false so the result arrives inline as base64 instead of a temporary URL.
     */
    suspend fun generateImage(
        model: String,
        prompt: String,
        width: Int,
        height: Int,
        steps: Int,
        seed: Int?
    ): ByteArray = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("prompt", prompt)
            put("model", model)
            put("width", HordeImageConverter.alignSize(width))
            put("height", HordeImageConverter.alignSize(height))
            put("steps", steps.coerceIn(1, 100))
            put("n", 1)
            put("r2", false)
            if (seed != null) put("seed", seed.toString())
        }.toString().toRequestBody(JSON)

        val submit = Request.Builder()
            .url("$HORDE_BASE/generate/async")
            .header("apikey", preferences.apiKey.value)
            .post(payload)
            .build()
        val jobId = imageClient.newCall(submit).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("AI Horde could not start the picture (${response.code}): ${body.take(160)}")
            }
            HordeModelParser.parseJobId(body)
                ?: throw IOException("AI Horde did not return a job id: ${body.take(160)}")
        }

        var faultMessage = "AI Horde could not draw the picture"
        repeat(MAX_POLLS) { attempt ->
            if (!isActive) throw kotlinx.coroutines.CancellationException()
            if (attempt > 0) delay(POLL_INTERVAL_MS)

            val check = Request.Builder()
                .url("$HORDE_BASE/generate/check/$jobId")
                .header("apikey", preferences.apiKey.value)
                .get()
                .build()
            val state = imageClient.newCall(check).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("AI Horde could not check the picture (${response.code})")
                }
                HordeModelParser.parseCheck(body)
            }
            if (state.faulted) throw IOException(faultMessage)
            if (!state.done) return@repeat

            val status = Request.Builder()
                .url("$HORDE_BASE/generate/status/$jobId")
                .header("apikey", preferences.apiKey.value)
                .get()
                .build()
            val result = imageClient.newCall(status).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("AI Horde could not fetch the picture (${response.code})")
                }
                HordeModelParser.parseImageResult(body)
            }
            if (result.faulted) throw IOException(faultMessage)
            val encoded = result.base64
                ?: throw IOException("AI Horde did not return image data. Try another model.")
            return@withContext HordeImageConverter.decodeBase64(encoded)
        }
        throw IOException("AI Horde did not finish the picture in time. Try again.")
    }

    private fun eventIsDone(line: String): Boolean =
        line.trim().removePrefix("data:").trim() == HordeSseParser.DONE

    private companion object {
        const val HORDE_BASE = "https://aihorde.net/api/v2"
        const val OAI_BASE = "https://oai.aihorde.net/v1"
        const val APP_TITLE = "MobileLLM"
        const val MIN_BUDGET = 512

        const val MAX_POLLS = 90
        const val POLL_INTERVAL_MS = 1000L

        val JSON = "application/json".toMediaType()
    }
}
