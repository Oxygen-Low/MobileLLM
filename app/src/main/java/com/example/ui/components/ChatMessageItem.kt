package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ChatMessageEntity
import com.example.ui.theme.BgDark
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.CardHighlight
import com.example.ui.theme.BrandEmerald
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceElevated
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TerminalGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatMessageItem(
    message: ChatMessageEntity,
    modelName: String,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier,
    showRewind: Boolean = false,
    showRegenerate: Boolean = false,
    showThinkingPlaceholder: Boolean = true,
    ttsEnabled: Boolean = false,
    isSpeaking: Boolean = false,
    isSynthesizing: Boolean = false,
    onRewind: () -> Unit = {},
    onRegenerate: () -> Unit = {},
    onSpeak: () -> Unit = {}
) {
    val context = LocalContext.current
    val isUser = message.role == "user"

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        if (isUser) {
            // User Message Bubble (Sleek matte SurfaceElevated bubble)
            Surface(
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .testTag("user_message_${message.id}"),
                shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
                color = SurfaceElevated,
                border = androidx.compose.foundation.BorderStroke(0.5.dp, BorderSubtle),
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 15.sp,
                            lineHeight = 22.sp
                        ),
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = formatTime(message.timestamp),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = TextMuted,
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            }
        } else if (message.role == "tool") {
            // A step of the agent run: what the model asked for and what it
            // got back. It is not a reply of its own.
            AgentStepItem(
                toolName = message.toolName,
                argument = message.toolQuery.orEmpty(),
                result = message.content,
                sourceUrl = message.toolSourceUrl,
                reasoning = message.reasoningContent.orEmpty(),
                modifier = Modifier.testTag("tool_step_${message.id}")
            )
        } else {
            // Assistant Message (natural full-width document flow)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("assistant_message_${message.id}")
            ) {
                // Tool Execution Marker (web search, clock, calculator). Rows
                // written before the agent trace existed keep their tool on the
                // reply itself.
                if (message.isToolExecution || !message.toolQuery.isNullOrEmpty()) {
                    ToolUsageChip(
                        toolName = message.toolName,
                        argument = message.toolQuery.orEmpty(),
                        sourceUrl = message.toolSourceUrl
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }

                // Reasoning Accordion. While waiting for the first
                // token we show "Thinking…"; it disappears if the model answers
                // without emitting a reasoning trace.
                if (!message.reasoningContent.isNullOrBlank() ||
                    (isStreaming && message.content.isBlank() && showThinkingPlaceholder)
                ) {
                    ReasoningAccordion(
                        reasoningText = message.reasoningContent ?: "",
                        isStreaming = isStreaming,
                        durationMs = if (message.tokensPerSecond > 0 && message.tokensCount > 0) {
                            ((message.tokensCount / message.tokensPerSecond) * 1000).toLong()
                        } else 1400L
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Main Assistant Content (clean typography flow)
                if (message.content.isNotBlank()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        // Render rich text and syntax highlighted code blocks
                        RichMessageContent(content = message.content)

                        Spacer(modifier = Modifier.height(10.dp))

                        // Understated Action Footer (Copy, Metrics)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Left: Performance metrics in muted tone
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (message.tokensCount > 0) {
                                    Text(
                                        text = "${message.tokensCount} tokens",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 11.sp,
                                            color = TextMuted
                                        )
                                    )
                                }
                                if (message.tokensPerSecond > 0) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = String.format("• %.1f T/s", message.tokensPerSecond),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 11.sp,
                                            color = BrandEmerald,
                                            fontWeight = FontWeight.Medium
                                        )
                                    )
                                }
                            }

                            // Right: Minimal action icons (Speak, Rewind, Regenerate, Copy)
                            Row(verticalAlignment = Alignment.CenterVertically) {

                                if (ttsEnabled) {
                                    IconButton(
                                        onClick = onSpeak,
                                        modifier = Modifier
                                            .size(30.dp)
                                            .testTag("speak_button_${message.id}")
                                    ) {
                                        if (isSynthesizing) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(13.dp),
                                                strokeWidth = 1.5.dp,
                                                color = TelemetryCyan
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                                contentDescription = if (isSpeaking) {
                                                    "Stop reading"
                                                } else {
                                                    "Read aloud"
                                                },
                                                tint = if (isSpeaking) BrandEmerald else TextMuted,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                    }
                                }

                                if (showRewind) {
                                    IconButton(
                                        onClick = onRewind,
                                        modifier = Modifier
                                            .size(30.dp)
                                            .testTag("rewind_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.Undo,
                                            contentDescription = "Rewind to this message",
                                            tint = TextMuted,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }

                                if (showRegenerate) {
                                    IconButton(
                                        onClick = onRegenerate,
                                        modifier = Modifier
                                            .size(30.dp)
                                            .testTag("regenerate_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = "Regenerate response",
                                            tint = TextMuted,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }

                                var copied by remember { mutableStateOf(false) }
                                IconButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("MobileLLM Response", message.content)
                                        clipboard.setPrimaryClip(clip)
                                        copied = true
                                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(
                                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                                        contentDescription = "Copy message",
                                        tint = if (copied) BrandEmerald else TextMuted,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Parses and renders markdown text with formatted code blocks and syntax header
 */
@Composable
private fun RichMessageContent(content: String) {
    val context = LocalContext.current
    val parts = content.split("```")

    Column(modifier = Modifier.fillMaxWidth()) {
        parts.forEachIndexed { index, part ->
            if (index % 2 == 1) {
                // Code block
                val lines = part.trim().lines()
                val lang = if (lines.isNotEmpty() && lines[0].matches(Regex("^[a-zA-Z0-9#+]+$"))) lines[0] else "code"
                val code = if (lang != "code" && lines.size > 1) lines.drop(1).joinToString("\n") else part.trim()

                val isHtml = lang.equals("html", ignoreCase = true) ||
                        code.contains("<html", ignoreCase = true) ||
                        code.contains("<!DOCTYPE", ignoreCase = true) ||
                        code.contains("<canvas", ignoreCase = true)

                if (isHtml) {
                    InteractiveHtmlBlock(code = code, lang = lang)
                } else {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        color = CardHighlight,
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
                    ) {
                        Column {
                            // Code block header
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(SurfaceElevated)
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = lang.uppercase(),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp,
                                        color = TelemetryCyan
                                    )
                                )

                                Row(
                                    modifier = Modifier
                                        .clickable {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("Code", code))
                                            Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
                                        }
                                        .padding(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy Code",
                                        tint = TextSecondary,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Copy",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 10.sp,
                                            color = TextSecondary
                                        )
                                    )
                                }
                            }

                            // Code content
                            Text(
                                text = code,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                    color = TextPrimary
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(10.dp)
                            )
                        }
                    }
                }
            } else {
                // Regular prose text
                if (part.isNotBlank()) {
                    Text(
                        text = part.trim(),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 14.sp,
                            lineHeight = 21.sp,
                            color = TextPrimary
                        ),
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun InteractiveHtmlBlock(
    code: String,
    lang: String
) {
    val context = LocalContext.current
    var isLivePreview by remember { mutableStateOf(true) }
    var isFullscreenOpen by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp)),
        color = CardHighlight,
        border = androidx.compose.foundation.BorderStroke(1.dp, TelemetryCyan.copy(alpha = 0.5f))
    ) {
        Column {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceElevated)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Title Chip
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(TerminalGreen.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SportsEsports,
                            contentDescription = null,
                            tint = TerminalGreen,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "INTERACTIVE HTML APP",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.5.sp,
                            color = TerminalGreen
                        )
                    )
                }

                // Controls: Preview / Code Toggle, Fullscreen, Copy
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Preview vs Code Pill
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = SurfaceDark,
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, BorderSubtle)
                    ) {
                        Row(modifier = Modifier.padding(2.dp)) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isLivePreview) TerminalGreen.copy(alpha = 0.2f) else Color.Transparent)
                                    .clickable { isLivePreview = true }
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = if (isLivePreview) TerminalGreen else TextMuted,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = "Play",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 10.sp,
                                            fontWeight = if (isLivePreview) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isLivePreview) TerminalGreen else TextSecondary
                                        )
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (!isLivePreview) TelemetryCyan.copy(alpha = 0.2f) else Color.Transparent)
                                    .clickable { isLivePreview = false }
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Code,
                                        contentDescription = null,
                                        tint = if (!isLivePreview) TelemetryCyan else TextMuted,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = "Code",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 10.sp,
                                            fontWeight = if (!isLivePreview) FontWeight.Bold else FontWeight.Normal,
                                            color = if (!isLivePreview) TelemetryCyan else TextSecondary
                                        )
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Fullscreen Button
                    IconButton(
                        onClick = { isFullscreenOpen = true },
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fullscreen,
                            contentDescription = "Fullscreen",
                            tint = TelemetryCyan,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Copy Code Button
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("HTML Code", code))
                            Toast.makeText(context, "HTML code copied", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Code",
                            tint = TextSecondary,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }

            // Body: Live Preview or Raw Code
            if (isLivePreview) {
                HtmlPreviewView(
                    htmlContent = code,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(290.dp),
                    onFullScreenClick = { isFullscreenOpen = true }
                )
            } else {
                Text(
                    text = code,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        color = TextPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp)
                )
            }
        }
    }

    // Fullscreen sandbox modal
    if (isFullscreenOpen) {
        FullscreenHtmlAppDialog(
            isOpen = true,
            htmlContent = code,
            title = "Interactive HTML Game / App Sandbox",
            onDismiss = { isFullscreenOpen = false }
        )
    }
}

private fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
