@file:OptIn(ExperimentalCoroutinesApi::class)

package com.healthguard.home.domain

import com.healthguard.activity.DoseDayStatus
import com.healthguard.activity.adherenceResult
import com.healthguard.home.weekDayStates
import com.healthguard.domain.model.DoseStatus
import com.healthguard.testing.FakeMedicationRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

class DemoDataUseCasesTest {

    private val now = Instant.parse("2026-07-08T14:00:00Z")
    private val zone = TimeZone.of("Europe/London")

    private fun repository(): FakeMedicationRepository = FakeMedicationRepository()

    private fun FakeMedicationRepository.seedDemoData() =
        SeedDemoDataUseCase(this, clock = { now }, zone = zone)

    private fun FakeMedicationRepository.removeDemoData() = RemoveDemoDataUseCase(this)

    private suspend fun totalDoses(repo: FakeMedicationRepository): Int =
        repo.takenDosesInRange(now - 90.days, now + 1.days).size

    @Test
    fun `seeding is idempotent`() = runTest {
        val repo = repository()

        assertTrue(repo.seedDemoData().invoke())
        val medsAfterFirst = repo.medications().first().size
        val dosesAfterFirst = totalDoses(repo)

        assertFalse(repo.seedDemoData().invoke())
        assertEquals(medsAfterFirst, repo.medications().first().size)
        assertEquals(dosesAfterFirst, totalDoses(repo))
    }

    @Test
    fun `seeding is deterministic across fresh databases`() = runTest {
        val a = repository()
        val b = repository()
        a.seedDemoData().invoke()
        b.seedDemoData().invoke()

        assertEquals(totalDoses(a), totalDoses(b))
        assertEquals(
            a.medications().first().map { it.medication.id },
            b.medications().first().map { it.medication.id },
        )
    }

    @Test
    fun `seeds five medications covering every treatment phase`() = runTest {
        val repo = repository()
        repo.seedDemoData().invoke()

        val all = repo.medications().first()
        assertEquals(5, all.size)
        assertEquals(
            listOf("demo-med-1", "demo-med-2", "demo-med-3", "demo-med-4", "demo-med-5"),
            demoMedicationIds,
        )
        // Three taking, one never started (Amoxicillin), one stopped (Loratadine).
        assertEquals(3, all.count { it.schedule.startedAt != null && it.schedule.stoppedAt == null })
        assertEquals(1, all.count { it.schedule.startedAt == null })
        assertEquals(1, all.count { it.schedule.stoppedAt != null })

        val taken = repo.takenDosesInRange(now - 71.days, now + 1.days)
        assertTrue(taken.size > 100, "expected substantial history, got ${taken.size}")
        // Nothing in the future, nothing older than the window.
        assertTrue(taken.all { it.takenAt <= now + 1.days && it.takenAt >= now - 71.days })
    }

    @Test
    fun `stopped loratadine has history only while it was active`() = runTest {
        val repo = repository()
        repo.seedDemoData().invoke()

        val schedule = repo.getMedication("demo-med-5")!!.schedule
        val startedAt = schedule.startedAt
        val stoppedAt = schedule.stoppedAt
        assertTrue(startedAt != null && stoppedAt != null)
        // Started ~8 weeks ago, stopped ~2 weeks ago: the trailing weeks of
        // its heat map read out-of-treatment.
        assertTrue(now - startedAt!! >= 55.days)
        assertTrue(now - stoppedAt!! >= 13.days)

        val logs = repo.dosesInRange("demo-sch-5", now - 90.days, now + 1.days)
        assertTrue(logs.isNotEmpty(), "expected active-stretch history")
        assertTrue(logs.all { it.plannedAt >= startedAt && it.plannedAt < stoppedAt })
    }

    @Test
    fun `cetirizine has a deliberately skipped day five days ago`() = runTest {
        val repo = repository()
        repo.seedDemoData().invoke()

        val skippedDay = now.toLocalDateTime(zone).date.minus(5, DateTimeUnit.DAY)
        val dayLogs = repo.dosesInRange(
            "demo-sch-2",
            skippedDay.atStartOfDayIn(zone),
            skippedDay.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone),
        )
        // Both of the day's doses logged as skipped: the dash state on the
        // detail heat map is always demonstrable.
        assertEquals(2, dayLogs.size)
        assertTrue(dayLogs.all { it.status == DoseStatus.SKIPPED })
    }

    @Test
    fun `seeded times-per-day history sits on the meal-aligned anchors`() = runTest {
        val repo = repository()
        repo.seedDemoData().invoke()

        // Cetirizine is 2x/day: every planned dose lies on the 09:00/21:00
        // anchors, so seeded history matches the computed next-dose slots.
        val planned = repo.dosesInRange("demo-sch-2", now - 90.days, now + 1.days)
            .map { it.plannedAt.toLocalDateTime(zone).time }
        assertTrue(planned.isNotEmpty())
        assertTrue(
            planned.all { it == LocalTime(9, 0) || it == LocalTime(21, 0) },
            "unexpected times: ${planned.distinct()}",
        )

        // Vitamin D3 is 1x/day on the 09:00 anchor.
        val vitamin = repo.dosesInRange("demo-sch-1", now - 90.days, now + 1.days)
            .map { it.plannedAt.toLocalDateTime(zone).time }
        assertTrue(vitamin.all { it == LocalTime(9, 0) })
    }

    @Test
    fun `seeded cetirizine adherence is plausible not perfect`() = runTest {
        val repo = repository()
        repo.seedDemoData().invoke()

        val schedule = repo.getMedication("demo-med-2")!!.schedule
        val from = schedule.startedAt!!
        val logs = repo.dosesInRange("demo-sch-2", from, now + 1.days)
        val result = adherenceResult(schedule, logs, from, now, zone)

        // Deterministic seed -> exact figure. The point is the band: the
        // seeder's skip-days and recent misses must pull the percent
        // visibly below 100 (honest gaps) without looking broken.
        assertEquals(86, result.percent)
        assertTrue(result.percent!! in 75..95, "expected a plausible band, got ${result.percent}")
    }

    @Test
    fun `seeded week circles are not uniformly full`() = runTest {
        val repo = repository()
        repo.seedDemoData().invoke()

        val schedules = repo.medications().first().map { it.schedule }
        val today = now.toLocalDateTime(zone).date
        val weekLogs = repo.doseLogsInRange(
            from = today.minus(6, DateTimeUnit.DAY).atStartOfDayIn(zone),
            to = now + 1.days,
        )
        val days = weekDayStates(schedules, weekLogs, now, zone)

        assertTrue(
            days.any { it.state != DoseDayStatus.MET },
            "expected at least one non-full day, got ${days.map { it.state }}",
        )
    }

    @Test
    fun `remove deletes all demo rows`() = runTest {
        val repo = repository()
        repo.seedDemoData().invoke()

        repo.removeDemoData().invoke()

        assertTrue(repo.medications().first().isEmpty())
        assertEquals(0, totalDoses(repo))
    }

    @Test
    fun `seeding applies in one visible step`() = runTest {
        val repo = repository()
        val sizes = mutableListOf<Int>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.medications().collect { sizes += it.size }
        }

        repo.seedDemoData().invoke()

        // Never a partially seeded emission (medications without their
        // history used to flash a bogus due alert on the home screen).
        assertTrue(sizes.none { it in 1..4 }, "saw partial emissions: $sizes")
        assertEquals(5, sizes.last())
        job.cancel()
    }
}
