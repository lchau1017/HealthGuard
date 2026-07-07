@file:OptIn(ExperimentalTime::class)

package com.medguard.format

import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class CountdownTextTest {

    private val now = Instant.parse("2024-07-03T10:00:00Z")
    private val zone = TimeZone.UTC

    private fun at(offset: kotlin.time.Duration) = countdownText(now + offset, now, zone)

    @Test
    fun `null next dose renders empty`() {
        assertEquals("", countdownText(null, now, zone))
    }

    @Test
    fun `under a minute either side is due now`() {
        assertEquals("due now", at(0.seconds))
        assertEquals("due now", at(59.seconds))
        assertEquals("due now", at((-59).seconds))
    }

    @Test
    fun `sixty seconds is in 1m`() {
        assertEquals("in 1m", at(60.seconds))
    }

    @Test
    fun `minutes only`() {
        assertEquals("in 45m", at(45.minutes))
    }

    @Test
    fun `hours pad the minute part`() {
        assertEquals("in 2h 05m", at(2.hours + 5.minutes))
        assertEquals("in 1h 00m", at(1.hours))
    }

    @Test
    fun `multi day drops minutes`() {
        assertEquals("in 2d 3h", at(2.days + 3.hours + 12.minutes))
        assertEquals("in 1d 0h", at(1.days + 5.minutes))
    }

    @Test
    fun `overdue mirrors the formatting`() {
        assertEquals("overdue by 1m", at((-60).seconds))
        assertEquals("overdue by 20m", at((-20).minutes))
        assertEquals("overdue by 3h 15m", at(-(3.hours + 15.minutes)))
    }

    private fun atSeconds(offset: kotlin.time.Duration) =
        countdownTextSeconds(now + offset, now, zone)

    @Test
    fun `seconds variant renders empty for null and due now within a second`() {
        assertEquals("", countdownTextSeconds(null, now, zone))
        assertEquals("due now", atSeconds(0.seconds))
    }

    @Test
    fun `seconds variant counts single seconds`() {
        assertEquals("in 59s", atSeconds(59.seconds))
        assertEquals("in 1s", atSeconds(1.seconds))
    }

    @Test
    fun `seconds variant pads seconds when minutes are present`() {
        assertEquals("in 1m 00s", atSeconds(60.seconds))
        assertEquals("in 45m 10s", atSeconds(45.minutes + 10.seconds))
    }

    @Test
    fun `seconds variant pads minutes and seconds when hours are present`() {
        assertEquals("in 1h 04m 32s", atSeconds(1.hours + 4.minutes + 32.seconds))
    }

    @Test
    fun `seconds variant drops detail on multi day spans`() {
        assertEquals("in 2d 3h", atSeconds(2.days + 3.hours + 12.minutes))
    }

    @Test
    fun `seconds variant mirrors formatting when overdue`() {
        assertEquals("overdue by 20m 15s", atSeconds(-(20.minutes + 15.seconds)))
        assertEquals("overdue by 5s", atSeconds((-5).seconds))
    }

    @Test
    fun `last taken today is time only`() {
        assertEquals(
            "08:30",
            lastTakenText(Instant.parse("2024-07-03T08:30:00Z"), now, zone),
        )
    }

    @Test
    fun `last taken yesterday is prefixed`() {
        assertEquals(
            "yesterday 21:15",
            lastTakenText(Instant.parse("2024-07-02T21:15:00Z"), now, zone),
        )
    }

    @Test
    fun `last taken earlier shows the date`() {
        assertEquals(
            "12 Jun 09:00",
            lastTakenText(Instant.parse("2024-06-12T09:00:00Z"), now, zone),
        )
    }

    @Test
    fun `last taken respects the zone for day boundaries`() {
        // 23:30Z on July 2nd is already July 3rd ("today") in UTC+8.
        assertEquals(
            "07:30",
            lastTakenText(Instant.parse("2024-07-02T23:30:00Z"), now, TimeZone.of("UTC+8")),
        )
    }

    @Test
    fun `dose time renders zero padded local HH mm`() {
        assertEquals("14:00", doseTimeText(Instant.parse("2024-07-03T14:00:00Z"), zone))
        assertEquals("08:05", doseTimeText(Instant.parse("2024-07-03T08:05:00Z"), zone))
        assertEquals(
            "22:00",
            doseTimeText(Instant.parse("2024-07-03T14:00:00Z"), TimeZone.of("UTC+8")),
        )
    }
}
