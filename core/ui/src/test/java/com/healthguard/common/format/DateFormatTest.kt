package com.healthguard.common.format

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

class DateFormatTest {

    // --- timeLabel ---

    @Test
    fun `timeLabel renders 12 hour times`() {
        assertEquals("9:00 PM", timeLabel(LocalTime(21, 0)))
        assertEquals("8:02 AM", timeLabel(LocalTime(8, 2)))
        assertEquals("12:00 PM", timeLabel(LocalTime(12, 0)))
        assertEquals("12:05 AM", timeLabel(LocalTime(0, 5)))
        assertEquals("11:59 PM", timeLabel(LocalTime(23, 59)))
    }

    // --- todayLabel ---

    @Test
    fun `todayLabel renders weekday day and month`() {
        assertEquals("Wednesday, 8 July", todayLabel(LocalDate(2026, 7, 8)))
        assertEquals("Sunday, 1 February", todayLabel(LocalDate(2026, 2, 1)))
    }
}
