package com.healthguard.activity

import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityWindowTest {

    /** 2024-07-03 is a Wednesday. */
    private val today = LocalDate(2024, 7, 3)

    @Test
    fun `seven day window starts six days back`() {
        assertEquals(
            LocalDate(2024, 6, 27),
            activityWindowStart(ActivityFilter.DAYS_7, today),
        )
    }

    @Test
    fun `thirty day window starts twenty nine days back`() {
        assertEquals(
            LocalDate(2024, 6, 4),
            activityWindowStart(ActivityFilter.DAYS_30, today),
        )
    }

    @Test
    fun `twelve month window starts a calendar year back`() {
        assertEquals(
            LocalDate(2023, 7, 3),
            activityWindowStart(ActivityFilter.MONTHS_12, today),
        )
    }

    @Test
    fun `each window names its record heading`() {
        assertEquals("Last 7 days", windowHeading(ActivityFilter.DAYS_7))
        assertEquals("Last 30 days", windowHeading(ActivityFilter.DAYS_30))
        assertEquals("Last 12 months", windowHeading(ActivityFilter.MONTHS_12))
    }

    @Test
    fun `each window names its filter chip`() {
        assertEquals("7 days", windowChipLabel(ActivityFilter.DAYS_7))
        assertEquals("30 days", windowChipLabel(ActivityFilter.DAYS_30))
        assertEquals("12 months", windowChipLabel(ActivityFilter.MONTHS_12))
    }
}
