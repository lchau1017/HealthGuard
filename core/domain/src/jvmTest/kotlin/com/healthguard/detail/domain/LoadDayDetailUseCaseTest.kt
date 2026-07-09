@file:OptIn(ExperimentalTime::class)

package com.healthguard.detail.domain

import com.healthguard.shared.extraction.Frequency
import com.healthguard.testing.FakeMedicationRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

class LoadDayDetailUseCaseTest {

    private val now = Instant.parse("2024-07-03T10:00:00Z")

    @Test
    fun `builds the day sheet with an expected-but-not-recorded slot`() = runTest {
        val repo = FakeMedicationRepository()
        // 2x/day (09:00, 21:00) active over 07-02; the 09:00 slot is taken and
        // the 21:00 slot is left unanswered.
        val item = repo.seedMedication(
            "a",
            drugName = "Cetirizine",
            frequency = Frequency.TimesPerDay(2),
            startedAt = Instant.parse("2024-07-01T08:00:00Z"),
        )
        repo.seedDose(
            "sched-a",
            takenAt = Instant.parse("2024-07-02T09:04:00Z"),
            plannedAt = Instant.parse("2024-07-02T09:00:00Z"),
        )

        val detail = LoadDayDetailUseCase(repo, clock = { now }, zone = TimeZone.UTC)(
            item,
            LocalDate(2024, 7, 2),
        )

        assertEquals(LocalDate(2024, 7, 2), detail.date)
        val line = detail.lines.single()
        assertEquals("Cetirizine 200 mg", line.name)
        assertEquals(listOf(LocalTime(9, 4)), line.takenTimes)
        assertEquals(1, line.notRecorded)
        assertEquals(0, detail.expectedNotRecorded)
    }
}
