package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.ChatMessageEntity
import com.example.data.ChatRepository
import com.example.data.ChatSessionEntity
import com.example.model.AudioModelProfile
import com.example.model.DownloadProgressInfo
import com.example.model.GenerationEvent
import com.example.model.ModelDownloadManager
import com.example.model.ModelDownloadState
import com.example.model.ModelEngineManager
import com.example.model.ModelLoadState
import com.example.model.ModelProfile
import com.example.speech.SpeechEngineManager
import com.example.tools.MCPToolExecutor
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val speechEngineManager = SpeechEngineManager(application, downloadManager, viewModelScope)

    val availableModels = MutableStateFlow(ModelProfile.DEFAULT_MODELS)
    val selectedModel = MutableStateFlow(ModelProfile.DEEP_SMOLLM3)

    val downloadStates: StateFlow<Map<String, DownloadProgressInfo>> = downloadManager.downloadStates

    val loadState: StateFlow<ModelLoadState> = modelEngineManager.loadState
    val allocatedMemoryMb: StateFlow<Int> = modelEngineManager.allocatedMemoryMb
    val currentTps: StateFlow<Double> = modelEngineManager.currentTps
    val currentDate: String = mcpToolExecutor.getCurrentFormattedDate()

    // Speech states
    val isTtsEnabled: StateFlow<Boolean> = speechEngineManager.isTtsEnabled
    val isSttEnabled: StateFlow<Boolean> = speechEngineManager.isSttEnabled
    val isSpeaking: StateFlow<Boolean> = speechEngineManager.isSpeaking
    val isListening: StateFlow<Boolean> = speechEngineManager.isListening
    val recognizedVoiceText: StateFlow<String> = speechEngineManager.recognizedText
    val sttModel: StateFlow<AudioModelProfile> = speechEngineManager.sttModel
    val ttsModel: StateFlow<AudioModelProfile> = speechEngineManager.ttsModel

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

    // Streaming state for the active message
    val streamingReasoning = MutableStateFlow("")
    val streamingContent = MutableStateFlow("")
    val activeToolName = MutableStateFlow<String?>(null)
    val activeToolQuery = MutableStateFlow<String?>(null)
    val activeToolSourceUrl = MutableStateFlow<String?>(null)

    private var generationJob: Job? = null

    init {
        // Check if default models were already downloaded locally
        checkDownloadedModels()
    }

    private fun checkDownloadedModels() {
        val updated = availableModels.value.map { model ->
            if (downloadManager.isModelDownloaded(model.id)) {
                model.copy(downloadState = ModelDownloadState.DOWNLOADED, downloadProgress = 1.0f)
            } else {
                model
            }
        }
        availableModels.value = updated

        // Auto-load if the selected model is downloaded
        val current = updated.firstOrNull { it.id == selectedModel.value.id } ?: updated[0]
        selectedModel.value = current
        if (current.isReadyToLoad) {
            viewModelScope.launch {
                modelEngineManager.loadModel(current)
            }
        }
    }

    fun downloadHuggingFaceModel(model: ModelProfile) {
        if (model.huggingFaceRepo == null || model.huggingFaceFile == null) return

        viewModelScope.launch {
            downloadManager.startDownload(
                modelId = model.id,
                repo = model.huggingFaceRepo,
                filename = model.huggingFaceFile,
                totalMb = model.downloadSizeMb
            ) {
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
            }
        }
    }

    fun loadModel(model: ModelProfile) {
        selectedModel.value = model
        viewModelScope.launch {
            modelEngineManager.loadModel(model)
        }
    }

    fun onAppStart() {
        viewModelScope.launch {
            if (selectedModel.value.isReadyToLoad && modelEngineManager.loadState.value != ModelLoadState.LOADED) {
                modelEngineManager.loadModel(selectedModel.value)
            }
        }
    }

    fun onAppStop() {
        viewModelScope.launch {
            modelEngineManager.unloadModel()
        }
    }

    fun addCustomModel(model: ModelProfile) {
        if (!availableModels.value.any { it.id == model.id }) {
            availableModels.value = availableModels.value + model
        }
        loadModel(model)
    }

    fun toggleSearch(enabled: Boolean) {
        _searchEnabled.value = enabled
    }

    fun toggleTts(enabled: Boolean) {
        speechEngineManager.toggleTts(enabled) { audioModel ->
            // Audio model needs download
            downloadAudioModel(audioModel)
        }
    }

    fun toggleStt(enabled: Boolean) {
        speechEngineManager.toggleStt(enabled) { audioModel ->
            // Audio model needs download
            downloadAudioModel(audioModel)
        }
    }

    fun downloadAudioModel(audioModel: AudioModelProfile) {
        if (audioModel.type == com.example.model.AudioModelType.STT) {
            speechEngineManager.downloadSttModel {
                // Done
            }
        } else {
            speechEngineManager.downloadTtsModel {
                // Done
            }
        }
    }

    fun speak(text: String) {
        speechEngineManager.speak(text)
    }

    fun startListening() {
        speechEngineManager.startListening { transcribed ->
            sendMessage(transcribed)
        }
    }

    fun stopListening() {
        speechEngineManager.stopListening()
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

        // If active model is not downloaded/ready, trigger its download and notify
        val model = selectedModel.value
        if (!model.isReadyToLoad) {
            downloadHuggingFaceModel(model)
            return
        }

        generationJob = viewModelScope.launch {
            var activeSessionId = _currentSessionId.value
            if (activeSessionId == null) {
                val newId = UUID.randomUUID().toString()
                val sessionTitle = if (promptText.length > 28) promptText.take(28) + "…" else promptText
                val newSession = ChatSessionEntity(
                    id = newId,
                    title = sessionTitle,
                    modelId = selectedModel.value.shortName
                )
                repository.createSession(newSession)
                _currentSessionId.value = newId
                activeSessionId = newId
            }

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
            try {
                modelEngineManager.generateStream(
                    prompt = promptText,
                    searchEnabled = _searchEnabled.value,
                    systemDate = currentDate
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

                // 5. If TTS is active, read the assistant's answer aloud
                if (isTtsEnabled.value && assistantMsg.content.isNotBlank()) {
                    speak(assistantMsg.content)
                }

                // Clear streaming buffers
                streamingReasoning.value = ""
                streamingContent.value = ""
                activeToolName.value = null
                activeToolQuery.value = null
                activeToolSourceUrl.value = null
            }
        }
    }

    fun stopGeneration() {
        generationJob?.cancel()
        _isGenerating.value = false
    }

    override fun onCleared() {
        super.onCleared()
        onAppStop()
        speechEngineManager.shutdown()
    }
}
