@file:OptIn(ExperimentalTime::class)

package com.healthguard.detail.domain

import com.healthguard.activity.AdherenceResult
import com.healthguard.activity.DoseDayStatus
import com.healthguard.detail.HistoryEntry
import com.healthguard.shared.data.DoseStatus
import com.healthguard.shared.data.StoredDoseLog
import com.healthguard.shared.extraction.Frequency
import com.healthguard.testing.FakeMedicationRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

class ComputeDetailStateUseCaseTest {

    /** 2024-07-03T10:00:00Z — a Wednesday, mid-morning UTC. */
    private val now = Instant.parse("2024-07-03T10:00:00Z")

    private fun useCase(repo: FakeMedicationRepository) =
        ComputeDetailStateUseCase(repo, clock = { now }, zone = TimeZone.UTC)

    @Test
    fun `builds history with a gap, day statuses and adherence over the window`() = runTest {
        val repo = FakeMedicationRepository()
        // 2x/day (09:00, 21:00) since 06-30 08:00; owed 06-30(9,21), 07-01(9,21),
        // 07-02(9,21), and today's 09:00 (21:00 still ahead) -> 7 slots.
        val item = repo.seedMedication(
            "a",
            frequency = Frequency.TimesPerDay(2),
            startedAt = Instant.parse("2024-06-30T08:00:00Z"),
        )
        repo.seedDose("sched-a", takenAt = Instant.parse("2024-06-30T09:00:00Z"))
        repo.seedDose("sched-a", takenAt = Instant.parse("2024-06-30T21:00:00Z"))
        repo.seedDose("sched-a", takenAt = Instant.parse("2024-07-01T09:00:00Z"))
        repo.seedDose("sched-a", takenAt = Instant.parse("2024-07-01T21:00:00Z"))
        repo.seedDose("sched-a", takenAt = Instant.parse("2024-07-02T09:00:00Z")) // 21:00 silent
        repo.seedDose("sched-a", takenAt = Instant.parse("2024-07-03T09:02:00Z"))

        val content = useCase(repo)(item)

        // Heat-map window starts on the Monday 15 weeks before this week's.
        assertEquals(LocalDate(2024, 3, 18), content.historyFrom)
        // Latest take by plannedAt; next slot strictly after now.
        assertEquals(Instant.parse("2024-07-03T09:02:00Z"), content.lastTakenAt)
        assertEquals(Instant.parse("2024-07-03T21:00:00Z"), content.nextDoseAt)
        // 6 taken, 7 owed, none skipped.
        assertEquals(AdherenceResult(taken = 6, expected = 7, skipped = 0), content.adherence)
        assertEquals(
            mapOf(
                LocalDate(2024, 6, 30) to DoseDayStatus.MET,
                LocalDate(2024, 7, 1) to DoseDayStatus.MET,
                LocalDate(2024, 7, 2) to DoseDayStatus.PARTIAL,
                LocalDate(2024, 7, 3) to DoseDayStatus.MET,
            ),
            content.dayStatuses,
        )
        // The one unanswered slot (07-02 21:00) surfaces as a "Not recorded" row;
        // the six takes are logged, newest first.
        assertEquals(
            listOf(HistoryEntry.NotRecorded(Instant.parse("2024-07-02T21:00:00Z"))),
            content.history.filterIsInstance<HistoryEntry.NotRecorded>(),
        )
        assertEquals(6, content.history.filterIsInstance<HistoryEntry.Logged>().size)
        assertEquals(Instant.parse("2024-07-03T09:02:00Z"), content.history.first().at)
    }

    @Test
    fun `adherence and day statuses count skipped slots`() = runTest {
        val repo = FakeMedicationRepository()
        // 2x/day since 07-02 08:00; owed 07-02(9,21) and today's 09:00 -> 3 slots.
        val item = repo.seedMedication(
            "b",
            frequency = Frequency.TimesPerDay(2),
            startedAt = Instant.parse("2024-07-02T08:00:00Z"),
        )
        repo.logDose(skipped("sched-b", "2024-07-02T09:00:00Z"))
        repo.logDose(skipped("sched-b", "2024-07-02T21:00:00Z"))

        val content = useCase(repo)(item)

        assertEquals(AdherenceResult(taken = 0, expected = 3, skipped = 2), content.adherence)
        assertEquals(
            mapOf(
                LocalDate(2024, 7, 2) to DoseDayStatus.SKIPPED,
                LocalDate(2024, 7, 3) to DoseDayStatus.NOT_TAKEN,
            ),
            content.dayStatuses,
        )
    }

    private fun skipped(scheduleId: String, plannedAt: String) = StoredDoseLog(
        id = "skip-$scheduleId-$plannedAt",
        scheduleId = scheduleId,
        plannedAt = Instant.parse(plannedAt),
        takenAt = null,
        status = DoseStatus.SKIPPED,
    )
}
