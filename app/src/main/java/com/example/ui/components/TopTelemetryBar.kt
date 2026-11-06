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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.example.model.ModelLoadState
import com.example.model.ModelProfile
import com.example.ui.theme.BgDark
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.GptEmerald
import com.example.ui.theme.ReasoningAmber
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceElevated
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TerminalGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun TopTelemetryBar(
    currentModel: ModelProfile,
    loadState: ModelLoadState,
    memoryMb: Int,
    currentTps: Double,
    currentDate: String,
    isTtsEnabled: Boolean,
    onOpenModelsHub: () -> Unit,
    onToggleHistory: () -> Unit,
    onNewChat: () -> Unit,
    onToggleTts: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("top_telemetry_bar"),
        color = BgDark,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            // Top Row: Model Selector Dropdown Button & Clean Minimalist Action Icons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Models Menu Button (ChatGPT Style Model Selector Button)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onOpenModelsHub() }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .testTag("models_hub_button"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusIndicatorDot(loadState = loadState)

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = currentModel.shortName,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            color = TextPrimary
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.width(4.dp))

                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "Open Models Menu",
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Actions: TTS Toggle, New Chat, History
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // TTS Voice Output Toggle
                    IconButton(
                        onClick = onToggleTts,
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("tts_toggle_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.VolumeUp,
                            contentDescription = "Text to Speech",
                            tint = if (isTtsEnabled) GptEmerald else TextMuted,
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    // Chat History
                    IconButton(
                        onClick = onToggleHistory,
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("history_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = "Chat History",
                            tint = TextSecondary,
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    // New Chat
                    IconButton(
                        onClick = onNewChat,
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("new_chat_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "New Chat",
                            tint = TextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Minimalist Corporate Telemetry Pill: Speed, Memory, Date
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(SurfaceDark.copy(alpha = 0.5f))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${currentModel.format.name} • ${currentModel.quantization}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = TextMuted,
                            fontSize = 10.5.sp
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (currentTps > 0) String.format("%.1f T/s", currentTps) else "0.0 T/s",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = if (currentTps > 0) GptEmerald else TextMuted,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (memoryMb > 0) "$memoryMb MB" else "Unloaded",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = TextMuted,
                            fontSize = 10.5.sp
                        )
                    )
                }

                // Date Chip
                Text(
                    text = currentDate,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = TextMuted,
                        fontSize = 10.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun StatusIndicatorDot(loadState: ModelLoadState) {
    val color = when (loadState) {
        ModelLoadState.LOADED -> TerminalGreen
        ModelLoadState.LOADING, ModelLoadState.UNLOADING -> ReasoningAmber
        ModelLoadState.UNLOADED -> TextMuted
        ModelLoadState.ERROR -> ErrorRed
    }

    Box(
        modifier = Modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(color)
    )
}
