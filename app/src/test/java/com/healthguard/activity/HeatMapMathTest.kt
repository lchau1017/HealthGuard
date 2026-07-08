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
    fun `mondayOf returns the week start`() {
        // 2024-07-03 is a Wednesday.
        assertEquals(LocalDate(2024, 7, 1), mondayOf(LocalDate(2024, 7, 3)))
        // A Monday maps to itself.
        assertEquals(LocalDate(2024, 7, 1), mondayOf(LocalDate(2024, 7, 1)))
        // A Sunday maps back six days.
        assertEquals(LocalDate(2024, 7, 1), mondayOf(LocalDate(2024, 7, 7)))
    }
}
