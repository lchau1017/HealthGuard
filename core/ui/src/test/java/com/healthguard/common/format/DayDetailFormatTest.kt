package com.healthguard.common.format

import com.healthguard.activity.DayMedicineLine
import kotlinx.datetime.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

class DayDetailFormatTest {

    private fun line(
        takenTimes: List<LocalTime> = emptyList(),
        skipped: Int = 0,
        missed: Int = 0,
        notRecorded: Int = 0,
    ) = DayMedicineLine(
        medicationId = "med-a",
        name = "Cetirizine 10 mg",
        takenTimes = takenTimes,
        skipped = skipped,
        missed = missed,
        notRecorded = notRecorded,
    )

    @Test
    fun `line title spells the take count and times`() {
        assertEquals(
            "Cetirizine 10 mg — 2 taken (9:04 AM · 9:12 PM)",
            dayLineTitle(line(takenTimes = listOf(LocalTime(9, 4), LocalTime(21, 12)))),
        )
        assertEquals(
            "Cetirizine 10 mg — 1 taken (9:04 AM)",
            dayLineTitle(line(takenTimes = listOf(LocalTime(9, 4)))),
        )
        assertEquals("Cetirizine 10 mg — 0 taken", dayLineTitle(line(skipped = 1)))
    }

    @Test
    fun `annotations list the non-taken outcomes that apply`() {
        assertEquals(
            listOf("1 skipped", "2 missed", "1 not recorded"),
            dayLineAnnotations(line(skipped = 1, missed = 2, notRecorded = 1)),
        )
        assertEquals(
            emptyList<String>(),
            dayLineAnnotations(line(takenTimes = listOf(LocalTime(9, 4)))),
        )
    }

    @Test
    fun `the aggregate line counts expected doses nothing answered`() {
        assertEquals("2 expected but not recorded", expectedNotRecordedText(2))
        assertEquals("1 expected but not recorded", expectedNotRecordedText(1))
    }
}
