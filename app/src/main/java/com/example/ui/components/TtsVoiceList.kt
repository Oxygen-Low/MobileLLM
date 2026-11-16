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
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.example.model.DownloadProgressInfo
import com.example.model.ModelDownloadState
import com.example.model.SpeechModelProfile
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceElevated
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

/**
 * The list of Piper voices the user can speak with.
 *
 * Every row shows the voice, its language and how big it is. A voice that is
 * not on the device yet can be fetched without switching to it, so the user
 * can hear it before making it the voice of the app. A voice that is on the
 * device can be removed again with the bin next to the preview button.
 */
@Composable
fun TtsVoiceList(
    voices: List<SpeechModelProfile>,
    selectedVoice: SpeechModelProfile,
    downloadedVoices: Set<String>,
    downloadStates: Map<String, DownloadProgressInfo>,
    previewingVoiceId: String?,
    onSelectVoice: (SpeechModelProfile) -> Unit,
    onDownloadVoice: (SpeechModelProfile) -> Unit,
    onPreviewVoice: (SpeechModelProfile) -> Unit,
    onDeleteVoice: (SpeechModelProfile) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("tts_voice_list"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        voices.forEach { voice ->
            TtsVoiceRow(
                voice = voice,
                isSelected = voice.id == selectedVoice.id,
                isDownloaded = voice.id in downloadedVoices,
                downloadInfo = downloadStates[voice.id],
                isPreviewing = previewingVoiceId == voice.id,
                onSelect = { onSelectVoice(voice) },
                onDownload = { onDownloadVoice(voice) },
                onPreview = { onPreviewVoice(voice) },
                onDelete = { onDeleteVoice(voice) }
            )
        }
    }
}

@Composable
private fun TtsVoiceRow(
    voice: SpeechModelProfile,
    isSelected: Boolean,
    isDownloaded: Boolean,
    downloadInfo: DownloadProgressInfo?,
    isPreviewing: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onPreview: () -> Unit,
    onDelete: () -> Unit
) {
    val isDownloading = downloadInfo?.state == ModelDownloadState.DOWNLOADING

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) SurfaceElevated else Color.Transparent)
            .border(
                1.dp,
                if (isSelected) TelemetryCyan.copy(alpha = 0.6f) else BorderSubtle,
                RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onSelect)
            .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
            .testTag("tts_voice_${voice.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(
                    if (isSelected) TelemetryCyan.copy(alpha = 0.2f) else SurfaceDark
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = TelemetryCyan,
                    modifier = Modifier.size(15.dp)
                )
            } else {
                Text(
                    text = voice.name.take(1).uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = TextMuted
                    )
                )
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = voice.name,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = if (isSelected) TelemetryCyan else TextPrimary
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${voice.language} • ${voice.quality} • ${voice.downloadSizeMb} MB",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = TextSecondary
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (isDownloading) {
                Spacer(modifier = Modifier.height(5.dp))
                LinearProgressIndicator(
                    progress = { downloadInfo?.progress ?: 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = TelemetryCyan,
                    trackColor = BorderSubtle
                )
            }
        }

        Spacer(modifier = Modifier.width(4.dp))

        if (isDownloading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = TelemetryCyan
            )
        } else if (isDownloaded) {
            IconButton(
                onClick = onDelete,
                modifier = Modifier.testTag("tts_delete_${voice.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete ${voice.name}",
                    tint = ErrorRed,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(
                onClick = onPreview,
                modifier = Modifier.testTag("tts_preview_${voice.id}")
            ) {
                if (isPreviewing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = TelemetryCyan
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Preview ${voice.name}",
                        tint = TelemetryCyan,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        } else {
            IconButton(
                onClick = onDownload,
                modifier = Modifier.testTag("tts_download_${voice.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = "Download ${voice.name}",
                    tint = TextSecondary,
                    modifier = Modifier.size(19.dp)
                )
            }
        }
    }
}
