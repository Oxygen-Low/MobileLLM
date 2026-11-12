package com.example.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AgentStep
import com.example.tools.toolDisplayName
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary

/**
 * One step of an agent turn.
 *
 * The step shows what the model did - the reasoning that led to the tool and
 * the tool call itself - instead of a box that only repeats the name of the
 * tool. The result stays out of the way until it is asked for.
 */
@Composable
fun AgentStepItem(
    step: AgentStep,
    modifier: Modifier = Modifier
) {
    AgentStepItem(
        toolName = step.toolName,
        argument = step.argument,
        result = step.result,
        sourceUrl = step.sourceUrl,
        reasoning = step.reasoning,
        isRunning = step.isRunning,
        modifier = modifier
    )
}

/**
 * One step of an agent turn, described by its parts.
 */
@Composable
fun AgentStepItem(
    toolName: String?,
    argument: String,
    result: String? = null,
    sourceUrl: String? = null,
    reasoning: String = "",
    isRunning: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (reasoning.isNotBlank()) {
            ReasoningAccordion(
                reasoningText = reasoning,
                isStreaming = false,
                durationMs = 0L
            )
            Spacer(modifier = Modifier.height(2.dp))
        }

        ToolUsageChip(
            toolName = toolName,
            argument = argument,
            result = result,
            sourceUrl = sourceUrl,
            isRunning = isRunning
        )
    }
}

/**
 * A quiet, inline marker for a tool the agent used.
 *
 * "Used DuckDuckGo Search" with the argument behind it - no panel, no frame.
 * Tapping it reveals what the tool returned.
 */
@Composable
fun ToolUsageChip(
    toolName: String?,
    argument: String,
    result: String? = null,
    sourceUrl: String? = null,
    isRunning: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    val label = toolDisplayName(toolName)
    val icon = toolIcon(toolName)
    val hasResult = !result.isNullOrBlank()

    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "chevronRotation"
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable(enabled = hasResult) { expanded = !expanded }
                .padding(vertical = 6.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Used $label",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextSecondary
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (argument.isNotBlank()) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = argument,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = TextMuted
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))
            if (isRunning) {
                CircularProgressIndicator(
                    strokeWidth = 1.5.dp,
                    color = TextMuted,
                    modifier = Modifier.size(10.dp)
                )
            } else if (hasResult) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Hide tool result" else "Show tool result",
                    tint = TextMuted,
                    modifier = Modifier
                        .size(14.dp)
                        .rotate(chevronRotation)
                )
            }
        }

        if (expanded && hasResult) {
            Text(
                text = result.orEmpty(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = TextSecondary
                ),
                modifier = Modifier
                    .padding(start = 21.dp, top = 2.dp, end = 2.dp)
            )
        }

        if (expanded && !sourceUrl.isNullOrEmpty()) {
            Row(
                modifier = Modifier
                    .padding(start = 21.dp, top = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(sourceUrl)))
                        } catch (e: Exception) {
                            // No browser, no link - nothing to open.
                        }
                    }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(11.dp)
                )
                Text(
                    text = "Open source",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                )
            }
        }
    }
}

private fun toolIcon(toolName: String?): ImageVector = when (toolName) {
    "duckduckgo_search" -> Icons.Default.Language
    "get_current_datetime" -> Icons.Default.Schedule
    "calculate_expression" -> Icons.Default.Calculate
    else -> Icons.Default.Build
}
