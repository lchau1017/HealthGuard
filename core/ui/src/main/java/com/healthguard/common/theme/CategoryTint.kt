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

/** Tint families a category resolves to; colors resolve per applied theme. */
private enum class TintFamily { SECONDARY, ROSE, LAVENDER, TAN, PEACH, NEUTRAL }

/**
 * One known category: the preset chip text (null for tint-only rows that
 * exist to color free-typed labels), the substrings that also select it,
 * and the tint family it resolves to. A preset always matches its own text
 * exactly (case-insensitively); [keywords] make matching substring-lenient
 * so "Heart", "BP" and "Heart · BP" all land on the same rose.
 */
private class CategoryEntry(
    val preset: String?,
    val family: TintFamily,
    val keywords: List<String> = emptyList(),
) {
    fun matches(normalized: String): Boolean =
        (preset != null && normalized == preset.lowercase()) ||
            keywords.any { it in normalized }
}

/**
 * The single category table both the preset chips and the tint lookup are
 * derived from, so a category's name and its color can never drift apart.
 * Preset rows are offered in this order; the tint-only rows sit where they
 * keep today's match precedence (heart/antibiotic before the "pain"
 * substring) for free-typed labels.
 */
private val CategoryTable = listOf(
    CategoryEntry("Supplement", TintFamily.TAN),
    CategoryEntry("Cold & Flu", TintFamily.NEUTRAL),
    CategoryEntry("Allergy", TintFamily.SECONDARY),
    CategoryEntry(null, TintFamily.ROSE, listOf("heart", "bp", "blood pressure")),
    CategoryEntry(null, TintFamily.LAVENDER, listOf("antibiotic")),
    CategoryEntry("Pain relief", TintFamily.PEACH, listOf("pain")),
    CategoryEntry("Chronic", TintFamily.NEUTRAL),
    CategoryEntry("Other", TintFamily.NEUTRAL),
)

/** Preset category chips offered wherever a label can be assigned, in display order. */
val CATEGORY_PRESETS: List<String> = CategoryTable.mapNotNull { it.preset }

/**
 * The tint for a medication's category label. Known categories get their own
 * pastel family; unknown/custom labels (and no label) fall back to a neutral
 * surface tint. Matching is case-insensitive and substring-lenient so "Heart",
 * "BP" and "Heart · BP" all land on the same rose.
 */
@Composable
fun categoryTint(label: String?): CategoryTint {
    val normalized = label.orEmpty().trim().lowercase()
    val family = CategoryTable.firstOrNull { it.matches(normalized) }?.family
        ?: TintFamily.NEUTRAL
    return family.resolve()
}

@Composable
private fun TintFamily.resolve(): CategoryTint {
    val dark = LocalAppDarkTheme.current
    return when (this) {
        TintFamily.SECONDARY -> CategoryTint(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        TintFamily.ROSE -> if (dark) RoseDark else RoseLight
        TintFamily.LAVENDER -> if (dark) LavenderDark else LavenderLight
        TintFamily.TAN -> if (dark) TanDark else TanLight
        TintFamily.PEACH -> if (dark) PeachDark else PeachLight
        TintFamily.NEUTRAL -> CategoryTint(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
