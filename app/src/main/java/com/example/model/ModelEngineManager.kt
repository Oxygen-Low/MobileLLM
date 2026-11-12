package com.example.model

import android.util.Log
import com.example.model.internal.LlamaBridge
import com.example.tools.MCPToolExecutor
import com.example.tools.ToolCallResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
 *
 * The model is given a small tool set (web search, clock, calculator) and can
 * call a tool by emitting `<tool_call>…</tool_call>`. The call is executed and
 * its result is fed back for another generation round, so a single user prompt
 * can involve several search → reason → act cycles before the final answer.
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
     * Runs a real completion, looping over tool calls until the model answers
     * or the round budget is exhausted.
     *
     * @param history previous turns of the conversation, oldest first.
     */
    fun generateStream(
        prompt: String,
        systemDate: String = mcpToolExecutor.getCurrentFormattedDate(),
        history: List<PromptFormat.Turn> = emptyList(),
        enableThinking: Boolean = true,
        reasoningLevel: ReasoningLevel =
            if (enableThinking) ReasoningLevel.DEFAULT else ReasoningLevel.NONE
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
                // The full conversation for this request: prior history, the new
                // user turn, and every tool call/result the loop appends.
                val conversation = history.toMutableList()
                conversation.add(PromptFormat.Turn("user", prompt))

                var totalTokens = 0
                var totalDurationMs = 0L
                var finalThinking = ""
                var finalContent = ""
                var lastToolResult: String? = null
                var rounds = 0

                while (rounds < MAX_TOOL_ROUNDS && isActive) {
                    rounds++

                    val system = buildSystemPrompt(model.name, systemDate)
                    val renderedPrompt = PromptFormat.render(
                        style = model.promptStyle,
                        system = system,
                        turns = conversation,
                        enableThinking = enableThinking,
                        today = templateDate(),
                        reasoningLevel = reasoningLevel
                    )

                    val round = generateRound(
                        scope = this,
                        handle = activeHandle,
                        system = system,
                        renderedPrompt = renderedPrompt,
                        enableThinking = enableThinking,
                        reasoningLevel = reasoningLevel
                    ) { event -> trySend(event) }

                    totalTokens += round.tokens
                    totalDurationMs += round.durationMs

                    if (round.cancelled) return@launch

                    finalThinking = round.displayThinking
                    finalContent = round.displayContent

                    val call = round.toolCall
                    if (call == null) break

                    trySend(GenerationEvent.ToolExecuting(call.first, call.second))
                    val response = mcpToolExecutor.executeTool(call.first, call.second)
                    lastToolResult = response.result
                    trySend(
                        GenerationEvent.ToolResult(
                            toolName = response.toolName,
                            resultSnippet = response.result.take(MAX_TOOL_RESULT_SNIPPET_CHARS),
                            sourceUrl = response.sourceUrl
                        )
                    )

                    // Feed the call and its result back into the conversation so
                    // the next round can reason over them.
                    conversation.add(
                        PromptFormat.Turn("assistant", buildAssistantTranscript(round))
                    )
                    conversation.add(
                        PromptFormat.Turn("tool", formatToolResult(response))
                    )
                }

                val averageTps = if (totalDurationMs > 0) {
                    totalTokens * 1000.0 / totalDurationMs
                } else {
                    0.0
                }
                _currentTps.value = averageTps
                // The model may run out of rounds while it is still calling
                // tools; fall back to the last tool result so the reply is
                // never empty.
                val answer = finalContent.ifEmpty { lastToolResult.orEmpty() }
                trySend(
                    GenerationEvent.Complete(
                        totalTokens = totalTokens,
                        averageTps = averageTps,
                        thinkingContent = finalThinking.trim(),
                        content = answer.trim(),
                        durationMs = totalDurationMs
                    )
                )
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

    /**
     * A single generation pass: streams the model's tokens and stops early when
     * it emits a complete tool call.
     */
    private suspend fun generateRound(
        scope: CoroutineScope,
        handle: Long,
        system: String,
        renderedPrompt: String,
        enableThinking: Boolean,
        reasoningLevel: ReasoningLevel,
        emit: (GenerationEvent) -> Unit
    ): GenerationRound {
        val promptTokens = runCatching {
            LlamaBridge.nativeCountTokens(handle, renderedPrompt)
        }.getOrDefault(0)
        Log.i(TAG, "Prompt: ${renderedPrompt.length} chars, ~$promptTokens tokens")

        // When the prompt is prefilled with `<think>`, the stream starts
        // inside the reasoning block rather than with the opening tag.
        val thinkingMode = PromptFormat.resolveReasoningMode(
            system = system,
            enableThinking = enableThinking,
            reasoningLevel = reasoningLevel
        ) == "/think"

        val displaySplitter = ThinkStreamSplitter(startInsideThinking = thinkingMode)
        val rawSplitter = ThinkStreamSplitter(startInsideThinking = thinkingMode)
        val toolDetector = ToolCallDetector(mcpToolExecutor.availableTools.map { it.name }.toSet())
        val decoder = Utf8StreamDecoder()

        val thinking = StringBuilder()
        val content = StringBuilder()
        val rawThinking = StringBuilder()
        val rawContent = StringBuilder()

        var tokens = 0
        var toolCallCompleted = false

        val startedNs = System.nanoTime()
        var lastTelemetryNs = 0L

        fun consume(text: String) {
            // Raw text keeps the tool call so it can be replayed to the model.
            for (chunk in rawSplitter.feed(text)) {
                if (chunk.thinking) rawThinking.append(chunk.text)
                else rawContent.append(chunk.text)
            }
            // Displayed text has the tool call withheld; the UI shows a banner.
            val display = toolDetector.feed(text)
            if (display.isNotEmpty()) {
                for (chunk in displaySplitter.feed(display)) {
                    if (chunk.thinking) {
                        thinking.append(chunk.text)
                        emit(GenerationEvent.ThinkingToken(chunk.text))
                    } else {
                        content.append(chunk.text)
                        emit(GenerationEvent.ContentToken(chunk.text))
                    }
                }
            }
            if (toolDetector.isComplete) toolCallCompleted = true
        }

        fun emitDisplayedChunks(chunks: List<ThinkStreamSplitter.Chunk>) {
            for (chunk in chunks) {
                if (chunk.thinking) {
                    thinking.append(chunk.text)
                    emit(GenerationEvent.ThinkingToken(chunk.text))
                } else {
                    content.append(chunk.text)
                    emit(GenerationEvent.ContentToken(chunk.text))
                }
            }
        }

        val result = LlamaBridge.nativeGenerate(
            handle = handle,
            prompt = renderedPrompt,
            // Deeper reasoning levels get a larger generation budget so
            // the model can actually spend tokens on thinking.
            maxTokens = reasoningLevel.maxNewTokens,
            temperature = TEMPERATURE,
            topK = TOP_K,
            topP = TOP_P,
            minP = MIN_P,
            repeatPenalty = REPEAT_PENALTY,
            seed = random.nextInt(),
            // llama.cpp cannot switch thinking off/on natively, so the
            // budget makes the level a real generation setting: the
            // native loop closes the think block once it is exhausted.
            maxReasoningTokens = if (thinkingMode) reasoningLevel.reasoningBudget else 0,
            callback = LlamaBridge.TokenCallback { bytes ->
                if (!scope.isActive) return@TokenCallback false

                val text = decoder.decode(bytes)
                if (text.isNotEmpty()) {
                    tokens++
                    consume(text)

                    // A complete tool call ends this round early; the caller
                    // executes it and starts the next one.
                    if (toolCallCompleted) return@TokenCallback false

                    val now = System.nanoTime()
                    if (now - lastTelemetryNs > TELEMETRY_INTERVAL_NS) {
                        lastTelemetryNs = now
                        val elapsed = (now - startedNs) / 1e9
                        val tps = if (elapsed > 0.0) tokens / elapsed else 0.0
                        _currentTps.value = tps
                        emit(
                            GenerationEvent.TelemetryUpdate(
                                tokensGenerated = tokens,
                                tokensPerSecond = tps,
                                memoryAllocatedMb = _allocatedMemoryMb.value
                            )
                        )
                    }
                }
                true
            }
        )

        decoder.flush().let { tail -> if (tail.isNotEmpty()) consume(tail) }

        // Flush the splitters. If the stream ended mid tool call, the detector
        // releases the partial block so it is not silently dropped.
        for (chunk in rawSplitter.flush()) {
            if (chunk.thinking) rawThinking.append(chunk.text)
            else rawContent.append(chunk.text)
        }
        val held = toolDetector.flush()
        if (held.isNotEmpty()) emitDisplayedChunks(displaySplitter.feed(held))
        emitDisplayedChunks(displaySplitter.flush())

        val durationMs = (System.nanoTime() - startedNs) / 1_000_000
        val averageTps = if (durationMs > 0) tokens * 1000.0 / durationMs else 0.0
        _currentTps.value = averageTps

        val cancelled = result == -2 && !toolCallCompleted
        if (result == -1) {
            emit(GenerationEvent.Error("llama.cpp generation failed"))
        }

        return GenerationRound(
            thinking = rawThinking.toString(),
            content = rawContent.toString(),
            displayThinking = thinking.toString(),
            displayContent = content.toString(),
            toolCall = toolDetector.toolCall()?.let { it.toolName to it.argument },
            tokens = tokens,
            durationMs = durationMs,
            cancelled = cancelled
        )
    }

    /**
     * Rebuilds the assistant turn that triggered a tool call. The raw reasoning
     * never contains the think delimiters (the splitter strips them), so they
     * are added back here to keep the replayed turn well-formed.
     */
    private fun buildAssistantTranscript(round: GenerationRound): String = buildString {
        if (round.thinking.isNotEmpty()) {
            append("<think>\n")
            append(round.thinking)
            append("\n</think>\n")
        }
        append(round.content)
    }

    private fun formatToolResult(response: ToolCallResponse): String =
        if (response.success) {
            response.result.take(MAX_TOOL_RESULT_CHARS)
        } else {
            "Tool '${response.toolName}' failed: ${response.result}"
        }

    private fun buildSystemPrompt(modelName: String, systemDate: String): String {
        val builder = StringBuilder()
        builder.append("You are ").append(modelName)
            .append(", a helpful assistant running entirely on the user's device.")
        builder.append(" Today's date is ").append(systemDate).append('.')
        builder.append(" Answer in markdown. Be concise but complete.")

        builder.append("\n\nYou have access to the following tools:")
        for (tool in mcpToolExecutor.availableTools) {
            builder.append("\n- ").append(tool.name).append(": ").append(tool.description)
            builder.append("\n  parameter: ").append(tool.parametersDescription)
        }

        builder.append("\n\nWhen you need a tool, output exactly one tool call in this format and nothing else (after your reasoning, if reasoning is enabled):")
        builder.append("\n<tool_call>")
        builder.append("\ntool_name: argument")
        builder.append("\n</tool_call>")
        builder.append("\nUse one of the tool names listed above. If a tool has no parameter, write only its name followed by a colon.")
        builder.append("\nAfter the tool result arrives, reason about it and then either call another tool or answer the user.")
        builder.append("\nCall a tool whenever the answer depends on live information, the current date or time, or arithmetic.")
        return builder.toString()
    }

    private fun templateDate(): String =
        SimpleDateFormat("dd MMMM yyyy", Locale.US).format(Date())

    /**
     * One generation pass: raw text (tool call included) for the replayed
     * conversation, display text (tool call withheld) for the UI.
     */
    private data class GenerationRound(
        val thinking: String,
        val content: String,
        val displayThinking: String,
        val displayContent: String,
        val toolCall: Pair<String, String>?,
        val tokens: Int,
        val durationMs: Long,
        val cancelled: Boolean
    )

    private companion object {
        const val TAG = "ModelEngineManager"

        const val MAX_CONTEXT = 4096
        const val MAX_THREADS = 6

        const val TEMPERATURE = 0.6f
        const val TOP_K = 40
        const val TOP_P = 0.95f
        const val MIN_P = 0.0f
        const val REPEAT_PENALTY = 1.1f

        const val TELEMETRY_INTERVAL_NS = 150_000_000L

        /** Upper bound on search → reason → act cycles for a single prompt. */
        const val MAX_TOOL_ROUNDS = 5

        /** Tool result kept in the replayed conversation (chars). */
        const val MAX_TOOL_RESULT_CHARS = 1500

        /** Tool result shown in the UI banner (chars). */
        const val MAX_TOOL_RESULT_SNIPPET_CHARS = 500
    }
}
