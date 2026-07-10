package com.healthguard.home.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.healthguard.BuildConfig
import com.healthguard.home.state.HomeIntent

/**
 * The top-bar overflow menu that loads and removes demo data. Demo data
 * controls exist in debug builds only; in release builds this composes
 * nothing.
 */
@Composable
fun DemoDataMenu(onIntent: (HomeIntent) -> Unit, modifier: Modifier = Modifier) {
    if (!BuildConfig.DEBUG) return
    var demoMenuOpen by remember { mutableStateOf(false) }
    IconButton(onClick = { demoMenuOpen = true }, modifier = modifier) {
        Icon(
            imageVector = Icons.Filled.MoreVert,
            contentDescription = "Developer options",
        )
    }
    DropdownMenu(
        expanded = demoMenuOpen,
        onDismissRequest = { demoMenuOpen = false },
    ) {
        DropdownMenuItem(
            text = { Text("Load demo data") },
            onClick = {
                demoMenuOpen = false
                onIntent(HomeIntent.LoadDemoData)
            },
        )
        DropdownMenuItem(
            text = { Text("Remove demo data") },
            onClick = {
                demoMenuOpen = false
                onIntent(HomeIntent.RemoveDemoData)
            },
        )
    }
}
