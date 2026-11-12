package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.delay

/** How long the reasoning trace stays open after the last reasoning token. */
private const val REASONING_IDLE_COLLAPSE_MS = 700L

/**
 * ChatGPT-style reasoning disclosure.
 * - Collapsed: a quiet "Thinking…" / "Thought for 4 seconds" row with a chevron.
 * - Streaming: the label sweeps with a soft shimmer.
 * - Expanded: plain muted prose, no card chrome or accent gutter.
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

    // While reasoning is arriving, the trace opens and streams automatically.
    // Once tokens stop (or generation ends), it folds back up like ChatGPT.
    var autoExpanded by remember { mutableStateOf(false) }
    var reasoningActive by remember { mutableStateOf(isStreaming) }
    var userExpanded by remember { mutableStateOf<Boolean?>(if (initialExpanded) true else null) }
    val expanded = userExpanded ?: autoExpanded
    val isReasoningStreaming = isStreaming && reasoningActive

    // Freeze the duration at the point reasoning finishes so the label does not
    // keep counting up while the final answer is still streaming.
    var reasoningDurationMs by remember { mutableLongStateOf(0L) }
    val displayDurationMs = reasoningDurationMs.takeIf { it > 0L } ?: durationMs

    LaunchedEffect(isStreaming) {
        if (isStreaming) {
            userExpanded = null
            autoExpanded = true
            reasoningActive = true
        } else {
            autoExpanded = false
            reasoningActive = false
        }
    }

    LaunchedEffect(reasoningText, isStreaming) {
        if (isStreaming && reasoningText.isNotBlank()) {
            reasoningDurationMs = durationMs
            reasoningActive = true
            autoExpanded = true
            delay(REASONING_IDLE_COLLAPSE_MS)
            // No new reasoning tokens arrived: the model has moved on to the answer.
            reasoningActive = false
            autoExpanded = false
        }
    }

    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "arrowRotation"
    )

    // Shimmer sweep for the live "Thinking…" label.
    val infiniteTransition = rememberInfiniteTransition(label = "thinkingShimmer")
    val shimmerProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing)
        ),
        label = "shimmerProgress"
    )
    var labelWidth by remember { mutableIntStateOf(0) }
    val shimmerWidth = (labelWidth.takeIf { it > 0 } ?: 220).toFloat()
    val shimmerBrush = Brush.linearGradient(
        colors = listOf(
            TextMuted.copy(alpha = 0.55f),
            TextPrimary,
            TextMuted.copy(alpha = 0.55f)
        ),
        start = Offset(shimmerProgress * shimmerWidth * 2f - shimmerWidth, 0f),
        end = Offset(shimmerProgress * shimmerWidth * 2f, 0f)
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("reasoning_accordion")
    ) {
        // ChatGPT-style disclosure header: muted text + chevron, no container.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    userExpanded = !expanded
                }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = thinkingLabel(isReasoningStreaming, displayDurationMs),
                modifier = Modifier.onSizeChanged { labelWidth = it.width },
                style = if (isReasoningStreaming) {
                    TextStyle(
                        brush = shimmerBrush,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                } else {
                    MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        color = TextSecondary
                    )
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.width(4.dp))

            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse reasoning" else "Expand reasoning",
                tint = TextMuted,
                modifier = Modifier
                    .size(16.dp)
                    .rotate(arrowRotation)
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(180)) + expandVertically(tween(220)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(180))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 2.dp, top = 2.dp, bottom = 10.dp)
            ) {
                Text(
                    text = reasoningText.ifBlank {
                        if (isReasoningStreaming) "Thinking…" else "No reasoning was recorded."
                    },
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Default,
                        fontSize = 13.5.sp,
                        lineHeight = 21.sp,
                        color = TextMuted
                    )
                )

                if (reasoningText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
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
                                contentDescription = "Copy reasoning",
                                tint = TextMuted,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun thinkingLabel(isStreaming: Boolean, durationMs: Long): String {
    if (isStreaming) return "Thinking…"
    if (durationMs <= 0L) return "Thought for a few seconds"

    val totalSeconds = (durationMs / 1000.0).let { if (it < 1.0) 1 else it.toLong() }
    return when {
        totalSeconds < 60 -> "Thought for $totalSeconds second${if (totalSeconds == 1L) "" else "s"}"
        else -> {
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            if (seconds == 0L) {
                "Thought for $minutes minute${if (minutes == 1L) "" else "s"}"
            } else {
                "Thought for ${minutes}m ${seconds}s"
            }
        }
    }
}
