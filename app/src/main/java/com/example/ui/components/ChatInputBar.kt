package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.example.ui.theme.GptEmerald
import com.example.ui.theme.ReasoningAmber
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun ChatInputBar(
    isGenerating: Boolean,
    searchEnabled: Boolean,
    modelName: String,
    reasoningLevel: ReasoningLevel,
    reasoningSupported: Boolean,
    onToggleSearch: (Boolean) -> Unit,
    onSelectReasoningLevel: (ReasoningLevel) -> Unit,
    onSendMessage: (String) -> Unit,
    onStopGenerating: () -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf("") }
    val canSend = text.isNotBlank() && !isGenerating

    var plusMenuExpanded by remember { mutableStateOf(false) }
    var reasoningMenuExpanded by remember { mutableStateOf(false) }

    val reasoningTint = when {
        !reasoningSupported -> TextMuted
        reasoningLevel == ReasoningLevel.NONE -> TextMuted
        reasoningLevel == ReasoningLevel.LOW -> TextSecondary
        reasoningLevel == ReasoningLevel.MEDIUM -> GptEmerald
        else -> ReasoningAmber
    }
    val reasoningActive = reasoningSupported && reasoningLevel != ReasoningLevel.NONE

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
            // Modern Floating Input Container with [+] Tools Menu, TextField & Send/Stop Button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp))
                    .background(SurfaceElevated)
                    .border(
                        1.dp,
                        if (searchEnabled) ReasoningAmber.copy(alpha = 0.6f) else BorderSubtle,
                        RoundedCornerShape(26.dp)
                    )
                    .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // [+] Menu Button for Tools & Features (DuckDuckGo Web Search)
                Box {
                    IconButton(
                        onClick = { plusMenuExpanded = true },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (searchEnabled) GptEmerald.copy(alpha = 0.2f) else SurfaceDark.copy(alpha = 0.6f))
                            .testTag("plus_menu_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Tools and Search Menu",
                            tint = if (searchEnabled) GptEmerald else TextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = plusMenuExpanded,
                        onDismissRequest = { plusMenuExpanded = false },
                        modifier = Modifier
                            .background(SurfaceElevated)
                            .border(0.5.dp, BorderSubtle, RoundedCornerShape(12.dp))
                    ) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "DuckDuckGo Web Search",
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = FontWeight.SemiBold,
                                                color = TextPrimary
                                            )
                                        )
                                        if (searchEnabled) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Enabled",
                                                tint = GptEmerald,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = if (searchEnabled) "Active: queries injected with system date" else "Disabled: on-device model only",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = if (searchEnabled) GptEmerald else TextMuted,
                                            fontSize = 11.sp
                                        )
                                    )
                                }
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Language,
                                    contentDescription = null,
                                    tint = if (searchEnabled) GptEmerald else TextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            onClick = {
                                onToggleSearch(!searchEnabled)
                                plusMenuExpanded = false
                            },
                            modifier = Modifier.testTag("web_search_toggle")
                        )
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Reasoning effort selector. Uses the model's native /think
                // switch plus level-specific prompt guidance and token budget.
                Box {
                    IconButton(
                        onClick = {
                            if (reasoningSupported) reasoningMenuExpanded = true
                        },
                        enabled = reasoningSupported,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                if (reasoningActive) reasoningTint.copy(alpha = 0.18f)
                                else SurfaceDark.copy(alpha = 0.6f)
                            )
                            .testTag("reasoning_menu_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Psychology,
                            contentDescription = "Reasoning effort: ${reasoningLevel.label}",
                            tint = reasoningTint,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = reasoningMenuExpanded,
                        onDismissRequest = { reasoningMenuExpanded = false },
                        modifier = Modifier
                            .background(SurfaceElevated)
                            .border(0.5.dp, BorderSubtle, RoundedCornerShape(12.dp))
                    ) {
                        Text(
                            text = "Reasoning effort",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = TextMuted,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp
                            ),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )

                        ReasoningLevel.entries.forEach { level ->
                                val selected = level == reasoningLevel
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = level.label,
                                                    style = MaterialTheme.typography.bodyMedium.copy(
                                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                                        color = if (selected) GptEmerald else TextPrimary
                                                    )
                                                )
                                                if (selected) {
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = "Selected",
                                                        tint = GptEmerald,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                            Text(
                                                text = level.description,
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    color = if (selected) GptEmerald else TextMuted,
                                                    fontSize = 11.sp
                                                )
                                            )
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Psychology,
                                            contentDescription = null,
                                            tint = when {
                                                selected -> GptEmerald
                                                level == ReasoningLevel.NONE -> TextMuted
                                                level == ReasoningLevel.LOW -> TextSecondary
                                                level == ReasoningLevel.MEDIUM -> GptEmerald
                                                else -> ReasoningAmber
                                            },
                                            modifier = Modifier.size(20.dp)
                                        )
                                    },
                                    onClick = {
                                        onSelectReasoningLevel(level)
                                        reasoningMenuExpanded = false
                                    },
                                    modifier = Modifier.testTag("reasoning_option_${level.name.lowercase()}")
                                )
                            }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
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
                            text = ""
                        }
                    }),
                    decorationBox = { innerTextField ->
                        Box(
                            contentAlignment = Alignment.CenterStart,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (text.isEmpty()) {
                                Text(
                                    text = if (searchEnabled) "Search web & message $modelName…" else "Message $modelName…",
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
                                text = ""
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
