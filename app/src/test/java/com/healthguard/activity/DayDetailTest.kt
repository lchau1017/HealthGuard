@file:OptIn(ExperimentalTime::class)

package com.healthguard.activity

import com.healthguard.shared.data.DoseLogWithMedication
import com.healthguard.shared.data.DoseStatus
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class DayDetailTest {

    private val zone = TimeZone.UTC
    private val date = LocalDate(2024, 7, 2)

    private fun log(
        medicationId: String = "med-a",
        drugName: String = "Cetirizine",
        dosage: String? = "10 mg",
        plannedAt: String,
        takenAt: String? = plannedAt,
        status: DoseStatus = DoseStatus.TAKEN,
    ) = DoseLogWithMedication(
        medicationId = medicationId,
        drugName = drugName,
        dosage = dosage,
        plannedAt = Instant.parse(plannedAt),
        takenAt = takenAt?.let(Instant::parse),
        status = status,
    )

    @Test
    fun `groups a day's logs per medicine with taken times ascending`() {
        val detail = dayDetail(
            date = date,
            logs = listOf(
                // Evening take listed first: the line must sort by time.
                log(plannedAt = "2024-07-02T21:00:00Z", takenAt = "2024-07-02T21:12:00Z"),
                log(plannedAt = "2024-07-02T09:00:00Z", takenAt = "2024-07-02T09:04:00Z"),
                log(
                    medicationId = "med-b",
                    drugName = "Vitamin D3",
                    dosage = "1000 IU",
                    plannedAt = "2024-07-02T09:00:00Z",
                    takenAt = "2024-07-02T09:30:00Z",
                ),
            ),
            expectedByMedication = emptyMap(),
            zone = zone,
        )

        assertEquals(
            listOf(
                DayMedicineLine(
                    medicationId = "med-a",
                    name = "Cetirizine 10 mg",
                    takenTimes = listOf(LocalTime(9, 4), LocalTime(21, 12)),
                    skipped = 0,
                    missed = 0,
                    notRecorded = 0,
                ),
                DayMedicineLine(
                    medicationId = "med-b",
                    name = "Vitamin D3 1000 IU",
                    takenTimes = listOf(LocalTime(9, 30)),
                    skipped = 0,
                    missed = 0,
                    notRecorded = 0,
                ),
            ),
            detail.lines,
        )
        assertEquals(0, detail.expectedNotRecorded)
    }

    @Test
    fun `counts skips misses and unanswered expected slots per medicine`() {
        val detail = dayDetail(
            date = date,
            logs = listOf(
                log(plannedAt = "2024-07-02T09:00:00Z", takenAt = "2024-07-02T09:04:00Z"),
                log(
                    plannedAt = "2024-07-02T15:00:00Z",
                    takenAt = null,
                    status = DoseStatus.SKIPPED,
                ),
                log(
                    plannedAt = "2024-07-02T21:00:00Z",
                    takenAt = null,
                    status = DoseStatus.MISSED,
                ),
            ),
            // Four slots owed; 09:00, 15:00 and 21:00 are answered by logs,
            // the 12:00 slot by nothing -> 1 not recorded.
            expectedByMedication = mapOf(
                "med-a" to listOf(
                    Instant.parse("2024-07-02T09:00:00Z"),
                    Instant.parse("2024-07-02T12:00:00Z"),
                    Instant.parse("2024-07-02T15:00:00Z"),
                    Instant.parse("2024-07-02T21:00:00Z"),
                ),
            ),
            zone = zone,
        )

        val line = detail.lines.single()
        assertEquals(listOf(LocalTime(9, 4)), line.takenTimes)
        assertEquals(1, line.skipped)
        assertEquals(1, line.missed)
        assertEquals(1, line.notRecorded)
    }

    @Test
    fun `a medicine with no logs that day feeds the expected-but-not-recorded total`() {
        val detail = dayDetail(
            date = date,
            logs = listOf(log(plannedAt = "2024-07-02T09:00:00Z")),
            expectedByMedication = mapOf(
                "med-a" to listOf(Instant.parse("2024-07-02T09:00:00Z")),
                "med-c" to listOf(
                    Instant.parse("2024-07-02T09:00:00Z"),
                    Instant.parse("2024-07-02T21:00:00Z"),
                ),
            ),
            zone = zone,
        )

        // med-c gets no line — nothing was recorded for it — but its two
        // silent slots surface in the aggregate.
        assertEquals(listOf("med-a"), detail.lines.map { it.medicationId })
        assertEquals(2, detail.expectedNotRecorded)
    }

    @Test
    fun `an empty day keeps only the expected-but-not-recorded total`() {
        val detail = dayDetail(
            date = date,
            logs = emptyList(),
            expectedByMedication = mapOf(
                "med-a" to listOf(
                    Instant.parse("2024-07-02T09:00:00Z"),
                    Instant.parse("2024-07-02T21:00:00Z"),
                ),
            ),
            zone = zone,
        )

        assertEquals(emptyList<DayMedicineLine>(), detail.lines)
        assertEquals(2, detail.expectedNotRecorded)
    }

    @Test
    fun `a dosage-less medicine lines up under its bare name`() {
        val detail = dayDetail(
            date = date,
            logs = listOf(log(drugName = "Ibuprofen", dosage = null, plannedAt = "2024-07-02T08:00:00Z")),
            expectedByMedication = emptyMap(),
            zone = zone,
        )
        assertEquals("Ibuprofen", detail.lines.single().name)
    }

    // --- line texts ---

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
