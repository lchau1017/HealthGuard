@file:OptIn(ExperimentalTime::class)

package com.healthguard.format

import com.healthguard.shared.data.DoseStatus
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class DoseStatusTextTest {

    private val zone = TimeZone.UTC

    /** Wednesday 2026-07-08, 10:00 UTC. */
    private val now = Instant.parse("2026-07-08T10:00:00Z")

    private fun status(
        nextDoseAt: Instant?,
        lastTaken: Instant? = null,
        isDue: Boolean = false,
        at: Instant = now,
    ) = doseRowStatus(nextDoseAt, lastTaken, at, zone, isDue)

    // --- timeLabel ---

    @Test
    fun `timeLabel renders 12 hour times`() {
        assertEquals("9:00 PM", timeLabel(LocalTime(21, 0)))
        assertEquals("8:02 AM", timeLabel(LocalTime(8, 2)))
        assertEquals("12:00 PM", timeLabel(LocalTime(12, 0)))
        assertEquals("12:05 AM", timeLabel(LocalTime(0, 5)))
        assertEquals("11:59 PM", timeLabel(LocalTime(23, 59)))
    }

    // --- doseRowStatus branches ---

    @Test
    fun `due item is DUE regardless of other timing`() {
        assertEquals(DoseRowStatus.Due, status(now - 5.minutes, isDue = true))
        assertEquals(DoseRowStatus.Due, status(now + 8.hours, lastTaken = now - 1.hours, isDue = true))
    }

    @Test
    fun `past next dose is DUE even without the flag`() {
        assertEquals(DoseRowStatus.Due, status(now - 5.minutes))
    }

    @Test
    fun `no more doses today with a take today is TakenForToday`() {
        // Next dose tomorrow morning, last take earlier today.
        val result = status(now + 23.hours, lastTaken = now - 1.hours)
        assertEquals(DoseRowStatus.TakenForToday, result)
    }

    @Test
    fun `no next dose at all with a take today is TakenForToday`() {
        assertEquals(DoseRowStatus.TakenForToday, status(null, lastTaken = now - 2.hours))
    }

    @Test
    fun `no next dose and no take today is None`() {
        assertEquals(DoseRowStatus.None, status(null))
        assertEquals(DoseRowStatus.None, status(null, lastTaken = now - 30.hours))
    }

    @Test
    fun `next dose today within six hours counts down`() {
        val result = status(now + 3.hours + 20.minutes)
        assertEquals(DoseRowStatus.Next("Next in 3h 20m"), result)
    }

    @Test
    fun `next dose under an hour drops the hours part`() {
        assertEquals(DoseRowStatus.Next("Next in 45m"), status(now + 45.minutes))
    }

    @Test
    fun `next dose today beyond six hours shows the slot time`() {
        // 21:00 today is 11 hours out.
        assertEquals(DoseRowStatus.Next("Next at 9:00 PM"), status(now + 11.hours))
    }

    @Test
    fun `six hour boundary is inclusive for the countdown`() {
        assertEquals(DoseRowStatus.Next("Next in 6h 0m"), status(now + 6.hours))
        assertEquals(
            DoseRowStatus.Next("Next at 4:01 PM"),
            status(now + 6.hours + 1.minutes),
        )
    }

    @Test
    fun `next dose tomorrow says tomorrow`() {
        // Tomorrow 09:00.
        assertEquals(
            DoseRowStatus.Next("Next at 9:00 AM tomorrow"),
            status(now + 23.hours),
        )
    }

    @Test
    fun `next dose beyond tomorrow names the weekday`() {
        // Friday 2026-07-10 08:00.
        assertEquals(
            DoseRowStatus.Next("Next at 8:00 AM, Fri"),
            status(Instant.parse("2026-07-10T08:00:00Z")),
        )
        // A month out still names the weekday (Saturday 2026-08-08).
        assertEquals(
            DoseRowStatus.Next("Next at 9:00 AM, Sat"),
            status(now + 30.days + 23.hours),
        )
    }

    @Test
    fun `day boundary just before midnight is still today`() {
        // now 23:00; next 23:59 today -> within 6h countdown.
        val lateNow = Instant.parse("2026-07-08T23:00:00Z")
        assertEquals(
            DoseRowStatus.Next("Next in 59m"),
            status(Instant.parse("2026-07-08T23:59:00Z"), at = lateNow),
        )
    }

    @Test
    fun `day boundary just after midnight is tomorrow even when close`() {
        // now 23:59; next 00:01 tomorrow -> tomorrow wording despite 2 minutes.
        val lateNow = Instant.parse("2026-07-08T23:59:00Z")
        assertEquals(
            DoseRowStatus.Next("Next at 12:01 AM tomorrow"),
            status(Instant.parse("2026-07-09T00:01:00Z"), at = lateNow),
        )
    }

    // --- takeByText ---

    @Test
    fun `takeBy shows the slot time while not overdue`() {
        assertEquals("Take by 10:00 AM", takeByText(now, now, zone))
        // 30 seconds past is still inside the due-now minute.
        assertEquals("Take by 9:00 AM", takeByText(now - 1.hours, now - 1.hours + 30.seconds, zone))
    }

    @Test
    fun `takeBy appends the overdue span`() {
        assertEquals("Take by 9:48 AM · overdue 12m", takeByText(now - 12.minutes, now, zone))
        assertEquals(
            "Take by 8:00 AM · overdue 2h 0m",
            takeByText(Instant.parse("2026-07-08T08:00:00Z"), now, zone),
        )
    }

    // --- todayLabel ---

    @Test
    fun `todayLabel renders weekday day and month`() {
        assertEquals("Wednesday, 8 July", todayLabel(LocalDate(2026, 7, 8)))
        assertEquals("Sunday, 1 February", todayLabel(LocalDate(2026, 2, 1)))
    }

    // --- mediumDateLabel ---

    @Test
    fun `mediumDateLabel renders day short month and year`() {
        assertEquals("27 Jun 2026", mediumDateLabel(LocalDate(2026, 6, 27)))
    }

    // --- dayTimeLabel / lastTakenLabel ---

    @Test
    fun `dayTimeLabel uses Today Yesterday then the dated form`() {
        assertEquals("Today, 8:02 AM", dayTimeLabel(Instant.parse("2026-07-08T08:02:00Z"), now, zone))
        assertEquals("Yesterday, 9:00 PM", dayTimeLabel(Instant.parse("2026-07-07T21:00:00Z"), now, zone))
        assertEquals("Mon 6 Jul, 8:00 AM", dayTimeLabel(Instant.parse("2026-07-06T08:00:00Z"), now, zone))
    }

    @Test
    fun `lastTakenLabel lowercases the day word`() {
        assertEquals(
            "Last taken today, 8:02 AM",
            lastTakenLabel(Instant.parse("2026-07-08T08:02:00Z"), now, zone),
        )
        assertEquals(
            "Last taken yesterday, 9:00 PM",
            lastTakenLabel(Instant.parse("2026-07-07T21:00:00Z"), now, zone),
        )
        assertEquals(
            "Last taken Mon 6 Jul, 8:00 AM",
            lastTakenLabel(Instant.parse("2026-07-06T08:00:00Z"), now, zone),
        )
    }

    // --- doseAnnotation ---

    private val planned = Instant.parse("2026-07-08T08:00:00Z")

    @Test
    fun `taken within ten minutes of planned is on time`() {
        assertEquals("Taken · on time", doseAnnotation(DoseStatus.TAKEN, planned, planned + 4.minutes))
        assertEquals("Taken · on time", doseAnnotation(DoseStatus.TAKEN, planned, planned + 10.minutes))
        assertEquals("Taken · on time", doseAnnotation(DoseStatus.TAKEN, planned, planned - 10.minutes))
    }

    @Test
    fun `taken late or early names the minutes`() {
        assertEquals("Taken · 11 min late", doseAnnotation(DoseStatus.TAKEN, planned, planned + 11.minutes))
        assertEquals("Taken · 125 min late", doseAnnotation(DoseStatus.TAKEN, planned, planned + 125.minutes))
        assertEquals("Taken · 15 min early", doseAnnotation(DoseStatus.TAKEN, planned, planned - 15.minutes))
    }

    @Test
    fun `taken without a takenAt is plain taken`() {
        assertEquals("Taken", doseAnnotation(DoseStatus.TAKEN, planned, null))
    }

    @Test
    fun `missed skipped and pending are plain labels`() {
        assertEquals("Missed", doseAnnotation(DoseStatus.MISSED, planned, null))
        assertEquals("Skipped", doseAnnotation(DoseStatus.SKIPPED, planned, null))
        assertEquals("Pending", doseAnnotation(DoseStatus.PENDING, planned, null))
    }
}
