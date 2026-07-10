@file:OptIn(ExperimentalTime::class)

package com.healthguard.activity.domain

import com.healthguard.activity.DayDetail
import com.healthguard.activity.DayMedicineLine
import com.healthguard.domain.extraction.Frequency
import com.healthguard.testing.FakeMedicationRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

class LoadActivityDayDetailUseCaseTest {

    /** 2024-07-03T10:00:00Z. */
    private val now = Instant.parse("2024-07-03T10:00:00Z")

    private fun useCase(repo: FakeMedicationRepository) =
        LoadActivityDayDetailUseCase(repo, repo, clock = { now }, zone = TimeZone.UTC)

    @Test
    fun `selecting a day builds its detail sheet across all medicines`() = runTest {
        val repo = FakeMedicationRepository()
        // Cetirizine 2x/day (09:00/21:00): the 09:04 take answers the morning
        // slot, the evening slot goes unanswered -> 1 not recorded.
        repo.seedMedication(
            "a", drugName = "Cetirizine",
            frequency = Frequency.TimesPerDay(2),
            startedAt = Instant.parse("2024-07-01T00:00:00Z"),
        )
        repo.seedDose("sched-a", takenAt = Instant.parse("2024-07-02T09:04:00Z"))
        // As-needed the same day: a count line, never a not-recorded count.
        repo.seedMedication(
            "b", drugName = "Ibuprofen",
            frequency = Frequency.EveryHours(6),
            startedAt = Instant.parse("2024-07-01T00:00:00Z"),
        )
        repo.seedDose("sched-b", takenAt = Instant.parse("2024-07-02T14:00:00Z"))

        val detail = useCase(repo)(LocalDate(2024, 7, 2))

        assertEquals(
            DayDetail(
                date = LocalDate(2024, 7, 2),
                lines = listOf(
                    DayMedicineLine(
                        medicationId = "a",
                        name = "Cetirizine 200 mg",
                        takenTimes = listOf(LocalTime(9, 4)),
                        skipped = 0,
                        missed = 0,
                        notRecorded = 1,
                    ),
                    DayMedicineLine(
                        medicationId = "b",
                        name = "Ibuprofen 200 mg",
                        takenTimes = listOf(LocalTime(14, 0)),
                        skipped = 0,
                        missed = 0,
                        notRecorded = 0,
                    ),
                ),
                expectedNotRecorded = 0,
            ),
            detail,
        )
    }

    @Test
    fun `an empty day with expectations reports them as not recorded`() = runTest {
        val repo = FakeMedicationRepository()
        repo.seedMedication(
            "a", drugName = "Cetirizine",
            frequency = Frequency.TimesPerDay(2),
            startedAt = Instant.parse("2024-07-01T00:00:00Z"),
        )

        val detail = useCase(repo)(LocalDate(2024, 7, 2))

        assertEquals(
            DayDetail(
                date = LocalDate(2024, 7, 2),
                lines = emptyList(),
                expectedNotRecorded = 2,
            ),
            detail,
        )
    }
}
