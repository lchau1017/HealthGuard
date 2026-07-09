@file:OptIn(ExperimentalTime::class)

package com.healthguard.activity.domain

import com.healthguard.activity.ActivityFilter
import com.healthguard.home.MedicationPhase
import com.healthguard.shared.data.DoseStatus
import com.healthguard.shared.data.StoredDoseLog
import com.healthguard.shared.extraction.Frequency
import com.healthguard.testing.FakeMedicationRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

class ComputeActivityStateUseCaseTest {

    /** 2024-07-03T10:00:00Z — a Wednesday. */
    private val now = Instant.parse("2024-07-03T10:00:00Z")

    private fun useCase(repo: FakeMedicationRepository) =
        ComputeActivityStateUseCase(repo, clock = { now }, zone = TimeZone.UTC)

    private fun FakeMedicationRepository.insert(
        id: String,
        drugName: String,
        frequency: Frequency? = null,
        startedAt: Instant? = null,
        stoppedAt: Instant? = null,
    ) = seedMedication(
        id = id,
        drugName = drugName,
        frequency = frequency,
        startedAt = startedAt,
        stoppedAt = stoppedAt,
    )

    private fun FakeMedicationRepository.logTaken(medicationId: String, takenAt: Instant) =
        seedDose(scheduleId = "sched-$medicationId", takenAt = takenAt)

    private suspend fun FakeMedicationRepository.logSkipped(medicationId: String, plannedAt: Instant) =
        logDose(
            StoredDoseLog(
                id = "skip-$medicationId-${plannedAt.toEpochMilliseconds()}",
                scheduleId = "sched-$medicationId",
                plannedAt = plannedAt,
                takenAt = null,
                status = DoseStatus.SKIPPED,
            ),
        )

    /** A TAKING-phase row; percent == null means an as-needed count row. */
    private fun takingRow(
        name: String,
        percent: Int?,
        taken: Int,
        skipped: Int = 0,
        asNeeded: Boolean = false,
        meetsTarget: Boolean? = null,
    ) = MedicationAdherenceContent(
        name = name,
        phase = MedicationPhase.TAKING,
        asNeeded = asNeeded,
        percent = percent,
        taken = taken,
        skipped = skipped,
        meetsTarget = meetsTarget,
        stoppedAt = null,
    )

    @Test
    fun `thirty days is the default window`() = runTest {
        val repo = FakeMedicationRepository()
        repo.insert("a", "Ibuprofen")
        repo.logTaken("a", now - 31.days)
        repo.logTaken("a", now - 15.days)

        val content = useCase(repo)(ActivityFilter.DAYS_30)

        assertEquals(ActivityFilter.DAYS_30, content.filter)
        assertEquals(1, content.stats.totalEvents)
        assertEquals(LocalDate(2024, 6, 4), content.from)
        // The grid follows the window: only the in-window take shows.
        assertEquals(1, content.dayCounts.sumOf { it.count })
    }

    @Test
    fun `twelve month filter loads a year of takes`() = runTest {
        val repo = FakeMedicationRepository()
        repo.insert("a", "Ibuprofen")
        repo.logTaken("a", now - 370.days) // outside the 12-month window
        repo.logTaken("a", now - 300.days)
        repo.logTaken("a", now - 1.hours)

        val content = useCase(repo)(ActivityFilter.MONTHS_12)

        assertEquals(ActivityFilter.MONTHS_12, content.filter)
        assertEquals(2, content.stats.totalEvents)
        assertEquals(LocalDate(2023, 7, 3), content.from)
        assertEquals(2, content.dayCounts.sumOf { it.count })
    }

    @Test
    fun `seven day filter narrows the window`() = runTest {
        val repo = FakeMedicationRepository()
        repo.insert("a", "Ibuprofen")
        repo.logTaken("a", now - 8.days)
        repo.logTaken("a", now - 2.days)
        repo.logTaken("a", now - 1.hours)

        val content = useCase(repo)(ActivityFilter.DAYS_7)

        assertEquals(ActivityFilter.DAYS_7, content.filter)
        assertEquals(2, content.stats.totalEvents)
        // Window shows exactly the last 7 days: 2024-06-27..2024-07-03.
        assertEquals(LocalDate(2024, 6, 27), content.from)
        assertEquals(2, content.dayCounts.sumOf { it.count })
    }

    @Test
    fun `breakdown measures each medicine against its schedule best first`() = runTest {
        val repo = FakeMedicationRepository()
        // Ibuprofen 1x/day since 2024-06-30: slots 09:00 on 6/30..7/3 = 4
        // expected. 2 taken, 1 skipped, 7/3 never recorded -> 2/(4-1) = 67%.
        repo.insert("a", "Ibuprofen", Frequency.TimesPerDay(1), Instant.parse("2024-06-30T00:00:00Z"))
        repo.logTaken("a", Instant.parse("2024-06-30T09:00:00Z"))
        repo.logTaken("a", Instant.parse("2024-07-01T09:00:00Z"))
        repo.logSkipped("a", Instant.parse("2024-07-02T09:00:00Z"))
        // Cetirizine 1x/day since 2024-07-02: both slots taken -> 100%.
        repo.insert("b", "Cetirizine", Frequency.TimesPerDay(1), Instant.parse("2024-07-02T00:00:00Z"))
        repo.logTaken("b", Instant.parse("2024-07-02T09:00:00Z"))
        repo.logTaken("b", Instant.parse("2024-07-03T09:00:00Z"))

        val content = useCase(repo)(ActivityFilter.DAYS_30)

        assertEquals(
            listOf(
                takingRow("Cetirizine", percent = 100, taken = 2, meetsTarget = true),
                takingRow("Ibuprofen", percent = 67, taken = 2, skipped = 1, meetsTarget = false),
            ),
            content.breakdown,
        )
    }

