package com.healthguard.domain.model

import com.healthguard.domain.extraction.Frequency
import kotlin.time.Instant

/**
 * A medication as persisted in the local inventory. Rows are created at
 * import time (from an OCR extraction); whether the user is actually taking
 * the medication lives on its [StoredSchedule].
 */
data class StoredMedication(
    val id: String,
    val drugName: String,
    /** Optional user-assigned category chip, e.g. "Heart". */
    val label: String?,
    val activeIngredients: List<String>,
    val dosage: String?,
    val form: String?,
    val extractionConfidence: Double,
    val createdAt: Instant,
)

/**
 * The dosing schedule attached to a medication. Created dormant at import
 * ([startedAt] = null) and activated later when the user starts taking the
 * medication. A schedule with a non-null [startedAt] and a null [stoppedAt]
 * is active.
 */
data class StoredSchedule(
    val id: String,
    val medicationId: String,
    val frequency: Frequency?,
    val withFood: Boolean?,
    val startedAt: Instant?,
    val stoppedAt: Instant?,
)

data class MedicationWithSchedule(
    val medication: StoredMedication,
    val schedule: StoredSchedule,
)

enum class DoseStatus { TAKEN, MISSED, SKIPPED, PENDING }

/** One recorded take, resolved to its medication: an activity-history event. */
data class TakenDose(
    val medicationId: String,
    val drugName: String,
    val takenAt: Instant,
)

/**
 * One dose log resolved to its medication's display identity — what a
 * day-detail view needs to group a day's events by medicine without a
 * second lookup.
 */
data class DoseLogWithMedication(
    val medicationId: String,
    val drugName: String,
    val dosage: String?,
    val plannedAt: Instant,
    val takenAt: Instant?,
    val status: DoseStatus,
)

data class StoredDoseLog(
    val id: String,
    val scheduleId: String,
    val plannedAt: Instant,
    val takenAt: Instant?,
    val status: DoseStatus,
)
