package com.healthguard.domain.repository

import com.healthguard.domain.model.MedicationId
import com.healthguard.domain.model.MedicationWithSchedule
import com.healthguard.domain.model.StoredDoseLog
import com.healthguard.domain.model.StoredMedication
import com.healthguard.domain.model.StoredSchedule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlin.time.Instant

/**
 * Persistence facade over the medication inventory: medication and schedule
 * reads, writes and activation state. Dose-log history lives on the sibling
 * [DoseLogRepository]; one store implements both. Query flows are cold and hop
 * to the implementation's dispatcher; mutating calls fire [dataChanges] so
 * screens deriving state from tables SQLDelight's per-query flows don't cover
 * can recompute. Ids are caller-provided (UUIDs in production) so tests stay
 * deterministic.
 */
interface MedicationRepository {

    /**
     * Fires after every mutating call (including [DoseLogRepository] writes on
     * the same store). SQLDelight's query flows only notify listeners of the
     * tables they read, so screens that derive state from dose logs (or hold
     * a retained view model while another screen writes) fold this into their
     * recompute triggers to never go stale.
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

        fun activate(medicationId: MedicationId, at: Instant)

        fun stop(medicationId: MedicationId, at: Instant)

        fun logDose(log: StoredDoseLog)
    }

    fun medications(): Flow<List<MedicationWithSchedule>>

    suspend fun getMedication(id: MedicationId): MedicationWithSchedule?

    /** Updates the editable medication fields; a missing id is a no-op. */
    suspend fun updateMedication(medication: StoredMedication)

    /**
     * Updates frequency and withFood only. [StoredSchedule.startedAt] and
     * [StoredSchedule.stoppedAt] on the passed value are deliberately ignored:
     * activation state changes go through [activate]/[stop]. A missing id is
     * a no-op.
     */
    suspend fun updateSchedule(schedule: StoredSchedule)

    suspend fun delete(id: MedicationId)

    suspend fun activate(medicationId: MedicationId, at: Instant)

    suspend fun stop(medicationId: MedicationId, at: Instant)
}
