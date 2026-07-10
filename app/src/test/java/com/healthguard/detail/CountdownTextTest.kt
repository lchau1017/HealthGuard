@file:OptIn(ExperimentalTime::class)

package com.healthguard.detail

import com.healthguard.detail.format.countdownTextSeconds
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

    private fun atSeconds(offset: kotlin.time.Duration) =
        countdownTextSeconds(now + offset, now, zone)

    @Test
    fun `renders empty for null and due now within a second`() {
        assertEquals("", countdownTextSeconds(null, now, zone))
        assertEquals("due now", atSeconds(0.seconds))
    }

    @Test
    fun `counts single seconds`() {
        assertEquals("in 59s", atSeconds(59.seconds))
        assertEquals("in 1s", atSeconds(1.seconds))
    }

    @Test
    fun `pads seconds when minutes are present`() {
        assertEquals("in 1m 00s", atSeconds(60.seconds))
        assertEquals("in 45m 10s", atSeconds(45.minutes + 10.seconds))
    }

    @Test
    fun `pads minutes and seconds when hours are present`() {
        assertEquals("in 1h 04m 32s", atSeconds(1.hours + 4.minutes + 32.seconds))
    }

    @Test
    fun `drops detail on multi day spans`() {
        assertEquals("in 2d 3h", atSeconds(2.days + 3.hours + 12.minutes))
    }

    @Test
    fun `mirrors formatting when overdue`() {
        assertEquals("overdue by 20m 15s", atSeconds(-(20.minutes + 15.seconds)))
        assertEquals("overdue by 5s", atSeconds((-5).seconds))
    }
}
