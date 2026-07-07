@file:OptIn(ExperimentalTime::class)

package com.medguard.shared.domain

import com.medguard.shared.data.StoredSchedule
import com.medguard.shared.extraction.Frequency
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Pins the exact semantics of [nextDose]:
 *
 * - Dormant (startedAt == null), stopped (stoppedAt != null), or missing
 *   frequency -> null.
 * - EveryHours(h): lastTaken + h real hours (instant arithmetic, may be in
 *   the past = overdue); first dose (lastTaken == null) is due at startedAt.
 * - TimesPerDay(n): wall-clock slots between 08:00 and 22:00 local. The
 *   returned slot is the earliest one strictly after
 *   maxOf(lastTaken, now) when a dose was taken before, or strictly after
 *   startedAt when none was (so an already-passed slot is returned as
 *   overdue for a freshly started schedule).
 * - Slots are wall-clock: 08:00 stays 08:00 local across DST transitions.
 */
class NextDoseCalculatorTest {

    private val utc = TimeZone.UTC
    private val london = TimeZone.of("Europe/London")

    private fun schedule(
        frequency: Frequency? = Frequency.TimesPerDay(2),
        startedAt: Instant? = Instant.parse("2026-07-01T06:00:00Z"),
        stoppedAt: Instant? = null,
    ) = StoredSchedule(
        id = "sched-1",
        medicationId = "med-1",
        frequency = frequency,
        withFood = null,
        startedAt = startedAt,
        stoppedAt = stoppedAt,
    )

    private val defaultNow = Instant.parse("2026-07-01T09:00:00Z")

    // --- Rule 1: inactive schedules ---

    @Test
    fun dormantScheduleReturnsNull() {
        assertNull(nextDose(schedule(startedAt = null), lastTaken = null, now = defaultNow, zone = utc))
    }

    @Test
    fun stoppedScheduleReturnsNull() {
        val stopped = schedule(stoppedAt = Instant.parse("2026-07-01T08:00:00Z"))
        assertNull(nextDose(stopped, lastTaken = Instant.parse("2026-07-01T08:00:00Z"), now = defaultNow, zone = utc))
    }

    @Test
    fun nullFrequencyReturnsNull() {
        assertNull(nextDose(schedule(frequency = null), lastTaken = null, now = defaultNow, zone = utc))
    }

    // --- Rule 2: EveryHours ---

    @Test
    fun everyHoursReturnsLastTakenPlusInterval() {
        val result = nextDose(
            schedule(frequency = Frequency.EveryHours(6)),
            lastTaken = Instant.parse("2026-07-01T08:00:00Z"),
            now = Instant.parse("2026-07-01T10:00:00Z"),
            zone = utc,
        )
        assertEquals(Instant.parse("2026-07-01T14:00:00Z"), result)
    }

    @Test
    fun everyHoursFirstDoseIsDueAtStartedAt() {
        val result = nextDose(
            schedule(frequency = Frequency.EveryHours(6), startedAt = Instant.parse("2026-07-01T06:00:00Z")),
            lastTaken = null,
            now = Instant.parse("2026-07-01T07:00:00Z"),
            zone = utc,
        )
        assertEquals(Instant.parse("2026-07-01T06:00:00Z"), result)
    }

    @Test
    fun everyHoursOverdueReturnsPastInstant() {
        val result = nextDose(
            schedule(frequency = Frequency.EveryHours(6), startedAt = Instant.parse("2026-06-30T00:00:00Z")),
            lastTaken = Instant.parse("2026-07-01T00:00:00Z"),
            now = Instant.parse("2026-07-01T12:00:00Z"),
            zone = utc,
        )
        assertEquals(Instant.parse("2026-07-01T06:00:00Z"), result)
    }

    // --- Rule 3: TimesPerDay slot layout ---

    @Test
    fun timesPerDayOneHasSingleEightAmSlot() {
        val result = nextDose(
            schedule(frequency = Frequency.TimesPerDay(1), startedAt = Instant.parse("2026-07-01T06:00:00Z")),
            lastTaken = null,
            now = Instant.parse("2026-07-01T06:30:00Z"),
            zone = utc,
        )
        assertEquals(Instant.parse("2026-07-01T08:00:00Z"), result)
    }

    @Test
    fun timesPerDayTwoNextSlotIsTenPm() {
        val result = nextDose(
            schedule(frequency = Frequency.TimesPerDay(2)),
            lastTaken = Instant.parse("2026-07-01T08:00:00Z"),
            now = Instant.parse("2026-07-01T09:00:00Z"),
            zone = utc,
        )
        assertEquals(Instant.parse("2026-07-01T22:00:00Z"), result)
    }

    @Test
    fun timesPerDayThreeMiddleSlotIsThreePm() {
        val result = nextDose(
            schedule(frequency = Frequency.TimesPerDay(3)),
            lastTaken = Instant.parse("2026-07-01T08:00:00Z"),
            now = Instant.parse("2026-07-01T09:00:00Z"),
            zone = utc,
        )
        assertEquals(Instant.parse("2026-07-01T15:00:00Z"), result)
    }

