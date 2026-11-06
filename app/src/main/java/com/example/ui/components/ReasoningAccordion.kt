package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Psychology
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.CardHighlight
import com.example.ui.theme.ReasoningAmber
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceElevated
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TerminalGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

/**
 * Modern, high-polish Reasoning Display inspired by Unsloth, ChatGPT (o1/o3), and Gemini Flash Thinking.
 * - Minimalist collapsed state ("Thought for 1.8 seconds ▼")
 * - Left accent gutter and soft translucent background
 * - Real-time animated pulse during active reasoning
 * - Quick copy button to copy thought traces
 */
@Composable
fun ReasoningAccordion(
    reasoningText: String,
    isStreaming: Boolean = false,
    durationMs: Long = 0L,
    modifier: Modifier = Modifier,
    initialExpanded: Boolean = false
) {
    val context = LocalContext.current
    var userExpanded by remember { mutableStateOf<Boolean?>(null) }
    val expanded = userExpanded ?: isStreaming

    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label = "arrowRotation"
    )

    // Pulse animation during live streaming
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val tokenCount = (reasoningText.length / 4).coerceAtLeast(1)
    val formattedDuration = if (durationMs > 0) {
        String.format("%.1f seconds", durationMs / 1000.0)
    } else {
        "a few moments"
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceElevated.copy(alpha = 0.5f))
            .border(1.dp, BorderSubtle.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
            .animateContentSize()
            .testTag("reasoning_accordion")
    ) {
        // Top Header Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    userExpanded = !expanded
                }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f, fill = false)
            ) {
                // Sparkle / Brain icon with pulse when streaming
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(
                            if (isStreaming) ReasoningAmber.copy(alpha = 0.18f * pulseAlpha)
                            else TelemetryCyan.copy(alpha = 0.12f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isStreaming) Icons.Default.AutoAwesome else Icons.Default.Psychology,
                        contentDescription = "Thinking",
                        tint = if (isStreaming) ReasoningAmber.copy(alpha = pulseAlpha) else TelemetryCyan,
                        modifier = Modifier.size(15.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                // Label (like Gemini / ChatGPT / Unsloth)
                if (isStreaming) {
                    Text(
                        text = "Thinking…",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = ReasoningAmber
                        )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "($tokenCount tokens)",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    )
                } else {
                    Text(
                        text = "Thought for $formattedDuration",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                            color = TextSecondary
                        )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "• $tokenCount tokens",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    )
                }
            }

            // Right icons: Copy thought trace and Expand/Collapse chevron
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (reasoningText.isNotBlank()) {
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Reasoning Trace", reasoningText)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Thought trace copied", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Thought",
                            tint = TextMuted,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = TextSecondary,
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(arrowRotation)
                )
            }
        }

        // Expanded Reasoning Body
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(200)) + expandVertically(tween(250)),
            exit = fadeOut(tween(150)) + shrinkVertically(tween(200))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .padding(start = 14.dp, end = 14.dp, bottom = 12.dp, top = 2.dp)
            ) {
                // Left accent bar (Unsloth / Gemini blockquote gutter)
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    if (isStreaming) ReasoningAmber else TelemetryCyan.copy(alpha = 0.8f),
                                    BorderSubtle
                                )
                            )
                        )
                )

                Spacer(modifier = Modifier.width(12.dp))

                // Thought text formatted with clean hierarchy
                Column(modifier = Modifier.weight(1f)) {
                    val cleanText = reasoningText.ifEmpty { "Analyzing prompt parameters and temporal context..." }
                    Text(
                        text = cleanText,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Default,
                            fontSize = 12.5.sp,
                            lineHeight = 19.sp,
                            color = TextSecondary.copy(alpha = 0.9f)
                        )
                    )

                    if (isStreaming) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(ReasoningAmber)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Evaluating reasoning steps…",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = ReasoningAmber,
                                    fontSize = 10.sp
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
