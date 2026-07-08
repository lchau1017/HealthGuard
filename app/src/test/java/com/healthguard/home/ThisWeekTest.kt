@file:OptIn(ExperimentalTime::class)

package com.healthguard.home

import com.healthguard.shared.data.DoseStatus
import com.healthguard.shared.data.StoredDoseLog
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class ThisWeekTest {

    private val zone = TimeZone.UTC

    /** Wednesday 2026-07-08. */
    private val today = LocalDate(2026, 7, 8)

    private var doseCounter = 0

    private fun log(
        dayIso: String,
        hour: Int = 9,
        status: DoseStatus = DoseStatus.TAKEN,
        takenSameTime: Boolean = status == DoseStatus.TAKEN,
    ): StoredDoseLog {
        val planned = Instant.parse("${dayIso}T${hour.toString().padStart(2, '0')}:00:00Z")
        return StoredDoseLog(
            id = "d-${doseCounter++}",
            scheduleId = "sch-1",
            plannedAt = planned,
            takenAt = if (takenSameTime) planned else null,
            status = status,
        )
    }

    // --- weekDayStates ---

    @Test
    fun `covers the last seven days ending today`() {
        val days = weekDayStates(emptyList(), today, zone)
        assertEquals(7, days.size)
        assertEquals(LocalDate(2026, 7, 2), days.first().date)
        assertEquals(today, days.last().date)
        assertEquals(List(7) { WeekDayState.EMPTY }, days.map { it.state })
    }

    @Test
    fun `a day with takes and no misses is on track`() {
        val days = weekDayStates(
            listOf(log("2026-07-06"), log("2026-07-06", hour = 21)),
            today,
            zone,
        )
        assertEquals(WeekDayState.ON_TRACK, days.first { it.date == LocalDate(2026, 7, 6) }.state)
    }

    @Test
    fun `a day with takes and a miss is partial`() {
        val days = weekDayStates(
            listOf(log("2026-07-06"), log("2026-07-06", hour = 21, status = DoseStatus.MISSED)),
            today,
            zone,
        )
        val day = days.first { it.date == LocalDate(2026, 7, 6) }
        assertEquals(WeekDayState.PARTIAL, day.state)
        assertEquals(true, day.hasMiss)
    }

    @Test
    fun `a missed-only day is empty but flagged as missed`() {
        val days = weekDayStates(
            listOf(log("2026-07-06", status = DoseStatus.MISSED)),
            today,
            zone,
        )
        val day = days.first { it.date == LocalDate(2026, 7, 6) }
        assertEquals(WeekDayState.EMPTY, day.state)
        assertEquals(true, day.hasMiss)
    }

    @Test
    fun `skips do not break an on-track day and do not fill an empty one`() {
        val days = weekDayStates(
            listOf(
                log("2026-07-06"),
                log("2026-07-06", hour = 21, status = DoseStatus.SKIPPED),
                log("2026-07-05", status = DoseStatus.SKIPPED),
            ),
            today,
            zone,
        )
        assertEquals(WeekDayState.ON_TRACK, days.first { it.date == LocalDate(2026, 7, 6) }.state)
        val skippedOnly = days.first { it.date == LocalDate(2026, 7, 5) }
        assertEquals(WeekDayState.EMPTY, skippedOnly.state)
        assertEquals(false, skippedOnly.hasMiss)
    }

    @Test
    fun `taken doses bucket by takenAt when it differs from planned`() {
        // Planned on the 5th late evening, actually taken past midnight on the 6th.
        val log = StoredDoseLog(
            id = "d-late",
            scheduleId = "sch-1",
            plannedAt = Instant.parse("2026-07-05T21:00:00Z"),
            takenAt = Instant.parse("2026-07-06T00:30:00Z"),
            status = DoseStatus.TAKEN,
        )
        val days = weekDayStates(listOf(log), today, zone)
        assertEquals(WeekDayState.ON_TRACK, days.first { it.date == LocalDate(2026, 7, 6) }.state)
        assertEquals(WeekDayState.EMPTY, days.first { it.date == LocalDate(2026, 7, 5) }.state)
    }

    @Test
    fun `logs outside the window are ignored`() {
        val days = weekDayStates(listOf(log("2026-07-01")), today, zone)
        assertEquals(List(7) { WeekDayState.EMPTY }, days.map { it.state })
    }

    // --- weekCaption ---

    private fun day(date: LocalDate, state: WeekDayState, hasMiss: Boolean = false) =
        WeekDay(date, state, hasMiss)

    private fun week(vararg states: WeekDayState, todayMiss: Boolean = false): List<WeekDay> =
        states.mapIndexed { index, state ->
            day(
                LocalDate(2026, 7, 2 + index),
                state,
                hasMiss = state == WeekDayState.PARTIAL ||
                    (index == states.lastIndex && todayMiss),
            )
        }

    @Test
    fun `incomplete today is excluded and announced`() {
        val days = week(
            WeekDayState.ON_TRACK, WeekDayState.ON_TRACK, WeekDayState.EMPTY,
            WeekDayState.ON_TRACK, WeekDayState.PARTIAL, WeekDayState.ON_TRACK,
            WeekDayState.EMPTY,
        )
        assertEquals(
            "4 of 6 days on track. Today still to come.",
            weekCaption(days, todayComplete = false),
        )
    }

    @Test
    fun `complete today is counted and the suffix is dropped`() {
        val days = week(
            WeekDayState.ON_TRACK, WeekDayState.ON_TRACK, WeekDayState.EMPTY,
            WeekDayState.ON_TRACK, WeekDayState.ON_TRACK, WeekDayState.ON_TRACK,
            WeekDayState.ON_TRACK,
        )
        assertEquals("6 of 7 days on track.", weekCaption(days, todayComplete = true))
    }

    @Test
    fun `a miss today counts the day off-track without the suffix`() {
        val days = week(
            WeekDayState.ON_TRACK, WeekDayState.ON_TRACK, WeekDayState.ON_TRACK,
            WeekDayState.ON_TRACK, WeekDayState.ON_TRACK, WeekDayState.ON_TRACK,
            WeekDayState.PARTIAL,
        )
        assertEquals("6 of 7 days on track.", weekCaption(days, todayComplete = false))
    }

    @Test
    fun `a missed-only today also counts off-track`() {
        val days = week(
            WeekDayState.ON_TRACK, WeekDayState.ON_TRACK, WeekDayState.ON_TRACK,
            WeekDayState.ON_TRACK, WeekDayState.ON_TRACK, WeekDayState.ON_TRACK,
            WeekDayState.EMPTY,
            todayMiss = true,
        )
        assertEquals("6 of 7 days on track.", weekCaption(days, todayComplete = false))
    }

    @Test
    fun `a perfect week gets the celebration line`() {
        val days = week(
            WeekDayState.ON_TRACK, WeekDayState.ON_TRACK, WeekDayState.ON_TRACK,
            WeekDayState.ON_TRACK, WeekDayState.ON_TRACK, WeekDayState.ON_TRACK,
            WeekDayState.ON_TRACK,
        )
        assertEquals("7 of 7 days on track — nice work.", weekCaption(days, todayComplete = true))
    }

    @Test
    fun `a perfect week needs today complete`() {
        val days = week(
            WeekDayState.ON_TRACK, WeekDayState.ON_TRACK, WeekDayState.ON_TRACK,
            WeekDayState.ON_TRACK, WeekDayState.ON_TRACK, WeekDayState.ON_TRACK,
            WeekDayState.ON_TRACK,
        )
        assertEquals(
            "6 of 6 days on track. Today still to come.",
            weekCaption(days, todayComplete = false),
        )
    }
}
