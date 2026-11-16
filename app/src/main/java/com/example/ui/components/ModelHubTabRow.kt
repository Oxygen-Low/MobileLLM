package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ModelHubTab
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TextSecondary

/**
 * The tabs of the Model Hub.
 *
 * The hub holds every kind of model, so the tabs are plain labels in a row
 * that scrolls sideways. The tab that is open is the only one that is
 * highlighted, and a line under it shows where the list below belongs to.
 */
@Composable
fun ModelHubTabRow(
    selectedTab: ModelHubTab,
    onSelectTab: (ModelHubTab) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .testTag("model_hub_tabs"),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(vertical = 2.dp)
    ) {
        items(ModelHubTab.entries.toList(), key = { it.name }) { tab ->
            ModelHubTabLabel(
                tab = tab,
                selected = tab == selectedTab,
                onClick = { onSelectTab(tab) }
            )
        }
    }
}

/** One tab of the hub. */
@Composable
private fun ModelHubTabLabel(
    tab: ModelHubTab,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(IntrinsicSize.Min)
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp)
            .testTag("hub_tab_${tab.name}"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = tab.title,
            style = MaterialTheme.typography.labelLarge.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                fontSize = 13.sp
            ),
            color = if (selected) TelemetryCyan else TextSecondary
        )

        Spacer(modifier = Modifier.height(6.dp))

        // The line under the open tab points out what the list below shows.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(if (selected) TelemetryCyan else Color.Transparent)
        )
    }
}
