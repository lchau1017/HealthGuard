package com.healthguard.activity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDate

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
}
