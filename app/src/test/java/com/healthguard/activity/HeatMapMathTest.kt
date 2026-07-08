package com.healthguard.activity

import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class HeatMapMathTest {

    @Test
    fun `heatLevel buckets counts at 1 2 3 and 4 plus`() {
        assertEquals(0, heatLevel(0))
        assertEquals(1, heatLevel(1))
        assertEquals(2, heatLevel(2))
        assertEquals(3, heatLevel(3))
        assertEquals(4, heatLevel(4))
        assertEquals(4, heatLevel(11))
    }

    @Test
    fun `hourLabel covers midnight noon and both halves`() {
        assertEquals("12 AM", hourLabel(0))
        assertEquals("9 AM", hourLabel(9))
        assertEquals("11 AM", hourLabel(11))
        assertEquals("12 PM", hourLabel(12))
        assertEquals("2 PM", hourLabel(14))
        assertEquals("11 PM", hourLabel(23))
    }

    @Test
    fun `dayLabel is compact day month text`() {
        assertEquals("Wed 3 Jul", dayLabel(LocalDate(2024, 7, 3)))
        assertEquals("Sun 29 Dec", dayLabel(LocalDate(2024, 12, 29)))
    }

    @Test
    fun `mondayOf returns the week start`() {
        // 2024-07-03 is a Wednesday.
        assertEquals(LocalDate(2024, 7, 1), mondayOf(LocalDate(2024, 7, 3)))
        // A Monday maps to itself.
        assertEquals(LocalDate(2024, 7, 1), mondayOf(LocalDate(2024, 7, 1)))
        // A Sunday maps back six days.
        assertEquals(LocalDate(2024, 7, 1), mondayOf(LocalDate(2024, 7, 7)))
    }
}
