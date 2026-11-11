package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.ChatMessageEntity
import com.example.data.ChatRepository
import com.example.data.ChatSessionEntity
import com.example.model.DownloadProgressInfo
import com.example.model.GenerationEvent
import com.example.model.ModelDownloadManager
import com.example.model.ModelDownloadState
import com.example.model.ModelEngineManager
import com.example.model.ModelLoadState
import com.example.model.ModelProfile
import com.example.model.PromptFormat
import com.example.model.ReasoningLevel
import com.example.tools.MCPToolExecutor
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _searchEnabled = MutableStateFlow(false)
    val searchEnabled: StateFlow<Boolean> = _searchEnabled.asStateFlow()

    private val _reasoningLevel = MutableStateFlow(ReasoningLevel.DEFAULT)
    val reasoningLevel: StateFlow<ReasoningLevel> = _reasoningLevel.asStateFlow()

    // Streaming state for the active message
    val streamingReasoning = MutableStateFlow("")
    val streamingContent = MutableStateFlow("")
    val activeToolName = MutableStateFlow<String?>(null)
    val activeToolQuery = MutableStateFlow<String?>(null)
    val activeToolSourceUrl = MutableStateFlow<String?>(null)

    // Queued prompt if user submitted while downloading
    private var pendingPrompt: String? = null

    private var generationJob: Job? = null

    init {
        // Check if models were already downloaded locally
        checkDownloadedModels()
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

    fun onAppStart() {
        viewModelScope.launch {
            if (selectedModel.value.isReadyToLoad && modelEngineManager.loadState.value != ModelLoadState.LOADED) {
                val file = downloadManager.getModelFile(selectedModel.value.id)
                modelEngineManager.loadModel(selectedModel.value, file)
            }
        }
    }

    fun onAppStop() {
        viewModelScope.launch {
            modelEngineManager.unloadModel()
        }
    }

    fun toggleSearch(enabled: Boolean) {
        _searchEnabled.value = enabled
    }

    fun setReasoningLevel(level: ReasoningLevel) {
        _reasoningLevel.value = level
    }

    fun createNewSession() {
        val newId = UUID.randomUUID().toString()
        val session = ChatSessionEntity(
            id = newId,
            title = "New Chat",
            modelId = selectedModel.value.shortName
        )
        viewModelScope.launch {
            repository.createSession(session)
            _currentSessionId.value = newId
        }
    }

    fun switchSession(sessionId: String) {
        _currentSessionId.value = sessionId
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            repository.deleteSession(sessionId)
            if (_currentSessionId.value == sessionId) {
                _currentSessionId.value = null
            }
        }
    }

    fun clearAllSessions() {
        viewModelScope.launch {
            repository.clearAll()
            _currentSessionId.value = null
        }
    }

    fun sendMessage(promptText: String) {
        if (promptText.isBlank() || _isGenerating.value) return

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
                content = promptText
            )
            repository.insertMessage(userMsg)

            // 2. Prepare streaming state
            _isGenerating.value = true
            streamingReasoning.value = ""
            streamingContent.value = ""
            activeToolName.value = null
            activeToolQuery.value = null
            activeToolSourceUrl.value = null

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
                    searchEnabled = _searchEnabled.value,
                    systemDate = currentDate,
                    history = history,
                    enableThinking = reasoningLevel.usesThinking,
                    reasoningLevel = reasoningLevel
                ).collect { event ->
                    when (event) {
                        is GenerationEvent.ToolExecuting -> {
                            activeToolName.value = event.toolName
                            activeToolQuery.value = event.argument
                        }
                        is GenerationEvent.ToolResult -> {
                            activeToolSourceUrl.value = event.sourceUrl
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
                _isGenerating.value = false

                // 4. Save Assistant Message into Room
                val assistantMsg = ChatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = activeSessionId,
                    role = "assistant",
                    content = if (finalContent.isNotEmpty()) finalContent else streamingContent.value,
                    reasoningContent = if (finalThinking.isNotEmpty()) finalThinking else streamingReasoning.value.ifBlank { null },
                    tokensCount = recordedTokens,
                    tokensPerSecond = recordedTps,
                    isToolExecution = activeToolName.value != null,
                    toolName = activeToolName.value,
                    toolQuery = activeToolQuery.value,
                    toolSourceUrl = activeToolSourceUrl.value
                )
                repository.insertMessage(assistantMsg)

                // Clear streaming buffers
                streamingReasoning.value = ""
                streamingContent.value = ""
                activeToolName.value = null
                activeToolQuery.value = null
                activeToolSourceUrl.value = null
            }
        }
    }

    /**
     * Builds conversation context from previously stored turns, trimmed from the
     * oldest message until it fits the prompt budget of an on-device model.
     */
    private suspend fun loadHistory(sessionId: String): List<PromptFormat.Turn> {
        return try {
            val turns = repository.getMessagesForSession(sessionId).first()
                .filter { it.role == "user" || it.role == "assistant" }
                .takeLast(MAX_HISTORY_MESSAGES)
                .mapNotNull { message ->
                    val text = message.content.trim().take(MAX_HISTORY_CHARS_PER_MESSAGE)
                    if (text.isEmpty()) null else PromptFormat.Turn(message.role, text)
                }

            var budget = MAX_HISTORY_CHARS_TOTAL
            val selected = ArrayDeque<PromptFormat.Turn>()
            for (turn in turns.asReversed()) {
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

    override fun onCleared() {
        super.onCleared()
        onAppStop()
    }

    private companion object {
        const val MAX_HISTORY_MESSAGES = 10
        const val MAX_HISTORY_CHARS_PER_MESSAGE = 2000
        const val MAX_HISTORY_CHARS_TOTAL = 6000
    }
}
