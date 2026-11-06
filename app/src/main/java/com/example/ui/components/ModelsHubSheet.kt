package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AudioModelProfile
import com.example.model.DownloadProgressInfo
import com.example.model.ModelDownloadState
import com.example.model.ModelProfile
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.CardHighlight
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.ReasoningAmber
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
    downloadStates: Map<String, DownloadProgressInfo>,
    onSelectAndLoadModel: (ModelProfile) -> Unit,
    onDownloadModel: (ModelProfile) -> Unit,
    onDeleteModel: (String) -> Unit,
    onOpenCustomFilePicker: () -> Unit,
    // Audio / Voice Models
    sttModel: AudioModelProfile,
    ttsModel: AudioModelProfile,
    isSttEnabled: Boolean,
    isTtsEnabled: Boolean,
    onToggleStt: (Boolean) -> Unit,
    onToggleTts: (Boolean) -> Unit,
    onDownloadAudioModel: (AudioModelProfile) -> Unit,
    onTestSpeak: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isOpen) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTab by remember { mutableIntStateOf(0) }

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
                .fillMaxHeight(0.88f)
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
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(TelemetryCyan.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SmartToy,
                            contentDescription = null,
                            tint = TelemetryCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Model Hub",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        )
                        Text(
                            text = "Download & Manage On-Device Weights",
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

            Spacer(modifier = Modifier.height(8.dp))

            // Navigation Tabs: LLM Models vs Audio (TTS / STT)
            SecondaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = SurfaceDark,
                contentColor = TelemetryCyan,
                divider = { HorizontalDivider(color = BorderSubtle) }
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("LLM Models (${models.size})", fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Headphones, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("TTS & STT Voice", fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Content per Tab
            if (selectedTab == 0) {
                // LLM Models Tab
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Text(
                            text = "HUGGING FACE LOCAL WEIGHTS",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = TextMuted
                        )
                    }

                    items(models, key = { it.id }) { model ->
                        val downloadInfo = downloadStates[model.id]
                        val isDownloaded = model.downloadState == ModelDownloadState.DOWNLOADED ||
                                downloadInfo?.state == ModelDownloadState.DOWNLOADED ||
                                model.isCustom
                        val isDownloading = downloadInfo?.state == ModelDownloadState.DOWNLOADING
                        val isCurrentActive = model.id == currentModel.id

                        ModelCard(
                            model = model,
                            isActive = isCurrentActive,
                            isDownloaded = isDownloaded,
                            isDownloading = isDownloading,
                            downloadInfo = downloadInfo,
                            onSelectAndLoad = {
                                onSelectAndLoadModel(model)
                                onDismiss()
                            },
                            onDownload = { onDownloadModel(model) },
                            onDelete = { onDeleteModel(model.id) }
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = {
                                onDismiss()
                                onOpenCustomFilePicker()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("import_custom_file_btn"),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = ReasoningAmber)
                        ) {
                            Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Import Custom .gguf / .safetensors Weights…")
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            } else {
                // Audio (TTS & STT) Tab
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Text(
                            text = "ON-DEVICE SPEECH & VOICE ENGINES",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = TextMuted
                        )
                    }

                    // STT Card
                    item {
                        AudioEngineCard(
                            title = "Speech-to-Text (STT)",
                            icon = Icons.Default.Mic,
                            model = sttModel,
                            isEnabled = isSttEnabled,
                            onToggle = onToggleStt,
                            onDownload = { onDownloadAudioModel(sttModel) },
                            downloadInfo = downloadStates[sttModel.id],
                            accentColor = TelemetryCyan
                        )
                    }

                    // TTS Card
                    item {
                        AudioEngineCard(
                            title = "Text-to-Speech (TTS)",
                            icon = Icons.Default.VolumeUp,
                            model = ttsModel,
                            isEnabled = isTtsEnabled,
                            onToggle = onToggleTts,
                            onDownload = { onDownloadAudioModel(ttsModel) },
                            downloadInfo = downloadStates[ttsModel.id],
                            accentColor = ReasoningAmber,
                            onTestAudio = {
                                onTestSpeak("PocketLM on-device speech engine is active and ready.")
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelCard(
    model: ModelProfile,
    isActive: Boolean,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    downloadInfo: DownloadProgressInfo?,
    onSelectAndLoad: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit
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
            // Header Row: Model Name, Active Pill / Format Badge
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
                                fontSize = 14.sp,
                                color = if (isActive) TelemetryCyan else TextPrimary
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isActive) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = TerminalGreen.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    text = "ACTIVE",
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

                    Text(
                        text = "${model.format.name} • ${model.quantization} • ${model.parameters} • ${model.contextLength} ctx",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    )
                }

                // Delete button if downloaded and not active
                if (isDownloaded && !isActive && !model.isCustom) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Hugging Face repo & size indicator
            if (model.huggingFaceRepo != null) {
                Text(
                    text = "HF: ${model.huggingFaceRepo}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = TextMuted
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Description removed per specification

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
                        text = "${downloadInfo.downloadedMb.toInt()} / ${downloadInfo.totalMb.toInt()} MB",
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

            Spacer(modifier = Modifier.height(10.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (isDownloaded) {
                    Button(
                        onClick = onSelectAndLoad,
                        enabled = !isActive,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isActive) BorderSubtle else TelemetryCyan,
                            disabledContainerColor = BorderSubtle
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        if (isActive) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Loaded in Memory", color = TextSecondary, fontSize = 12.sp)
                        } else {
                            Text("Load into Memory", color = SurfaceDark, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                } else if (isDownloading) {
                    OutlinedButton(
                        onClick = {},
                        enabled = false,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = TelemetryCyan)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Downloading…", fontSize = 12.sp)
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

@Composable
private fun AudioEngineCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    model: AudioModelProfile,
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onDownload: () -> Unit,
    downloadInfo: DownloadProgressInfo?,
    accentColor: Color,
    onTestAudio: (() -> Unit)? = null
) {
    val isDownloaded = model.downloadState == ModelDownloadState.DOWNLOADED || downloadInfo?.state == ModelDownloadState.DOWNLOADED
    val isDownloading = model.downloadState == ModelDownloadState.DOWNLOADING || downloadInfo?.state == ModelDownloadState.DOWNLOADING

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp)),
        color = SurfaceElevated
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(imageVector = icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(16.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        )
                        Text(
                            text = "${model.name} (${model.sizeMb} MB)",
                            style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
                        )
                    }
                }

                Switch(
                    checked = isEnabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = SurfaceDark,
                        checkedTrackColor = accentColor
                    )
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "HuggingFace: ${model.huggingFaceRepo}/${model.huggingFaceFile}",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = TextMuted
                )
            )

            // Description removed per specification

            if (isDownloading && downloadInfo != null) {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { downloadInfo.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = accentColor,
                    trackColor = BorderSubtle
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isDownloaded && !isDownloading) {
                    Text(
                        text = "Auto-downloads when enabled",
                        style = MaterialTheme.typography.labelSmall.copy(color = TextMuted)
                    )

                    Button(
                        onClick = onDownload,
                        colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, tint = SurfaceDark, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Download Model", color = SurfaceDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                } else if (isDownloading) {
                    Text(
                        text = "Downloading model weights…",
                        style = MaterialTheme.typography.labelSmall.copy(color = accentColor)
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = TerminalGreen, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Model Downloaded",
                            style = MaterialTheme.typography.labelSmall.copy(color = TerminalGreen, fontWeight = FontWeight.Bold)
                        )
                    }

                    if (onTestAudio != null && isEnabled) {
                        OutlinedButton(
                            onClick = onTestAudio,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = accentColor)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Test Voice", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}
