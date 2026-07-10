package com.healthguard.testing

import com.healthguard.domain.model.DoseId
import com.healthguard.domain.model.DoseLogWithMedication
import com.healthguard.domain.model.DoseStatus
import com.healthguard.domain.repository.DoseLogRepository
import com.healthguard.domain.repository.MedicationRepository
import com.healthguard.domain.model.MedicationId
import com.healthguard.domain.model.MedicationWithSchedule
import com.healthguard.domain.model.ScheduleId
import com.healthguard.domain.model.StoredDoseLog
import com.healthguard.domain.model.StoredMedication
import com.healthguard.domain.model.StoredSchedule
import com.healthguard.domain.model.TakenDose
import com.healthguard.domain.extraction.Frequency
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * In-memory [MedicationRepository] for domain use-case tests. `:core:domain`'s
 * test source set cannot depend on `:core:data` (that would close a project
 * dependency cycle), so the real SQLDelight repository is unavailable here.
 *
 * Every interface member is implemented against simple collections, each
 * mirroring the real SQL's semantics (half-open ranges, effective-time
 * COALESCE, editable-columns-only updates) so use-case tests exercise
 * production behaviour rather than a convenient fiction.
 */
class FakeMedicationRepository : MedicationRepository, DoseLogRepository {

    private val medications = mutableListOf<MedicationWithSchedule>()
    private val doseLogs = mutableListOf<StoredDoseLog>()

    /** Recorded mutation calls, so delegation tests can assert the arguments. */
    val loggedDoses = mutableListOf<StoredDoseLog>()
    val deletedDoseIds = mutableListOf<DoseId>()
    val deletedMedicationIds = mutableListOf<MedicationId>()
    val activations = mutableListOf<Pair<MedicationId, Instant>>()
    val stops = mutableListOf<Pair<MedicationId, Instant>>()
    val updatedMedications = mutableListOf<StoredMedication>()
    val updatedSchedules = mutableListOf<StoredSchedule>()

    private val _dataChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    override val dataChanges: SharedFlow<Unit> = _dataChanges

    /**
     * Backs [medications] as a reactive stream: like SQLDelight's medications
     * query, it re-emits whenever the medication/schedule set changes (never on
     * a bare dose-log write). A [batch] publishes a single snapshot at the end,
     * so observers never see a partially seeded set.
     */
    private val _medications = MutableStateFlow<List<MedicationWithSchedule>>(emptyList())

    private fun publishMedications() {
        _medications.value = medications.toList()
    }

    // --- Test seeding helpers -------------------------------------------------

