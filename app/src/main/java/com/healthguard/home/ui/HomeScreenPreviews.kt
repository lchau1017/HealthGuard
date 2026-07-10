@file:OptIn(ExperimentalTime::class)

package com.healthguard.home.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.healthguard.activity.DoseDayStatus
import com.healthguard.common.theme.HealthGuardTheme
import com.healthguard.domain.model.MedicationId
import com.healthguard.domain.model.ScheduleId
import com.healthguard.home.MedicationPhase
import com.healthguard.home.WeekDay
import com.healthguard.home.format.DoseRowStatus
import com.healthguard.home.state.CabinetRow
import com.healthguard.home.state.DoseCard
import com.healthguard.home.state.DueAlert
import com.healthguard.home.state.HomeUiState
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.datetime.LocalDate

/*
 * Design-time previews for the whole home screen. Sample data uses fixed
 * instants and dates only — never the system clock — so renders are
 * reproducible. The populated state gets a light and a dark variant; the
 * empty state documents the first-run layout.
 */

/** Sunday 5 Jul 2026, mid-morning — every derived label is fixed. */
private val previewNow = Instant.parse("2026-07-05T09:30:00Z")

private val overdueCard = DoseCard(
    medicationId = MedicationId("med-amoxicillin"),
    scheduleId = ScheduleId("sch-amoxicillin"),
    title = "Amoxicillin 500 mg",
    drugName = "Amoxicillin",
    categoryLabel = "Antibiotic",
    formLabel = "Capsule",
    nextDoseAt = Instant.parse("2026-07-05T09:18:00Z"),
    lastTaken = Instant.parse("2026-07-05T01:00:00Z"),
    isDue = true,
    status = DoseRowStatus.Due,
)

private val upcomingCard = DoseCard(
    medicationId = MedicationId("med-cetirizine"),
    scheduleId = ScheduleId("sch-cetirizine"),
    title = "Cetirizine 10 mg",
    drugName = "Cetirizine",
    categoryLabel = "Allergy",
    formLabel = "Tablet",
    nextDoseAt = Instant.parse("2026-07-05T21:00:00Z"),
    lastTaken = Instant.parse("2026-07-04T21:02:00Z"),
    status = DoseRowStatus.Next("Next at 9:00 PM"),
)

private val cabinetRows = listOf(
    CabinetRow(
        medicationId = MedicationId("med-ibuprofen"),
        title = "Ibuprofen 200 mg",
        drugName = "Ibuprofen",
        categoryLabel = "Pain relief",
        formLabel = "Tablet",
        phaseChipText = "Not started",
        phase = MedicationPhase.NOT_STARTED,
    ),
    CabinetRow(
        medicationId = MedicationId("med-prednisone"),
        title = "Prednisone 5 mg",
        drugName = "Prednisone",
        categoryLabel = null,
        formLabel = "Tablet",
        phaseChipText = "Stopped 3 Jul",
        phase = MedicationPhase.STOPPED,
    ),
)

/** Mon 29 Jun .. Sun 5 Jul: met, met, partial, met, skipped, met, undecided. */
private val previewWeek = listOf(
    WeekDay(LocalDate(2026, 6, 29), DoseDayStatus.MET),
    WeekDay(LocalDate(2026, 6, 30), DoseDayStatus.MET),
    WeekDay(LocalDate(2026, 7, 1), DoseDayStatus.PARTIAL),
    WeekDay(LocalDate(2026, 7, 2), DoseDayStatus.MET),
    WeekDay(LocalDate(2026, 7, 3), DoseDayStatus.SKIPPED),
    WeekDay(LocalDate(2026, 7, 4), DoseDayStatus.MET),
    WeekDay(LocalDate(2026, 7, 5), DoseDayStatus.NOT_TAKEN),
)

private val populatedState = HomeUiState(
    now = previewNow,
    dueAlert = DueAlert(card = overdueCard, othersDueCount = 1),
    taking = listOf(overdueCard, upcomingCard),
    cabinet = cabinetRows,
    dueCount = 2,
    weekDays = previewWeek,
    weekCaption = "4 of 6 days on track. Today still to come.",
)

@Composable
private fun HomeScreenSample(state: HomeUiState) {
    HomeScreen(
        state = state,
        onIntent = {},
        effects = emptyFlow(),
        onOpenDetail = {},
        onOpenActivity = {},
        onTakePhoto = {},
        onPickFromGallery = {},
    )
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    HealthGuardTheme(darkTheme = false) { HomeScreenSample(populatedState) }
}

@Preview(showBackground = true, backgroundColor = 0xFF1B1B1F)
@Composable
private fun HomeScreenPreviewDark() {
    HealthGuardTheme(darkTheme = true) { HomeScreenSample(populatedState) }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenEmptyPreview() {
    HealthGuardTheme(darkTheme = false) {
        HomeScreenSample(HomeUiState(now = previewNow))
    }
}
