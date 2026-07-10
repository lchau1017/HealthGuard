package com.healthguard.common.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.healthguard.activity.DayCount
import com.healthguard.common.theme.HealthGuardTheme
import com.healthguard.common.theme.Spacing
import kotlinx.datetime.LocalDate

/*
 * Design-time previews for the shared components. Sample data uses fixed
 * dates only — never the system clock — so renders are reproducible. Each
 * key component gets a light and a dark variant; the dark ones also exercise
 * the LocalAppDarkTheme plumbing behind heatRamp/categoryTint.
 */

private val sampleFrom = LocalDate(2026, 6, 29) // a Monday
private val sampleToday = LocalDate(2026, 7, 5)
private val sampleCounts = listOf(
    DayCount(LocalDate(2026, 6, 29), 2),
    DayCount(LocalDate(2026, 6, 30), 1),
    DayCount(LocalDate(2026, 7, 2), 3),
    DayCount(LocalDate(2026, 7, 4), 1),
    DayCount(LocalDate(2026, 7, 5), 2),
)

@Composable
private fun ChipsSample() {
    Column(
        modifier = Modifier.padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            StatusChip("Not started", outlined = true)
            StatusChip("Stopped 3 Jul")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            CategoryChip("Allergy")
            CategoryChip("Pain relief")
            CategoryChip("Heart")
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ChipsPreview() {
    HealthGuardTheme(darkTheme = false) { ChipsSample() }
}

@Preview(showBackground = true, backgroundColor = 0xFF1B1B1F)
@Composable
private fun ChipsPreviewDark() {
    HealthGuardTheme(darkTheme = true) { ChipsSample() }
}

@Preview
@Composable
private fun DoubleDoseDialogPreview() {
    HealthGuardTheme(darkTheme = false) {
        DoubleDoseDialog(
            drugName = "Ibuprofen",
            minutesAgo = 12,
            onConfirm = {},
            onDismiss = {},
        )
    }
}

@Preview
@Composable
private fun DoubleDoseDialogPreviewDark() {
    HealthGuardTheme(darkTheme = true) {
        DoubleDoseDialog(
            drugName = "Ibuprofen",
            minutesAgo = 0,
            onConfirm = {},
            onDismiss = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun DayRowHeatMapPreview() {
    HealthGuardTheme(darkTheme = false) {
        DayRowHeatMap(
            dayCounts = sampleCounts,
            from = sampleFrom,
            today = sampleToday,
            modifier = Modifier.padding(Spacing.lg),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1B1B1F)
@Composable
private fun DayRowHeatMapPreviewDark() {
    HealthGuardTheme(darkTheme = true) {
        DayRowHeatMap(
            dayCounts = sampleCounts,
            from = sampleFrom,
            today = sampleToday,
            modifier = Modifier.padding(Spacing.lg),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ActivityHeatMapPreview() {
    HealthGuardTheme(darkTheme = false) {
        ActivityHeatMap(
            dayCounts = sampleCounts,
            from = LocalDate(2026, 6, 8),
            today = sampleToday,
            modifier = Modifier.padding(Spacing.lg),
            showMonthLabels = true,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AppNavBarHomePreview() {
    HealthGuardTheme(darkTheme = false) {
        AppNavBar(selected = AppTab.HOME, onSelect = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun AppNavBarActivityPreview() {
    HealthGuardTheme(darkTheme = false) {
        AppNavBar(selected = AppTab.ACTIVITY, onSelect = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun CategoryLabelInputPreview() {
    HealthGuardTheme(darkTheme = false) {
        CategoryLabelInput(
            label = "Allergy",
            onLabelChange = {},
            modifier = Modifier.padding(Spacing.lg),
        )
    }
}
