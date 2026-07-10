package com.healthguard.common.theme

import androidx.compose.ui.unit.dp

/**
 * Spacing scale for paddings and gaps. One source of truth so screen
 * spacing can't drift; components read these instead of raw dp literals.
 */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
}
