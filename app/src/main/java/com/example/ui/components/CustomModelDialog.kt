package com.example.ui.components

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.model.GgufParser
import com.example.model.ModelFormat
import com.example.model.ModelProfile
import com.example.model.SafeTensorsParser
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

@Composable
fun CustomModelDialog(
    isOpen: Boolean,
    onDismiss: () -> Unit,
    onModelConfigured: (ModelProfile) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isOpen) return

    val context = LocalContext.current
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf<String?>(null) }
    var fileSize by remember { mutableStateOf(0L) }
    var detectedFormat by remember { mutableStateOf<ModelFormat?>(null) }
    var parsedDetails by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedUri = uri
            val meta = queryFileMetadata(context, uri)
            fileName = meta.first
            fileSize = meta.second

            val lowerName = (fileName ?: "").lowercase()
            when {
                lowerName.endsWith(".gguf") -> {
                    detectedFormat = ModelFormat.GGUF
                    try {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            val info = GgufParser.parse(stream)
                            if (info.isValid) {
                                parsedDetails = mapOf(
                                    "Format" to "GGUF v${info.version}",
                                    "Architecture" to info.architecture,
                                    "Tensors" to "${info.tensorCount}",
                                    "Quantization" to info.quantizationType,
                                    "Context Length" to "${info.contextLength}"
                                )
                                statusMessage = "Valid GGUF header parsed successfully!"
                            } else {
                                statusMessage = "Notice: ${info.errorMessage ?: "Could not parse full GGUF header"}"
                            }
                        }
                    } catch (e: Exception) {
                        statusMessage = "Read error: ${e.message}"
                    }
                }
                lowerName.endsWith(".safetensors") || lowerName.endsWith(".bin") -> {
                    detectedFormat = ModelFormat.SAFETENSORS
                    try {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            val info = SafeTensorsParser.parse(stream)
                            if (info.isValid) {
                                parsedDetails = mapOf(
                                    "Format" to "SafeTensors",
                                    "Tensors" to "${info.tensorCount}",
                                    "Est. Parameters" to info.totalParamsEstimate,
                                    "Header Size" to "${info.headerSize} bytes"
                                )
                                statusMessage = "Valid SafeTensors header parsed successfully!"
                            } else {
                                statusMessage = "Notice: ${info.errorMessage ?: "Could not parse SafeTensors header"}"
                            }
                        }
                    } catch (e: Exception) {
                        statusMessage = "Read error: ${e.message}"
                    }
                }
                else -> {
                    detectedFormat = ModelFormat.GGUF
                    statusMessage = "Unrecognized extension. Defaulting to GGUF container parser."
                }
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
                .testTag("custom_model_dialog"),
            color = SurfaceDark
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Dialog Title
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = ReasoningAmber,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Import Model Weights",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        )
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = TextSecondary
                        )
                    }
                }

                Text(
                    text = "Load custom .gguf or .safetensors models currently being fine-tuned.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )

                HorizontalDivider(color = BorderSubtle)
                Spacer(modifier = Modifier.height(14.dp))

                // File Pick Action
                OutlinedButton(
                    onClick = { filePickerLauncher.launch("*/*") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pick_weights_file_button"),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TelemetryCyan)
                ) {
                    Icon(
                        imageVector = Icons.Default.FileOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = if (fileName != null) "Choose Different File…" else "Pick .gguf / .safetensors File")
                }

                // Or Preset Fine-Tuning Templates
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "OR TEST PRESET WEIGHT TEMPLATES:",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = TextMuted
                )
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PresetButton(
                        label = "Custom GGUF (Q4)",
                        format = "GGUF",
                        onClick = {
                            fileName = "custom_finetuned_smollm.gguf"
                            fileSize = 1048576000L
                            detectedFormat = ModelFormat.GGUF
                            parsedDetails = mapOf(
                                "Format" to "GGUF v3",
                                "Architecture" to "SmolLM-3",
                                "Tensors" to "196",
                                "Quantization" to "Q4_K_M",
                                "Context Length" to "8192"
                            )
                            statusMessage = "Preset GGUF configuration loaded."
                        },
                        modifier = Modifier.weight(1f)
                    )

                    PresetButton(
                        label = "SafeTensors (BF16)",
                        format = "SafeTensors",
                        onClick = {
                            fileName = "model_weights.safetensors"
                            fileSize = 2147483648L
                            detectedFormat = ModelFormat.SAFETENSORS
                            parsedDetails = mapOf(
                                "Format" to "SafeTensors",
                                "Tensors" to "224",
                                "Est. Parameters" to "1.7B",
                                "Dtype" to "BF16"
                            )
                            statusMessage = "Preset SafeTensors configuration loaded."
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Parsed File Info Card
                if (fileName != null) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = CardHighlight,
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = TerminalGreen,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = fileName ?: "",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                )
                            }

                            if (fileSize > 0) {
                                Text(
                                    text = formatFileSize(fileSize),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary,
                                    modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
                                )
                            }

                            parsedDetails.forEach { (k, v) ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = k,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMuted
                                    )
                                    Text(
                                        text = v,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            color = TelemetryCyan
                                        )
                                    )
                                }
                            }

                            if (statusMessage != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = statusMessage ?: "",
                                    style = MaterialTheme.typography.labelSmall.copy(color = ReasoningAmber)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Actions: Cancel & Load
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                    ) {
                        Text(text = "Cancel")
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            val format = detectedFormat ?: ModelFormat.GGUF
                            val name = fileName ?: "Custom Model"
                            val quant = parsedDetails["Quantization"] ?: "Q4_K_M"
                            val params = parsedDetails["Est. Parameters"] ?: "1.7B"
                            val contextLen = parsedDetails["Context Length"]?.toIntOrNull() ?: 4096

                            val profile = ModelProfile(
                                id = "custom_${System.currentTimeMillis()}",
                                name = "Custom: $name",
                                shortName = name.take(14),
                                format = format,
                                quantization = quant,
                                parameters = params,
                                contextLength = contextLen,
                                memoryMb = (fileSize / (1024 * 1024)).toInt().coerceIn(600, 2400),
                                description = "User imported weights ($format format)",
                                supportsReasoning = true,
                                isCustom = true,
                                customFileName = name,
                                customFileSize = fileSize,
                                customMetadata = parsedDetails
                            )
                            onModelConfigured(profile)
                            onDismiss()
                        },
                        enabled = fileName != null,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = TelemetryCyan),
                        modifier = Modifier.testTag("confirm_load_custom_model")
                    ) {
                        Text(
                            text = "Load into Engine",
                            color = SurfaceDark,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetButton(
    label: String,
    format: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp)),
        color = SurfaceElevated
    ) {
        Column(
            modifier = Modifier
                .clickable { onClick() }
                .padding(8.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TelemetryCyan
                )
            )
            Text(
                text = format,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = TextMuted
            )
        }
    }
}

private fun queryFileMetadata(context: Context, uri: Uri): Pair<String?, Long> {
    var name: String? = null
    var size = 0L
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex != -1) name = cursor.getString(nameIndex)
                if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
            }
        }
    } catch (e: Exception) {
        // ignore
    }
    return Pair(name, size)
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 * 1024 -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> String.format("%d KB", bytes / 1024)
        else -> "$bytes bytes"
    }
}
