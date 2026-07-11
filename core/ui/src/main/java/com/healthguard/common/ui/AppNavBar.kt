package com.healthguard.common.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** The app's three top-level tabs; the assistant holds the center slot. */
enum class AppTab { HOME, ASSISTANT, ACTIVITY }

/** Bottom navigation shared by the tab screens: Home, Assistant (center) and Activity. */
@Composable
fun AppNavBar(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(modifier = modifier) {
        NavigationBarItem(
            selected = selected == AppTab.HOME,
            onClick = { onSelect(AppTab.HOME) },
            icon = { Icon(Icons.Filled.Home, contentDescription = null) },
            label = { Text("Home") },
        )
        NavigationBarItem(
            selected = selected == AppTab.ASSISTANT,
            onClick = { onSelect(AppTab.ASSISTANT) },
            icon = { Icon(Icons.Filled.Assistant, contentDescription = null) },
            label = { Text("Assistant") },
        )
        NavigationBarItem(
            selected = selected == AppTab.ACTIVITY,
            onClick = { onSelect(AppTab.ACTIVITY) },
            icon = { Icon(Icons.Filled.Insights, contentDescription = null) },
            label = { Text("Activity") },
        )
    }
}
