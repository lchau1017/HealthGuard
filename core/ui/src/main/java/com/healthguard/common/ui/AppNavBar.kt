package com.healthguard.common.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** The app's three top-level tabs. */
enum class AppTab { HOME, ACTIVITY, CHAT }

/** Bottom navigation shared by the tab screens: Home, Activity and Chat. */
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
            selected = selected == AppTab.ACTIVITY,
            onClick = { onSelect(AppTab.ACTIVITY) },
            icon = { Icon(Icons.Filled.DateRange, contentDescription = null) },
            label = { Text("Activity") },
        )
        NavigationBarItem(
            selected = selected == AppTab.CHAT,
            onClick = { onSelect(AppTab.CHAT) },
            icon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) },
            label = { Text("Chat") },
        )
    }
}