    /**
     * Seeds a medication + schedule. Newest-first ordering is the caller's job.
     * Takes the raw string for brevity and wraps it: the medication id is
     * [MedicationId] of [id], the schedule id [ScheduleId] of `"sched-$id"`.
     */
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
                id = MedicationId(id),
                drugName = drugName,
                label = null,
                activeIngredients = emptyList(),
                dosage = "200 mg",
                form = "tablet",
                extractionConfidence = 0.9,
                createdAt = createdAt,
            ),
            schedule = StoredSchedule(
                id = ScheduleId("sched-$id"),
                medicationId = MedicationId(id),
                frequency = frequency,
                withFood = true,
                startedAt = startedAt,
                stoppedAt = stoppedAt,
            ),
        )
        medications += item
        publishMedications()
        return item
    }

    /** Seeds a TAKEN dose log for a schedule (raw string wrapped, like [seedMedication]). */
    fun seedDose(scheduleId: String, takenAt: Instant, plannedAt: Instant = takenAt) {
        doseLogs += StoredDoseLog(
            id = DoseId("dose-$scheduleId-${takenAt.toEpochMilliseconds()}"),
            scheduleId = ScheduleId(scheduleId),
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
        publishMedications()
        _dataChanges.emit(Unit)
    }

    override fun medications(): Flow<List<MedicationWithSchedule>> = _medications

    override suspend fun delete(id: MedicationId) {
        deletedMedicationIds += id
        medications.removeAll { it.medication.id == id }
        publishMedications()
        _dataChanges.emit(Unit)
    }

    override suspend fun activate(medicationId: MedicationId, at: Instant) {
        activations += medicationId to at
        replaceSchedule(medicationId) { it.copy(startedAt = at, stoppedAt = null) }
        publishMedications()
        _dataChanges.emit(Unit)
    }

    override suspend fun stop(medicationId: MedicationId, at: Instant) {
        stops += medicationId to at
        replaceSchedule(medicationId) { it.copy(stoppedAt = at) }
        publishMedications()
        _dataChanges.emit(Unit)
    }

    override suspend fun logDose(log: StoredDoseLog) {
        loggedDoses += log
        doseLogs += log
        _dataChanges.emit(Unit)
    }

    override suspend fun deleteDoseLog(id: DoseId) {
        deletedDoseIds += id
        doseLogs.removeAll { it.id == id }
        _dataChanges.emit(Unit)
    }

    /**
     * Mirrors the SQL: the schedule's newest TAKEN dose by effective time
     * (takenAt when present, plannedAt otherwise); skipped and missed rows
     * never shift it.
     */
    override suspend fun latestTakenDose(scheduleId: ScheduleId): StoredDoseLog? =
        doseLogs
            .filter { it.scheduleId == scheduleId && it.status == DoseStatus.TAKEN }
            .maxByOrNull { it.takenAt ?: it.plannedAt }

    override suspend fun doseLogsInRange(from: Instant, to: Instant): List<StoredDoseLog> =
        doseLogs
            .filter { (it.takenAt ?: it.plannedAt) in from..<to }
            .sortedBy { it.plannedAt }

    override suspend fun getMedication(id: MedicationId): MedicationWithSchedule? =
        medications.firstOrNull { it.medication.id == id }

    /**
     * Mirrors the SQL: only the editable columns change (drugName, label,
     * activeIngredients, dosage, form); createdAt/extractionConfidence and the
     * schedule are untouched, and a missing id is a no-op.
     */
    override suspend fun updateMedication(medication: StoredMedication) {
        val index = medications.indexOfFirst { it.medication.id == medication.id }
        if (index < 0) {
            // Parity with the real repository: it signals a data change even
            // when the UPDATE matched no row.
            _dataChanges.emit(Unit)
            return
        }
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
        publishMedications()
        _dataChanges.emit(Unit)
    }

    /**
     * Mirrors the SQL: only frequency and withFood change; startedAt/stoppedAt
     * on the passed value are deliberately ignored (activation goes through
     * activate/stop), and a missing id is a no-op.
     */
    override suspend fun updateSchedule(schedule: StoredSchedule) {
        val index = medications.indexOfFirst { it.schedule.id == schedule.id }
        if (index < 0) {
            // Parity with the real repository: it signals a data change even
            // when the UPDATE matched no row.
            _dataChanges.emit(Unit)
            return
        }
        updatedSchedules += schedule
        val current = medications[index].schedule
        medications[index] = medications[index].copy(
            schedule = current.copy(
                frequency = schedule.frequency,
                withFood = schedule.withFood,
            ),
        )
        publishMedications()
        _dataChanges.emit(Unit)
    }

    override suspend fun dosesInRange(
        scheduleId: ScheduleId,
        from: Instant,
        to: Instant,
    ): List<StoredDoseLog> =
        doseLogs
            .filter { it.scheduleId == scheduleId && it.plannedAt in from..<to }
            .sortedBy { it.plannedAt }

    override suspend fun recentDoses(scheduleId: ScheduleId, limit: Int): List<StoredDoseLog> =
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

    /**
     * Mirrors the SQL transaction: all writes apply, then a single medications
     * snapshot and one [dataChanges] fire at the end — observers never see a
     * partially seeded set.
     */
    override suspend fun batch(block: MedicationRepository.BatchWriter.() -> Unit) {
        val writer = object : MedicationRepository.BatchWriter {
            override fun insertMedication(
                medication: StoredMedication,
                schedule: StoredSchedule,
            ) {
                medications += MedicationWithSchedule(medication, schedule)
            }

            override fun activate(medicationId: MedicationId, at: Instant) {
                activations += medicationId to at
                replaceSchedule(medicationId) { it.copy(startedAt = at, stoppedAt = null) }
            }

            override fun stop(medicationId: MedicationId, at: Instant) {
                stops += medicationId to at
                replaceSchedule(medicationId) { it.copy(stoppedAt = at) }
            }

            override fun logDose(log: StoredDoseLog) {
                loggedDoses += log
                doseLogs += log
            }
        }
        writer.block()
        publishMedications()
        _dataChanges.emit(Unit)
    }

    // --- Implemented for the Activity use cases -------------------------------

    /**
     * Mirrors the SQL `takenDosesInRange`: every TAKEN dose (with a takenAt)
     * across all schedules whose takenAt falls in [from, to) (half-open),
     * oldest first, resolved through its schedule to the owning medication.
     */
    override suspend fun takenDosesInRange(from: Instant, to: Instant): List<TakenDose> =
        doseLogs
            .filter { it.status == DoseStatus.TAKEN && it.takenAt != null }
            // INNER JOIN: logs without a matching schedule drop out.
            .mapNotNull { log ->
                val owner = medications.firstOrNull { it.schedule.id == log.scheduleId }
                    ?: return@mapNotNull null
                log to owner
            }
            .filter { (log, _) -> log.takenAt!! in from..<to }
            .sortedBy { (log, _) -> log.takenAt }
            .map { (log, owner) ->
                TakenDose(
                    medicationId = owner.medication.id,
                    drugName = owner.medication.drugName,
                    takenAt = log.takenAt!!,
                )
            }

    private fun replaceSchedule(
        medicationId: MedicationId,
        transform: (StoredSchedule) -> StoredSchedule,
    ) {
        val index = medications.indexOfFirst { it.medication.id == medicationId }
        if (index >= 0) {
            val current = medications[index]
            medications[index] = current.copy(schedule = transform(current.schedule))
        }
    }
}
