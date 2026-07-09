@file:OptIn(ExperimentalTime::class)

package com.healthguard.home.domain

import com.healthguard.shared.data.MedicationRepository
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Undoes a just-recorded take by removing its dose log. */
class UndoDoseUseCase(
    private val repository: MedicationRepository,
) {
    suspend operator fun invoke(doseId: String) = repository.deleteDoseLog(doseId)
}

/** Starts taking a medication as of now (clears any prior stop). */
class ActivateMedicationUseCase(
    private val repository: MedicationRepository,
    private val clock: () -> Instant,
) {
    suspend operator fun invoke(medicationId: String) = repository.activate(medicationId, clock())
}

/** Stops taking a medication as of now. */
class StopMedicationUseCase(
    private val repository: MedicationRepository,
    private val clock: () -> Instant,
) {
    suspend operator fun invoke(medicationId: String) = repository.stop(medicationId, clock())
}

/** Removes a medication (and its schedule/history) entirely. */
class DeleteMedicationUseCase(
    private val repository: MedicationRepository,
) {
    suspend operator fun invoke(medicationId: String) = repository.delete(medicationId)
}
