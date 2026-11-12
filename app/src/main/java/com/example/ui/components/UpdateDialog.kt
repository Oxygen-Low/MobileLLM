package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.update.UpdateState

@Composable
fun UpdateDialog(
    state: UpdateState,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    when (state) {
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Update available") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${state.info.versionName} is available (you have V${BuildConfig.VERSION_CODE}).")
                    val notes = state.info.releaseNotes.trim()
                    if (notes.isNotEmpty()) {
                        Text(notes.take(600), style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDownload) { Text("Update") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Later") }
            }
        )

        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Downloading update") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                    )
                    Text("${(state.progress * 100).toInt()}%")
                }
            },
            confirmButton = {}
        )

        is UpdateState.ReadyToInstall -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Ready to install") },
            text = { Text("Tap Install to finish updating to ${state.info.versionName}.") },
            confirmButton = {
                TextButton(onClick = onInstall) { Text("Install") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Later") }
            }
        )

        is UpdateState.Error -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Update failed") },
            text = { Text(state.message) },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("OK") }
            }
        )

        else -> Unit
    }
}
