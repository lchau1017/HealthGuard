@file:OptIn(ExperimentalTime::class)

package com.healthguard.home

import com.healthguard.activity.DoseDayStatus
import com.healthguard.domain.model.DoseStatus
import com.healthguard.domain.model.StoredDoseLog
import com.healthguard.domain.model.StoredSchedule
import com.healthguard.domain.extraction.Frequency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

class WeekDayStatesTest {

    private val zone = TimeZone.UTC

    /** Wednesday 2026-07-08, mid-morning: today's 09:00 slot has passed. */
    private val now = Instant.parse("2026-07-08T10:00:00Z")

    private val today = LocalDate(2026, 7, 8)

    private var counter = 0

    private fun schedule(
        frequency: Frequency? = Frequency.TimesPerDay(2),
        startedAt: Instant? = Instant.parse("2026-06-01T00:00:00Z"),
        stoppedAt: Instant? = null,
        id: String = "sch-${counter++}",
    ) = StoredSchedule(
        id = id,
        medicationId = "med-$id",
        frequency = frequency,
        withFood = null,
        startedAt = startedAt,
        stoppedAt = stoppedAt,
    )

    private fun log(
        scheduleId: String,
        at: String,
        status: DoseStatus = DoseStatus.TAKEN,
    ): StoredDoseLog {
        val instant = Instant.parse(at)
        return StoredDoseLog(
            id = "d-${counter++}",
            scheduleId = scheduleId,
            plannedAt = instant,
            takenAt = if (status == DoseStatus.TAKEN) instant else null,
            status = status,
        )
    }

    private fun stateOf(days: List<WeekDay>, date: LocalDate): DoseDayStatus =
        days.first { it.date == date }.state

    // --- weekDayStates ---

    @Test
    fun `covers the last seven days ending today all empty without schedules`() {
        val days = weekDayStates(emptyList(), emptyList(), now, zone)
        assertEquals(7, days.size)
        assertEquals(LocalDate(2026, 7, 2), days.first().date)
        assertEquals(today, days.last().date)
        assertEquals(List(7) { DoseDayStatus.OUT_OF_TREATMENT }, days.map { it.state })
    }

    @Test
    fun `a day with every expected dose taken is full`() {
        val sch = schedule(id = "a")
        val days = weekDayStates(
            listOf(sch),
            listOf(log("a", "2026-07-06T09:00:00Z"), log("a", "2026-07-06T21:05:00Z")),
            now,
            zone,
        )
        assertEquals(DoseDayStatus.MET, stateOf(days, LocalDate(2026, 7, 6)))
    }

    @Test
    fun `a day with some doses taken is partial`() {
        val sch = schedule(id = "a")
        val days = weekDayStates(
            listOf(sch),
            listOf(log("a", "2026-07-06T09:00:00Z")),
            now,
            zone,
        )
        assertEquals(DoseDayStatus.PARTIAL, stateOf(days, LocalDate(2026, 7, 6)))
    }

    @Test
    fun `a silent day with expectations is none even without logged misses`() {
        // The user never opened the app on the 6th: previously that day
        // vanished; now the schedule still owed two doses.
        val days = weekDayStates(listOf(schedule(id = "a")), emptyList(), now, zone)
        assertEquals(DoseDayStatus.NOT_TAKEN, stateOf(days, LocalDate(2026, 7, 6)))
    }

    @Test
    fun `a logged miss and a silent slot read the same`() {
        val sch = schedule(id = "a")
        val days = weekDayStates(
            listOf(sch),
            listOf(log("a", "2026-07-06T09:00:00Z", status = DoseStatus.MISSED)),
            now,
            zone,
        )
        assertEquals(DoseDayStatus.NOT_TAKEN, stateOf(days, LocalDate(2026, 7, 6)))
    }

