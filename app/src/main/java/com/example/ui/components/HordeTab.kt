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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ImageModelProfile
import com.example.model.ModelProfile
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.CardHighlight
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.SurfaceElevated
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

/**
 * The AI Horde tab of the Model Hub.
 *
 * Remote models need no download: the key is saved, the catalogues are fetched
 * and picking a row changes the model used for the next chat or picture.
 */
@Composable
fun HordeTab(
    apiKey: String,
    textModels: List<ModelProfile>,
    imageModels: List<ImageModelProfile>,
    selectedTextModel: ModelProfile?,
    selectedImageModel: ImageModelProfile?,
    loading: Boolean,
    error: String?,
    onSaveApiKey: (String) -> Unit,
    onRefresh: () -> Unit,
    onSelectTextModel: (ModelProfile) -> Unit,
    onSelectImageModel: (ImageModelProfile) -> Unit,
    modifier: Modifier = Modifier
) {
    var keyInput by remember(apiKey) { mutableStateOf(apiKey) }
    var search by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("horde_tab"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "AI HORDE ACCOUNT",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            ),
            color = TextMuted
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                label = { Text("API key") },
                placeholder = { Text("0000000000 for anonymous use") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier
                    .weight(1f)
                    .testTag("horde_api_key_input")
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { onSaveApiKey(keyInput) },
                modifier = Modifier.testTag("horde_save_api_key")
            ) {
                Text("Save")
            }
        }

        Text(
            text = "Leave the default key to use AI Horde anonymously. Your key is stored only on this device.",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = TextMuted
            )
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "MODELS CURRENTLY ONLINE",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                ),
                color = TextMuted,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onRefresh,
                modifier = Modifier.testTag("horde_refresh")
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh the AI Horde model list",
                    tint = TelemetryCyan,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        if (loading) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    color = TelemetryCyan,
                    modifier = Modifier
                        .size(24.dp)
                        .testTag("horde_loading")
                )
            }
        }

        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = ErrorRed
                ),
                modifier = Modifier.testTag("horde_error")
            )
        }

        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            label = { Text("Search models") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("horde_search")
        )

        val query = search.trim()
        val visibleText = textModels.filter { query.isEmpty() || it.name.contains(query, true) }
        val visibleImages = imageModels.filter { query.isEmpty() || it.name.contains(query, true) }

        if (visibleText.isNotEmpty()) {
            HordeSectionTitle("TEXT")
            visibleText.forEach { model ->
                HordeRow(
                    name = model.name,
                    subtitle = model.description,
                    selected = model.id == selectedTextModel?.id,
                    onClick = { onSelectTextModel(model) },
                    testTag = "horde_text_model_${model.id}"
                )
            }
        }

        if (visibleImages.isNotEmpty()) {
            HordeSectionTitle("IMAGE")
            visibleImages.forEach { model ->
                HordeRow(
                    name = model.name,
                    subtitle = model.description,
                    selected = model.id == selectedImageModel?.id,
                    onClick = { onSelectImageModel(model) },
                    testTag = "horde_image_model_${model.id}"
                )
            }
        }

        if (!loading && textModels.isEmpty() && imageModels.isEmpty()) {
            Text(
                text = "No models are online right now. Refresh to try again.",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            )
        }
    }
}

@Composable
private fun HordeRow(
    name: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(
                1.dp,
                if (selected) TelemetryCyan.copy(alpha = 0.6f) else BorderSubtle,
                RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .testTag(testTag),
        color = if (selected) CardHighlight else SurfaceElevated
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) TelemetryCyan.copy(alpha = 0.18f)
                        else SurfaceElevated.copy(alpha = 0.6f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = TelemetryCyan,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = if (selected) TelemetryCyan else TextPrimary
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = TextSecondary
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun HordeSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp
        ),
        color = TextMuted,
        modifier = Modifier.padding(top = 4.dp)
    )
}
