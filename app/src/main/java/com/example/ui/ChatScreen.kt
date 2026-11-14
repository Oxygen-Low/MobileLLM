package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import com.example.data.ChatMessageEntity
import com.example.model.ModelDownloadState
import com.example.model.ModelLoadState
import com.example.ui.components.AgentStepItem
import com.example.ui.components.ChatInputBar
import com.example.ui.components.ChatMessageItem
import com.example.ui.components.ModelsHubSheet
import com.example.ui.components.SessionDrawerSheet
import com.example.ui.components.TopTelemetryBar
import com.example.ui.components.ChangelogDialog
import com.example.ui.components.FullscreenHtmlAppDialog
import com.example.ui.components.UpdateDialog
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
    val reasoningLevel by viewModel.reasoningLevel.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val changelogState by viewModel.changelogState.collectAsStateWithLifecycle()

    // Speech. The engines stay off until their toggle in the Model Hub is on.
    val sttEnabled by viewModel.sttEnabled.collectAsStateWithLifecycle()
    val ttsEnabled by viewModel.ttsEnabled.collectAsStateWithLifecycle()
    val sttState by viewModel.sttState.collectAsStateWithLifecycle()
    val ttsState by viewModel.ttsState.collectAsStateWithLifecycle()
    val sttError by viewModel.sttError.collectAsStateWithLifecycle()
    val ttsError by viewModel.ttsError.collectAsStateWithLifecycle()
    val speechError by viewModel.speechError.collectAsStateWithLifecycle()
    val isListening by viewModel.isListening.collectAsStateWithLifecycle()
    val isTranscribing by viewModel.isTranscribing.collectAsStateWithLifecycle()
    val transcribedText by viewModel.transcribedText.collectAsStateWithLifecycle()

    val streamingReasoning by viewModel.streamingReasoning.collectAsStateWithLifecycle()
    val streamingContent by viewModel.streamingContent.collectAsStateWithLifecycle()
    val agentSteps by viewModel.agentSteps.collectAsStateWithLifecycle()
    val persistedReplyId by viewModel.persistedReplyId.collectAsStateWithLifecycle()
    val sandboxContent by viewModel.sandboxContent.collectAsStateWithLifecycle()

    var isHistoryOpen by remember { mutableStateOf(false) }
    var isModelsHubOpen by remember { mutableStateOf(false) }
    var isSandboxOpen by remember { mutableStateOf(false) }

    // The composer text is hoisted so dictation can append a finished
    // transcription to whatever the user already typed.
    var inputText by remember { mutableStateOf("") }

    LaunchedEffect(transcribedText) {
        val text = transcribedText ?: return@LaunchedEffect
        inputText = if (inputText.isBlank()) text else inputText.trimEnd() + " " + text
        viewModel.consumeTranscription()
    }

    val context = LocalContext.current
    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startListening() else viewModel.onMicrophonePermissionDenied()
    }
    val onMicClick = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.startListening()
        } else {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val listState = rememberLazyListState()

    // True while the transcript is parked at the bottom. Once the user scrolls up
    // to read, this turns false and streaming updates stop chasing them.
    val isAtBottom by remember(listState) {
        derivedStateOf { !listState.canScrollForward }
    }

    // The live streaming bubble hands over to the finished reply only once that
    // reply is part of the transcript. Handing over earlier blinks the answer
    // out for a frame, handing over later shows it twice.
    val replyIsShown = persistedReplyId != null && messages.any { it.id == persistedReplyId }
    val showStreamingItem = !replyIsShown &&
            (isGenerating || streamingContent.isNotEmpty() || streamingReasoning.isNotEmpty())

    // A tool step that has already been written to the transcript is rendered
    // from there - showing the live one as well would print it twice.
    val liveSteps = remember(agentSteps, messages) {
        agentSteps.filterNot { step -> messages.any { it.id == step.id } }
    }

    val transcriptSize = messages.size + liveSteps.size + if (showStreamingItem) 1 else 0

    val downloadInfo = downloadStates[currentModel.id]
    val isCurrentDownloaded = currentModel.downloadState == ModelDownloadState.DOWNLOADED ||
            downloadInfo?.state == ModelDownloadState.DOWNLOADED
    val currentDownload = downloadInfo?.takeIf { it.state == ModelDownloadState.DOWNLOADING }
    val isCurrentDownloading = currentDownload != null

    // New message (user send or finalized reply): glide to the bottom. A message
    // the user just sent always pulls the view down; otherwise only follow when
    // the user is already at the bottom.
    //
    // A rewind makes the transcript *shorter*. The list is still scrolled to an
    // index that no longer exists at that moment, so an animated scroll would
    // race the removal of the items and land somewhere in between. Snap to the
    // new end instead - one clean reposition, no flicker. The item count of the
    // last layout is what tells us the list is about to shrink.
    LaunchedEffect(transcriptSize) {
        val shrinking = transcriptSize < listState.layoutInfo.totalItemsCount
        val userJustSent = messages.lastOrNull()?.role == "user"
        if (transcriptSize > 0 && (isAtBottom || userJustSent)) {
            if (shrinking) {
                listState.scrollToItem(transcriptSize - 1)
            } else {
                listState.animateScrollToItem(transcriptSize - 1)
            }
        }
    }

    // Streaming text grows the last item several times a second. Follow it with a
    // snap instead of starting a fresh animation per token - restarting an
    // animation that fast is what made mid-generation scrolling stutter.
    LaunchedEffect(streamingContent.length, streamingReasoning.length) {
        val hasStreamedText = streamingContent.isNotEmpty() || streamingReasoning.isNotEmpty()
        if (isGenerating && hasStreamedText && transcriptSize > 0 && isAtBottom) {
            listState.scrollToItem(transcriptSize - 1)
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .windowInsetsPadding(WindowInsets.ime)
            // Any touch/scroll counts as user activity and restarts the idle timer.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        viewModel.onUserActivity()
                    }
                }
            }
            .testTag("chat_screen"),
        containerColor = BgDark,
        topBar = {
            TopTelemetryBar(
                currentModel = currentModel,
                loadState = loadState,
                memoryMb = memoryMb,
                currentTps = currentTps,
                currentDate = viewModel.currentDate,
                hasSandboxContent = sandboxContent.isNotBlank(),
                onOpenSandbox = { isSandboxOpen = true },
                onOpenModelsHub = { isModelsHubOpen = true },
                onToggleHistory = { isHistoryOpen = true },
                onNewChat = { viewModel.createNewSession() }
            )
        },
        bottomBar = {
            ChatInputBar(
                isGenerating = isGenerating,
                modelName = currentModel.shortName,
                reasoningLevel = reasoningLevel,
                reasoningSupported = currentModel.supportsReasoning,
                sttEnabled = sttEnabled,
                isListening = isListening,
                isTranscribing = isTranscribing,
                text = inputText,
                onTextChange = { inputText = it },
                onMicClick = onMicClick,
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
                            if (currentDownload != null) {
                                Spacer(modifier = Modifier.height(10.dp))
                                LinearProgressIndicator(
                                    progress = { currentDownload.progress },
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
                                        text = "${(currentDownload.progress * 100).toInt()}% • ${currentDownload.downloadedMb.toInt()} / ${currentDownload.totalMb.toInt()} MB",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            color = TelemetryCyan,
                                            fontSize = 11.sp
                                        )
                                    )
                                    Text(
                                        text = "${currentDownload.speedMbps} MB/s",
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

                // Speech failures (permission, download, load, synthesis) are
                // reported here rather than in the model banner: they don't
                // block chatting.
                val activeSpeechError = speechError ?: sttError ?: ttsError
                if (activeSpeechError != null) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .testTag("speech_error_banner"),
                        color = SurfaceElevated,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            ErrorRed.copy(alpha = 0.5f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 12.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = activeSpeechError,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    color = ErrorRed,
                                    fontSize = 11.sp
                                )
                            )
                            IconButton(
                                onClick = { viewModel.dismissSpeechError() },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Dismiss",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(14.dp)
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
                        val isLastMessage = message.id == messages.lastOrNull()?.id
                        ChatMessageItem(
                            message = message,
                            modelName = currentModel.shortName,
                            // Rewind rolls back to an earlier response; regenerate
                            // re-runs the prompt behind the final response. Both are
                            // hidden while a reply is being streamed.
                            showRewind = !isGenerating && message.role == "assistant" && !isLastMessage,
                            showRegenerate = !isGenerating && message.role == "assistant" && isLastMessage,
                            onRewind = { viewModel.rewindToMessage(message.id) },
                            onRegenerate = { viewModel.regenerateLastResponse() }
                        )
                    }

                    // The steps of the agent run that are not in the transcript
                    // yet: the model asked for a tool, the tool answered.
                    items(liveSteps, key = { it.id }) { step ->
                        AgentStepItem(step = step)
                    }

                    // Render active streaming message item
                    if (showStreamingItem) {
                        item(key = "active_stream") {
                            // While a tool is running the step above already
                            // says what is going on, so the bubble stays empty
                            // instead of repeating "Thinking…".
                            val placeholder = liveSteps.isEmpty()
                            val activeMsg = ChatMessageEntity(
                                id = "streaming_active",
                                sessionId = currentSessionId ?: "",
                                role = "assistant",
                                content = when {
                                    streamingContent.isNotEmpty() -> streamingContent
                                    streamingReasoning.isNotEmpty() -> ""
                                    placeholder -> "Thinking…"
                                    else -> ""
                                },
                                reasoningContent = streamingReasoning.ifBlank { null },
                                tokensCount = (streamingReasoning.length + streamingContent.length) / 4,
                                tokensPerSecond = currentTps
                            )
                            ChatMessageItem(
                                message = activeMsg,
                                modelName = currentModel.shortName,
                                isStreaming = true,
                                showThinkingPlaceholder = placeholder
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
        loadState = loadState,
        downloadStates = downloadStates,
        sttEnabled = sttEnabled,
        ttsEnabled = ttsEnabled,
        sttState = sttState,
        ttsState = ttsState,
        sttError = sttError,
        ttsError = ttsError,
        onToggleStt = { viewModel.setSpeechToTextEnabled(it) },
        onToggleTts = { viewModel.setTextToSpeechEnabled(it) },
        onSelectAndLoadModel = { viewModel.loadModel(it) },
        onUnloadModel = { viewModel.unloadModel() },
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

    // App update prompt (checks on start and every 60 seconds while foregrounded)
    UpdateDialog(
        state = updateState,
        onDownload = { viewModel.downloadAndInstallUpdate() },
        onInstall = { viewModel.installUpdate() },
        onDismiss = { viewModel.dismissUpdate() }
    )

    // Changelog of the versions the app was updated over.
    ChangelogDialog(
        state = changelogState,
        onShow = { viewModel.showChangelog() },
        onDismiss = { viewModel.dismissChangelog() }
    )

    // Fullscreen interactive HTML sandbox dialog
    if (isSandboxOpen && sandboxContent.isNotBlank()) {
        FullscreenHtmlAppDialog(
            isOpen = true,
            htmlContent = sandboxContent,
            title = "Interactive App / Game Sandbox",
            onDismiss = { isSandboxOpen = false }
        )
    }
}
