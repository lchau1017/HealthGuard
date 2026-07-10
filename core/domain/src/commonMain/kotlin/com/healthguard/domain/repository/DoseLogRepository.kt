@file:OptIn(ExperimentalTime::class)

package com.healthguard.domain.repository

import com.healthguard.domain.model.DoseLogWithMedication
import com.healthguard.domain.model.StoredDoseLog
import com.healthguard.domain.model.TakenDose
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Persistence facade over the dose-log history: single-take writes and the
 * range/analytics queries the heat maps and adherence figures read. Medication
 * and schedule concerns live on the sibling [MedicationRepository]; one store
 * implements both, so writes here fire [MedicationRepository.dataChanges] too.
 */
interface DoseLogRepository {

    suspend fun logDose(log: StoredDoseLog)

    /** Removes a single dose log (undo of a just-recorded take); missing id is a no-op. */
    suspend fun deleteDoseLog(id: String)

    /** Half-open range: plannedAt in [from, to). */
    suspend fun dosesInRange(scheduleId: String, from: Instant, to: Instant): List<StoredDoseLog>

    /**
     * The schedule's newest TAKEN dose by effective time (takenAt when
     * present, plannedAt otherwise) — the "last taken" input to next-dose
     * calculations. Skipped and missed rows never shift it.
     */
    suspend fun latestTakenDose(scheduleId: String): StoredDoseLog?

    /**
     * Every TAKEN dose across all schedules with takenAt in [from, to)
     * (half-open), oldest first, resolved to its medication.
     */
    suspend fun takenDosesInRange(from: Instant, to: Instant): List<TakenDose>

    /** The schedule's latest [limit] dose logs, any status, newest planned first. */
    suspend fun recentDoses(scheduleId: String, limit: Int): List<StoredDoseLog>

    /**
     * Every dose log (any status) across all schedules whose effective time —
     * takenAt when present, plannedAt otherwise — is in [from, to) (half-open).
     */
    suspend fun doseLogsInRange(from: Instant, to: Instant): List<StoredDoseLog>

    /**
     * Like [doseLogsInRange] (any status, effective time in [from, to),
     * half-open), but each log carries its medication's identity — the
     * day-detail sheet's per-medicine grouping input.
     */
    suspend fun doseLogsWithMedicationInRange(
        from: Instant,
        to: Instant,
    ): List<DoseLogWithMedication>
}
