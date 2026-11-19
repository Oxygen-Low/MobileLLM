package com.example.model

import android.util.Log
import com.example.horde.HordeChatEvent
import com.example.horde.HordeClient
import com.example.horde.HordeSseParser
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
    data class ContentToken(val token: String) : GenerationEvent()

    /**
     * The model asked for a tool.
     *
     * [reasoning] is the trace it produced before the call and [preamble] the
     * text it wrote next to it. Both belong to the step that is starting, not
     * to the round that comes after it.
     */
    data class ToolExecuting(
        val toolName: String,
        val argument: String,
        val reasoning: String = "",
        val preamble: String = ""
    ) : GenerationEvent()

    /**
     * The tool answered.
     *
     * [result] is the text the model gets to see and the transcript keeps.
     */
    data class ToolResult(
        val toolName: String,
        val argument: String,
        val result: String,
        val sourceUrl: String?
    ) : GenerationEvent()

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
    private val mcpToolExecutor: MCPToolExecutor,
    private val hordeClient: HordeClient? = null
) {
    private var handle: Long = 0L
    private var currentModelFile: File? = null
    private val loadMutex = Mutex()
    private val random = Random()

    /**
     * Generates a picture with the image engine while the chat weights are out
     * of memory. Installed by the view model, which owns both engines.
     *
     * A round that asks for a picture releases this engine, runs the hook and
     * loads the chat model back, so the two engines never share the memory.
     */
    var imageGeneration: (suspend (prompt: String, sessionId: String?) -> ToolCallResponse)? = null

    /**
     * Generates a clip with the video engine while the chat weights are out of
     * memory. Installed by the view model next to [imageGeneration].
     */
    var videoGeneration: (suspend (prompt: String, sessionId: String?) -> ToolCallResponse)? = null

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
            _lastError.value = null

            // A Horde model has no weights to map: it is ready as soon as it is
            // selected. Keep it in the same state flow so the UI and the prompt
            // flow do not need a second set of checks.
            if (profile.isHorde) {
                _currentModel.value = profile
                _allocatedMemoryMb.value = 0
                _loadState.value = ModelLoadState.LOADED
                return@withLock
            }

            _loadState.value = ModelLoadState.LOADING
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
                currentModelFile = file
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
        currentModelFile = null
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
    ): Flow<GenerationEvent> = if (_currentModel.value?.isHorde == true) {
        generateHordeStream(prompt, systemDate, history, enableThinking, reasoningLevel)
    } else {
        generateLocalStream(prompt, systemDate, history, enableThinking, reasoningLevel)
    }

    private fun generateLocalStream(
        prompt: String,
        systemDate: String,
        history: List<PromptFormat.Turn>,
        enableThinking: Boolean,
        reasoningLevel: ReasoningLevel
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

                    // The handle can change between rounds: a picture call
                    // releases the chat weights and loads them back, so the
                    // round has to read the handle it actually uses.
                    val roundHandle = handle
                    if (roundHandle == 0L || _loadState.value != ModelLoadState.LOADED) {
                        trySend(GenerationEvent.Error("The model was unloaded during the turn."))
                        break
                    }

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
                        handle = roundHandle,
                        system = system,
                        renderedPrompt = renderedPrompt,
                        enableThinking = enableThinking,
                        reasoningLevel = reasoningLevel
                    ) { event -> trySend(event) }

                    totalTokens += round.tokens
                    totalDurationMs += round.durationMs

                    if (round.cancelled) return@launch

                    val call = round.toolCall
                    if (call == null) {
                        // The model answered: this round holds the reasoning
                        // that belongs to the answer. A round that ended in a
                        // tool call does not - its reasoning belongs to the
                        // step, and the next round reasons over the result.
                        finalThinking = round.displayThinking
                        finalContent = round.displayContent
                        break
                    }

                    // The round that produced the call is over. Its reasoning
                    // and its preamble belong to the step, so they are handed
                    // over instead of being overwritten by the next round.
                    trySend(
                        GenerationEvent.ToolExecuting(
                            toolName = call.first,
                            argument = call.second,
                            reasoning = round.displayThinking,
                            preamble = round.displayContent
                        )
                    )
                    val response = executeToolCall(call.first, call.second)
                    lastToolResult = response.result
                    trySend(
                        GenerationEvent.ToolResult(
                            toolName = response.toolName,
                            argument = response.argument,
                            result = response.result.take(MAX_TOOL_RESULT_CHARS),
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

                // A final answer that stops mid-sentence gets one extra pass:
                // the partial text is put back into the conversation and the
                // model is asked to continue from exactly that point.
                if (finalContent.isNotEmpty() && OutputContinuation.looksCutOff(finalContent)) {
                    val partial = finalContent
                    conversation.add(PromptFormat.Turn("assistant", partial))
                    conversation.add(PromptFormat.Turn("user", OutputContinuation.CONTINUE_INSTRUCTION))
                    val system = buildSystemPrompt(model.name, systemDate)
                    val renderedPrompt = PromptFormat.render(
                        style = model.promptStyle,
                        system = system,
                        turns = conversation,
                        enableThinking = false,
                        today = templateDate(),
                        reasoningLevel = ReasoningLevel.NONE
                    )
                    val roundHandle = handle
                    if (roundHandle != 0L && _loadState.value == ModelLoadState.LOADED && isActive) {
                        val tail = generateRound(
                            scope = this,
                            handle = roundHandle,
                            system = system,
                            renderedPrompt = renderedPrompt,
                            enableThinking = false,
                            reasoningLevel = ReasoningLevel.NONE
                        ) { event -> trySend(event) }
                        totalTokens += tail.tokens
                        totalDurationMs += tail.durationMs
                        if (tail.displayContent.isNotBlank()) {
                            val merged = OutputContinuation.merge(partial, tail.displayContent)
                            val added = OutputContinuation.appended(partial, merged)
                            if (added.isNotEmpty()) trySend(GenerationEvent.ContentToken(added))
                            finalContent = merged
                            if (tail.displayThinking.isNotBlank()) {
                                finalThinking += "\n" + tail.displayThinking
                            }
                        }
                    }
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
            LlamaBridge.nativeCancel(handle)
            worker.cancel()
        }
    }

    /**
     * Runs a Horde chat turn. The conversation, tool loop and streamed events
     * are the same as for a local model; only the source of the tokens differs.
     */
    private fun generateHordeStream(
        prompt: String,
        systemDate: String,
        history: List<PromptFormat.Turn>,
        enableThinking: Boolean,
        reasoningLevel: ReasoningLevel
    ): Flow<GenerationEvent> = callbackFlow {
        val model = _currentModel.value
        val client = hordeClient
        if (model == null || !model.isHorde || client == null) {
            trySend(GenerationEvent.Error("AI Horde is not configured. Pick a Horde model first."))
            close()
            return@callbackFlow
        }

        val worker = launch(Dispatchers.IO) {
            try {
                val conversation = history.toMutableList()
                conversation.add(PromptFormat.Turn("user", prompt))
                val system = buildSystemPrompt(model.name, systemDate)

                var tokens = 0
                var totalDurationMs = 0L
                var finalThinking = ""
                var finalContent = ""
                var lastToolResult: String? = null
                var rounds = 0

                while (rounds < MAX_TOOL_ROUNDS && isActive) {
                    rounds++
                    val result = runHordeRound(
                        client = client,
                        model = model,
                        system = system,
                        turns = conversation
                    ) { event -> trySend(event) }

                    tokens += result.tokens
                    totalDurationMs += result.durationMs
                    if (result.cancelled) return@launch

                    val call = result.toolCall
                    if (call == null) {
                        finalThinking = result.displayThinking
                        finalContent = result.displayContent

                        // A token-budget stop is the clearest sign of a cut-off
                        // answer; the text heuristic catches workers that report
                        // a stop even though they ran out of room.
                        if ((result.finishReason == "length" ||
                                OutputContinuation.looksCutOff(finalContent)) &&
                            result.displayContent.isNotEmpty()
                        ) {
                            val partial = finalContent
                            conversation.add(PromptFormat.Turn("assistant", partial))
                            conversation.add(
                                PromptFormat.Turn("user", OutputContinuation.CONTINUE_INSTRUCTION)
                            )
                            val tail = runHordeRound(
                                client = client,
                                model = model,
                                system = system,
                                turns = conversation
                            ) { event -> trySend(event) }
                            tokens += tail.tokens
                            totalDurationMs += tail.durationMs
                            if (tail.displayContent.isNotBlank()) {
                                val merged = OutputContinuation.merge(partial, tail.displayContent)
                                val added = OutputContinuation.appended(partial, merged)
                                if (added.isNotEmpty()) trySend(GenerationEvent.ContentToken(added))
                                finalContent = merged
                                if (tail.displayThinking.isNotBlank()) {
                                    finalThinking += "\n" + tail.displayThinking
                                }
                            }
                        }
                        break
                    }

                    trySend(
                        GenerationEvent.ToolExecuting(
                            toolName = call.first,
                            argument = call.second,
                            reasoning = result.displayThinking,
                            preamble = result.displayContent
                        )
                    )
                    val response = executeToolCall(call.first, call.second)
                    lastToolResult = response.result
                    trySend(
                        GenerationEvent.ToolResult(
                            toolName = response.toolName,
                            argument = response.argument,
                            result = response.result.take(MAX_TOOL_RESULT_CHARS),
                            sourceUrl = response.sourceUrl
                        )
                    )
                    conversation.add(PromptFormat.Turn("assistant", result.content))
                    conversation.add(PromptFormat.Turn("tool", formatToolResult(response)))
                }

                val averageTps = if (totalDurationMs > 0) {
                    tokens * 1000.0 / totalDurationMs
                } else 0.0
                _currentTps.value = averageTps
                trySend(
                    GenerationEvent.Complete(
                        totalTokens = tokens,
                        averageTps = averageTps,
                        thinkingContent = finalThinking.trim(),
                        content = finalContent.ifEmpty { lastToolResult.orEmpty() }.trim(),
                        durationMs = totalDurationMs
                    )
                )
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.e(TAG, "Horde generation failed", t)
                trySend(GenerationEvent.Error(t.message ?: "AI Horde generation failed"))
            } finally {
                close()
            }
        }

        awaitClose { worker.cancel() }
    }

    /** Streams one Horde completion and splits it into thinking and answer. */
    private suspend fun runHordeRound(
        client: HordeClient,
        model: ModelProfile,
        system: String,
        turns: List<PromptFormat.Turn>,
        emit: (GenerationEvent) -> Unit
    ): GenerationRound {
        val detector = ToolCallDetector(mcpToolExecutor.availableTools.map { it.name }.toSet())
        val splitter = ThinkStreamSplitter()
        val thinking = StringBuilder()
        val content = StringBuilder()
        val rawContent = StringBuilder()
        var tokens = 0
        var finishReason: String? = null
        val startedNs = System.nanoTime()
        var lastTelemetryNs = 0L

        client.streamChat(
            model = model.remoteModelId ?: model.name,
            messages = buildHordeMessages(system, turns),
            maxTokens = HORDE_MAX_TOKENS,
            temperature = TEMPERATURE.toDouble()
        ).collect { event ->
            when (event) {
                is HordeChatEvent.Reasoning -> {
                    thinking.append(event.text)
                    emit(GenerationEvent.ThinkingToken(event.text))
                }
                is HordeChatEvent.Content -> {
                    rawContent.append(event.text)
                    val display = detector.feed(event.text)
                    if (display.isNotEmpty()) {
                        for (chunk in splitter.feed(display)) {
                            if (chunk.thinking) {
                                thinking.append(chunk.text)
                                emit(GenerationEvent.ThinkingToken(chunk.text))
                            } else {
                                content.append(chunk.text)
                                emit(GenerationEvent.ContentToken(chunk.text))
                            }
                        }
                    }
                    tokens++
                }
                is HordeChatEvent.Finish -> finishReason = event.reason
                is HordeChatEvent.Usage -> tokens = maxOf(tokens, event.totalTokens)
            }
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
                        memoryAllocatedMb = 0
                    )
                )
            }
        }

        val held = detector.flush()
        if (held.isNotEmpty()) {
            for (chunk in splitter.feed(held)) {
                if (chunk.thinking) thinking.append(chunk.text) else content.append(chunk.text)
            }
        }
        for (chunk in splitter.flush()) {
            if (chunk.thinking) thinking.append(chunk.text) else content.append(chunk.text)
        }

        val durationMs = (System.nanoTime() - startedNs) / 1_000_000
        return GenerationRound(
            thinking = thinking.toString(),
            content = rawContent.toString(),
            displayThinking = thinking.toString(),
            displayContent = content.toString(),
            toolCall = detector.toolCall()?.let { it.toolName to it.argument },
            tokens = tokens,
            durationMs = durationMs,
            cancelled = false,
            finishReason = finishReason
        )
    }

    /**
     * OpenAI-style messages for a Horde completion. Reasoning is stripped from
     * replayed assistant turns: the model's own template will otherwise see
     * the previous think block as part of the answer.
     */
    private fun buildHordeMessages(
        system: String,
        turns: List<PromptFormat.Turn>
    ): List<HordeSseParser.HordeMessage> {
        val messages = mutableListOf(HordeSseParser.HordeMessage("system", system))
        for (turn in turns) {
            when (turn.role) {
                "assistant" -> messages.add(
                    HordeSseParser.HordeMessage(
                        "assistant",
                        HORDE_THINK_BLOCK.replace(turn.content, "")
                    )
                )
                "tool" -> messages.add(
                    HordeSseParser.HordeMessage(
                        "user",
                        "Tool result: ${turn.content.take(MAX_TOOL_RESULT_CHARS)}"
                    )
                )
                else -> messages.add(HordeSseParser.HordeMessage("user", turn.content))
            }
        }
        return messages
    }

    /**
     * Runs one tool call.
     *
     * A picture or a clip is the one kind of call the executor cannot answer on
     * its own: the chat model and a generation model do not fit in memory
     * together, so the weights are released, the media is generated and the
     * chat model is brought back, all before the model continues the same turn.
     */
    private suspend fun executeToolCall(toolName: String, argument: String): ToolCallResponse {
        val generator = when (toolName) {
            "generate_image" -> imageGeneration
            "generate_video" -> videoGeneration
            else -> null
        }
        if (generator == null) {
            return mcpToolExecutor.executeTool(toolName, argument)
        }

        val profile = _currentModel.value
        val file = currentModelFile
        val sessionId = mcpToolExecutor.activeSessionId

        // A Horde chat model has no weights in memory, so there is nothing to
        // release and nothing to reload between tool rounds.
        if (profile?.isHorde == true) {
            return generator(argument, sessionId)
        }

        // Free the chat weights first so the generation model has the memory.
        loadMutex.withLock { releaseEngine() }
        _loadState.value = ModelLoadState.UNLOADED
        _currentTps.value = 0.0
        _allocatedMemoryMb.value = 0

        return try {
            generator(argument, sessionId)
        } finally {
            // Bring the conversation back, also when the media failed.
            if (profile != null && file != null) {
                loadModel(profile, file)
            }
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

        builder.append("\n\nWork in steps: think briefly, call a tool when you need one, read what it returned, then either call another tool or answer.")
        builder.append("\nWhen you need a tool, output exactly one tool call in this format and nothing else (after your reasoning, if reasoning is enabled):")
        builder.append("\n<tool_call>")
        builder.append("\nduckduckgo_search: latest news")
        builder.append("\n</tool_call>")
        builder.append("\nReplace the tool name with one of the names listed above and the text after the colon with the argument that tool needs.")
        builder.append("\nIf a tool has no parameter, write only its name followed by a colon.")
        builder.append("\nAfter the tool result arrives, reason about it and then either call another tool or answer the user.")
        builder.append("\n\nWhen asked to create or edit an application, web app, or game, use the HTML sandbox tools (read_html_sandbox, edit_html_sandbox):")
        builder.append("\n- Specifically design the HTML for mobile screens, not desktop. Include `<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no\">`.")
        builder.append("\n- Build touch-first controls (touch/tap listeners, touch controls, on-screen buttons/joysticks instead of keyboard or hover events).")
        builder.append("\n- Make the UI mobile-friendly: full width/height responsive layouts, dark background, thumb-friendly tap targets, and no horizontal scroll.")
        builder.append("\n- For a new sandbox, call `edit_html_sandbox` with `{\"old_string\": \"\", \"new_string\": \"...\"}` to write the complete initial HTML file.")
        builder.append("\n- When modifying an existing sandbox, read it first with `read_html_sandbox` if needed, then use `edit_html_sandbox` with the exact unique `old_string` and the updated `new_string`.")
        builder.append("\nCall a tool whenever the answer depends on live information, the current date or time, arithmetic, or creating/editing an HTML sandbox app.")
        builder.append("\nTo draw a picture, call generate_image with the description. The result names a reference token: show the picture in your reply as ![description](gen:ID) or in the HTML sandbox as <img src=\"gen:ID\">. Never invent an id, only use the one the tool returned.")
        builder.append("\nTo generate a short video with sound, call generate_video with what happens in the clip. The result names a reference token: show the clip in your reply as [description](vid:ID) or in the HTML sandbox as <video src=\"vid:ID\" controls></video>. Never invent an id, only use the one the tool returned.")
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
        val cancelled: Boolean,
        val finishReason: String? = null
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

        /** Generation budget of a remote round; the client retries with 512. */
        const val HORDE_MAX_TOKENS = 2048

        val HORDE_THINK_BLOCK = Regex("<think>[\\s\\S]*?</think>")
    }
}