    @Test
    fun `skips adjust the day's expectation instead of breaking it`() {
        val sch = schedule(id = "a")
        val days = weekDayStates(
            listOf(sch),
            listOf(
                // 5th: both deliberately skipped -> a visible choice.
                log("a", "2026-07-05T09:00:00Z", status = DoseStatus.SKIPPED),
                log("a", "2026-07-05T21:00:00Z", status = DoseStatus.SKIPPED),
                // 6th: one skipped, the other taken -> met.
                log("a", "2026-07-06T09:00:00Z", status = DoseStatus.SKIPPED),
                log("a", "2026-07-06T21:00:00Z"),
            ),
            now,
            zone,
        )
        assertEquals(DoseDayStatus.SKIPPED, stateOf(days, LocalDate(2026, 7, 5)))
        assertEquals(DoseDayStatus.MET, stateOf(days, LocalDate(2026, 7, 6)))
    }

    @Test
    fun `expectations combine across schedules`() {
        val a = schedule(frequency = Frequency.TimesPerDay(1), id = "a")
        val b = schedule(frequency = Frequency.TimesPerDay(1), id = "b")
        val days = weekDayStates(
            listOf(a, b),
            listOf(log("a", "2026-07-06T09:00:00Z")),
            now,
            zone,
        )
        // One of the two owed doses taken.
        assertEquals(DoseDayStatus.PARTIAL, stateOf(days, LocalDate(2026, 7, 6)))
    }

    @Test
    fun `interval schedules neither owe doses nor answer scheduled ones`() {
        val scheduled = schedule(frequency = Frequency.TimesPerDay(1), id = "a")
        val interval = schedule(frequency = Frequency.EveryHours(6), id = "b")
        val days = weekDayStates(
            listOf(scheduled, interval),
            listOf(log("b", "2026-07-06T09:00:00Z")),
            now,
            zone,
        )
        // The as-needed take cannot stand in for the scheduled dose.
        assertEquals(DoseDayStatus.NOT_TAKEN, stateOf(days, LocalDate(2026, 7, 6)))
    }

    @Test
    fun `today only counts slots that have already passed`() {
        // 2x/day at 10:00: only the 09:00 slot is owed so far.
        val sch = schedule(id = "a")
        val days = weekDayStates(
            listOf(sch),
            listOf(log("a", "2026-07-08T09:02:00Z")),
            now,
            zone,
        )
        assertEquals(DoseDayStatus.MET, stateOf(days, today))
    }

    @Test
    fun `a schedule started mid-week owes nothing before its start`() {
        val sch = schedule(startedAt = Instant.parse("2026-07-06T00:00:00Z"), id = "a")
        val days = weekDayStates(listOf(sch), emptyList(), now, zone)
        assertEquals(DoseDayStatus.OUT_OF_TREATMENT, stateOf(days, LocalDate(2026, 7, 5)))
        assertEquals(DoseDayStatus.NOT_TAKEN, stateOf(days, LocalDate(2026, 7, 6)))
    }

    @Test
    fun `a stopped schedule owes doses only while it was active`() {
        val sch = schedule(
            startedAt = Instant.parse("2026-06-01T00:00:00Z"),
            stoppedAt = Instant.parse("2026-07-06T12:00:00Z"),
            id = "a",
        )
        val days = weekDayStates(
            listOf(sch),
            listOf(log("a", "2026-07-06T09:00:00Z")),
            now,
            zone,
        )
        // The 6th owed only its morning dose (stopped before 21:00) — taken.
        assertEquals(DoseDayStatus.MET, stateOf(days, LocalDate(2026, 7, 6)))
        assertEquals(DoseDayStatus.OUT_OF_TREATMENT, stateOf(days, LocalDate(2026, 7, 7)))
    }

    // --- todayHasPendingSlots ---

    @Test
    fun `today has pending slots while a later anchor remains`() {
        assertTrue(todayHasPendingSlots(listOf(schedule(id = "a")), now, zone))
    }

    @Test
    fun `today has no pending slots once every anchor passed`() {
        val onceDaily = schedule(frequency = Frequency.TimesPerDay(1), id = "a")
        assertFalse(todayHasPendingSlots(listOf(onceDaily), now, zone))
    }

    @Test
    fun `interval and dormant schedules never have pending slots`() {
        assertFalse(
            todayHasPendingSlots(
                listOf(
                    schedule(frequency = Frequency.EveryHours(6), id = "a"),
                    schedule(startedAt = null, id = "b"),
                ),
                now,
                zone,
            ),
        )
    }
}
