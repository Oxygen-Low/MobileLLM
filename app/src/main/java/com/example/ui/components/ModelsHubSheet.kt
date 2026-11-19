package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.DownloadProgressInfo
import com.example.model.ImageEngineState
import com.example.model.ImageModelProfile
import com.example.model.ModelDownloadState
import com.example.model.ModelHubTab
import com.example.model.ModelLoadState
import com.example.model.ModelProfile
import com.example.model.SpeechEngineState
import com.example.model.SpeechModelKind
import com.example.model.SpeechModelProfile
import com.example.model.VideoEngineState
import com.example.model.VideoModelProfile
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsHubSheet(
    isOpen: Boolean,
    onDismiss: () -> Unit,
    models: List<ModelProfile>,
    currentModel: ModelProfile,
    loadState: ModelLoadState,
    downloadStates: Map<String, DownloadProgressInfo>,
    sttEnabled: Boolean,
    ttsEnabled: Boolean,
    sttState: SpeechEngineState,
    ttsState: SpeechEngineState,
    sttError: String?,
    ttsError: String?,
    ttsVoices: List<SpeechModelProfile>,
    selectedTtsVoice: SpeechModelProfile,
    downloadedTtsVoices: Set<String>,
    previewingTtsVoiceId: String?,
    imageModels: List<ImageModelProfile> = emptyList(),
    selectedImageModel: ImageModelProfile? = null,
    downloadedImageModels: Set<String> = emptySet(),
    imageState: ImageEngineState = ImageEngineState.UNLOADED,
    imageError: String? = null,
    videoModels: List<VideoModelProfile> = emptyList(),
    selectedVideoModel: VideoModelProfile? = null,
    downloadedVideoModels: Set<String> = emptySet(),
    videoState: VideoEngineState = VideoEngineState.UNLOADED,
    videoError: String? = null,
    hordeApiKey: String = "",
    hordeTextModels: List<ModelProfile> = emptyList(),
    hordeImageModels: List<ImageModelProfile> = emptyList(),
    hordeSelectedTextModel: ModelProfile? = null,
    hordeSelectedImageModel: ImageModelProfile? = null,
    hordeLoading: Boolean = false,
    hordeError: String? = null,
    onToggleStt: (Boolean) -> Unit,
    onToggleTts: (Boolean) -> Unit,
    onSelectTtsVoice: (SpeechModelProfile) -> Unit,
    onDownloadTtsVoice: (SpeechModelProfile) -> Unit,
    onPreviewTtsVoice: (SpeechModelProfile) -> Unit,
    onDeleteTtsVoice: (SpeechModelProfile) -> Unit,
    onSelectAndLoadModel: (ModelProfile) -> Unit,
    onUnloadModel: () -> Unit,
    onDownloadModel: (ModelProfile) -> Unit,
    onReinstallModel: (ModelProfile) -> Unit,
    onDeleteModel: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onSelectImageModel: (ImageModelProfile) -> Unit = {},
    onDownloadImageModel: (ImageModelProfile) -> Unit = {},
    onDeleteImageModel: (ImageModelProfile) -> Unit = {},
    onSelectVideoModel: (VideoModelProfile) -> Unit = {},
    onDownloadVideoModel: (VideoModelProfile) -> Unit = {},
    onDeleteVideoModel: (VideoModelProfile) -> Unit = {},
    onHordeTabOpened: () -> Unit = {},
    onSaveHordeApiKey: (String) -> Unit = {},
    onRefreshHordeModels: () -> Unit = {},
    onSelectHordeTextModel: (ModelProfile) -> Unit = {},
    onSelectHordeImageModel: (ImageModelProfile) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (!isOpen) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // The hub holds every kind of model, so each kind gets its own tab.
    var selectedTab by remember { mutableStateOf(ModelHubTab.TEXT) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceDark,
        tonalElevation = 6.dp,
        modifier = modifier.testTag("models_hub_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 16.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(TelemetryCyan.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SmartToy,
                            contentDescription = null,
                            tint = TelemetryCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Model Hub",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                fontSize = 18.sp
                            )
                        )
                        Text(
                            text = "Download & Run On-Device GGUF Models",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }

                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // The tabs stay above the scroll area, so the kind of model the
            // user is looking at is always visible.
            ModelHubTabRow(
                selectedTab = selectedTab,
                onSelectTab = { selectedTab = it }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Every tab has its own list of models.
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("model_hub_list"),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (selectedTab) {
                    ModelHubTab.TEXT -> {
                        item(key = "models_header") {
                            Column {
                                Spacer(modifier = Modifier.height(6.dp))
                                SectionHeader("AVAILABLE LOCAL WEIGHTS")
                            }
                        }

                        items(models, key = { it.id }) { model ->
                            val downloadInfo = downloadStates[model.id]
                            val isDownloaded = model.downloadState == ModelDownloadState.DOWNLOADED ||
                                    downloadInfo?.state == ModelDownloadState.DOWNLOADED
                            val isDownloading = downloadInfo?.state == ModelDownloadState.DOWNLOADING
                            val isCurrentActive = model.id == currentModel.id
                            val isLoaded = isCurrentActive && loadState == ModelLoadState.LOADED
                            val isLoading = isCurrentActive && loadState == ModelLoadState.LOADING
                            val isUnloading = isCurrentActive && loadState == ModelLoadState.UNLOADING

                            ModelCard(
                                model = model,
                                isActive = isCurrentActive,
                                isLoaded = isLoaded,
                                isLoading = isLoading,
                                isUnloading = isUnloading,
                                isDownloaded = isDownloaded,
                                isDownloading = isDownloading,
                                downloadInfo = downloadInfo,
                                onSelectAndLoad = {
                                    onSelectAndLoadModel(model)
                                    onDismiss()
                                },
                                onUnload = onUnloadModel,
                                onDownload = { onDownloadModel(model) },
                                onReinstall = { onReinstallModel(model) },
                                onDelete = { onDeleteModel(model.id) },
                                onCancelDownload = { onCancelDownload(model.id) }
                            )
                        }
                    }

                    ModelHubTab.STT -> {
                        item(key = "speech_header") {
                            SectionHeader("ON-DEVICE SPEECH")
                        }

                        item(key = "speech_stt") {
                            SpeechFeatureCard(
                                title = "Speech to Text",
                                subtitle = "Whisper Tiny (int8) • offline dictation",
                                model = SpeechModelProfile.WHISPER_TINY,
                                enabled = sttEnabled,
                                state = sttState,
                                error = sttError,
                                downloadInfo = downloadStates[SpeechModelProfile.WHISPER_TINY.id],
                                onToggle = onToggleStt,
                                onCancelDownload = {
                                    onCancelDownload(SpeechModelProfile.WHISPER_TINY.id)
                                }
                            )
                        }
                    }

                    ModelHubTab.TTS -> {
                        item(key = "speech_header") {
                            SectionHeader("ON-DEVICE SPEECH")
                        }

                        item(key = "speech_tts") {
                            SpeechFeatureCard(
                                title = "Text to Speech",
                                subtitle = "${selectedTtsVoice.name} • ${selectedTtsVoice.language} • offline read-aloud",
                                model = selectedTtsVoice,
                                enabled = ttsEnabled,
                                state = ttsState,
                                error = ttsError,
                                downloadInfo = downloadStates[selectedTtsVoice.id],
                                onToggle = onToggleTts,
                                onCancelDownload = {
                                    onCancelDownload(selectedTtsVoice.id)
                                },
                                trailing = {
                                    IconButton(
                                        onClick = { onPreviewTtsVoice(selectedTtsVoice) },
                                        enabled = selectedTtsVoice.id in downloadedTtsVoices &&
                                            previewingTtsVoiceId == null,
                                        modifier = Modifier.testTag("tts_preview_current")
                                    ) {
                                        if (previewingTtsVoiceId == selectedTtsVoice.id) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp,
                                                color = TelemetryCyan
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = "Preview the current voice",
                                                tint = TelemetryCyan,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            )
                        }

                        item(key = "speech_tts_voices") {
                            Column {
                                // Every voice of the catalogue is listed right
                                // away - the tab exists to pick a voice, so
                                // hiding the list behind a toggle only made the
                                // user tap once more for it.
                                Text(
                                    text = "VOICES",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        letterSpacing = 1.sp
                                    ),
                                    color = TextMuted,
                                    modifier = Modifier.testTag("tts_voices_header")
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                TtsVoiceList(
                                    voices = ttsVoices,
                                    selectedVoice = selectedTtsVoice,
                                    downloadedVoices = downloadedTtsVoices,
                                    downloadStates = downloadStates,
                                    previewingVoiceId = previewingTtsVoiceId,
                                    onSelectVoice = onSelectTtsVoice,
                                    onDownloadVoice = onDownloadTtsVoice,
                                    onPreviewVoice = onPreviewTtsVoice,
                                    onDeleteVoice = onDeleteTtsVoice,
                                    onCancelDownload = { voice -> onCancelDownload(voice.id) }
                                )
                            }
                        }
                    }

                    ModelHubTab.IMAGE -> {
                        item(key = "image_header") {
                            Column {
                                Spacer(modifier = Modifier.height(6.dp))
                                SectionHeader("ON-DEVICE IMAGE GENERATION")
                            }
                        }

                        item(key = "image_models") {
                            Column {
                                val model = selectedImageModel
                                if (model != null) {
                                    Text(
                                        text = "PICTURES ARE DRAWN WITH ${model.name.uppercase()}",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            letterSpacing = 0.5.sp
                                        ),
                                        color = TextMuted
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                }
                                ImageModelList(
                                    models = imageModels,
                                    selectedModel = selectedImageModel,
                                    downloadedModels = downloadedImageModels,
                                    downloadStates = downloadStates,
                                    onSelectModel = onSelectImageModel,
                                    onDownloadModel = onDownloadImageModel,
                                    onDeleteModel = onDeleteImageModel,
                                    onCancelDownload = { image -> onCancelDownload(image.id) }
                                )
                                val imageMessage = imageError ?: downloadStates[selectedImageModel?.id]
                                    ?.takeIf { it.state == ModelDownloadState.ERROR }
                                    ?.error
                                if (imageMessage != null && imageState == ImageEngineState.ERROR) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = imageMessage,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            color = ErrorRed
                                        )
                                    )
                                }
                            }
                        }
                    }

                    ModelHubTab.VIDEO -> {
                        item(key = "video_header") {
                            Column {
                                Spacer(modifier = Modifier.height(6.dp))
                                SectionHeader("ON-DEVICE VIDEO GENERATION WITH SOUND")
                            }
                        }

                        item(key = "video_models") {
                            Column {
                                val model = selectedVideoModel
                                if (model != null) {
                                    Text(
                                        text = "CLIPS ARE RENDERED WITH ${model.name.uppercase()} AND ITS SOUNDTRACK",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            letterSpacing = 0.5.sp
                                        ),
                                        color = TextMuted
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                }
                                VideoModelList(
                                    models = videoModels,
                                    selectedModel = selectedVideoModel,
                                    downloadedModels = downloadedVideoModels,
                                    downloadStates = downloadStates,
                                    onSelectModel = onSelectVideoModel,
                                    onDownloadModel = onDownloadVideoModel,
                                    onDeleteModel = onDeleteVideoModel,
                                    onCancelDownload = { video -> onCancelDownload(video.id) }
                                )
                                val videoMessage = videoError ?: downloadStates[selectedVideoModel?.id]
                                    ?.takeIf { it.state == ModelDownloadState.ERROR }
                                    ?.error
                                if (videoMessage != null && videoState == VideoEngineState.ERROR) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = videoMessage,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            color = ErrorRed
                                        )
                                    )
                                }
                            }
                        }
                    }

                    ModelHubTab.HORDE -> {
                        item(key = "horde") {
                            // The catalogues are fetched the first time the tab
                            // opens, not on every app start: they change from
                            // minute to minute and need a network round trip.
                            LaunchedEffect(Unit) { onHordeTabOpened() }
                            HordeTab(
                                apiKey = hordeApiKey,
                                textModels = hordeTextModels,
                                imageModels = hordeImageModels,
                                selectedTextModel = hordeSelectedTextModel,
                                selectedImageModel = hordeSelectedImageModel,
                                loading = hordeLoading,
                                error = hordeError,
                                onSaveApiKey = onSaveHordeApiKey,
                                onRefresh = onRefreshHordeModels,
                                onSelectTextModel = onSelectHordeTextModel,
                                onSelectImageModel = onSelectHordeImageModel
                            )
                        }
                    }

                    ModelHubTab.MUSIC -> {
                        item(key = "work_in_progress") {
                            WorkInProgressPanel(title = selectedTab.title)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp
        ),
        color = TextMuted
    )
}

