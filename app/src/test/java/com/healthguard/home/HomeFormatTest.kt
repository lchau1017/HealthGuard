@file:OptIn(ExperimentalTime::class)

package com.healthguard.home

import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeFormatTest {

    private val zone = TimeZone.UTC

    /** Wednesday 2026-07-08, 10:00 UTC. */
    private val now = Instant.parse("2026-07-08T10:00:00Z")

    private fun status(
        nextDoseAt: Instant?,
        lastTaken: Instant? = null,
        isDue: Boolean = false,
        at: Instant = now,
    ) = doseRowStatus(nextDoseAt, lastTaken, at, zone, isDue)

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
}
