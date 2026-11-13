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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ReasoningLevel
import com.example.ui.theme.BgDark
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.BrandEmerald
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.ReasoningAmber
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceElevated
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun ChatInputBar(
    isGenerating: Boolean,
    modelName: String,
    reasoningLevel: ReasoningLevel,
    reasoningSupported: Boolean,
    sttEnabled: Boolean,
    isListening: Boolean,
    isTranscribing: Boolean,
    text: String,
    onTextChange: (String) -> Unit,
    onMicClick: () -> Unit,
    onSelectReasoningLevel: (ReasoningLevel) -> Unit,
    onSendMessage: (String) -> Unit,
    onStopGenerating: () -> Unit,
    modifier: Modifier = Modifier
) {
    val canSend = text.isNotBlank() && !isGenerating

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("chat_input_bar"),
        color = SurfaceDark.copy(alpha = 0.95f),
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            // Compact segmented control: None / Low / Medium / High / Max.
            if (reasoningSupported) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 2.dp, end = 2.dp, bottom = 8.dp)
                        .testTag("reasoning_selector"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = "Reasoning",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    )

                    ReasoningLevel.entries.forEach { level ->
                        val selected = level == reasoningLevel
                        val tint = when (level) {
                            ReasoningLevel.NONE -> TextMuted
                            ReasoningLevel.LOW -> TextSecondary
                            ReasoningLevel.MEDIUM -> BrandEmerald
                            ReasoningLevel.HIGH, ReasoningLevel.MAX -> ReasoningAmber
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (selected) tint.copy(alpha = 0.18f)
                                    else SurfaceDark.copy(alpha = 0.55f)
                                )
                                .border(
                                    1.dp,
                                    if (selected) tint.copy(alpha = 0.65f) else BorderSubtle,
                                    RoundedCornerShape(50)
                                )
                                .clickable { onSelectReasoningLevel(level) }
                                .padding(horizontal = 9.dp, vertical = 4.dp)
                                .testTag("reasoning_option_${level.name.lowercase()}")
                        ) {
                            Text(
                                text = level.shortLabel,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = if (selected) tint else TextSecondary,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    fontSize = 11.sp
                                )
                            )
                        }
                    }
                }
            }

            // Modern Floating Input Container with TextField & Send/Stop Button.
            // Web search is always available to the model, so there is no
            // tools toggle next to the input anymore.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp))
                    .background(SurfaceElevated)
                    .border(
                        1.dp,
                        BorderSubtle,
                        RoundedCornerShape(26.dp)
                    )
                    .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 24.dp, max = 120.dp)
                        .testTag("chat_input_field"),
                    textStyle = TextStyle(
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Default,
                        lineHeight = 22.sp
                    ),
                    cursorBrush = SolidColor(TextPrimary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (canSend) {
                            onSendMessage(text.trim())
                            onTextChange("")
                        }
                    }),
                    decorationBox = { innerTextField ->
                        Box(
                            contentAlignment = Alignment.CenterStart,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (text.isEmpty()) {
                                Text(
                                    text = "Message $modelName…",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = TextMuted,
                                        fontSize = 14.5.sp
                                    )
                                )
                            }
                            innerTextField()
                        }
                    }
                )

                Spacer(modifier = Modifier.width(4.dp))

                // Push-to-talk: tap to record, tap again to transcribe.
                if (sttEnabled) {
                    IconButton(
                        onClick = onMicClick,
                        enabled = !isGenerating && !isTranscribing,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isListening -> ErrorRed.copy(alpha = 0.18f)
                                    isTranscribing -> TelemetryCyan.copy(alpha = 0.15f)
                                    else -> SurfaceDark.copy(alpha = 0.5f)
                                }
                            )
                            .testTag("mic_button")
                    ) {
                        if (isTranscribing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = TelemetryCyan
                            )
                        } else {
                            Icon(
                                imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                                contentDescription = if (isListening) "Stop dictation" else "Dictate",
                                tint = when {
                                    isListening -> ErrorRed
                                    isGenerating -> TextMuted
                                    else -> TextSecondary
                                },
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                // Send or Stop Button
                if (isGenerating) {
                    IconButton(
                        onClick = onStopGenerating,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(TextPrimary)
                            .testTag("stop_generation_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop",
                            tint = BgDark,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else {
                    IconButton(
                        onClick = {
                            if (canSend) {
                                onSendMessage(text.trim())
                                onTextChange("")
                            }
                        },
                        enabled = canSend,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (canSend) TextPrimary else SurfaceDark.copy(alpha = 0.5f))
                            .testTag("send_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = if (canSend) BgDark else TextMuted,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }
        }
    }
}
