package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.example.model.ModelDownloadState
import com.example.model.VideoModelProfile
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.CardHighlight
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.SurfaceElevated
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TerminalGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

/**
 * The picker for the video generation models of the Model Hub.
 *
 * Every catalogue entry is always listed: tapping a row makes it the model a
 * clip is rendered with, the side button downloads or removes its weights.
 * The same shape as the image and voice pickers keeps the hub predictable.
 * Every entry carries a sound marker, because the catalogue only holds
 * checkpoints that generate the soundtrack together with the picture.
 */
@Composable
fun VideoModelList(
    models: List<VideoModelProfile>,
    selectedModel: VideoModelProfile?,
    downloadedModels: Set<String>,
    downloadStates: Map<String, DownloadProgressInfo>,
    onSelectModel: (VideoModelProfile) -> Unit,
    onDownloadModel: (VideoModelProfile) -> Unit,
    onDeleteModel: (VideoModelProfile) -> Unit,
    onCancelDownload: (VideoModelProfile) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("video_model_list"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        models.forEach { model ->
            VideoModelRow(
                model = model,
                isSelected = model.id == selectedModel?.id,
                isDownloaded = model.id in downloadedModels,
                downloadInfo = downloadStates[model.id],
                onSelect = { onSelectModel(model) },
                onDownload = { onDownloadModel(model) },
                onDelete = { onDeleteModel(model) },
                onCancel = { onCancelDownload(model) }
            )
        }
    }
}

@Composable
private fun VideoModelRow(
    model: VideoModelProfile,
    isSelected: Boolean,
    isDownloaded: Boolean,
    downloadInfo: DownloadProgressInfo?,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit
) {
    val activeDownload = downloadInfo?.takeIf { it.state == ModelDownloadState.DOWNLOADING }
    val isDownloading = activeDownload != null

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(
                1.dp,
                if (isSelected) TelemetryCyan.copy(alpha = 0.6f) else BorderSubtle,
                RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onSelect)
            .testTag("video_model_${model.id}"),
        color = if (isSelected) CardHighlight else SurfaceElevated
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) TelemetryCyan.copy(alpha = 0.18f)
                            else SurfaceElevated.copy(alpha = 0.6f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isSelected) Icons.Default.Check else Icons.Default.Videocam,
                        contentDescription = null,
                        tint = if (isSelected) TelemetryCyan else TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = model.name,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = if (isSelected) TelemetryCyan else TextPrimary
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (model.hasAudio) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = TerminalGreen.copy(alpha = 0.14f)
                            ) {
                                Text(
                                    text = "SOUND",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 8.sp,
                                        color = TerminalGreen
                                    ),
                                    modifier = Modifier
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                        .testTag("video_sound_${model.id}")
                                )
                            }
                        }
                    }
                    Text(
                        text = "${model.quantization} • ${model.parameters} • ${model.resolution}px • ${model.frameRate} fps • ${model.durationSeconds}s",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.5.sp,
                            color = TextSecondary
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                when {
                    isDownloading -> IconButton(
                        onClick = onCancel,
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("video_cancel_${model.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel the download",
                            tint = ErrorRed,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    isDownloaded -> IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("video_delete_${model.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete ${model.name}",
                            tint = ErrorRed,
                            modifier = Modifier.size(17.dp)
                        )
                    }

                    else -> IconButton(
                        onClick = onDownload,
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("video_download_${model.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = "Download ${model.name}",
                            tint = TelemetryCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Text(
                text = model.description,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp,
                    color = TextMuted
                ),
                modifier = Modifier.padding(top = 6.dp)
            )

            if (activeDownload != null) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { activeDownload.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
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
                            fontSize = 10.sp,
                            color = TelemetryCyan
                        )
                    )
                    if (activeDownload.speedMbps > 0f) {
                        Text(
                            text = "${activeDownload.speedMbps} MB/s",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = TerminalGreen
                            )
                        )
                    }
                }
            } else if (!isDownloaded && downloadInfo?.state != ModelDownloadState.ERROR) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Not downloaded • ${model.downloadSizeMb} MB",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = TextMuted
                    )
                )
            }

            val error = downloadInfo?.takeIf { it.state == ModelDownloadState.ERROR }?.error
            if (error != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Download failed: $error",
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