/**
 * One speech feature. The switch is the only thing that allocates or releases
 * the engine; while it is on, the card reports download/load progress and the
 * model is cached until the app goes idle.
 */
@Composable
private fun SpeechFeatureCard(
    title: String,
    subtitle: String,
    model: SpeechModelProfile,
    enabled: Boolean,
    state: SpeechEngineState,
    error: String?,
    downloadInfo: DownloadProgressInfo?,
    onToggle: (Boolean) -> Unit,
    onCancelDownload: () -> Unit,
    trailing: @Composable (() -> Unit)? = null
) {
    val activeDownload = downloadInfo?.takeIf { it.state == ModelDownloadState.DOWNLOADING }
    val status = when (state) {
        SpeechEngineState.DISABLED -> "Off • no memory or storage used"
        SpeechEngineState.NOT_DOWNLOADED -> "Enabled • model not downloaded yet"
        SpeechEngineState.DOWNLOADING -> "Downloading ${model.downloadSizeMb} MB…"
        SpeechEngineState.LOADING -> "Loading into memory…"
        SpeechEngineState.READY -> "Loaded • ready"
        SpeechEngineState.UNLOADED -> "Released while idle • reloads on first use"
        SpeechEngineState.ERROR -> "Error"
    }
    val statusColor = when (state) {
        SpeechEngineState.READY -> TerminalGreen
        SpeechEngineState.DOWNLOADING, SpeechEngineState.LOADING -> TelemetryCyan
        SpeechEngineState.ERROR -> ErrorRed
        else -> TextMuted
    }
    val icon = when (model.kind) {
        SpeechModelKind.SPEECH_TO_TEXT -> Icons.Default.RecordVoiceOver
        SpeechModelKind.TEXT_TO_SPEECH -> Icons.AutoMirrored.Filled.VolumeUp
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(
                1.dp,
                if (enabled) TelemetryCyan.copy(alpha = 0.6f) else BorderSubtle,
                RoundedCornerShape(12.dp)
            )
            .testTag("speech_card_${model.id}"),
        color = if (enabled) CardHighlight else SurfaceElevated
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(
                            if (enabled) TelemetryCyan.copy(alpha = 0.18f)
                            else SurfaceDark.copy(alpha = 0.6f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (enabled) TelemetryCyan else TextMuted,
                        modifier = Modifier.size(17.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = if (enabled) TelemetryCyan else TextPrimary
                        )
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.5.sp,
                            color = TextSecondary
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (trailing != null) {
                    trailing()
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.testTag("speech_toggle_${model.id}")
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = status,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.5.sp,
                    color = statusColor
                )
            )

            if (activeDownload != null) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { activeDownload.progress },
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
                        text = "${(activeDownload.progress * 100).toInt()}% • ${activeDownload.downloadedMb.toInt()} / ${activeDownload.totalMb.toInt()} MB",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = TelemetryCyan,
                            fontSize = 10.5.sp
                        )
                    )
                    if (activeDownload.speedMbps > 0f) {
                        Text(
                            text = "${activeDownload.speedMbps} MB/s",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                color = TerminalGreen,
                                fontSize = 10.5.sp
                            )
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onCancelDownload,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
                    modifier = Modifier.testTag("cancel_speech_${model.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        tint = ErrorRed,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Cancel Download", color = ErrorRed, fontSize = 12.sp)
                }
            }

            val message = error ?: downloadInfo
                ?.takeIf { it.state == ModelDownloadState.ERROR }
                ?.error
            if (message != null && state == SpeechEngineState.ERROR) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = ErrorRed
                    )
                )
            }
        }
    }
}

