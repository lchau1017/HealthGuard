package com.healthguard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.healthguard.common.theme.HealthGuardTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HealthGuardTheme {
                // Screens carry their own Scaffold (top bar, FAB, snackbar).
                HealthGuardApp(modifier = Modifier.fillMaxSize())
            }
        }
    }
}
