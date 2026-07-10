@file:OptIn(ExperimentalTime::class)

package com.healthguard.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.healthguard.data.db.DoseLog
import com.healthguard.data.db.HealthGuardDb
import com.healthguard.domain.extraction.Frequency
import com.healthguard.domain.model.DoseLogWithMedication
import com.healthguard.domain.model.DoseStatus
import com.healthguard.domain.model.MedicationWithSchedule
import com.healthguard.domain.model.StoredDoseLog
import com.healthguard.domain.model.StoredMedication
import com.healthguard.domain.model.StoredSchedule
import com.healthguard.domain.model.TakenDose
import com.healthguard.domain.repository.DoseLogRepository
import com.healthguard.domain.repository.MedicationRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * SQLDelight-backed [MedicationRepository] over [HealthGuardDb]. All calls hop
 * to [dispatcher]; ids are caller-provided (UUIDs in production) so tests stay
 * deterministic.
 */
class SqlDelightMedicationRepository(
    db: HealthGuardDb,
    private val dispatcher: CoroutineDispatcher,
) : MedicationRepository, DoseLogRepository {
    private val queries = db.healthGuardQueries

    private val _dataChanges = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Fires after every mutating call. SQLDelight's query flows only notify
     * listeners of the tables they read, so screens that derive state from
     * dose logs (or hold a retained view model while another screen writes)
     * fold this into their recompute triggers to never go stale.
     */
    override val dataChanges: SharedFlow<Unit> = _dataChanges.asSharedFlow()

    private fun notifyChanged() {
        _dataChanges.tryEmit(Unit)
    }

    override suspend fun insertMedication(medication: StoredMedication, schedule: StoredSchedule) =
        withContext(dispatcher) {
            queries.transaction { writeMedication(medication, schedule) }
            notifyChanged()
        }

    /**
     * Runs several writes inside ONE transaction, so observers see a single
     * state change (no intermediate "medication exists but its history
     * doesn't yet" emissions) and [dataChanges] fires once.
     */
    override suspend fun batch(block: MedicationRepository.BatchWriter.() -> Unit) =
        withContext(dispatcher) {
            queries.transaction { BatchWriter().block() }
            notifyChanged()
        }

    /** The writes available inside [batch]; each is the plain-call equivalent. */
    inner class BatchWriter internal constructor() : MedicationRepository.BatchWriter {
        override fun insertMedication(medication: StoredMedication, schedule: StoredSchedule) =
            writeMedication(medication, schedule)

        override fun activate(medicationId: String, at: Instant) = writeActivation(medicationId, at)

        override fun stop(medicationId: String, at: Instant) = writeStop(medicationId, at)

        override fun logDose(log: StoredDoseLog) = writeDoseLog(log)
    }

    private fun writeMedication(medication: StoredMedication, schedule: StoredSchedule) {
        queries.insertMedication(
            id = medication.id,
            drugName = medication.drugName,
            label = medication.label,
            activeIngredients = encodeIngredients(medication.activeIngredients),
            dosage = medication.dosage,
            form = medication.form,
            extractionConfidence = medication.extractionConfidence,
            createdAt = medication.createdAt.toEpochMilliseconds(),
        )
        queries.insertSchedule(
            id = schedule.id,
            medicationId = schedule.medicationId,
            frequencyType = schedule.frequency.typeColumn(),
            frequencyValue = schedule.frequency.valueColumn(),
            withFood = schedule.withFood.toDbBool(),
            startedAt = schedule.startedAt?.toEpochMilliseconds(),
            stoppedAt = schedule.stoppedAt?.toEpochMilliseconds(),
        )
    }

    private fun writeActivation(medicationId: String, at: Instant) {
        queries.scheduleIdForMedication(medicationId).executeAsList().forEach { scheduleId ->
            queries.activateSchedule(startedAt = at.toEpochMilliseconds(), id = scheduleId)
        }
    }

    private fun writeStop(medicationId: String, at: Instant) {
        queries.scheduleIdForMedication(medicationId).executeAsList().forEach { scheduleId ->
            queries.stopSchedule(stoppedAt = at.toEpochMilliseconds(), id = scheduleId)
        }
    }

    private fun writeDoseLog(log: StoredDoseLog) {
        queries.insertDoseLog(
            id = log.id,
            scheduleId = log.scheduleId,
            plannedAt = log.plannedAt.toEpochMilliseconds(),
            takenAt = log.takenAt?.toEpochMilliseconds(),
            status = log.status.name,
        )
    }

    override fun medications(): Flow<List<MedicationWithSchedule>> =
        queries.listMedications(::rowToMedicationWithSchedule).asFlow().mapToList(dispatcher)

    override suspend fun getMedication(id: String): MedicationWithSchedule? = withContext(dispatcher) {
        queries.getMedication(id, ::rowToMedicationWithSchedule).executeAsOneOrNull()
    }

    /** Updates the editable medication fields; a missing id is a no-op. */
    override suspend fun updateMedication(medication: StoredMedication) = withContext(dispatcher) {
        queries.updateMedication(
            drugName = medication.drugName,
            label = medication.label,
            activeIngredients = encodeIngredients(medication.activeIngredients),
            dosage = medication.dosage,
            form = medication.form,
            id = medication.id,
        )
        notifyChanged()
    }

    /**
     * Updates frequency and withFood only. [StoredSchedule.startedAt] and
     * [StoredSchedule.stoppedAt] on the passed value are deliberately ignored:
     * activation state changes go through [activate]/[stop]. A missing id is
     * a no-op.
     */
    override suspend fun updateSchedule(schedule: StoredSchedule) = withContext(dispatcher) {
        queries.updateSchedule(
            frequencyType = schedule.frequency.typeColumn(),
            frequencyValue = schedule.frequency.valueColumn(),
            withFood = schedule.withFood.toDbBool(),
            id = schedule.id,
        )
        notifyChanged()
    }

    override suspend fun delete(id: String) = withContext(dispatcher) {
        queries.deleteMedication(id)
        notifyChanged()
    }

    override suspend fun activate(medicationId: String, at: Instant) = withContext(dispatcher) {
        queries.transaction { writeActivation(medicationId, at) }
        notifyChanged()
    }

    override suspend fun stop(medicationId: String, at: Instant) = withContext(dispatcher) {
        queries.transaction { writeStop(medicationId, at) }
        notifyChanged()
    }

    override suspend fun logDose(log: StoredDoseLog) = withContext(dispatcher) {
        writeDoseLog(log)
        notifyChanged()
    }

    /** Removes a single dose log (undo of a just-recorded take); missing id is a no-op. */
    override suspend fun deleteDoseLog(id: String) = withContext(dispatcher) {
        queries.deleteDoseLog(id)
        notifyChanged()
    }

    /** Half-open range: plannedAt in [from, to). */
    override suspend fun dosesInRange(scheduleId: String, from: Instant, to: Instant): List<StoredDoseLog> =
        withContext(dispatcher) {
            queries.doseLogsForScheduleInRange(
                scheduleId = scheduleId,
                fromMillis = from.toEpochMilliseconds(),
                toMillis = to.toEpochMilliseconds(),
            ).executeAsList().map { it.toStored() }
        }

    override suspend fun latestDose(scheduleId: String): StoredDoseLog? = withContext(dispatcher) {
        queries.latestDoseLogForSchedule(scheduleId).executeAsOneOrNull()?.toStored()
    }

    /**
     * Every TAKEN dose across all schedules with takenAt in [from, to)
     * (half-open), oldest first, resolved to its medication.
     */
    override suspend fun takenDosesInRange(from: Instant, to: Instant): List<TakenDose> =
        withContext(dispatcher) {
            queries.takenDosesInRange(
                fromMillis = from.toEpochMilliseconds(),
                toMillis = to.toEpochMilliseconds(),
            ) { medicationId, drugName, takenAt ->
                TakenDose(
                    medicationId = medicationId,
                    drugName = drugName,
                    // Never null: the query filters on takenAt IS NOT NULL.
                    takenAt = Instant.fromEpochMilliseconds(takenAt!!),
                )
            }.executeAsList()
        }

    /** The schedule's latest [limit] dose logs, any status, newest planned first. */
    override suspend fun recentDoses(scheduleId: String, limit: Int): List<StoredDoseLog> =
        withContext(dispatcher) {
            queries.recentDoseLogsForSchedule(scheduleId, limit.toLong())
                .executeAsList().map { it.toStored() }
        }

    /**
     * Every dose log (any status) across all schedules whose effective time —
     * takenAt when present, plannedAt otherwise — is in [from, to) (half-open).
     */
    override suspend fun doseLogsInRange(from: Instant, to: Instant): List<StoredDoseLog> =
        withContext(dispatcher) {
            queries.doseLogsInRange(
                fromMillis = from.toEpochMilliseconds(),
                toMillis = to.toEpochMilliseconds(),
            ).executeAsList().map { it.toStored() }
        }

    /**
     * Like [doseLogsInRange] (any status, effective time in [from, to),
     * half-open), but each log carries its medication's identity — the
     * day-detail sheet's per-medicine grouping input.
     */
    override suspend fun doseLogsWithMedicationInRange(
        from: Instant,
        to: Instant,
    ): List<DoseLogWithMedication> = withContext(dispatcher) {
        queries.doseLogsWithMedicationInRange(
            fromMillis = from.toEpochMilliseconds(),
            toMillis = to.toEpochMilliseconds(),
        ) { medicationId, drugName, dosage, plannedAt, takenAt, status ->
            DoseLogWithMedication(
                medicationId = medicationId,
                drugName = drugName,
                dosage = dosage,
                plannedAt = Instant.fromEpochMilliseconds(plannedAt),
                takenAt = takenAt?.let(Instant::fromEpochMilliseconds),
                status = DoseStatus.valueOf(status),
            )
        }.executeAsList()
    }
}

