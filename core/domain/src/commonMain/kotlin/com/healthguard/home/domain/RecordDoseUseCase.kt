@file:OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)

package com.healthguard.home.domain

import com.healthguard.dose.RecordedTake
import com.healthguard.shared.data.DoseStatus
import com.healthguard.shared.data.MedicationRepository
import com.healthguard.shared.data.StoredDoseLog
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Logs a dose as taken now and returns an undo handle. The one take-now path
 * for every screen: [plannedAt] is the slot being satisfied; null (no
 * schedule) plans it at the take time itself. The id is a random UUID so undo
 * has a stable handle; [kotlin.uuid.Uuid] keeps this KMP-safe (no java.util).
 */
class RecordDoseUseCase(
    private val repository: MedicationRepository,
    private val clock: () -> Instant,
) {
    suspend operator fun invoke(
        scheduleId: String,
        plannedAt: Instant?,
        drugName: String,
    ): RecordedTake {
        val now = clock()
        val id = Uuid.random().toString()
        repository.logDose(
            StoredDoseLog(
                id = id,
                scheduleId = scheduleId,
                plannedAt = plannedAt ?: now,
                takenAt = now,
                status = DoseStatus.TAKEN,
            ),
        )
        return RecordedTake(id, drugName)
    }
}
