package com.example.model

import android.util.Log
import com.example.model.internal.LlamaBridge
import com.example.tools.MCPToolExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random

sealed class GenerationEvent {
    data class ThinkingToken(val token: String) : GenerationEvent()
    data class ToolExecuting(val toolName: String, val argument: String) : GenerationEvent()
    data class ToolResult(val toolName: String, val resultSnippet: String, val sourceUrl: String?) : GenerationEvent()
    data class ContentToken(val token: String) : GenerationEvent()
    data class TelemetryUpdate(
        val tokensGenerated: Int,
        val tokensPerSecond: Double,
        val memoryAllocatedMb: Int
    ) : GenerationEvent()
    data class Complete(
        val totalTokens: Int,
        val averageTps: Double,
        val thinkingContent: String,
        val content: String,
        val durationMs: Long
    ) : GenerationEvent()
    data class Error(val message: String) : GenerationEvent()
}

/**
 * On-device inference backed by llama.cpp.
 *
 * There is no simulation here: [loadModel] memory-maps a real GGUF file and
 * [generateStream] runs the actual transformer, streaming sampled tokens.
 */
class ModelEngineManager(
    private val mcpToolExecutor: MCPToolExecutor
) {
    private var handle: Long = 0L
    private val loadMutex = Mutex()
    private val random = Random()

    private val _loadState = MutableStateFlow(ModelLoadState.UNLOADED)
    val loadState: StateFlow<ModelLoadState> = _loadState.asStateFlow()

    private val _currentModel = MutableStateFlow<ModelProfile?>(null)
    val currentModel: StateFlow<ModelProfile?> = _currentModel.asStateFlow()

    private val _allocatedMemoryMb = MutableStateFlow(0)
    val allocatedMemoryMb: StateFlow<Int> = _allocatedMemoryMb.asStateFlow()

    private val _currentTps = MutableStateFlow(0.0)
    val currentTps: StateFlow<Double> = _currentTps.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    suspend fun loadModel(
        profile: ModelProfile,
        modelFile: File? = null
    ) = withContext(Dispatchers.IO) {
        loadMutex.withLock {
            if (handle != 0L &&
                _currentModel.value?.id == profile.id &&
                _loadState.value == ModelLoadState.LOADED
            ) {
                return@withLock
            }

            releaseEngine()
            _loadState.value = ModelLoadState.LOADING
            _lastError.value = null

            try {
                LlamaBridge.nativeBackendInit()

                val file = modelFile ?: File("")
                if (!file.isFile || file.length() < 1024L * 1024L) {
                    throw IllegalStateException(
                        "Model file is missing or incomplete (${file.absolutePath}). Download it again."
                    )
                }

                val contextSize = profile.contextLength.coerceIn(512, MAX_CONTEXT)
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, MAX_THREADS)

                Log.i(TAG, "Loading ${file.name} (${file.length() / (1024 * 1024)} MB), ctx=$contextSize, threads=$threads")

                val newHandle = LlamaBridge.nativeLoadModel(file.absolutePath, contextSize, threads)
                if (newHandle == 0L) {
                    throw IllegalStateException("llama.cpp could not load the GGUF weights")
                }

                handle = newHandle
                _currentModel.value = profile
                _allocatedMemoryMb.value = (file.length() / (1024 * 1024)).toInt()
                _loadState.value = ModelLoadState.LOADED
            } catch (e: CancellationException) {
                _loadState.value = ModelLoadState.UNLOADED
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load model", t)
                releaseEngine()
                _loadState.value = ModelLoadState.ERROR
                _lastError.value = t.message ?: "Failed to load model"
            }
        }
    }

    suspend fun unloadModel() = withContext(Dispatchers.IO) {
        loadMutex.withLock {
            if (handle == 0L && _loadState.value == ModelLoadState.UNLOADED) return@withLock
            _loadState.value = ModelLoadState.UNLOADING
            releaseEngine()
            _loadState.value = ModelLoadState.UNLOADED
        }
    }

    private fun releaseEngine() {
        val current = handle
        handle = 0L
        if (current != 0L) {
            runCatching { LlamaBridge.nativeFree(current) }
                .onFailure { Log.e(TAG, "nativeFree failed", it) }
        }
        _currentModel.value = null
        _allocatedMemoryMb.value = 0
        _currentTps.value = 0.0
    }

    /**
     * Runs a real completion.
     *
     * @param history previous turns of the conversation, oldest first.
     */
    fun generateStream(
        prompt: String,
        searchEnabled: Boolean,
        systemDate: String = mcpToolExecutor.getCurrentFormattedDate(),
        history: List<PromptFormat.Turn> = emptyList(),
        enableThinking: Boolean = true
    ): Flow<GenerationEvent> = callbackFlow {
        val activeHandle = handle
        val model = _currentModel.value
        if (activeHandle == 0L || _loadState.value != ModelLoadState.LOADED || model == null) {
            trySend(GenerationEvent.Error("No model loaded. Download and load the model first."))
            close()
            return@callbackFlow
        }

        val worker = launch(Dispatchers.IO) {
            try {
                var grounding = ""
                var sourceUrl: String? = null

                if (searchEnabled || isWebSearchIntent(prompt)) {
                    val query = extractSearchTerms(prompt)
                    trySend(GenerationEvent.ToolExecuting("duckduckgo_search", query))
                    val result = mcpToolExecutor.performDuckDuckGoSearch(query)
                    sourceUrl = result.sourceUrl
                    trySend(
                        GenerationEvent.ToolResult(
                            "duckduckgo_search",
                            result.result.take(500),
                            sourceUrl
                        )
                    )
                    if (result.success) grounding = result.result.take(MAX_GROUNDING_CHARS)
                }

                val system = buildSystemPrompt(systemDate, grounding, sourceUrl)
                val turns = history + PromptFormat.Turn("user", prompt)
                val renderedPrompt = PromptFormat.smolLM3(
                    system = system,
                    turns = turns,
                    enableThinking = enableThinking,
                    today = templateDate()
                )

                val promptTokens = runCatching {
                    LlamaBridge.nativeCountTokens(activeHandle, renderedPrompt)
                }.getOrDefault(0)
                Log.i(TAG, "Prompt: ${renderedPrompt.length} chars, ~$promptTokens tokens, history=${history.size}")

                val splitter = ThinkStreamSplitter()
                val decoder = Utf8StreamDecoder()
                val thinking = StringBuilder()
                val content = StringBuilder()

                var tokens = 0
                val startedNs = System.nanoTime()
                var lastTelemetryNs = 0L

                fun emitChunks(chunks: List<ThinkStreamSplitter.Chunk>) {
                    for (chunk in chunks) {
                        if (chunk.thinking) {
                            thinking.append(chunk.text)
                            trySend(GenerationEvent.ThinkingToken(chunk.text))
                        } else {
                            content.append(chunk.text)
                            trySend(GenerationEvent.ContentToken(chunk.text))
                        }
                    }
                }

                val result = LlamaBridge.nativeGenerate(
                    handle = activeHandle,
                    prompt = renderedPrompt,
                    maxTokens = MAX_NEW_TOKENS,
                    temperature = TEMPERATURE,
                    topK = TOP_K,
                    topP = TOP_P,
                    minP = MIN_P,
                    repeatPenalty = REPEAT_PENALTY,
                    seed = random.nextInt(),
                    callback = LlamaBridge.TokenCallback { bytes ->
                        if (!isActive) return@TokenCallback false

                        val text = decoder.decode(bytes)
                        if (text.isNotEmpty()) {
                            tokens++
                            emitChunks(splitter.feed(text))

                            val now = System.nanoTime()
                            if (now - lastTelemetryNs > TELEMETRY_INTERVAL_NS) {
                                lastTelemetryNs = now
                                val elapsed = (now - startedNs) / 1e9
                                val tps = if (elapsed > 0.0) tokens / elapsed else 0.0
                                _currentTps.value = tps
                                trySend(
                                    GenerationEvent.TelemetryUpdate(
                                        tokens,
                                        tps,
                                        _allocatedMemoryMb.value
                                    )
                                )
                            }
                        }
                        true
                    }
                )

                decoder.flush().let { tail -> if (tail.isNotEmpty()) emitChunks(splitter.feed(tail)) }
                emitChunks(splitter.flush())

                val durationMs = (System.nanoTime() - startedNs) / 1_000_000
                val averageTps = if (durationMs > 0) tokens * 1000.0 / durationMs else 0.0
                _currentTps.value = averageTps

                when {
                    result < 0 -> trySend(
                        GenerationEvent.Error(
                            if (result == -2) "Generation cancelled" else "llama.cpp generation failed"
                        )
                    )

                    else -> trySend(
                        GenerationEvent.Complete(
                            totalTokens = tokens,
                            averageTps = averageTps,
                            thinkingContent = thinking.toString().trim(),
                            content = content.toString().trim(),
                            durationMs = durationMs
                        )
                    )
                }
            } catch (c: CancellationException) {
                LlamaBridge.nativeCancel(activeHandle)
                throw c
            } catch (t: Throwable) {
                Log.e(TAG, "Generation failed", t)
                trySend(GenerationEvent.Error(t.message ?: "Generation failed"))
            } finally {
                close()
            }
        }

        awaitClose {
            LlamaBridge.nativeCancel(activeHandle)
            worker.cancel()
        }
    }

    private fun buildSystemPrompt(systemDate: String, grounding: String, sourceUrl: String?): String {
        val builder = StringBuilder()
        builder.append("You are DeepSmolLM3, a helpful assistant running entirely on the user's device.")
        builder.append(" Today's date is ").append(systemDate).append('.')
        builder.append(" Answer in markdown. Be concise but complete.")

        if (grounding.isNotEmpty()) {
            builder.append("\n\nUse the following live web search results to answer the user's question.")
            builder.append(" Cite the source URL when relevant.\n\n")
            builder.append(grounding)
            if (sourceUrl != null) {
                builder.append("\n\nSource: ").append(sourceUrl)
            }
        }
        return builder.toString()
    }

    private fun templateDate(): String =
        SimpleDateFormat("dd MMMM yyyy", Locale.US).format(Date())

    private fun isWebSearchIntent(prompt: String): Boolean {
        val lower = prompt.lowercase(Locale.US)
        return SEARCH_HINTS.any { lower.contains(it) }
    }

    private fun extractSearchTerms(prompt: String): String =
        prompt
            .replace(Regex("(?i)^(search|search for|lookup|look up|find|who is|what is|tell me about)\\s+"), "")
            .take(160)
            .trim()

    private companion object {
        const val TAG = "ModelEngineManager"

        const val MAX_CONTEXT = 4096
        const val MAX_THREADS = 6
        const val MAX_NEW_TOKENS = 640

        const val TEMPERATURE = 0.6f
        const val TOP_K = 40
        const val TOP_P = 0.95f
        const val MIN_P = 0.0f
        const val REPEAT_PENALTY = 1.1f

        const val TELEMETRY_INTERVAL_NS = 150_000_000L
        const val MAX_GROUNDING_CHARS = 1800

        val SEARCH_HINTS = listOf(
            "latest", "current", "news", "today", "weather", "stock price",
            "release date", "recent", "search for", "look up", "who is"
        )
    }
}
