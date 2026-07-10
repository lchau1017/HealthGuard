@file:OptIn(ExperimentalTime::class)

package com.healthguard.home

import com.healthguard.domain.model.StoredSchedule
import kotlin.time.ExperimentalTime

/**
 * Where a medication stands in its treatment lifecycle — the initiation and
 * persistence axes of adherence, derived entirely from the schedule's
 * activation timestamps (no schema of its own). Implementation (doses versus
 * schedule while taking) is measured separately by [com.healthguard.activity.AdherenceResult].
 */
enum class MedicationPhase {
    /** Scanned but never activated: treatment not initiated. */
    NOT_STARTED,

    /** Currently active. */
    TAKING,

    /** Started once, then stopped (discontinued); resuming clears the stop. */
    STOPPED,
}

/**
 * Derives the phase. A stop always wins — reactivation clears `stoppedAt`,
 * so a non-null stop means the medication is discontinued right now.
 */
val StoredSchedule.phase: MedicationPhase
    get() = when {
        stoppedAt != null -> MedicationPhase.STOPPED
        startedAt != null -> MedicationPhase.TAKING
        else -> MedicationPhase.NOT_STARTED
    }
