package com.healthguard.activity

import com.healthguard.common.format.hourLabel
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `mondayOf returns the week start`() {
        // 2024-07-03 is a Wednesday.
        assertEquals(LocalDate(2024, 7, 1), mondayOf(LocalDate(2024, 7, 3)))
        // A Monday maps to itself.
        assertEquals(LocalDate(2024, 7, 1), mondayOf(LocalDate(2024, 7, 1)))
        // A Sunday maps back six days.
        assertEquals(LocalDate(2024, 7, 1), mondayOf(LocalDate(2024, 7, 7)))
    }

    @Test
    fun `a thirty day window spans five week columns`() {
        // 2024-06-04 (Tue) .. 2024-07-03 (Wed): Mondays 6/3 through 7/1.
        val weeks = weekStarts(LocalDate(2024, 6, 4), LocalDate(2024, 7, 3))
        assertEquals(
            listOf(
                LocalDate(2024, 6, 3),
                LocalDate(2024, 6, 10),
                LocalDate(2024, 6, 17),
                LocalDate(2024, 6, 24),
                LocalDate(2024, 7, 1),
            ),
            weeks,
        )
    }

    @Test
    fun `a twelve month window spans fifty three week columns`() {
        val weeks = weekStarts(LocalDate(2023, 7, 3), LocalDate(2024, 7, 3))
        assertEquals(53, weeks.size)
        assertEquals(LocalDate(2023, 7, 3), weeks.first())
        assertEquals(LocalDate(2024, 7, 1), weeks.last())
    }

    @Test
    fun `a single week window is one column`() {
        assertEquals(
            listOf(LocalDate(2024, 7, 1)),
            weekStarts(LocalDate(2024, 7, 1), LocalDate(2024, 7, 3)),
        )
    }

    @Test
    fun `month labels mark the columns where the month changes`() {
        val labels = monthLabels(
            listOf(
                LocalDate(2024, 6, 10),
                LocalDate(2024, 6, 17),
                LocalDate(2024, 6, 24),
                LocalDate(2024, 7, 1),
                LocalDate(2024, 7, 8),
                LocalDate(2024, 7, 15),
            ),
        )
        assertEquals(listOf("Jun", null, null, "Jul", null, null), labels)
    }

    @Test
    fun `the first column is unlabeled when its month ends within two columns`() {
        val labels = monthLabels(
            listOf(
                LocalDate(2024, 6, 24),
                LocalDate(2024, 7, 1),
                LocalDate(2024, 7, 8),
                LocalDate(2024, 7, 15),
            ),
        )
        assertEquals(listOf(null, "Jul", null, null), labels)
    }

    @Test
    fun `a month entering within the last two columns stays unlabeled`() {
        // The label would clip at the grid's right edge mid-word.
        val labels = monthLabels(
            listOf(
                LocalDate(2024, 6, 10),
                LocalDate(2024, 6, 17),
                LocalDate(2024, 6, 24),
                LocalDate(2024, 7, 1),
            ),
        )
        assertEquals(listOf("Jun", null, null, null), labels)
    }

    @Test
    fun `a full year of columns carries twelve month labels`() {
        val labels = monthLabels(weekStarts(LocalDate(2023, 7, 3), LocalDate(2024, 7, 3)))
        assertEquals(53, labels.size)
        // Every month once; the year-old leading July included, the brand-new
        // July at the right edge suppressed for room.
        assertEquals(12, labels.count { it != null })
        assertEquals("Jul", labels.first())
        assertNull(labels.last())
    }

    @Test
    fun `weekdayInitial is the single letter day mark`() {
        assertEquals("M", weekdayInitial(LocalDate(2024, 7, 1)))
        assertEquals("W", weekdayInitial(LocalDate(2024, 7, 3)))
        assertEquals("S", weekdayInitial(LocalDate(2024, 7, 6)))
        assertEquals("S", weekdayInitial(LocalDate(2024, 7, 7)))
    }
}
