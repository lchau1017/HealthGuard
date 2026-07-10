@file:OptIn(ExperimentalTime::class)

package com.healthguard.activity.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.healthguard.activity.ActivityFilter
import com.healthguard.activity.ActivityStats
import com.healthguard.activity.DayCount
import com.healthguard.activity.state.ActivityUiState
import com.healthguard.activity.state.MedicationAdherence
import com.healthguard.common.theme.HealthGuardTheme
import com.healthguard.home.MedicationPhase
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/*
 * Design-time previews for the Activity dashboard. Sample data uses fixed
 * instants and dates only — never the system clock — so renders are
 * reproducible. The populated 30-day view gets a light and a dark variant.
 */

/** Sunday 5 Jul 2026, mid-morning — every derived label is fixed. */
private val previewNow = Instant.parse("2026-07-05T09:30:00Z")

/** First day of the 30-day window ending 5 Jul. */
private val previewFrom = LocalDate(2026, 6, 6)

/** A deterministic 0–3 takes-per-day sprinkle over the window; gaps stay 0. */
private val previewDayCounts: List<DayCount> = buildList {
    var day = previewFrom
    while (day <= LocalDate(2026, 7, 5)) {
        val count = when (day.day % 5) {
            0 -> 0
            1, 3 -> 2
            2 -> 3
            else -> 1
        }
        if (count > 0) add(DayCount(day, count))
        day = day.plus(1, DateTimeUnit.DAY)
    }
}

private val previewStats = ActivityStats(
    totalEvents = 47,
    activeDays = 24,
    currentStreakDays = 5,
    longestStreakDays = 11,
    peakHour = 8,
    topItem = ActivityStats.TopItem("Amoxicillin", 21),
)

private val previewBreakdown = listOf(
    MedicationAdherence(
        name = "Amoxicillin",
        phase = MedicationPhase.TAKING,
        asNeeded = false,
        percent = 94,
        taken = 21,
        skipped = 1,
        meetsTarget = true,
        stoppedText = null,
    ),
    MedicationAdherence(
        name = "Ibuprofen",
        phase = MedicationPhase.TAKING,
        asNeeded = true,
        percent = null,
        taken = 7,
        skipped = 0,
        meetsTarget = null,
        stoppedText = null,
    ),
    MedicationAdherence(
        name = "Prednisone",
        phase = MedicationPhase.STOPPED,
        asNeeded = false,
        percent = 62,
        taken = 10,
        skipped = 0,
        meetsTarget = false,
        stoppedText = "Stopped 3 Jul",
    ),
)

private val populatedState = ActivityUiState(
    now = previewNow,
    filter = ActivityFilter.DAYS_30,
    from = previewFrom,
    stats = previewStats,
    dayCounts = previewDayCounts,
    breakdown = previewBreakdown,
)

@Preview(showBackground = true)
@Composable
private fun ActivityScreenPreview() {
    HealthGuardTheme(darkTheme = false) {
        ActivityScreen(state = populatedState, onIntent = {})
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1B1B1F)
@Composable
private fun ActivityScreenPreviewDark() {
    HealthGuardTheme(darkTheme = true) {
        ActivityScreen(state = populatedState, onIntent = {})
    }
}
