package com.healthguard.data

import com.healthguard.activity.AdherenceResult
import com.healthguard.chat.ChatContext
import com.healthguard.domain.extraction.Frequency
import com.healthguard.domain.model.DoseStatus
import com.healthguard.home.MedicationPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

class ChatContextRendererTest {

    private val renderer = ChatContextRenderer(TimeZone.of("UTC"))

    @Test
    fun `renders medications, weeks and grouped events`() {
        val context = ChatContext(
            generatedAt = Instant.parse("2025-06-18T12:00:00Z"),
            medications = listOf(
                ChatContext.MedicationFacts(
                    name = "Aspirin",
                    dosage = "100 mg",
                    frequency = Frequency.TimesPerDay(1),
                    phase = MedicationPhase.TAKING,
                    adherence = AdherenceResult(taken = 2, expected = 3, skipped = 0),
                    nextDoseAt = Instant.parse("2025-06-19T09:00:00Z"),
                ),
            ),
            weeks = listOf(
                ChatContext.WeekFacts(LocalDate(2025, 6, 16), expected = 3, taken = 1, skipped = 0),
            ),
            events = listOf(
                ChatContext.DoseEventFact(LocalDate(2025, 6, 16), "Aspirin", DoseStatus.TAKEN),
                ChatContext.DoseEventFact(LocalDate(2025, 6, 17), "Aspirin", DoseStatus.TAKEN),
                ChatContext.DoseEventFact(LocalDate(2025, 6, 17), "Aspirin", DoseStatus.SKIPPED),
            ),
        )

        assertEquals(
            """
            Snapshot: 2025-06-18 12:00 (Wednesday)

            Medications:
            - Aspirin (100 mg) — 1x per day — currently taking — 67% adherence over last 30 days (2 taken / 3 expected, 0 skipped) — next dose 2025-06-19 09:00 (Thursday)

            Weekly totals (weeks start Monday):
            - 2025-06-16: 3 expected, 1 taken, 2 missed, 0 skipped

            Recorded doses:
            - 2025-06-16 (Monday): Aspirin taken
            - 2025-06-17 (Tuesday): Aspirin taken, Aspirin skipped
            """.trimIndent(),
            renderer.render(context),
        )
    }

    @Test
    fun `as-needed and dormant medications render without a percent`() {
        val context = ChatContext(
            generatedAt = Instant.parse("2025-06-18T12:00:00Z"),
            medications = listOf(
                ChatContext.MedicationFacts(
                    name = "Ibuprofen",
                    dosage = null,
                    frequency = Frequency.EveryHours(6),
                    phase = MedicationPhase.TAKING,
                    adherence = AdherenceResult(taken = 4, expected = 0, skipped = 0),
                    nextDoseAt = null,
                ),
                ChatContext.MedicationFacts(
                    name = "Vitamin D",
                    dosage = null,
                    frequency = null,
                    phase = MedicationPhase.NOT_STARTED,
                    adherence = AdherenceResult(taken = 0, expected = 0, skipped = 0),
                    nextDoseAt = null,
                ),
            ),
            weeks = emptyList(),
            events = emptyList(),
        )

        val text = renderer.render(context)

        assertTrue("- Ibuprofen — as needed (max every 6h) — currently taking — 4 taken over last 30 days (no scheduled expectation)" in text)
        assertTrue("- Vitamin D — no schedule — not started — 0 taken over last 30 days (no scheduled expectation)" in text)
        assertTrue("%" !in text)
    }

    @Test
    fun `no medications renders an explicit empty marker`() {
        val context = ChatContext(
            generatedAt = Instant.parse("2025-06-18T12:00:00Z"),
            medications = emptyList(),
            weeks = emptyList(),
            events = emptyList(),
        )

        assertTrue("No medications are tracked yet." in renderer.render(context))
    }
}