@Composable
private fun ModelCard(
    model: ModelProfile,
    isActive: Boolean,
    isLoaded: Boolean,
    isLoading: Boolean,
    isUnloading: Boolean,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    downloadInfo: DownloadProgressInfo?,
    onSelectAndLoad: () -> Unit,
    onUnload: () -> Unit,
    onDownload: () -> Unit,
    onReinstall: () -> Unit,
    onDelete: () -> Unit,
    onCancelDownload: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(
                1.dp,
                if (isActive) TelemetryCyan else BorderSubtle,
                RoundedCornerShape(12.dp)
            )
            .testTag("model_card_${model.id}"),
        color = if (isActive) CardHighlight else SurfaceElevated
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header Row: Model Name, Active Pill, Specs
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = model.name,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = if (isActive) TelemetryCyan else TextPrimary
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isLoaded) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = TerminalGreen.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    text = "LOADED",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TerminalGreen
                                    ),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = "${model.format.name} • ${model.quantization} • ${model.parameters} • ${model.contextLength} ctx • ${model.memoryMb} MB RAM",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (model.modelScopeRepo != null) {
                Text(
                    text = "Repo: ${model.modelScopeRepo}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = TextMuted
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Live Download Progress Bar
            if (isDownloading && downloadInfo != null) {
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
                            color = TelemetryCyan
                        )
                    )
                    Text(
                        text = "${downloadInfo.speedMbps} MB/s",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = TerminalGreen
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
                        fontSize = 10.sp,
                        color = ErrorRed
                    )
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (isDownloaded) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (isLoaded) {
                            OutlinedButton(
                                onClick = onUnload,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("unload_${model.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PowerSettingsNew,
                                    contentDescription = null,
                                    tint = ErrorRed,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    "Unload from Memory",
                                    color = ErrorRed,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        } else {
                            Button(
                                onClick = onSelectAndLoad,
                                enabled = !isActive && !isLoading && !isUnloading,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isActive) BorderSubtle else TelemetryCyan,
                                    disabledContainerColor = BorderSubtle
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (isLoading || isUnloading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = TelemetryCyan
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        if (isLoading) "Loading into Memory…" else "Unloading…",
                                        color = TextSecondary,
                                        fontSize = 12.sp
                                    )
                                } else {
                                    Text("Load into Memory", color = SurfaceDark, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                        ) {
                            OutlinedButton(
                                onClick = onReinstall,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("reinstall_${model.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    tint = TelemetryCyan,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Reinstall", color = TelemetryCyan, fontSize = 12.sp)
                            }

                            OutlinedButton(
                                onClick = onDelete,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("delete_${model.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = ErrorRed,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Delete", color = ErrorRed, fontSize = 12.sp)
                            }
                        }
                    }
                } else if (isDownloading) {
                    OutlinedButton(
                        onClick = onCancelDownload,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
                        modifier = Modifier.testTag("cancel_download_${model.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            tint = ErrorRed,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Cancel Download", color = ErrorRed, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                } else {
                    Button(
                        onClick = onDownload,
                        colors = ButtonDefaults.buttonColors(containerColor = TelemetryCyan),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("download_${model.id}")
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, tint = SurfaceDark, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Download (${model.downloadSizeMb} MB)", color = SurfaceDark, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
