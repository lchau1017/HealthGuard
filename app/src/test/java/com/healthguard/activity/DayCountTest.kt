@file:OptIn(ExperimentalTime::class)

package com.healthguard.activity

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DayCountTest {

    private val utc = TimeZone.UTC

    @Test
    fun `empty events produce no day counts`() {
        assertTrue(dayCounts(emptyList(), utc).isEmpty())
    }

    @Test
    fun `events bucket per local day and sort ascending`() {
        val events = listOf(
            Instant.parse("2024-07-03T10:00:00Z"),
            Instant.parse("2024-07-01T08:00:00Z"),
            Instant.parse("2024-07-03T22:00:00Z"),
            Instant.parse("2024-07-03T06:00:00Z"),
        )
        assertEquals(
            listOf(
                DayCount(LocalDate(2024, 7, 1), 1),
                DayCount(LocalDate(2024, 7, 3), 3),
            ),
            dayCounts(events, utc),
        )
    }

    @Test
    fun `day boundaries follow the zone not UTC`() {
        // 23:30 UTC on the 1st is already the 2nd in UTC+2.
        val events = listOf(
            Instant.parse("2024-07-01T23:30:00Z"),
            Instant.parse("2024-07-02T08:00:00Z"),
        )
        assertEquals(
            listOf(DayCount(LocalDate(2024, 7, 2), 2)),
            dayCounts(events, TimeZone.of("UTC+2")),
        )
        assertEquals(
            listOf(
                DayCount(LocalDate(2024, 7, 1), 1),
                DayCount(LocalDate(2024, 7, 2), 1),
            ),
            dayCounts(events, utc),
        )
    }
}
