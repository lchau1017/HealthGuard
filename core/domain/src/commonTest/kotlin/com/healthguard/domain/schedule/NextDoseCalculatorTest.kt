package com.healthguard.domain.schedule

import com.healthguard.domain.model.StoredSchedule
import com.healthguard.domain.extraction.Frequency
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Pins the exact semantics of [nextDose] and [doseSlots]:
 *
 * - Dormant (startedAt == null), stopped (stoppedAt != null), or missing
 *   frequency -> null.
 * - EveryHours(h): lastTaken + h real hours (instant arithmetic, may be in
 *   the past = overdue); first dose (lastTaken == null) is due at startedAt.
 * - TimesPerDay(n): meal-aligned wall-clock anchors — 1x 09:00; 2x 09:00 and
 *   21:00; 3x 08:00/14:00/21:00; 4x 08:00/12:00/17:00/21:00; 5+ evenly in
 *   08:00-22:00. The returned slot is the earliest one strictly after
 *   maxOf(lastTaken, now) when a dose was taken before, or strictly after
 *   startedAt when none was (so an already-passed slot is returned as
 *   overdue for a freshly started schedule).
 * - Slots are wall-clock: 09:00 stays 09:00 local across DST transitions.
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

    private val defaultNow = Instant.parse("2026-07-01T09:30:00Z")

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

    // --- Rule 3: TimesPerDay slot anchors ---

    @Test
    fun timesPerDayOneHasSingleNineAmSlot() {
        val result = nextDose(
            schedule(frequency = Frequency.TimesPerDay(1), startedAt = Instant.parse("2026-07-01T06:00:00Z")),
            lastTaken = null,
            now = Instant.parse("2026-07-01T06:30:00Z"),
            zone = utc,
        )
        assertEquals(Instant.parse("2026-07-01T09:00:00Z"), result)
    }

    @Test
    fun timesPerDayTwoEveningSlotIsNinePm() {
        // Took the 09:00 slot a little late; next is 21:00 the same day.
        val result = nextDose(
            schedule(frequency = Frequency.TimesPerDay(2)),
            lastTaken = Instant.parse("2026-07-01T09:05:00Z"),
            now = Instant.parse("2026-07-01T09:10:00Z"),
            zone = utc,
        )
        assertEquals(Instant.parse("2026-07-01T21:00:00Z"), result)
    }

    @Test
    fun timesPerDayThreeMiddleSlotIsTwoPm() {
        val result = nextDose(
            schedule(frequency = Frequency.TimesPerDay(3)),
            lastTaken = Instant.parse("2026-07-01T08:00:00Z"),
            now = Instant.parse("2026-07-01T09:00:00Z"),
            zone = utc,
        )
        assertEquals(Instant.parse("2026-07-01T14:00:00Z"), result)
    }

    @Test
    fun timesPerDayFourAfternoonSlotIsFivePm() {
        // Took the 12:00 anchor; the next 4x anchor is 17:00.
        val result = nextDose(
            schedule(frequency = Frequency.TimesPerDay(4)),
            lastTaken = Instant.parse("2026-07-01T12:00:00Z"),
            now = Instant.parse("2026-07-01T12:30:00Z"),
            zone = utc,
        )
        assertEquals(Instant.parse("2026-07-01T17:00:00Z"), result)
    }

    @Test
    fun rollsOverToTomorrowAfterLastSlot() {
        val result = nextDose(
            schedule(frequency = Frequency.TimesPerDay(2)),
            lastTaken = Instant.parse("2026-07-01T21:00:00Z"),
            now = Instant.parse("2026-07-01T22:30:00Z"),
            zone = utc,
        )
        assertEquals(Instant.parse("2026-07-02T09:00:00Z"), result)
    }

    @Test
    fun slotAfterLastTakenSameDay() {
        // Took the 14:00 slot a little late; next is 21:00 the same day.
        val result = nextDose(
            schedule(frequency = Frequency.TimesPerDay(3)),
            lastTaken = Instant.parse("2026-07-01T14:05:00Z"),
            now = Instant.parse("2026-07-01T14:10:00Z"),
            zone = utc,
        )
        assertEquals(Instant.parse("2026-07-01T21:00:00Z"), result)
    }

    @Test
    fun oldLastTakenYieldsNextUpcomingSlotNotAPastOne() {
        // Days without doses do not accumulate: the next slot is after now.
        val result = nextDose(
            schedule(frequency = Frequency.TimesPerDay(2), startedAt = Instant.parse("2026-06-01T06:00:00Z")),
            lastTaken = Instant.parse("2026-06-20T09:00:00Z"),
            now = Instant.parse("2026-07-01T09:30:00Z"),
            zone = utc,
        )
        assertEquals(Instant.parse("2026-07-01T21:00:00Z"), result)
    }

    @Test
    fun neverTakenWithPassedSlotIsOverdueAtThatSlot() {
        // Started before today's 09:00 slot, never took anything: the 09:00
        // slot is returned even though it is before now (overdue-now).
        val result = nextDose(
            schedule(frequency = Frequency.TimesPerDay(2), startedAt = Instant.parse("2026-07-01T06:00:00Z")),
            lastTaken = null,
            now = Instant.parse("2026-07-01T12:00:00Z"),
            zone = utc,
        )
        assertEquals(Instant.parse("2026-07-01T09:00:00Z"), result)
    }

    @Test
    fun slotsBeforeStartedAtAreNeverReturned() {
        // Started mid-afternoon: 08:00 and 14:00 already passed before the
        // schedule existed, so the first dose is the 21:00 anchor.
        val result = nextDose(
            schedule(frequency = Frequency.TimesPerDay(3), startedAt = Instant.parse("2026-07-01T16:00:00Z")),
            lastTaken = null,
            now = Instant.parse("2026-07-01T16:05:00Z"),
            zone = utc,
        )
        assertEquals(Instant.parse("2026-07-01T21:00:00Z"), result)
    }

    // --- Rule 4: doseSlots layout ---

    @Test
    fun doseSlotsUseMealAlignedAnchors() {
        assertEquals(listOf(LocalTime(9, 0)), doseSlots(Frequency.TimesPerDay(1)))
        assertEquals(
            listOf(LocalTime(9, 0), LocalTime(21, 0)),
            doseSlots(Frequency.TimesPerDay(2)),
        )
        assertEquals(
            listOf(LocalTime(8, 0), LocalTime(14, 0), LocalTime(21, 0)),
            doseSlots(Frequency.TimesPerDay(3)),
        )
        assertEquals(
            listOf(LocalTime(8, 0), LocalTime(12, 0), LocalTime(17, 0), LocalTime(21, 0)),
            doseSlots(Frequency.TimesPerDay(4)),
        )
    }

    @Test
    fun doseSlotsFiveAndUpSpreadEvenlyInWakingWindow() {
        assertEquals(
            listOf(
                LocalTime(8, 0), LocalTime(11, 30), LocalTime(15, 0),
                LocalTime(18, 30), LocalTime(22, 0),
            ),
            doseSlots(Frequency.TimesPerDay(5)),
        )
        assertEquals(
            listOf(
                LocalTime(8, 0), LocalTime(10, 20), LocalTime(12, 40), LocalTime(15, 0),
                LocalTime(17, 20), LocalTime(19, 40), LocalTime(22, 0),
            ),
            doseSlots(Frequency.TimesPerDay(7)),
        )
    }

    @Test
    fun doseSlotsNeverFallInTheSleepWindow() {
        // Nothing is ever scheduled between 22:00 and 08:00.
        (1..12).forEach { n ->
            doseSlots(Frequency.TimesPerDay(n)).forEach { slot ->
                assertTrue(
                    slot >= LocalTime(8, 0) && slot <= LocalTime(22, 0),
                    "TimesPerDay($n) slot $slot is outside 08:00-22:00",
                )
            }
        }
    }

    @Test
    fun doseSlotsEmptyForEveryHoursAndNull() {
        // EveryHours doses anchor to the schedule/last take, not wall-clock slots.
        assertEquals(emptyList(), doseSlots(Frequency.EveryHours(6)))
        assertEquals(emptyList(), doseSlots(null))
    }

    // --- Rule 5: DST ---

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
    fun timesPerDaySlotStaysAtNineLocalAcrossSpringForward() {
        // Last dose 21:00 GMT on the 28th; the morning slot on the DST day
        // still lands at 09:00 local (BST), i.e. 08:00 UTC.
        val result = nextDose(
            schedule(frequency = Frequency.TimesPerDay(2), startedAt = Instant.parse("2026-03-20T08:00:00Z")),
            lastTaken = Instant.parse("2026-03-28T21:00:00Z"),
            now = Instant.parse("2026-03-29T01:30:00Z"),
            zone = london,
        )
        assertEquals(Instant.parse("2026-03-29T08:00:00Z"), result)
        assertEquals(LocalTime(9, 0), result!!.toLocalDateTime(london).time)
    }

    @Test
    fun timesPerDaySlotStaysAtNineLocalAcrossAutumnBack() {
        // Europe/London 2026-10-25: clocks fall back 02:00 BST -> 01:00 GMT.
        // Last dose 21:00 BST on the 24th (20:00 UTC); the morning slot on
        // the DST day is 09:00 local (GMT), i.e. 09:00 UTC.
        val result = nextDose(
            schedule(frequency = Frequency.TimesPerDay(2), startedAt = Instant.parse("2026-10-20T08:00:00Z")),
            lastTaken = Instant.parse("2026-10-24T20:00:00Z"),
            now = Instant.parse("2026-10-25T05:00:00Z"),
            zone = london,
        )
        assertEquals(Instant.parse("2026-10-25T09:00:00Z"), result)
        assertEquals(LocalTime(9, 0), result!!.toLocalDateTime(london).time)
    }
}
