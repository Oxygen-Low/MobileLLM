package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.ChatMessageEntity
import com.example.model.ModelDownloadState
import com.example.model.ModelLoadState
import com.example.ui.components.ChatInputBar
import com.example.ui.components.ChatMessageItem
import com.example.ui.components.ModelsHubSheet
import com.example.ui.components.SessionDrawerSheet
import com.example.ui.components.TopTelemetryBar
import com.example.ui.theme.BgDark
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.CardHighlight
import com.example.ui.theme.ErrorRed
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
    val modelError by viewModel.modelError.collectAsStateWithLifecycle()
    val memoryMb by viewModel.allocatedMemoryMb.collectAsStateWithLifecycle()
    val currentTps by viewModel.currentTps.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val currentSessionId by viewModel.currentSessionId.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val searchEnabled by viewModel.searchEnabled.collectAsStateWithLifecycle()
    val reasoningLevel by viewModel.reasoningLevel.collectAsStateWithLifecycle()

    val streamingReasoning by viewModel.streamingReasoning.collectAsStateWithLifecycle()
    val streamingContent by viewModel.streamingContent.collectAsStateWithLifecycle()
    val activeToolName by viewModel.activeToolName.collectAsStateWithLifecycle()
    val activeToolQuery by viewModel.activeToolQuery.collectAsStateWithLifecycle()
    val activeToolSourceUrl by viewModel.activeToolSourceUrl.collectAsStateWithLifecycle()

    var isHistoryOpen by remember { mutableStateOf(false) }
    var isModelsHubOpen by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    val downloadInfo = downloadStates[currentModel.id]
    val isCurrentDownloaded = currentModel.downloadState == ModelDownloadState.DOWNLOADED ||
            downloadInfo?.state == ModelDownloadState.DOWNLOADED
    val isCurrentDownloading = downloadInfo?.state == ModelDownloadState.DOWNLOADING

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
                onOpenModelsHub = { isModelsHubOpen = true },
                onToggleHistory = { isHistoryOpen = true },
                onNewChat = { viewModel.createNewSession() }
            )
        },
        bottomBar = {
            ChatInputBar(
                isGenerating = isGenerating,
                searchEnabled = searchEnabled,
                modelName = currentModel.shortName,
                reasoningLevel = reasoningLevel,
                reasoningSupported = currentModel.supportsReasoning,
                onToggleSearch = { viewModel.toggleSearch(it) },
                onSelectReasoningLevel = { viewModel.setReasoningLevel(it) },
                onSendMessage = { viewModel.sendMessage(it) },
                onStopGenerating = { viewModel.stopGeneration() }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Persistent Model Download Banner if current model is not downloaded or downloading
                if (!isCurrentDownloaded) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, TelemetryCyan.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
                        color = SurfaceElevated
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(CircleShape)
                                            .background(TelemetryCyan.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Memory,
                                            contentDescription = null,
                                            tint = TelemetryCyan,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = "${currentModel.name} (${currentModel.parameters})",
                                            style = MaterialTheme.typography.titleSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = TextPrimary
                                            )
                                        )
                                        Text(
                                            text = if (isCurrentDownloading) "Downloading model weights into storage…"
                                            else "GGUF • ${currentModel.quantization} • ${currentModel.downloadSizeMb} MB",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                color = TextSecondary
                                            )
                                        )
                                    }
                                }

                                if (!isCurrentDownloading) {
                                    Button(
                                        onClick = { viewModel.downloadModel(currentModel) },
                                        colors = ButtonDefaults.buttonColors(containerColor = TelemetryCyan),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.testTag("download_banner_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CloudDownload,
                                            contentDescription = null,
                                            tint = SurfaceDark,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Download",
                                            color = SurfaceDark,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }

                            // Live Download Progress Bar
                            if (isCurrentDownloading && downloadInfo != null) {
                                Spacer(modifier = Modifier.height(10.dp))
                                LinearProgressIndicator(
                                    progress = { downloadInfo.progress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = TelemetryCyan,
                                    trackColor = BorderSubtle
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${(downloadInfo.progress * 100).toInt()}% • ${downloadInfo.downloadedMb.toInt()} / ${downloadInfo.totalMb.toInt()} MB",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            color = TelemetryCyan,
                                            fontSize = 11.sp
                                        )
                                    )
                                    Text(
                                        text = "${downloadInfo.speedMbps} MB/s",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            color = TerminalGreen,
                                            fontSize = 11.sp
                                        )
                                    )
                                }
                            }

                            val downloadError = downloadInfo?.error
                                ?.takeIf { downloadInfo?.state == ModelDownloadState.ERROR }
                            if (downloadError != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Download failed: $downloadError",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        color = ErrorRed,
                                        fontSize = 11.sp
                                    )
                                )
                            } else if (modelError != null && loadState == ModelLoadState.ERROR) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Model failed to load: $modelError",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        color = ErrorRed,
                                        fontSize = 11.sp
                                    )
                                )
                            }
                        }
                    }
                }

                // Chat Messages List
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        ChatMessageItem(
                            message = message,
                            modelName = currentModel.shortName
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
                                isStreaming = true
                            )
                        }
                    }
                }
            }
        }
    }

    // Models Hub Bottom Sheet
    ModelsHubSheet(
        isOpen = isModelsHubOpen,
        onDismiss = { isModelsHubOpen = false },
        models = availableModels,
        currentModel = currentModel,
        downloadStates = downloadStates,
        onSelectAndLoadModel = { viewModel.loadModel(it) },
        onDownloadModel = { viewModel.downloadModel(it) },
        onReinstallModel = { viewModel.reinstallModel(it) },
        onDeleteModel = { viewModel.deleteDownloadedModel(it) }
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
}
