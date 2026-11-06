package com.example.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
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
fun ChatInputBar(
    isGenerating: Boolean,
    searchEnabled: Boolean,
    isListening: Boolean,
    recognizedVoiceText: String,
    onToggleSearch: (Boolean) -> Unit,
    onSendMessage: (String) -> Unit,
    onStopGenerating: () -> Unit,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    val canSend = text.isNotBlank() && !isGenerating

    // Sync speech to text
    LaunchedEffect(recognizedVoiceText) {
        if (recognizedVoiceText.isNotBlank()) {
            text = recognizedVoiceText
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            onStartListening()
        }
    }

    // Pulse animation when listening
    val infiniteTransition = rememberInfiniteTransition(label = "micPulse")
    val micScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "micScale"
    )

    var plusMenuExpanded by remember { mutableStateOf(false) }

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
            // Voice Listening Status Banner (only shown if actively listening)
            AnimatedVisibility(visible = isListening) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(ErrorRed.copy(alpha = 0.2f))
                        .border(1.dp, ErrorRed.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(ErrorRed)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Listening… Speak now",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = ErrorRed,
                            fontSize = 11.sp
                        )
                    )
                }
            }

            // Modern Floating Input Container with [+] Tools Menu, TextField, Mic & Send
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp))
                    .background(SurfaceElevated)
                    .border(
                        1.dp,
                        if (isListening) ErrorRed else if (searchEnabled) ReasoningAmber.copy(alpha = 0.6f) else BorderSubtle,
                        RoundedCornerShape(26.dp)
                    )
                    .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // [+] Menu Button for Tools & Features (Web Search, etc.)
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
                                        text = if (searchEnabled) "Active: live queries injected with date" else "Disabled: on-device model only",
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
                                    text = if (isListening) "Listening to voice input…"
                                    else if (searchEnabled) "Search web & message on-device model…"
                                    else "Message on-device model…",
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

                // Microphone (STT) Button
                IconButton(
                    onClick = {
                        if (isListening) {
                            onStopListening()
                        } else {
                            val hasPermission = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED

                            if (hasPermission) {
                                onStartListening()
                            } else {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    },
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (isListening) ErrorRed.copy(alpha = 0.25f) else SurfaceDark.copy(alpha = 0.4f))
                        .testTag("mic_stt_button")
                ) {
                    Icon(
                        imageVector = if (isListening) Icons.Default.GraphicEq else Icons.Default.Mic,
                        contentDescription = "Voice Input STT",
                        tint = if (isListening) ErrorRed else TextSecondary,
                        modifier = Modifier
                            .size(18.dp)
                            .then(if (isListening) Modifier.scale(micScale) else Modifier)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Send or Stop Button (ChatGPT style solid white/black or dark circle)
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
