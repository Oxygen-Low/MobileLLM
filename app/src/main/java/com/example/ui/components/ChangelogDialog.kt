package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.changelog.ChangelogEntry
import com.example.changelog.ChangelogState
import com.example.changelog.ChangelogVersion
import com.example.changelog.Markdown
import com.example.changelog.MarkdownBlock
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

/**
 * Shows the changelog of the versions the user skipped when the app was
 * updated.
 *
 * The app first asks whether the user wants to read the notes and only opens
 * the notes themselves when they say yes.
 */
@Composable
fun ChangelogDialog(
    state: ChangelogState,
    onShow: () -> Unit,
    onDismiss: () -> Unit
) {
    when (state) {
        is ChangelogState.Prompt -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Update installed") },
            text = {
                Text(
                    "MobileLLM was updated from ${ChangelogVersion.label(state.range.fromVersion)} " +
                        "to ${ChangelogVersion.label(state.range.toVersion)}.\n\n" +
                        "Do you want to see what changed?"
                )
            },
            confirmButton = {
                TextButton(onClick = onShow) { Text("Show changes") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Not now") }
            }
        )

        is ChangelogState.Showing -> ChangelogContentDialog(
            entries = state.entries,
            onDismiss = onDismiss
        )

        ChangelogState.Idle -> Unit
    }
}

/** The notes of every version between the old one and the new one. */
@Composable
private fun ChangelogContentDialog(
    entries: List<ChangelogEntry>,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f),
            shape = RoundedCornerShape(16.dp),
            color = SurfaceDark,
            border = BorderStroke(1.dp, BorderSubtle)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "What's new",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = entries.joinToString(", ") { it.versionName },
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(14.dp))

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    entries.forEachIndexed { index, entry ->
                        if (index > 0) Spacer(modifier = Modifier.height(18.dp))
                        ChangelogEntryView(entry)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

/** The notes of one version. */
@Composable
private fun ChangelogEntryView(entry: ChangelogEntry) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = entry.versionName,
            style = MaterialTheme.typography.titleMedium,
            color = TelemetryCyan
        )
        Spacer(modifier = Modifier.height(6.dp))

        if (!entry.hasNotes) {
            // A version can be released without a changelog file, so there is
            // nothing to read. The entry says that instead of leaving an
            // empty section behind.
            Text(
                text = entry.body,
                style = MaterialTheme.typography.bodyLarge,
                color = TextMuted
            )
        } else {
            val blocks = Markdown.parse(entry.body).dropTitle(entry.versionName)
            blocks.forEach { block ->
                when (block) {
                    is MarkdownBlock.Heading -> Text(
                        text = inlineMarkdown(block.text),
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                    )

                    is MarkdownBlock.Bullet -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextMuted,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = inlineMarkdown(block.text),
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextSecondary
                        )
                    }

                    is MarkdownBlock.Paragraph -> Text(
                        text = inlineMarkdown(block.text),
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}

/**
 * Drops the heading of the file.
 *
 * The version is already written above the notes, so the `# V12` line of the
 * file would only repeat it.
 */
private fun List<MarkdownBlock>.dropTitle(versionName: String): List<MarkdownBlock> {
    val first = firstOrNull() ?: return this
    val isTitle = first is MarkdownBlock.Heading &&
        first.text.trim().equals(versionName, ignoreCase = true)
    return if (isTitle) drop(1) else this
}

/**
 * Renders the inline markdown of a line: `**bold**` and `` `code` ``.
 */
private fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var rest = text
    while (rest.isNotEmpty()) {
        val boldIndex = rest.indexOf("**")
        val codeIndex = rest.indexOf('`')
        val marker = when {
            boldIndex >= 0 && (codeIndex < 0 || boldIndex < codeIndex) -> boldIndex
            codeIndex >= 0 -> codeIndex
            else -> -1
        }

        if (marker < 0) {
            append(rest)
            break
        }

        append(rest.substring(0, marker))

        if (marker == boldIndex) {
            val end = rest.indexOf("**", marker + 2)
            if (end < 0) {
                append(rest.substring(marker))
                break
            }
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = TextPrimary)) {
                append(rest.substring(marker + 2, end))
            }
            rest = rest.substring(end + 2)
        } else {
            val end = rest.indexOf('`', marker + 1)
            if (end < 0) {
                append(rest.substring(marker))
                break
            }
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = TelemetryCyan)) {
                append(rest.substring(marker + 1, end))
            }
            rest = rest.substring(end + 1)
        }
    }
}
