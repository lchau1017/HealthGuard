package com.healthguard.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Sequential heat-map ramp, index 0 (empty) to 4 (most). Light values are the
 * mock's sage steps; dark values stay in the same hue family with lightness
 * inverted, monotonically brighter from empty to most so intensity still
 * reads as "more".
 */
private val HeatRampLight = listOf(
    Color(0xFFE4EBE8),
    Color(0xFFDBE9E3),
    Color(0xFFA4D3C6),
    Color(0xFF6FB8A8),
    Color(0xFF2F6A5F),
)

private val HeatRampDark = listOf(
    Color(0xFF232B28),
    Color(0xFF24423A),
    Color(0xFF2F6A5F),
    Color(0xFF55A896),
    Color(0xFF7FD3C2),
)

/** The 5-step sequential ramp for the current theme (index 0 = empty). */
@Composable
fun heatRamp(): List<Color> = if (isSystemInDarkTheme()) HeatRampDark else HeatRampLight
