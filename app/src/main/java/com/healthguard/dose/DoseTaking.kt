@file:OptIn(ExperimentalTime::class)

package com.healthguard.dose

import com.healthguard.shared.data.DoseStatus
import com.healthguard.shared.data.MedicationRepository
import com.healthguard.shared.data.StoredDoseLog
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * One shared take-now path for the home and detail screens: the same
 * double-dose safety window and the same undoable TAKEN log everywhere.
 */
val DOUBLE_DOSE_WINDOW = 30.minutes

/** True when recording now would repeat a dose within the safety window. */
fun isDoubleDose(lastTaken: Instant?, now: Instant): Boolean =
    lastTaken != null && now - lastTaken < DOUBLE_DOSE_WINDOW

/** A just-recorded take the UI can offer to undo. */
data class RecordedTake(val doseId: String, val drugName: String)

/**
 * Logs a TAKEN dose at [now] and returns the new log's id (the undo handle).
 * [plannedAt] is the dose slot being satisfied; null (no schedule) plans it
 * at the take time itself.
 */
suspend fun recordTakenDose(
    repository: MedicationRepository,
    scheduleId: String,
    plannedAt: Instant?,
    now: Instant,
): String {
    val id = UUID.randomUUID().toString()
    repository.logDose(
        StoredDoseLog(
            id = id,
            scheduleId = scheduleId,
            plannedAt = plannedAt ?: now,
            takenAt = now,
            status = DoseStatus.TAKEN,
        ),
    )
    return id
}
