@file:OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)

package com.healthguard.confirm.domain

import com.healthguard.shared.data.MedicationRepository
import com.healthguard.shared.data.StoredMedication
import com.healthguard.shared.data.StoredSchedule
import com.healthguard.shared.extraction.Frequency
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The reviewed, user-confirmed medication ready to be persisted. Carries the
 * values the confirm screen collected; the identity id and creation timestamp
 * are stamped by [SaveNewMedicationUseCase] rather than the presentation layer.
 */
data class NewMedication(
    val drugName: String,
    val label: String?,
    val activeIngredients: List<String>,
    val dosage: String?,
    val form: String?,
    val extractionConfidence: Double,
    val frequency: Frequency?,
    val withFood: Boolean?,
)

/**
 * Persists a freshly imported medication with a dormant schedule (started later
 * from the home list). Ports `ConfirmViewModel.onAccept`'s writes: it mints both
 * ids and stamps [createdAt] from [clock]. Ids use [kotlin.uuid.Uuid] to keep
 * this KMP-safe (no java.util.UUID). Returns the new medication id.
 */
class SaveNewMedicationUseCase(
    private val repository: MedicationRepository,
    private val clock: () -> Instant,
) {
    suspend operator fun invoke(medication: NewMedication): String {
        val medicationId = Uuid.random().toString()
        repository.insertMedication(
            StoredMedication(
                id = medicationId,
                drugName = medication.drugName,
                label = medication.label,
                activeIngredients = medication.activeIngredients,
                dosage = medication.dosage,
                form = medication.form,
                extractionConfidence = medication.extractionConfidence,
                createdAt = clock(),
            ),
            StoredSchedule(
                id = Uuid.random().toString(),
                medicationId = medicationId,
                frequency = medication.frequency,
                withFood = medication.withFood,
                startedAt = null,
                stoppedAt = null,
            ),
        )
        return medicationId
    }
}
