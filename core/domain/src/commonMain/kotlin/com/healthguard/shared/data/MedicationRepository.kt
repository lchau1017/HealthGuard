@file:OptIn(ExperimentalTime::class)

package com.healthguard.shared.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Persistence facade over the medication store. Query flows are cold and hop
 * to the implementation's dispatcher; mutating calls fire [dataChanges] so
 * screens deriving state from tables SQLDelight's per-query flows don't cover
 * can recompute. Ids are caller-provided (UUIDs in production) so tests stay
 * deterministic.
 */
interface MedicationRepository {

    /**
     * Fires after every mutating call. SQLDelight's query flows only notify
     * listeners of the tables they read, so screens that derive state from
     * dose logs (or hold a retained view model while another screen writes)
     * fold this into their recompute triggers to never go stale.
     */
    val dataChanges: SharedFlow<Unit>

    suspend fun insertMedication(medication: StoredMedication, schedule: StoredSchedule)

    /**
     * Runs several writes inside ONE transaction, so observers see a single
     * state change (no intermediate "medication exists but its history
     * doesn't yet" emissions) and [dataChanges] fires once.
     */
    suspend fun batch(block: BatchWriter.() -> Unit)

    /** The writes available inside [batch]; each is the plain-call equivalent. */
    interface BatchWriter {
        fun insertMedication(medication: StoredMedication, schedule: StoredSchedule)

        fun activate(medicationId: String, at: Instant)

        fun stop(medicationId: String, at: Instant)

        fun logDose(log: StoredDoseLog)
    }

    fun medications(): Flow<List<MedicationWithSchedule>>

    fun activeMedications(): Flow<List<MedicationWithSchedule>>

    suspend fun getMedication(id: String): MedicationWithSchedule?

    /** Updates the editable medication fields; a missing id is a no-op. */
    suspend fun updateMedication(medication: StoredMedication)

    /**
     * Updates frequency and withFood only. [StoredSchedule.startedAt] and
     * [StoredSchedule.stoppedAt] on the passed value are deliberately ignored:
     * activation state changes go through [activate]/[stop]. A missing id is
     * a no-op.
     */
    suspend fun updateSchedule(schedule: StoredSchedule)

    suspend fun delete(id: String)

    suspend fun activate(medicationId: String, at: Instant)

    suspend fun stop(medicationId: String, at: Instant)

    suspend fun logDose(log: StoredDoseLog)

    /** Removes a single dose log (undo of a just-recorded take); missing id is a no-op. */
    suspend fun deleteDoseLog(id: String)

    suspend fun updateDoseStatus(id: String, status: DoseStatus, takenAt: Instant?)

    /** Half-open range: plannedAt in [from, to). */
    suspend fun dosesInRange(scheduleId: String, from: Instant, to: Instant): List<StoredDoseLog>

    suspend fun latestDose(scheduleId: String): StoredDoseLog?

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