private const val FREQ_TIMES_PER_DAY = "TIMES_PER_DAY"
private const val FREQ_EVERY_HOURS = "EVERY_HOURS"

private fun Frequency?.typeColumn(): String? = when (this) {
    is Frequency.TimesPerDay -> FREQ_TIMES_PER_DAY
    is Frequency.EveryHours -> FREQ_EVERY_HOURS
    null -> null
}

private fun Frequency?.valueColumn(): Long? = when (this) {
    is Frequency.TimesPerDay -> count.toLong()
    is Frequency.EveryHours -> hours.toLong()
    null -> null
}

private fun frequencyFrom(type: String?, value: Long?): Frequency? = when {
    type == FREQ_TIMES_PER_DAY && value != null -> Frequency.TimesPerDay(value.toInt())
    type == FREQ_EVERY_HOURS && value != null -> Frequency.EveryHours(value.toInt())
    else -> null
}

private fun Boolean?.toDbBool(): Long? = when (this) {
    true -> 1L
    false -> 0L
    null -> null
}

private fun Long?.fromDbBool(): Boolean? = when (this) {
    null -> null
    0L -> false
    else -> true
}

private val ingredientListSerializer = ListSerializer(String.serializer())

private fun encodeIngredients(ingredients: List<String>): String =
    Json.encodeToString(ingredientListSerializer, ingredients)

