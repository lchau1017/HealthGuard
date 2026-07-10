@file:OptIn(ExperimentalUuidApi::class)

package com.healthguard.home.domain

import com.healthguard.dose.RecordedTake
import com.healthguard.domain.model.DoseId
import com.healthguard.domain.model.DoseStatus
import com.healthguard.domain.model.ScheduleId
import com.healthguard.domain.repository.DoseLogRepository
import com.healthguard.domain.model.StoredDoseLog
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
    private val repository: DoseLogRepository,
    private val clock: () -> Instant,
) {
    suspend operator fun invoke(
        scheduleId: ScheduleId,
        plannedAt: Instant?,
        drugName: String,
    ): RecordedTake {
        val now = clock()
        val id = DoseId(Uuid.random().toString())
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
