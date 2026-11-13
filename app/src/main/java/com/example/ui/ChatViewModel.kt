package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.SpeechPlayer
import com.example.audio.SpeechRecorder
import com.example.data.AppDatabase
import com.example.data.ChatMessageEntity
import com.example.data.ChatRepository
import com.example.data.ChatSessionEntity
import com.example.data.ChatTranscript
import com.example.model.AgentStep
import com.example.model.AgentTranscript
import com.example.model.DownloadProgressInfo
import com.example.model.GenerationEvent
import com.example.model.ModelDownloadManager
import com.example.model.ModelDownloadState
import com.example.model.ModelEngineManager
import com.example.model.ModelLoadState
import com.example.model.ModelProfile
import com.example.model.PromptFormat
import com.example.model.ReasoningLevel
import com.example.model.SpeechEngineManager
import com.example.model.SpeechEngineState
import com.example.model.StoredTurn
import com.example.tools.MCPToolExecutor
import com.example.update.UpdateManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getInstance(application)
    private val repository = ChatRepository(database.chatDao())
    val mcpToolExecutor = MCPToolExecutor()
    val modelEngineManager = ModelEngineManager(mcpToolExecutor)
    val downloadManager = ModelDownloadManager(application)
    val updateManager = UpdateManager(application)

    /**
     * Small speech models live next to the chat models and follow the same
     * autoload rules: loaded on start when the toggle was on, released on stop
     * and on the idle timeout, and reloaded on demand.
     */
    val speechEngineManager = SpeechEngineManager(application, downloadManager)
    private val speechRecorder = SpeechRecorder(application)
    private val speechPlayer = SpeechPlayer(application)

    val sttEnabled: StateFlow<Boolean> = speechEngineManager.sttEnabled
    val ttsEnabled: StateFlow<Boolean> = speechEngineManager.ttsEnabled
    val sttState: StateFlow<SpeechEngineState> = speechEngineManager.sttState
    val ttsState: StateFlow<SpeechEngineState> = speechEngineManager.ttsState
    val sttError: StateFlow<String?> = speechEngineManager.sttError
    val ttsError: StateFlow<String?> = speechEngineManager.ttsError
    val isListening: StateFlow<Boolean> = speechRecorder.isRecording
    val isTranscribing: StateFlow<Boolean> = speechEngineManager.isTranscribing
    val isSpeaking: StateFlow<Boolean> = speechPlayer.isSpeaking

    private val _speechError = MutableStateFlow<String?>(null)
    val speechError: StateFlow<String?> = _speechError.asStateFlow()

    /**
     * Text produced by the last dictation pass. The input field consumes it and
     * calls [consumeTranscription], so it is written exactly once.
     */
    private val _transcribedText = MutableStateFlow<String?>(null)
    val transcribedText: StateFlow<String?> = _transcribedText.asStateFlow()

    private val _speakingMessageId = MutableStateFlow<String?>(null)
    val speakingMessageId: StateFlow<String?> = _speakingMessageId.asStateFlow()

    private val _synthesizingMessageId = MutableStateFlow<String?>(null)
    val synthesizingMessageId: StateFlow<String?> = _synthesizingMessageId.asStateFlow()

    private var speechJob: Job? = null
    private var dictationJob: Job? = null

    val updateState = updateManager.state

    val availableModels = MutableStateFlow(ModelProfile.DEFAULT_MODELS)
    val selectedModel = MutableStateFlow(ModelProfile.DEEP_SMOLLM3)

    val downloadStates: StateFlow<Map<String, DownloadProgressInfo>> = downloadManager.downloadStates

    val loadState: StateFlow<ModelLoadState> = modelEngineManager.loadState
    val modelError: StateFlow<String?> = modelEngineManager.lastError
    val allocatedMemoryMb: StateFlow<Int> = modelEngineManager.allocatedMemoryMb
    val currentTps: StateFlow<Double> = modelEngineManager.currentTps
    val currentDate: String = mcpToolExecutor.getCurrentFormattedDate()

    val sessions: StateFlow<List<ChatSessionEntity>> = repository.allSessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    val messages: StateFlow<List<ChatMessageEntity>> = _currentSessionId.flatMapLatest { sessionId ->
        if (sessionId != null) {
            repository.getMessagesForSession(sessionId)
        } else {
            flowOf(emptyList())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _reasoningLevel = MutableStateFlow(ReasoningLevel.DEFAULT)
    val reasoningLevel: StateFlow<ReasoningLevel> = _reasoningLevel.asStateFlow()

    // Streaming state for the active message
    val streamingReasoning = MutableStateFlow("")
    val streamingContent = MutableStateFlow("")

    /**
     * The steps of the agent loop of the current turn.
     *
     * Every entry is one "the model reasoned → the model called a tool → the
     * tool answered" cycle. They are kept next to each other instead of being
     * overwritten by the next round, so a turn that used three tools shows
     * three steps and the next turn can be replayed with that history.
     */
    val agentSteps = MutableStateFlow<List<AgentStep>>(emptyList())

    // Id of the assistant reply that has been written to the database for the
    // current turn. The UI keeps the live streaming bubble on screen until
    // this reply is actually part of the transcript, so the finished answer
    // is swapped in instead of blinking out for a frame.
    private val _persistedReplyId = MutableStateFlow<String?>(null)
    val persistedReplyId: StateFlow<String?> = _persistedReplyId.asStateFlow()

    /**
     * Drops the live streaming state.
     *
     * The streaming bubble is rendered from these buffers, so they have to be
     * cleared whenever the transcript is rewritten behind the user's back (a
     * rewind) or a new turn starts - otherwise a stale bubble can reappear.
     */
    private fun clearStreamingState() {
        _persistedReplyId.value = null
        streamingReasoning.value = ""
        streamingContent.value = ""
        agentSteps.value = emptyList()
    }

    // Queued prompt if user submitted while downloading
    private var pendingPrompt: String? = null

    private var generationJob: Job? = null
    private var inactivityJob: Job? = null
    private var updateCheckJob: Job? = null
    private var lastActivityResetMs = 0L

    /** Timestamp of the last stored message; see [nextTimestamp]. */
    private var lastMessageTimestamp = 0L

    init {
        // Check if models were already downloaded locally
        checkDownloadedModels()
        speechEngineManager.refresh()

        // Keep the inactivity timer in sync with the engine: schedule it whenever
        // weights are resident, cancel it as soon as they are not.
        viewModelScope.launch {
            modelEngineManager.loadState.collect { state ->
                if (state == ModelLoadState.LOADED) {
                    scheduleInactivityUnload()
                } else {
                    inactivityJob?.cancel()
                    inactivityJob = null
                }
            }
        }
    }

    fun checkDownloadedModels() {
        val updated = availableModels.value.map { model ->
            if (downloadManager.isModelDownloaded(model.id)) {
                model.copy(downloadState = ModelDownloadState.DOWNLOADED, downloadProgress = 1.0f)
            } else {
                model.copy(downloadState = ModelDownloadState.NOT_DOWNLOADED, downloadProgress = 0f)
            }
        }
        availableModels.value = updated

        // Auto-load if the selected model is downloaded
        val current = updated.firstOrNull { it.id == selectedModel.value.id } ?: updated[0]
        selectedModel.value = current
        if (current.isReadyToLoad) {
            loadModel(current)
        }
    }

    fun downloadModel(model: ModelProfile) {
        if (model.modelScopeRepo == null || model.modelScopeFile == null) return

        viewModelScope.launch {
            downloadManager.startDownload(
                modelId = model.id,
                repo = model.modelScopeRepo,
                filename = model.modelScopeFile,
                totalMb = model.downloadSizeMb,
                hfRepo = model.hfRepo,
                hfFilename = model.hfFile
            ) { downloadedFile ->
                // Update model state to DOWNLOADED
                val updated = availableModels.value.map {
                    if (it.id == model.id) it.copy(downloadState = ModelDownloadState.DOWNLOADED, downloadProgress = 1.0f)
                    else it
                }
                availableModels.value = updated

                // If this is the current model, load it
                if (selectedModel.value.id == model.id) {
                    val downloadedModel = updated.first { it.id == model.id }
                    selectedModel.value = downloadedModel
                    loadModel(downloadedModel)

                    // If a prompt was queued while downloading, run it automatically
                    val queued = pendingPrompt
                    if (queued != null) {
                        pendingPrompt = null
                        sendMessage(queued)
                    }
                }
            }
        }
    }

    fun deleteDownloadedModel(modelId: String) {
        downloadManager.deleteModel(modelId)
        val updated = availableModels.value.map {
            if (it.id == modelId) it.copy(downloadState = ModelDownloadState.NOT_DOWNLOADED, downloadProgress = 0f)
            else it
        }
        availableModels.value = updated
        if (selectedModel.value.id == modelId) {
            viewModelScope.launch {
                modelEngineManager.unloadModel()
                selectedModel.value = selectedModel.value.copy(downloadState = ModelDownloadState.NOT_DOWNLOADED)
            }
        }
    }

    fun reinstallModel(model: ModelProfile) {
        if (model.modelScopeRepo == null || model.modelScopeFile == null) return

        viewModelScope.launch {
            // Unload the weights first so the file is not in use while it is replaced.
            if (selectedModel.value.id == model.id) {
                modelEngineManager.unloadModel()
            }
            downloadManager.deleteModel(model.id)
            val updated = availableModels.value.map {
                if (it.id == model.id) {
                    it.copy(downloadState = ModelDownloadState.NOT_DOWNLOADED, downloadProgress = 0f)
                } else {
                    it
                }
            }
            availableModels.value = updated
            selectedModel.value = updated.first { it.id == selectedModel.value.id }
            downloadModel(model)
        }
    }

    fun loadModel(model: ModelProfile) {
        selectedModel.value = model
        viewModelScope.launch {
            val file = downloadManager.getModelFile(model.id)
            modelEngineManager.loadModel(model, file)
        }
    }

    /**
     * Releases the native model weights immediately. Any in-flight generation is
     * cancelled and awaited first so the native handle is not freed underneath it.
     */
    fun unloadModel() {
        viewModelScope.launch {
            inactivityJob?.cancel()
            inactivityJob = null

            val running = generationJob
            if (running != null && running.isActive) {
                running.cancel()
                running.join()
            }
            _isGenerating.value = false
            modelEngineManager.unloadModel()
        }
    }

    /**
     * Signals that the user interacted with the UI. Restarts the inactivity
     * countdown so the model is only released after a genuinely idle period.
     */
    fun onUserActivity() {
        val llmResident = modelEngineManager.loadState.value == ModelLoadState.LOADED
        if ((!llmResident && !hasResidentSpeech()) || _isGenerating.value) return

        val now = System.currentTimeMillis()
        if (now - lastActivityResetMs < ACTIVITY_RESET_THROTTLE_MS) return
        lastActivityResetMs = now
        scheduleInactivityUnload()
    }

    private fun hasResidentSpeech(): Boolean =
        speechEngineManager.sttState.value == SpeechEngineState.READY ||
            speechEngineManager.ttsState.value == SpeechEngineState.READY

    private fun scheduleInactivityUnload() {
        val llmResident = modelEngineManager.loadState.value == ModelLoadState.LOADED
        if ((!llmResident && !hasResidentSpeech()) || _isGenerating.value) return

        inactivityJob?.cancel()
        inactivityJob = viewModelScope.launch {
            delay(INACTIVITY_TIMEOUT_MS)
            if (modelEngineManager.loadState.value == ModelLoadState.LOADED && !_isGenerating.value) {
                modelEngineManager.unloadModel()
            }
            // Speech engines are small but they follow the same idle policy:
            // release them and let the next mic/speak action reload on demand.
            speechEngineManager.releaseEngines()
        }
    }

    fun onAppStart() {
        startUpdateChecks()
        viewModelScope.launch {
            if (selectedModel.value.isReadyToLoad && modelEngineManager.loadState.value != ModelLoadState.LOADED) {
                val file = downloadManager.getModelFile(selectedModel.value.id)
                modelEngineManager.loadModel(selectedModel.value, file)
            }
        }
        // Autoload the speech engines the user left enabled.
        viewModelScope.launch { speechEngineManager.restoreEnabled() }
    }

    fun onAppStop() {
        stopUpdateChecks()
        inactivityJob?.cancel()
        inactivityJob = null
        stopSpeaking()
        viewModelScope.launch {
            speechRecorder.cancel()
            speechEngineManager.releaseEngines()
        }
        viewModelScope.launch {
            modelEngineManager.unloadModel()
        }
    }

    /**
     * Checks for a newer GitHub release immediately and then once per minute
     * while the app is in the foreground.
     */
    private fun startUpdateChecks() {
        if (updateCheckJob?.isActive == true) return
        updateCheckJob = viewModelScope.launch {
            while (isActive) {
                updateManager.checkForUpdates()
                delay(UPDATE_CHECK_INTERVAL_MS)
            }
        }
    }

    private fun stopUpdateChecks() {
        updateCheckJob?.cancel()
        updateCheckJob = null
    }

    fun downloadAndInstallUpdate() {
        viewModelScope.launch {
            updateManager.downloadAndInstall()
        }
    }

    fun installUpdate() {
        updateManager.install()
    }

    fun dismissUpdate() {
        updateManager.dismiss()
    }

    fun setReasoningLevel(level: ReasoningLevel) {
        _reasoningLevel.value = level
    }

    // ---------------------------------------------------------------------
    // Speech
    // ---------------------------------------------------------------------

    fun setSpeechToTextEnabled(enabled: Boolean) {
        _speechError.value = null
        viewModelScope.launch {
            if (!enabled) {
                // A toggle turned off mid-dictation must not leave the
                // microphone recording in the background.
                dictationJob?.cancel()
                dictationJob = null
                speechRecorder.cancel()
            }
            speechEngineManager.setSttEnabled(enabled)
            scheduleInactivityUnload()
        }
    }

    fun setTextToSpeechEnabled(enabled: Boolean) {
        _speechError.value = null
        if (!enabled) stopSpeaking()
        viewModelScope.launch {
            speechEngineManager.setTtsEnabled(enabled)
            scheduleInactivityUnload()
        }
    }

    /** Starts push-to-talk recording. Tapping the mic again stops it. */
    fun startListening() {
        if (!sttEnabled.value || isGenerationRunning()) return
        if (speechRecorder.isRecording.value) {
            stopListening()
            return
        }
        if (dictationJob?.isActive == true) return

        _speechError.value = null
        try {
            speechRecorder.start(viewModelScope)
        } catch (t: Throwable) {
            _speechError.value = t.message ?: "Could not open the microphone"
            return
        }

        // The model may have been released by the idle timer. Load it while
        // the user is speaking so a short clip is transcribed without delay.
        viewModelScope.launch { speechEngineManager.ensureSttReady() }
    }

    fun stopListening() {
        if (!speechRecorder.isRecording.value || dictationJob?.isActive == true) return

        dictationJob = viewModelScope.launch {
            val samples = speechRecorder.stop()
            if (samples.isEmpty()) {
                scheduleInactivityUnload()
                return@launch
            }
            val text = speechEngineManager.transcribe(samples)
            if (text.isNotBlank()) {
                _transcribedText.value = text
            } else if (sttState.value == SpeechEngineState.READY) {
                _speechError.value = "No speech detected"
            }
            scheduleInactivityUnload()
        }
    }

    /** Called by the input field after it appended [transcribedText]. */
    fun consumeTranscription() {
        _transcribedText.value = null
    }

    fun onMicrophonePermissionDenied() {
        _speechError.value = "Microphone permission is required for speech to text"
    }

    fun dismissSpeechError() {
        _speechError.value = null
        speechEngineManager.clearErrors()
    }

    /** Speaks an assistant reply, or stops it when it is already speaking. */
    fun toggleSpeakMessage(messageId: String, text: String) {
        if (!ttsEnabled.value) return
        if (_speakingMessageId.value == messageId || _synthesizingMessageId.value == messageId) {
            stopSpeaking()
            return
        }

        stopSpeaking()
        speechJob = viewModelScope.launch {
            _synthesizingMessageId.value = messageId
            val audio = speechEngineManager.synthesize(text)
            if (_synthesizingMessageId.value != messageId) return@launch
            _synthesizingMessageId.value = null

            if (audio == null) {
                _speechError.value = speechEngineManager.ttsError.value
                    ?: "Could not synthesize this reply"
                scheduleInactivityUnload()
                return@launch
            }

            _speakingMessageId.value = messageId
            speechPlayer.play(audio.samples, audio.sampleRate) {
                if (_speakingMessageId.value == messageId) {
                    _speakingMessageId.value = null
                }
            }
            // The synthesized WAV is complete; let the idle timer release the
            // model while the media player finishes the audio.
            scheduleInactivityUnload()
        }
    }

    fun stopSpeaking() {
        speechJob?.cancel()
        speechJob = null
        speechPlayer.stop()
        _speakingMessageId.value = null
        _synthesizingMessageId.value = null
    }

    fun createNewSession() {
        val newId = UUID.randomUUID().toString()
        val session = ChatSessionEntity(
            id = newId,
            title = "New Chat",
            modelId = selectedModel.value.shortName
        )
        clearStreamingState()
        viewModelScope.launch {
            repository.createSession(session)
            _currentSessionId.value = newId
        }
    }

    fun switchSession(sessionId: String) {
        // The streaming bubble belongs to the transcript it was streamed into;
        // carrying it over would show it in a chat it never belonged to.
        clearStreamingState()
        _currentSessionId.value = sessionId
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            repository.deleteSession(sessionId)
            if (_currentSessionId.value == sessionId) {
                clearStreamingState()
                _currentSessionId.value = null
            }
        }
    }

    fun clearAllSessions() {
        clearStreamingState()
        viewModelScope.launch {
            repository.clearAll()
            _currentSessionId.value = null
        }
    }

    fun sendMessage(promptText: String) {
        if (promptText.isBlank() || isGenerationRunning()) return
        onUserActivity()

        val model = selectedModel.value
        // If active model is not downloaded yet, queue prompt and initiate download
        if (!model.isReadyToLoad) {
            pendingPrompt = promptText
            downloadModel(model)
            return
        }

        // If downloaded but unloaded, ensure it is loaded
        if (modelEngineManager.loadState.value != ModelLoadState.LOADED) {
            viewModelScope.launch {
                val file = downloadManager.getModelFile(model.id)
                modelEngineManager.loadModel(model, file)
                executePromptStream(promptText, model)
            }
            return
        }

        executePromptStream(promptText, model)
    }

    private fun executePromptStream(promptText: String, model: ModelProfile) {
        generationJob = viewModelScope.launch {
            // Generation keeps the device busy; never unload mid-stream.
            inactivityJob?.cancel()
            inactivityJob = null

            var activeSessionId = _currentSessionId.value
            if (activeSessionId == null) {
                val newId = UUID.randomUUID().toString()
                val sessionTitle = if (promptText.length > 28) promptText.take(28) + "…" else promptText
                val newSession = ChatSessionEntity(
                    id = newId,
                    title = sessionTitle,
                    modelId = model.shortName
                )
                repository.createSession(newSession)
                _currentSessionId.value = newId
                activeSessionId = newId
            }

            // Capture the conversation so far before the new user turn is stored.
            val history = loadHistory(activeSessionId)

            // 1. Insert User Message
            val userMsg = ChatMessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = activeSessionId,
                role = "user",
                content = promptText,
                timestamp = nextTimestamp()
            )
            repository.insertMessage(userMsg)

            // 2. Prepare streaming state
            clearStreamingState()
            _isGenerating.value = true

            // The id of the reply is known up front: the tool steps of this turn
            // are numbered relative to it, so a live step and the transcript
            // row it becomes share the same identity.
            val replyId = UUID.randomUUID().toString()

            var recordedTps = 0.0
            var recordedTokens = 0
            var finalThinking = ""
            var finalContent = ""

            // 3. Collect from ModelEngineManager stream
            // Models without a thinking mode always run with reasoning disabled.
            val reasoningLevel = if (model.supportsReasoning) {
                _reasoningLevel.value
            } else {
                ReasoningLevel.NONE
            }
            try {
                modelEngineManager.generateStream(
                    prompt = promptText,
                    systemDate = currentDate,
                    history = history,
                    enableThinking = reasoningLevel.usesThinking,
                    reasoningLevel = reasoningLevel
                ).collect { event ->
                    when (event) {
                        is GenerationEvent.ToolExecuting -> {
                            // The model switched to a tool. The round is over,
                            // so its reasoning and its preamble move into the
                            // step that is starting instead of being
                            // overwritten by the next round.
                            val step = AgentStep(
                                id = "$replyId/step-${agentSteps.value.size}",
                                toolName = event.toolName,
                                argument = event.argument,
                                reasoning = buildStepReasoning(
                                    event.reasoning,
                                    event.preamble
                                ),
                                isRunning = true
                            )
                            agentSteps.value = agentSteps.value + step
                            streamingReasoning.value = ""
                            streamingContent.value = ""
                        }
                        is GenerationEvent.ToolResult -> {
                            // The tool answered: the step that was waiting for
                            // it is complete.
                            val result = event.result
                            val sourceUrl = event.sourceUrl
                            agentSteps.value = agentSteps.value.map { step ->
                                if (step.toolName == event.toolName && step.isRunning) {
                                    step.copy(
                                        result = result,
                                        sourceUrl = sourceUrl,
                                        isRunning = false
                                    )
                                } else {
                                    step
                                }
                            }
                        }
                        is GenerationEvent.ThinkingToken -> {
                            streamingReasoning.value += event.token
                        }
                        is GenerationEvent.ContentToken -> {
                            streamingContent.value += event.token
                        }
                        is GenerationEvent.TelemetryUpdate -> {
                            recordedTps = event.tokensPerSecond
                            recordedTokens = event.tokensGenerated
                        }
                        is GenerationEvent.Complete -> {
                            finalThinking = event.thinkingContent
                            finalContent = event.content
                            recordedTokens = event.totalTokens
                            recordedTps = event.averageTps
                        }
                        is GenerationEvent.Error -> {
                            finalContent = "Error: ${event.message}"
                        }
                    }
                }
            } catch (e: Exception) {
                finalContent = streamingContent.value.ifEmpty { "Generation interrupted: ${e.message}" }
            } finally {
                try {
                    // 4. Save the agent trace and the reply into Room.
                    // Every tool step becomes a row of its own so the
                    // transcript keeps the order of the run: the model asked,
                    // the tool answered, the model continued.
                    for (step in agentSteps.value) {
                        repository.insertMessage(
                            ChatMessageEntity(
                                id = step.id,
                                sessionId = activeSessionId,
                                role = "tool",
                                content = step.result.orEmpty(),
                                reasoningContent = step.reasoning.ifBlank { null },
                                isToolExecution = true,
                                toolName = step.toolName,
                                toolQuery = step.argument,
                                toolSourceUrl = step.sourceUrl,
                                timestamp = nextTimestamp()
                            )
                        )
                    }

                    val assistantMsg = ChatMessageEntity(
                        id = replyId,
                        sessionId = activeSessionId,
                        role = "assistant",
                        content = if (finalContent.isNotEmpty()) finalContent else streamingContent.value,
                        reasoningContent = if (finalThinking.isNotEmpty()) finalThinking else streamingReasoning.value.ifBlank { null },
                        tokensCount = recordedTokens,
                        tokensPerSecond = recordedTps,
                        timestamp = nextTimestamp()
                    )
                    repository.insertMessage(assistantMsg)

                    // The reply is part of the transcript now. Publishing its id
                    // lets the UI swap the live streaming bubble for it in one
                    // step instead of blinking out between the two.
                    _persistedReplyId.value = assistantMsg.id
                } finally {
                    _isGenerating.value = false

                    // The streaming buffers are deliberately kept: the bubble
                    // renders from them and stays on screen until the persisted
                    // reply has replaced it. A turn that never made it into the
                    // transcript has nothing to hand over to, so it is dropped.
                    if (_persistedReplyId.value == null) {
                        clearStreamingState()
                    }

                    // Restart the idle countdown once the engine is free again.
                    if (modelEngineManager.loadState.value == ModelLoadState.LOADED) {
                        scheduleInactivityUnload()
                    }
                }
            }
        }
    }

    /**
     * The reasoning of a tool step: what the model thought about it plus what
     * it wrote next to the call.
     */
    private fun buildStepReasoning(reasoning: String, preamble: String): String {
        val trace = reasoning.trim()
        val note = preamble.trim()
        return when {
            trace.isEmpty() -> note
            note.isEmpty() -> trace
            else -> "$trace\n\n$note"
        }
    }

    /**
     * A timestamp that is strictly larger than the one of the previous
     * message.
     *
     * The transcript is ordered by timestamp, and a turn can write several
     * rows within the same millisecond. Without the bump their order would be
     * left to the database.
     */
    private fun nextTimestamp(): Long {
        val now = System.currentTimeMillis()
        lastMessageTimestamp = maxOf(now, lastMessageTimestamp + 1)
        return lastMessageTimestamp
    }

    /**
     * Builds conversation context from previously stored turns, trimmed from the
     * oldest message until it fits the prompt budget of an on-device model.
     *
     * Tool steps are part of the context: a step is replayed as the assistant
     * turn that made the call followed by the tool turn that answered it. That
     * is what keeps the model from forgetting what it already looked up and
     * repeating the same search in the next question.
     */
    private suspend fun loadHistory(sessionId: String): List<PromptFormat.Turn> {
        return try {
            val messages = repository.getMessagesForSession(sessionId).first()
            val turns = mutableListOf<PromptFormat.Turn>()

            for (message in messages) {
                turns.addAll(
                    AgentTranscript.turnsFor(
                        StoredTurn(
                            role = message.role,
                            content = message.content,
                            reasoning = message.reasoningContent,
                            toolName = message.toolName,
                            toolQuery = message.toolQuery
                        )
                    )
                )
            }

            var budget = MAX_HISTORY_CHARS_TOTAL
            val selected = ArrayDeque<PromptFormat.Turn>()
            for (turn in turns.takeLast(MAX_HISTORY_TURNS).asReversed()) {
                if (turn.content.length > budget) break
                budget -= turn.content.length
                selected.addFirst(turn)
            }
            selected.toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun stopGeneration() {
        generationJob?.cancel()
        _isGenerating.value = false
    }

    /**
     * Re-runs the most recent user prompt and replaces the final assistant reply
     * with a freshly generated one. No-op unless the transcript ends with an
     * assistant response that has a user prompt before it.
     */
    fun regenerateLastResponse() {
        if (isGenerationRunning()) return

        val model = selectedModel.value
        if (!model.isReadyToLoad) return

        val sessionId = _currentSessionId.value ?: return
        onUserActivity()

        viewModelScope.launch {
            val transcript = repository.getMessagesForSession(sessionId).first()

            // The prompt that produced the reply we are replacing. It has to be
            // the *last* user turn, not the first one in the session - the old
            // code took the position of the match inside a descending range,
            // which pointed at an unrelated turn and replayed the wrong prompt.
            val target = ChatTranscript.regenerationTarget(transcript) ?: return@launch

            // The transcript is about to be rewritten - drop the streaming state
            // so a stale bubble cannot reappear over the new turn.
            clearStreamingState()

            // Drop the reply, its prompt and the tool steps in between; they
            // belong to the run that is being replaced. executePromptStream
            // re-inserts the prompt as a fresh user turn before generating the
            // new reply.
            repository.deleteMessagesByIds(
                ChatTranscript.idsFrom(transcript, target.promptIndex)
            )

            if (modelEngineManager.loadState.value != ModelLoadState.LOADED) {
                val file = downloadManager.getModelFile(model.id)
                modelEngineManager.loadModel(model, file)
            }
            executePromptStream(transcript[target.promptIndex].content, model)
        }
    }

    /**
     * Rolls the transcript back to the given assistant response by deleting
     * every message that came after it.
     */
    fun rewindToMessage(messageId: String) {
        if (isGenerationRunning()) return

        val sessionId = _currentSessionId.value ?: return

        viewModelScope.launch {
            val transcript = repository.getMessagesForSession(sessionId).first()
            val index = ChatTranscript.rewindAnchorIndex(transcript, messageId)
            if (index < 0) return@launch

            // The transcript is about to lose the messages the live bubble was
            // showing - drop it so nothing stale is left behind.
            clearStreamingState()

            repository.deleteMessagesByIds(
                ChatTranscript.idsAfter(transcript, index)
            )
        }
    }

    /**
     * True while a generation owns the transcript.
     *
     * The job is checked as well as the flag: `_isGenerating` is only flipped
     * inside the generation coroutine, so there is a window right after a
     * prompt is submitted where the flag still reads false while the job is
     * already running. Rewinding during that window would delete messages the
     * running generation is about to write.
     */
    private fun isGenerationRunning(): Boolean =
        _isGenerating.value || generationJob?.isActive == true

    override fun onCleared() {
        super.onCleared()
        onAppStop()
    }

    private companion object {
        const val MAX_HISTORY_TURNS = 16
        const val MAX_HISTORY_CHARS_TOTAL = 6000

        const val INACTIVITY_TIMEOUT_MS = 30_000L
        const val ACTIVITY_RESET_THROTTLE_MS = 500L
        const val UPDATE_CHECK_INTERVAL_MS = 60_000L
    }
}
