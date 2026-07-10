package com.healthguard.home.domain

import com.healthguard.domain.model.DoseId
import com.healthguard.domain.model.MedicationId
import com.healthguard.domain.repository.DoseLogRepository
import com.healthguard.domain.repository.MedicationRepository
import kotlin.time.Instant

/** Undoes a just-recorded take by removing its dose log. */
class UndoDoseUseCase(
    private val repository: DoseLogRepository,
) {
    suspend operator fun invoke(doseId: DoseId) = repository.deleteDoseLog(doseId)
}

/** Starts taking a medication as of now (clears any prior stop). */
class ActivateMedicationUseCase(
    private val repository: MedicationRepository,
    private val clock: () -> Instant,
) {
    suspend operator fun invoke(medicationId: MedicationId) = repository.activate(medicationId, clock())
}

/** Stops taking a medication as of now. */
class StopMedicationUseCase(
    private val repository: MedicationRepository,
    private val clock: () -> Instant,
) {
    suspend operator fun invoke(medicationId: MedicationId) = repository.stop(medicationId, clock())
}

/** Removes a medication (and its schedule/history) entirely. */
class DeleteMedicationUseCase(
    private val repository: MedicationRepository,
) {
    suspend operator fun invoke(medicationId: MedicationId) = repository.delete(medicationId)
}
