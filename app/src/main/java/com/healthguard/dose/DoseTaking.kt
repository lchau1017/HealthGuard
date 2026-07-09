@file:OptIn(ExperimentalTime::class)

package com.healthguard.dose

import com.healthguard.shared.data.DoseStatus
import com.healthguard.shared.data.MedicationRepository
import com.healthguard.shared.data.StoredDoseLog
import java.util.UUID
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

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