    @Test
    fun `a scheduled medicine with silent days scores below 100`() = runTest {
        val repo = FakeMedicationRepository()
        // 2x/day since 2024-07-01, only 7/1 fully logged; 7/2 has no rows at
        // all and 7/3's 09:00 passed silently: 2 taken of 5 expected = 40%.
        repo.insert("a", "Ibuprofen", Frequency.TimesPerDay(2), Instant.parse("2024-07-01T00:00:00Z"))
        repo.logTaken("a", Instant.parse("2024-07-01T09:00:00Z"))
        repo.logTaken("a", Instant.parse("2024-07-01T21:00:00Z"))

        val content = useCase(repo)(ActivityFilter.DAYS_30)

        assertEquals(
            listOf(takingRow("Ibuprofen", percent = 40, taken = 2, meetsTarget = false)),
            content.breakdown,
        )
    }

    @Test
    fun `rows cover taking as-needed and stopped medicines but never not-started ones`() = runTest {
        val repo = FakeMedicationRepository()
        // Taking + scheduled: a percent row.
        repo.insert("a", "Cetirizine", Frequency.TimesPerDay(1), Instant.parse("2024-07-02T00:00:00Z"))
        repo.logTaken("a", Instant.parse("2024-07-02T09:00:00Z"))
        repo.logTaken("a", Instant.parse("2024-07-03T09:00:00Z"))
        // Taking + interval with takes: an as-needed count row.
        repo.insert("b", "Ibuprofen", Frequency.EveryHours(6), Instant.parse("2024-07-01T00:00:00Z"))
        repo.logTaken("b", now - 3.hours)
        // Never activated: phase noise, not activity — no row.
        repo.insert("c", "Amoxicillin", Frequency.TimesPerDay(3))
        // Stopped yesterday noon after three owed slots, two answered (both in
        // the window): 2 of 3 = 67% while taking.
        repo.insert(
            "d", "Loratadine", Frequency.TimesPerDay(1),
            startedAt = Instant.parse("2024-06-30T00:00:00Z"),
            stoppedAt = Instant.parse("2024-07-02T12:00:00Z"),
        )
        repo.logTaken("d", Instant.parse("2024-06-30T09:00:00Z"))
        repo.logTaken("d", Instant.parse("2024-07-01T09:00:00Z"))

        val content = useCase(repo)(ActivityFilter.DAYS_30)

        assertEquals(
            listOf(
                takingRow("Cetirizine", percent = 100, taken = 2, meetsTarget = true),
                takingRow("Ibuprofen", percent = null, taken = 1, asNeeded = true),
                MedicationAdherenceContent(
                    name = "Loratadine",
                    phase = MedicationPhase.STOPPED,
                    asNeeded = false,
                    percent = 67,
                    taken = 2,
                    skipped = 0,
                    meetsTarget = false,
                    stoppedAt = Instant.parse("2024-07-02T12:00:00Z"),
                ),
            ),
            content.breakdown,
        )
    }

    @Test
    fun `medicines without activity in the window hide their rows`() = runTest {
        val repo = FakeMedicationRepository()
        repo.insert("a", "Ibuprofen")
        repo.insert("b", "Cetirizine", Frequency.EveryHours(6), now - 30.days)
        repo.logTaken("b", now - 10.days)
        repo.insert(
            "d", "Paracetamol", Frequency.TimesPerDay(1),
            startedAt = now - 30.days,
            stoppedAt = now - 20.days,
        )
        repo.logTaken("d", now - 25.days)
        repo.insert("c", "Loratadine", Frequency.TimesPerDay(1), Instant.parse("2024-07-02T00:00:00Z"))
        repo.logTaken("c", Instant.parse("2024-07-02T09:00:00Z"))

        val content = useCase(repo)(ActivityFilter.DAYS_7)

        assertEquals(
            listOf(takingRow("Loratadine", percent = 50, taken = 1, meetsTarget = false)),
            content.breakdown,
        )
    }

    @Test
    fun `a stopped medicine with in-window logs keeps its clipped percent row`() = runTest {
        val repo = FakeMedicationRepository()
        repo.insert(
            "d", "Paracetamol", Frequency.TimesPerDay(1),
            startedAt = now - 30.days,
            stoppedAt = now - 20.days,
        )
        repo.logTaken("d", now - 25.days)

        val content = useCase(repo)(ActivityFilter.MONTHS_12)

        val row = content.breakdown.single()
        assertEquals("Paracetamol", row.name)
        assertEquals(MedicationPhase.STOPPED, row.phase)
        assertEquals(1, row.taken)
    }

    @Test
    fun `no takes yield an empty state and no breakdown rows`() = runTest {
        val repo = FakeMedicationRepository()
        repo.insert("a", "Ibuprofen")

        val content = useCase(repo)(ActivityFilter.DAYS_30)

        assertEquals(0, content.stats.totalEvents)
        assertTrue(content.dayCounts.isEmpty())
        assertTrue(content.breakdown.isEmpty())
    }
}
