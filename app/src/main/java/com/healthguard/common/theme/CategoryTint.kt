package com.healthguard.common.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Container/content pair tinting a category chip and its row avatar. */
data class CategoryTint(val container: Color, val content: Color)

// Fixed pastel families per known category (light containers with deep
// same-hue text; roles swap in dark). Values sit tonally beside the sage
// brand palette so mixed lists stay calm.
private val RoseLight = CategoryTint(Color(0xFFE9D9D7), Color(0xFF5F3B37))
private val RoseDark = CategoryTint(Color(0xFF5F3B37), Color(0xFFE9D9D7))
private val LavenderLight = CategoryTint(Color(0xFFE2DCEF), Color(0xFF4A3F66))
private val LavenderDark = CategoryTint(Color(0xFF4A3F66), Color(0xFFE2DCEF))
private val TanLight = CategoryTint(Color(0xFFE7E2D8), Color(0xFF6B5A2F))
private val TanDark = CategoryTint(Color(0xFF4F431F), Color(0xFFE7E2D8))
private val PeachLight = CategoryTint(Color(0xFFF2DFD1), Color(0xFF6E4A2F))
private val PeachDark = CategoryTint(Color(0xFF573A22), Color(0xFFF2DFD1))

/**
 * The tint for a medication's category label. Known categories get their own
 * pastel family; unknown/custom labels (and no label) fall back to a neutral
 * surface tint. Matching is case-insensitive and substring-lenient so "Heart",
 * "BP" and "Heart · BP" all land on the same rose.
 */
@Composable
fun categoryTint(label: String?): CategoryTint {
    val dark = LocalAppDarkTheme.current
    val normalized = label.orEmpty().trim().lowercase()
    return when {
        normalized == "allergy" -> CategoryTint(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        "heart" in normalized || "bp" in normalized ||
            "blood pressure" in normalized -> if (dark) RoseDark else RoseLight
        "antibiotic" in normalized -> if (dark) LavenderDark else LavenderLight
        normalized == "supplement" -> if (dark) TanDark else TanLight
        "pain" in normalized -> if (dark) PeachDark else PeachLight
        else -> CategoryTint(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
