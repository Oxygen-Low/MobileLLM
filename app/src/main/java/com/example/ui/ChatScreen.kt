package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.ChatMessageEntity
import com.example.ui.components.ChatInputBar
import com.example.ui.components.ChatMessageItem
import com.example.ui.components.CustomModelDialog
import com.example.ui.components.ModelsHubSheet
import com.example.ui.components.SessionDrawerSheet
import com.example.ui.components.TopTelemetryBar
import com.example.ui.theme.BgDark
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.ReasoningAmber
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceElevated
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TerminalGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    val currentModel by viewModel.selectedModel.collectAsStateWithLifecycle()
    val availableModels by viewModel.availableModels.collectAsStateWithLifecycle()
    val downloadStates by viewModel.downloadStates.collectAsStateWithLifecycle()
    val loadState by viewModel.loadState.collectAsStateWithLifecycle()
    val memoryMb by viewModel.allocatedMemoryMb.collectAsStateWithLifecycle()
    val currentTps by viewModel.currentTps.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val currentSessionId by viewModel.currentSessionId.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val searchEnabled by viewModel.searchEnabled.collectAsStateWithLifecycle()

    val isTtsEnabled by viewModel.isTtsEnabled.collectAsStateWithLifecycle()
    val isSttEnabled by viewModel.isSttEnabled.collectAsStateWithLifecycle()
    val isListening by viewModel.isListening.collectAsStateWithLifecycle()
    val recognizedVoiceText by viewModel.recognizedVoiceText.collectAsStateWithLifecycle()
    val sttModel by viewModel.sttModel.collectAsStateWithLifecycle()
    val ttsModel by viewModel.ttsModel.collectAsStateWithLifecycle()

    val streamingReasoning by viewModel.streamingReasoning.collectAsStateWithLifecycle()
    val streamingContent by viewModel.streamingContent.collectAsStateWithLifecycle()
    val activeToolName by viewModel.activeToolName.collectAsStateWithLifecycle()
    val activeToolQuery by viewModel.activeToolQuery.collectAsStateWithLifecycle()
    val activeToolSourceUrl by viewModel.activeToolSourceUrl.collectAsStateWithLifecycle()

    var isHistoryOpen by remember { mutableStateOf(false) }
    var isModelsHubOpen by remember { mutableStateOf(false) }
    var isCustomModelDialogOpen by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    // Auto-scroll to bottom on new messages
    LaunchedEffect(messages.size, streamingContent.length, streamingReasoning.length) {
        if (messages.isNotEmpty() || isGenerating) {
            val targetIdx = (messages.size + if (isGenerating) 1 else 0) - 1
            if (targetIdx >= 0) {
                listState.animateScrollToItem(targetIdx)
            }
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .windowInsetsPadding(WindowInsets.ime)
            .testTag("chat_screen"),
        containerColor = BgDark,
        topBar = {
            TopTelemetryBar(
                currentModel = currentModel,
                loadState = loadState,
                memoryMb = memoryMb,
                currentTps = currentTps,
                currentDate = viewModel.currentDate,
                isTtsEnabled = isTtsEnabled,
                onOpenModelsHub = { isModelsHubOpen = true },
                onToggleHistory = { isHistoryOpen = true },
                onNewChat = { viewModel.createNewSession() },
                onToggleTts = { viewModel.toggleTts(!isTtsEnabled) }
            )
        },
        bottomBar = {
            ChatInputBar(
                isGenerating = isGenerating,
                searchEnabled = searchEnabled,
                isListening = isListening,
                recognizedVoiceText = recognizedVoiceText,
                onToggleSearch = { viewModel.toggleSearch(it) },
                onSendMessage = { viewModel.sendMessage(it) },
                onStopGenerating = { viewModel.stopGeneration() },
                onStartListening = { viewModel.startListening() },
                onStopListening = { viewModel.stopListening() },
                onOpenVoiceSettings = { isModelsHubOpen = true }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    ChatMessageItem(
                        message = message,
                        modelName = currentModel.shortName,
                        onSpeak = { text -> viewModel.speak(text) }
                    )
                }

                // Render active streaming message item
                if (isGenerating) {
                    item(key = "active_stream") {
                        val activeMsg = ChatMessageEntity(
                            id = "streaming_active",
                            sessionId = currentSessionId ?: "",
                            role = "assistant",
                            content = if (streamingContent.isNotEmpty()) streamingContent else if (streamingReasoning.isNotEmpty()) "" else "Thinking…",
                            reasoningContent = streamingReasoning.ifBlank { null },
                            tokensCount = (streamingReasoning.length + streamingContent.length) / 4,
                            tokensPerSecond = currentTps,
                            isToolExecution = activeToolName != null,
                            toolName = activeToolName,
                            toolQuery = activeToolQuery,
                            toolSourceUrl = activeToolSourceUrl
                        )
                        ChatMessageItem(
                            message = activeMsg,
                            modelName = currentModel.shortName,
                            isStreaming = true,
                            onSpeak = { text -> viewModel.speak(text) }
                        )
                    }
                }
            }
        }
    }

    // Models Hub Bottom Sheet (replaces old dropdown)
    ModelsHubSheet(
        isOpen = isModelsHubOpen,
        onDismiss = { isModelsHubOpen = false },
        models = availableModels,
        currentModel = currentModel,
        downloadStates = downloadStates,
        onSelectAndLoadModel = { viewModel.loadModel(it) },
        onDownloadModel = { viewModel.downloadHuggingFaceModel(it) },
        onDeleteModel = { viewModel.deleteDownloadedModel(it) },
        onOpenCustomFilePicker = { isCustomModelDialogOpen = true },
        sttModel = sttModel,
        ttsModel = ttsModel,
        isSttEnabled = isSttEnabled,
        isTtsEnabled = isTtsEnabled,
        onToggleStt = { viewModel.toggleStt(it) },
        onToggleTts = { viewModel.toggleTts(it) },
        onDownloadAudioModel = { viewModel.downloadAudioModel(it) },
        onTestSpeak = { viewModel.speak(it) }
    )

    // Chat History Drawer Modal
    SessionDrawerSheet(
        isOpen = isHistoryOpen,
        sessions = sessions,
        currentSessionId = currentSessionId,
        onDismiss = { isHistoryOpen = false },
        onSelectSession = { viewModel.switchSession(it) },
        onNewChat = { viewModel.createNewSession() },
        onDeleteSession = { viewModel.deleteSession(it) },
        onClearAllSessions = { viewModel.clearAllSessions() }
    )

    // Custom Model Importer Dialog
    CustomModelDialog(
        isOpen = isCustomModelDialogOpen,
        onDismiss = { isCustomModelDialogOpen = false },
        onModelConfigured = { customModel ->
            viewModel.addCustomModel(customModel)
        }
    )
}
