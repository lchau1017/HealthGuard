@file:OptIn(ExperimentalTime::class)

package com.healthguard.testing

import com.healthguard.shared.data.DoseLogWithMedication
import com.healthguard.shared.data.DoseStatus
import com.healthguard.shared.data.MedicationRepository
import com.healthguard.shared.data.MedicationWithSchedule
import com.healthguard.shared.data.StoredDoseLog
import com.healthguard.shared.data.StoredMedication
import com.healthguard.shared.data.StoredSchedule
import com.healthguard.shared.data.TakenDose
import com.healthguard.shared.extraction.Frequency
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flowOf

/**
 * In-memory [MedicationRepository] for domain use-case tests. `:core:domain`'s
 * test source set cannot depend on `:core:data` (that would close a project
 * dependency cycle), so the real SQLDelight repository is unavailable here.
 *
 * Only the methods the Phase-3 use cases actually touch are implemented against
 * simple collections; every other interface member throws so an accidental
 * reliance surfaces loudly rather than silently returning empty data.
 */
class FakeMedicationRepository : MedicationRepository {

    private val medications = mutableListOf<MedicationWithSchedule>()
    private val doseLogs = mutableListOf<StoredDoseLog>()

    /** Recorded mutation calls, so delegation tests can assert the arguments. */
    val loggedDoses = mutableListOf<StoredDoseLog>()
    val deletedDoseIds = mutableListOf<String>()
    val deletedMedicationIds = mutableListOf<String>()
    val activations = mutableListOf<Pair<String, Instant>>()
    val stops = mutableListOf<Pair<String, Instant>>()
    val updatedMedications = mutableListOf<StoredMedication>()
    val updatedSchedules = mutableListOf<StoredSchedule>()

    private val _dataChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    override val dataChanges: SharedFlow<Unit> = _dataChanges

    // --- Test seeding helpers -------------------------------------------------

    /** Seeds a medication + schedule. Newest-first ordering is the caller's job. */
    fun seedMedication(
        id: String,
        drugName: String = "Ibuprofen",
        createdAt: Instant = Instant.fromEpochMilliseconds(1_000),
        frequency: Frequency? = Frequency.TimesPerDay(2),
        startedAt: Instant? = null,
        stoppedAt: Instant? = null,
    ): MedicationWithSchedule {
        val item = MedicationWithSchedule(
            medication = StoredMedication(
                id = id,
                drugName = drugName,
                label = null,
                activeIngredients = emptyList(),
                dosage = "200 mg",
                form = "tablet",
                extractionConfidence = 0.9,
                createdAt = createdAt,
            ),
            schedule = StoredSchedule(
                id = "sched-$id",
                medicationId = id,
                frequency = frequency,
                withFood = true,
                startedAt = startedAt,
                stoppedAt = stoppedAt,
            ),
        )
        medications += item
        return item
    }

    /** Seeds a TAKEN dose log for a schedule. */
    fun seedDose(scheduleId: String, takenAt: Instant, plannedAt: Instant = takenAt) {
        doseLogs += StoredDoseLog(
            id = "dose-$scheduleId-${takenAt.toEpochMilliseconds()}",
            scheduleId = scheduleId,
            plannedAt = plannedAt,
            takenAt = takenAt,
            status = DoseStatus.TAKEN,
        )
    }

    /** Current seeded medications, in seed order. */
    fun currentMedications(): List<MedicationWithSchedule> = medications.toList()

    // --- Implemented surface --------------------------------------------------

    override suspend fun insertMedication(
        medication: StoredMedication,
        schedule: StoredSchedule,
    ) {
        medications += MedicationWithSchedule(medication, schedule)
        _dataChanges.emit(Unit)
    }

    override fun medications(): Flow<List<MedicationWithSchedule>> = flowOf(medications.toList())

    override suspend fun delete(id: String) {
        deletedMedicationIds += id
        medications.removeAll { it.medication.id == id }
        _dataChanges.emit(Unit)
    }

    override suspend fun activate(medicationId: String, at: Instant) {
        activations += medicationId to at
        replaceSchedule(medicationId) { it.copy(startedAt = at, stoppedAt = null) }
        _dataChanges.emit(Unit)
    }

    override suspend fun stop(medicationId: String, at: Instant) {
        stops += medicationId to at
        replaceSchedule(medicationId) { it.copy(stoppedAt = at) }
        _dataChanges.emit(Unit)
    }

