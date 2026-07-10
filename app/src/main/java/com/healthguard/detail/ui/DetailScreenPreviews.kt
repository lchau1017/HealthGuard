@file:OptIn(ExperimentalTime::class)

package com.healthguard.detail.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.healthguard.activity.AdherenceResult
import com.healthguard.activity.DoseDayStatus
import com.healthguard.common.theme.HealthGuardTheme
import com.healthguard.detail.state.DetailUiState
import com.healthguard.detail.state.HistoryRowData
import com.healthguard.detail.state.HistoryRowKind
import com.healthguard.home.MedicationPhase
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/*
 * Design-time previews for the medication detail screen. Sample data uses
 * fixed instants and dates only — never the system clock — so renders are
 * reproducible (the per-second countdown inside StatusCard owns its own
 * clock and simply shows the render-time value). The loaded state gets a
 * light and a dark variant.
 */

/** Sunday 5 Jul 2026, mid-morning — every derived label is fixed. */
private val previewNow = Instant.parse("2026-07-05T09:30:00Z")

/** First day of the heat-map window (a Monday). */
private val previewHistoryFrom = LocalDate(2026, 6, 1)

private val previewHistory = listOf(
    HistoryRowData(
        id = "log-4",
        title = "Today, 8:02 AM",
        annotation = "Taken · on time",
        kind = HistoryRowKind.TAKEN,
    ),
    HistoryRowData(
        id = "log-3",
        title = "Yesterday, 8:05 PM",
        annotation = "Taken · 5m late",
        kind = HistoryRowKind.TAKEN,
    ),
    HistoryRowData(
        id = "slot-2026-07-04T13:00:00Z",
        title = "Yesterday, 2:00 PM",
        annotation = "Not recorded",
        kind = HistoryRowKind.NOT_RECORDED,
    ),
    HistoryRowData(
        id = "log-2",
        title = "Yesterday, 8:00 AM",
        annotation = "Missed",
        kind = HistoryRowKind.MISSED,
    ),
    HistoryRowData(
        id = "log-1",
        title = "Fri 3 Jul, 8:01 PM",
        annotation = "Taken · on time",
        kind = HistoryRowKind.TAKEN,
    ),
)

/**
 * Treatment stretch 15 Jun – 4 Jul: mostly met, with a deterministic sprinkle
 * of partial/not-taken/skipped days; days outside the stretch stay absent
 * (rendered as out-of-treatment hairline blanks).
 */
private val previewDayStatuses: Map<LocalDate, DoseDayStatus> = buildMap {
    var day = LocalDate(2026, 6, 15)
    while (day <= LocalDate(2026, 7, 4)) {
        val status = when (day.day % 8) {
            1 -> DoseDayStatus.PARTIAL
            3 -> DoseDayStatus.NOT_TAKEN
            5 -> DoseDayStatus.SKIPPED
            else -> DoseDayStatus.MET
        }
        put(day, status)
        day = day.plus(1, DateTimeUnit.DAY)
    }
}

private val loadedState = DetailUiState(
    isLoaded = true,
    drugName = "Amoxicillin",
    subtitle = "500 mg · Capsule",
    categoryLabel = "Antibiotic",
    phaseChipText = null,
    phase = MedicationPhase.TAKING,
    isActive = true,
    isAsNeeded = false,
    scheduleTimesText = "8:00 AM, 2:00 PM, 8:00 PM",
    scheduleStartedText = "15 Jun 2026",
    now = previewNow,
    name = "Amoxicillin",
    dosage = "500 mg",
    form = "Capsule",
    label = "Antibiotic",
    ingredients = "Amoxicillin trihydrate",
    frequencyText = "3 times a day",
    withFood = true,
    nextDoseAt = Instant.parse("2026-07-05T14:00:00Z"),
    lastTakenAt = Instant.parse("2026-07-05T08:02:00Z"),
    history = previewHistory,
    dayStatuses = previewDayStatuses,
    adherence = AdherenceResult(taken = 52, expected = 60, skipped = 2),
    historyFrom = previewHistoryFrom,
)

@Composable
private fun DetailScreenSample(state: DetailUiState) {
    DetailScreen(
        state = state,
        onIntent = {},
        effects = emptyFlow(),
        onBack = {},
        onFinished = {},
    )
}

@Preview(showBackground = true)
@Composable
private fun DetailScreenPreview() {
    HealthGuardTheme(darkTheme = false) { DetailScreenSample(loadedState) }
}

@Preview(showBackground = true, backgroundColor = 0xFF1B1B1F)
@Composable
private fun DetailScreenPreviewDark() {
    HealthGuardTheme(darkTheme = true) { DetailScreenSample(loadedState) }
}
