@file:OptIn(ExperimentalTime::class)

package com.healthguard.activity

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActivityStatsTest {

    private val utc = TimeZone.UTC

    /** 2024-07-03T10:00:00Z — "today" for every test. */
    private val now = Instant.parse("2024-07-03T10:00:00Z")

    private fun event(iso: String, name: String = "Ibuprofen") =
        ActivityEvent(itemName = name, at = Instant.parse(iso))

    private fun stats(vararg events: ActivityEvent) =
        activityStats(events.toList(), now, utc)

    @Test
    fun `empty events yield zeroes and nulls`() {
        val stats = stats()
        assertEquals(ActivityStats(0, 0, 0, 0, null, null), stats)
    }

    @Test
    fun `single event today is a one day streak`() {
        val stats = stats(event("2024-07-03T09:00:00Z"))
        assertEquals(1, stats.totalEvents)
        assertEquals(1, stats.activeDays)
        assertEquals(1, stats.currentStreakDays)
        assertEquals(1, stats.longestStreakDays)
        assertEquals(9, stats.peakHour)
        assertEquals(ActivityStats.TopItem("Ibuprofen", 1), stats.topItem)
    }

    @Test
    fun `streak ending yesterday still counts as current`() {
        val stats = stats(
            event("2024-07-01T08:00:00Z"),
            event("2024-07-02T08:00:00Z"),
        )
        assertEquals(2, stats.currentStreakDays)
        assertEquals(2, stats.longestStreakDays)
    }

    @Test
    fun `streak that stopped two days ago is not current`() {
        val stats = stats(
            event("2024-06-29T08:00:00Z"),
            event("2024-06-30T08:00:00Z"),
            event("2024-07-01T08:00:00Z"),
        )
        assertEquals(0, stats.currentStreakDays)
        assertEquals(3, stats.longestStreakDays)
    }

    @Test
    fun `today extends a streak that runs through yesterday`() {
        val stats = stats(
            event("2024-07-01T08:00:00Z"),
            event("2024-07-02T08:00:00Z"),
            event("2024-07-03T08:00:00Z"),
            // A second take today must not double-count the day.
            event("2024-07-03T09:00:00Z"),
        )
        assertEquals(3, stats.currentStreakDays)
        assertEquals(3, stats.activeDays)
        assertEquals(4, stats.totalEvents)
    }

    @Test
    fun `longest streak spans a month boundary`() {
        val stats = stats(
            event("2024-06-29T08:00:00Z"),
            event("2024-06-30T08:00:00Z"),
            event("2024-07-01T08:00:00Z"),
            event("2024-07-02T08:00:00Z"),
        )
        assertEquals(4, stats.longestStreakDays)
        assertEquals(4, stats.currentStreakDays)
    }

    @Test
    fun `longest streak beats a shorter current one`() {
        val stats = stats(
            event("2024-06-20T08:00:00Z"),
            event("2024-06-21T08:00:00Z"),
            event("2024-06-22T08:00:00Z"),
            event("2024-07-03T08:00:00Z"),
        )
        assertEquals(1, stats.currentStreakDays)
        assertEquals(3, stats.longestStreakDays)
    }

    @Test
    fun `peak hour is the local-time mode`() {
        // 23:30 UTC is 01:30 in UTC+2 — the peak must be a *local* hour.
        val stats = activityStats(
            listOf(
                event("2024-07-01T23:30:00Z"),
                event("2024-07-02T23:40:00Z"),
                event("2024-07-02T08:00:00Z"),
            ),
            now,
            TimeZone.of("UTC+2"),
        )
        assertEquals(1, stats.peakHour)
    }

    @Test
    fun `peak hour tie picks the earliest hour`() {
        val stats = stats(
            event("2024-07-03T14:00:00Z"),
            event("2024-07-02T14:10:00Z"),
            event("2024-07-03T09:00:00Z"),
            event("2024-07-02T09:30:00Z"),
        )
        assertEquals(9, stats.peakHour)
    }

    @Test
    fun `topItem is the most frequent name with its count`() {
        val stats = stats(
            event("2024-07-01T08:00:00Z", name = "Cetirizine"),
            event("2024-07-02T08:00:00Z", name = "Ibuprofen"),
            event("2024-07-02T14:00:00Z", name = "Cetirizine"),
        )
        assertEquals(ActivityStats.TopItem("Cetirizine", 2), stats.topItem)
    }
}