    override suspend fun logDose(log: StoredDoseLog) {
        loggedDoses += log
        doseLogs += log
        _dataChanges.emit(Unit)
    }

    override suspend fun deleteDoseLog(id: String) {
        deletedDoseIds += id
        doseLogs.removeAll { it.id == id }
        _dataChanges.emit(Unit)
    }

    override suspend fun latestDose(scheduleId: String): StoredDoseLog? =
        doseLogs.filter { it.scheduleId == scheduleId }.maxByOrNull { it.plannedAt }

    override suspend fun doseLogsInRange(from: Instant, to: Instant): List<StoredDoseLog> =
        doseLogs
            .filter { (it.takenAt ?: it.plannedAt) in from..<to }
            .sortedBy { it.plannedAt }

    override suspend fun getMedication(id: String): MedicationWithSchedule? =
        medications.firstOrNull { it.medication.id == id }

    /**
     * Mirrors the SQL: only the editable columns change (drugName, label,
     * activeIngredients, dosage, form); createdAt/extractionConfidence and the
     * schedule are untouched, and a missing id is a no-op.
     */
    override suspend fun updateMedication(medication: StoredMedication) {
        val index = medications.indexOfFirst { it.medication.id == medication.id }
        if (index < 0) return
        updatedMedications += medication
        val current = medications[index].medication
        medications[index] = medications[index].copy(
            medication = current.copy(
                drugName = medication.drugName,
                label = medication.label,
                activeIngredients = medication.activeIngredients,
                dosage = medication.dosage,
                form = medication.form,
            ),
        )
        _dataChanges.emit(Unit)
    }

    /**
     * Mirrors the SQL: only frequency and withFood change; startedAt/stoppedAt
     * on the passed value are deliberately ignored (activation goes through
     * activate/stop), and a missing id is a no-op.
     */
    override suspend fun updateSchedule(schedule: StoredSchedule) {
        val index = medications.indexOfFirst { it.schedule.id == schedule.id }
        if (index < 0) return
        updatedSchedules += schedule
        val current = medications[index].schedule
        medications[index] = medications[index].copy(
            schedule = current.copy(
                frequency = schedule.frequency,
                withFood = schedule.withFood,
            ),
        )
        _dataChanges.emit(Unit)
    }

    override suspend fun dosesInRange(
        scheduleId: String,
        from: Instant,
        to: Instant,
    ): List<StoredDoseLog> =
        doseLogs
            .filter { it.scheduleId == scheduleId && it.plannedAt in from..<to }
            .sortedBy { it.plannedAt }

    override suspend fun recentDoses(scheduleId: String, limit: Int): List<StoredDoseLog> =
        doseLogs
            .filter { it.scheduleId == scheduleId }
            .sortedByDescending { it.plannedAt }
            .take(limit)

    override suspend fun doseLogsWithMedicationInRange(
        from: Instant,
        to: Instant,
    ): List<DoseLogWithMedication> =
        doseLogs
            // INNER JOIN: logs without a matching schedule drop out.
            .mapNotNull { log ->
                val owner = medications.firstOrNull { it.schedule.id == log.scheduleId }
                    ?: return@mapNotNull null
                log to owner
            }
            .filter { (log, _) -> (log.takenAt ?: log.plannedAt) in from..<to }
            .sortedBy { (log, _) -> log.takenAt ?: log.plannedAt }
            .map { (log, owner) ->
                DoseLogWithMedication(
                    medicationId = owner.medication.id,
                    drugName = owner.medication.drugName,
                    dosage = owner.medication.dosage,
                    plannedAt = log.plannedAt,
                    takenAt = log.takenAt,
                    status = log.status,
                )
            }

    // --- Unused surface -------------------------------------------------------

    override suspend fun batch(block: MedicationRepository.BatchWriter.() -> Unit): Unit =
        throw NotImplementedError()

    override fun activeMedications(): Flow<List<MedicationWithSchedule>> = throw NotImplementedError()

    override suspend fun updateDoseStatus(id: String, status: DoseStatus, takenAt: Instant?): Unit =
        throw NotImplementedError()

    override suspend fun takenDosesInRange(from: Instant, to: Instant): List<TakenDose> =
        throw NotImplementedError()

    private fun replaceSchedule(
        medicationId: String,
        transform: (StoredSchedule) -> StoredSchedule,
    ) {
        val index = medications.indexOfFirst { it.medication.id == medicationId }
        if (index >= 0) {
            val current = medications[index]
            medications[index] = current.copy(schedule = transform(current.schedule))
        }
    }
}