    @Test
    fun rollsOverToTomorrowAfterLastSlot() {
        val result = nextDose(
            schedule(frequency = Frequency.TimesPerDay(2)),
            lastTaken = Instant.parse("2026-07-01T22:00:00Z"),
            now = Instant.parse("2026-07-01T22:30:00Z"),
            zone = utc,
        )
        assertEquals(Instant.parse("2026-07-02T08:00:00Z"), result)
    }

    @Test
    fun slotAfterLastTakenSameDay() {
        // Took the 15:00 slot a little late; next is 22:00 the same day.
        val result = nextDose(
            schedule(frequency = Frequency.TimesPerDay(3)),
            lastTaken = Instant.parse("2026-07-01T15:05:00Z"),
            now = Instant.parse("2026-07-01T15:10:00Z"),
            zone = utc,
        )
        assertEquals(Instant.parse("2026-07-01T22:00:00Z"), result)
    }

    @Test
    fun oldLastTakenYieldsNextUpcomingSlotNotAPastOne() {
        // Days without doses do not accumulate: the next slot is after now.
        val result = nextDose(
            schedule(frequency = Frequency.TimesPerDay(2), startedAt = Instant.parse("2026-06-01T06:00:00Z")),
            lastTaken = Instant.parse("2026-06-20T08:00:00Z"),
            now = Instant.parse("2026-07-01T09:00:00Z"),
            zone = utc,
        )
        assertEquals(Instant.parse("2026-07-01T22:00:00Z"), result)
    }

    @Test
    fun neverTakenWithPassedSlotIsOverdueAtThatSlot() {
        // Started before today's 08:00 slot, never took anything: the 08:00
        // slot is returned even though it is before now (overdue-now).
        val result = nextDose(
            schedule(frequency = Frequency.TimesPerDay(2), startedAt = Instant.parse("2026-07-01T06:00:00Z")),
            lastTaken = null,
            now = Instant.parse("2026-07-01T12:00:00Z"),
            zone = utc,
        )
        assertEquals(Instant.parse("2026-07-01T08:00:00Z"), result)
    }

    @Test
    fun slotsBeforeStartedAtAreNeverReturned() {
        // Started mid-afternoon: 08:00 and 15:00 already passed before the
        // schedule existed, so the first dose is the 22:00 slot.
        val result = nextDose(
            schedule(frequency = Frequency.TimesPerDay(3), startedAt = Instant.parse("2026-07-01T16:00:00Z")),
            lastTaken = null,
            now = Instant.parse("2026-07-01T16:05:00Z"),
            zone = utc,
        )
        assertEquals(Instant.parse("2026-07-01T22:00:00Z"), result)
    }

    // --- Rule 4: DST ---

    @Test
    fun everyHoursAcrossSpringForwardIsSixRealHours() {
        // Europe/London 2026-03-29: clocks jump 01:00 GMT -> 02:00 BST.
        // Taken 23:00 local (GMT); 6 real hours later is 05:00 UTC, which is
        // 06:00 local BST (7 wall-clock hours later).
        val result = nextDose(
            schedule(frequency = Frequency.EveryHours(6), startedAt = Instant.parse("2026-03-28T08:00:00Z")),
            lastTaken = Instant.parse("2026-03-28T23:00:00Z"),
            now = Instant.parse("2026-03-29T01:30:00Z"),
            zone = london,
        )
        assertEquals(Instant.parse("2026-03-29T05:00:00Z"), result)
        assertEquals(LocalTime(6, 0), result!!.toLocalDateTime(london).time)
    }

    @Test
    fun timesPerDaySlotStaysAtEightLocalAcrossSpringForward() {
        // Last dose 22:00 GMT on the 28th; the morning slot on the DST day
        // still lands at 08:00 local (BST), i.e. 07:00 UTC.
        val result = nextDose(
            schedule(frequency = Frequency.TimesPerDay(2), startedAt = Instant.parse("2026-03-20T08:00:00Z")),
            lastTaken = Instant.parse("2026-03-28T22:00:00Z"),
            now = Instant.parse("2026-03-29T01:30:00Z"),
            zone = london,
        )
        assertEquals(Instant.parse("2026-03-29T07:00:00Z"), result)
        assertEquals(LocalTime(8, 0), result!!.toLocalDateTime(london).time)
    }

    @Test
    fun timesPerDaySlotStaysAtEightLocalAcrossAutumnBack() {
        // Europe/London 2026-10-25: clocks fall back 02:00 BST -> 01:00 GMT.
        // Last dose 22:00 BST on the 24th (21:00 UTC); the morning slot on
        // the DST day is 08:00 local (GMT), i.e. 08:00 UTC.
        val result = nextDose(
            schedule(frequency = Frequency.TimesPerDay(2), startedAt = Instant.parse("2026-10-20T08:00:00Z")),
            lastTaken = Instant.parse("2026-10-24T21:00:00Z"),
            now = Instant.parse("2026-10-25T05:00:00Z"),
            zone = london,
        )
        assertEquals(Instant.parse("2026-10-25T08:00:00Z"), result)
        assertEquals(LocalTime(8, 0), result!!.toLocalDateTime(london).time)
    }
}
