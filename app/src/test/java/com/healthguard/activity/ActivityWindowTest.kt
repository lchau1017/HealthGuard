package com.healthguard.activity

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The window display labels ([windowHeading]/[windowChipLabel]) stay in `:app`;
 * the pure window-start math moved to `:core:domain` (see its ActivityWindowTest).
 */
class ActivityWindowTest {

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
