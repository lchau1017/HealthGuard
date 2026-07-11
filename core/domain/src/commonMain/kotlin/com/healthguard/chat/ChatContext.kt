package com.healthguard.chat

import com.healthguard.activity.AdherenceResult
import com.healthguard.domain.extraction.Frequency
import com.healthguard.domain.model.DoseStatus
import com.healthguard.home.MedicationPhase
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

/**
 * Everything the assistant is allowed to answer from: a snapshot of the
 * user's adherence facts computed by the same domain math that drives the
 * screens, so a chat answer can never contradict the heat map. Built by
 * [com.healthguard.chat.domain.BuildChatContextUseCase]; the data layer
 * renders it to prompt text — this model stays presentation-free.
 */
data class ChatContext(
    /** The wall-clock instant the snapshot was computed against. */
    val generatedAt: Instant,
    val medications: List<MedicationFacts>,
    /** Weekly totals, oldest week first; the current week is clipped to now. */
    val weeks: List<WeekFacts>,
    /** Recorded dose events of the window, oldest first. */
    val events: List<DoseEventFact>,
) {

    /** One medication's facts over the context window (last 30 days). */
    data class MedicationFacts(
        val name: String,
        val dosage: String?,
        val frequency: Frequency?,
        val phase: MedicationPhase,
        /** Windowed taken/expected/skipped; percent is null when nothing was expected. */
        val adherence: AdherenceResult,
        /** When the next dose is due; null when dormant, stopped or unscheduled. */
        val nextDoseAt: Instant?,
    )

    /** One calendar week's totals across all schedules. */
    data class WeekFacts(
        /** The week's Monday. */
        val weekStart: LocalDate,
        val expected: Int,
        val taken: Int,
        val skipped: Int,
    ) {
        /** Expected doses that neither happened nor were deliberately skipped. */
        val missed: Int get() = (expected - taken - skipped).coerceAtLeast(0)
    }

    /** One recorded dose log, resolved to its local day and medicine. */
    data class DoseEventFact(
        val date: LocalDate,
        val drugName: String,
        val status: DoseStatus,
    )
}
