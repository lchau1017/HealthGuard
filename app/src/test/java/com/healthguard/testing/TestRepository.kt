@file:OptIn(ExperimentalTime::class)

package com.healthguard.testing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.healthguard.domain.model.DoseStatus
import com.healthguard.domain.repository.MedicationRepository
import com.healthguard.data.SqlDelightMedicationRepository
import com.healthguard.domain.model.StoredDoseLog
import com.healthguard.domain.model.StoredMedication
import com.healthguard.domain.model.StoredSchedule
import com.healthguard.data.db.HealthGuardDb
import com.healthguard.domain.extraction.Frequency
import java.util.Properties
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope

/**
 * Shared scaffolding for the view-model tests: the in-memory SQLDelight
 * repository they all drive, plus the seed/log/collect helpers that were
 * copy-pasted between them. Test classes keep thin wrappers only where
 * their seed defaults genuinely differ.
 */

/** A fresh in-memory database driver with the schema created and FKs on. */
fun inMemoryDriver(): JdbcSqliteDriver = JdbcSqliteDriver(
    JdbcSqliteDriver.IN_MEMORY,
    Properties().apply { put("foreign_keys", "true") },
).also { HealthGuardDb.Schema.create(it) }

/** The real repository over a fresh in-memory database. */
fun inMemoryRepository(dispatcher: CoroutineDispatcher): MedicationRepository =
    SqlDelightMedicationRepository(HealthGuardDb(inMemoryDriver()), dispatcher)

/** Inserts one medication + schedule; defaults keep the row minimal. */
suspend fun MedicationRepository.seedMedication(
    id: String,
    drugName: String = "Ibuprofen",
    scheduleId: String = "sched-$id",
    label: String? = null,
    activeIngredients: List<String> = emptyList(),
    dosage: String? = null,
    form: String? = null,
    extractionConfidence: Double = 1.0,
    createdAtMillis: Long = 1_000,
    frequency: Frequency? = null,
    withFood: Boolean? = null,
    startedAt: Instant? = null,
    stoppedAt: Instant? = null,
) {
    insertMedication(
        StoredMedication(
            id = id,
            drugName = drugName,
            label = label,
            activeIngredients = activeIngredients,
            dosage = dosage,
            form = form,
            extractionConfidence = extractionConfidence,
            createdAt = Instant.fromEpochMilliseconds(createdAtMillis),
        ),
        StoredSchedule(
            id = scheduleId,
            medicationId = id,
            frequency = frequency,
            withFood = withFood,
            startedAt = startedAt,
            stoppedAt = stoppedAt,
        ),
    )
}

/** Logs a TAKEN dose against the seeded "sched-<medicationId>" schedule. */
suspend fun MedicationRepository.logTaken(medicationId: String, takenAt: Instant) {
    logDose(
        StoredDoseLog(
            id = "dose-$medicationId-${takenAt.toEpochMilliseconds()}",
            scheduleId = "sched-$medicationId",
            plannedAt = takenAt,
            takenAt = takenAt,
            status = DoseStatus.TAKEN,
        ),
    )
}

/** Records every effect a view model emits, for the one-shot assertions. */
fun <E> TestScope.collectEffects(effects: Flow<E>): List<E> {
    val collected = mutableListOf<E>()
    backgroundScope.launch { effects.collect { collected += it } }
    return collected
}

/** Keeps a state flow hot for the test and settles the initial emissions. */
fun TestScope.collectState(state: Flow<*>) {
    backgroundScope.launch { state.collect {} }
    testScheduler.advanceUntilIdle()
}