private fun decodeIngredients(raw: String): List<String> =
    runCatching { Json.decodeFromString(ingredientListSerializer, raw) }.getOrElse { emptyList() }

private fun DoseLog.toStored() = StoredDoseLog(
    id = id,
    scheduleId = scheduleId,
    plannedAt = Instant.fromEpochMilliseconds(plannedAt),
    takenAt = takenAt?.let(Instant::fromEpochMilliseconds),
    status = DoseStatus.valueOf(status),
)

@Suppress("LongParameterList") // shape dictated by the SQL join's column list
private fun rowToMedicationWithSchedule(
    medicationId: String,
    drugName: String,
    label: String?,
    activeIngredients: String,
    dosage: String?,
    form: String?,
    extractionConfidence: Double,
    createdAt: Long,
    scheduleId: String,
    frequencyType: String?,
    frequencyValue: Long?,
    withFood: Long?,
    startedAt: Long?,
    stoppedAt: Long?,
): MedicationWithSchedule = MedicationWithSchedule(
    medication = StoredMedication(
        id = medicationId,
        drugName = drugName,
        label = label,
        activeIngredients = decodeIngredients(activeIngredients),
        dosage = dosage,
        form = form,
        extractionConfidence = extractionConfidence,
        createdAt = Instant.fromEpochMilliseconds(createdAt),
    ),
    schedule = StoredSchedule(
        id = scheduleId,
        medicationId = medicationId,
        frequency = frequencyFrom(frequencyType, frequencyValue),
        withFood = withFood.fromDbBool(),
        startedAt = startedAt?.let(Instant::fromEpochMilliseconds),
        stoppedAt = stoppedAt?.let(Instant::fromEpochMilliseconds),
    ),
)
