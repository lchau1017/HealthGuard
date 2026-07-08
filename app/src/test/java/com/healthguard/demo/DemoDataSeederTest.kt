@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)

package com.healthguard.demo

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.healthguard.activity.DayCompleteness
import com.healthguard.activity.adherenceResult
import com.healthguard.home.weekDayStates
import com.healthguard.shared.data.MedicationRepository
import com.healthguard.shared.db.HealthGuardDb
import java.util.Properties
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoDataSeederTest {

    private val now = Instant.parse("2026-07-08T14:00:00Z")
    private val zone = TimeZone.of("Europe/London")

    private fun repository(): MedicationRepository {
        val driver = JdbcSqliteDriver(
            JdbcSqliteDriver.IN_MEMORY,
            Properties().apply { put("foreign_keys", "true") },
        )
        HealthGuardDb.Schema.create(driver)
        return MedicationRepository(HealthGuardDb(driver), UnconfinedTestDispatcher())
    }

    private suspend fun totalDoses(repo: MedicationRepository): Int =
        repo.takenDosesInRange(now - 90.days, now + 1.days).size

    @Test
    fun `seeding is idempotent`() = runTest {
        val repo = repository()

        assertTrue(DemoDataSeeder.seed(repo, now, zone))
        val medsAfterFirst = repo.medications().first().size
        val dosesAfterFirst = totalDoses(repo)

        assertFalse(DemoDataSeeder.seed(repo, now, zone))
        assertEquals(medsAfterFirst, repo.medications().first().size)
        assertEquals(dosesAfterFirst, totalDoses(repo))
    }

    @Test
    fun `seeding is deterministic across fresh databases`() = runTest {
        val a = repository()
        val b = repository()
        DemoDataSeeder.seed(a, now, zone)
        DemoDataSeeder.seed(b, now, zone)

        assertEquals(totalDoses(a), totalDoses(b))
        assertEquals(
            a.medications().first().map { it.medication.id },
            b.medications().first().map { it.medication.id },
        )
    }

    @Test
    fun `seeds four medications with three active and recent history`() = runTest {
        val repo = repository()
        DemoDataSeeder.seed(repo, now, zone)

        val all = repo.medications().first()
        assertEquals(4, all.size)
        assertEquals(3, all.count { it.schedule.startedAt != null && it.schedule.stoppedAt == null })

        val taken = repo.takenDosesInRange(now - 71.days, now + 1.days)
        assertTrue("expected substantial history, got ${taken.size}", taken.size > 100)
        // Nothing in the future, nothing older than the window.
        assertTrue(taken.all { it.takenAt <= now + 1.days && it.takenAt >= now - 71.days })
    }

    @Test
    fun `seeded times-per-day history sits on the meal-aligned anchors`() = runTest {
        val repo = repository()
        DemoDataSeeder.seed(repo, now, zone)

        // Cetirizine is 2x/day: every planned dose lies on the 09:00/21:00
        // anchors, so seeded history matches the computed next-dose slots.
        val planned = repo.dosesInRange("demo-sch-2", now - 90.days, now + 1.days)
            .map { it.plannedAt.toLocalDateTime(zone).time }
        assertTrue(planned.isNotEmpty())
        assertTrue(
            "unexpected times: ${planned.distinct()}",
            planned.all { it == LocalTime(9, 0) || it == LocalTime(21, 0) },
        )

        // Vitamin D3 is 1x/day on the 09:00 anchor.
        val vitamin = repo.dosesInRange("demo-sch-1", now - 90.days, now + 1.days)
            .map { it.plannedAt.toLocalDateTime(zone).time }
        assertTrue(vitamin.all { it == LocalTime(9, 0) })
    }

    @Test
    fun `seeded cetirizine adherence is plausible not perfect`() = runTest {
        val repo = repository()
        DemoDataSeeder.seed(repo, now, zone)

        val schedule = repo.getMedication("demo-med-2")!!.schedule
        val from = schedule.startedAt!!
        val logs = repo.dosesInRange("demo-sch-2", from, now + 1.days)
        val result = adherenceResult(schedule, logs, from, now, zone)

        // Deterministic seed -> exact figure. The point is the band: the
        // seeder's skip-days and recent misses must pull the percent
        // visibly below 100 (honest gaps) without looking broken.
        assertEquals(87, result.percent)
        assertTrue("expected a plausible band, got ${result.percent}", result.percent!! in 75..95)
    }

    @Test
    fun `seeded week circles are not uniformly full`() = runTest {
        val repo = repository()
        DemoDataSeeder.seed(repo, now, zone)

        val schedules = repo.medications().first().map { it.schedule }
        val today = now.toLocalDateTime(zone).date
        val weekLogs = repo.doseLogsInRange(
            from = today.minus(6, DateTimeUnit.DAY).atStartOfDayIn(zone),
            to = now + 1.days,
        )
        val days = weekDayStates(schedules, weekLogs, now, zone)

        assertTrue(
            "expected at least one non-full day, got ${days.map { it.state }}",
            days.any { it.state != DayCompleteness.FULL },
        )
    }

    @Test
    fun `remove deletes all demo rows`() = runTest {
        val repo = repository()
        DemoDataSeeder.seed(repo, now, zone)

        DemoDataSeeder.remove(repo)

        assertTrue(repo.medications().first().isEmpty())
        assertEquals(0, totalDoses(repo))
    }
}
