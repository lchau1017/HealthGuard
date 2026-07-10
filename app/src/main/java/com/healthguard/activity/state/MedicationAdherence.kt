package com.healthguard.activity.state

import com.healthguard.home.MedicationPhase

/**
 * One "Adherence by medicine" row for the UI: the tracked
 * [com.healthguard.activity.domain.MedicationAdherenceContent] with its stopped
 * label folded in by the ViewModel (see [ActivityViewModel]). [percent]
 * measures the medicine against its *own* schedule over the window (never a
 * share of total doses); it is null for medications without an expected-dose
 * schedule — interval ("every N hours") medications are as-needed and tracked
 * by count, never by percent. For stopped medications the expectation is
 * clipped to the active stretch, so [percent] reads "while taking".
 */
data class MedicationAdherence(
    val name: String,
    val phase: MedicationPhase,
    val asNeeded: Boolean,
    val percent: Int?,
    val taken: Int,
    val skipped: Int,
    /** Whether [percent] reaches the 80% PDC target; null without a percent. */
    val meetsTarget: Boolean?,
    /** "Stopped 3 Jul" while [phase] is STOPPED, else null. */
    val stoppedText: String?,
)
