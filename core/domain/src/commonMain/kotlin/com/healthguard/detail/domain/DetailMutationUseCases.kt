@file:OptIn(ExperimentalTime::class)

package com.healthguard.detail.domain

import com.healthguard.shared.data.MedicationRepository
import com.healthguard.shared.data.StoredMedication
import com.healthguard.shared.data.StoredSchedule
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Persists the edited medication and schedule. Ports `DetailViewModel.save`'s
 * repository writes: both tables, in order. Activation state is never touched —
 * that goes through [ToggleTakingUseCase] — and the repository ignores any
 * startedAt/stoppedAt on the passed [schedule].
 */
class SaveMedicationUseCase(
    private val repository: MedicationRepository,
) {
    suspend operator fun invoke(medication: StoredMedication, schedule: StoredSchedule) {
        repository.updateMedication(medication)
        repository.updateSchedule(schedule)
    }
}

/**
 * Starts a dormant/stopped schedule or stops an active one, as of now. Ports
 * `DetailViewModel.toggleTaking`: [currentlyActive] decides the direction.
 */
class ToggleTakingUseCase(
    private val repository: MedicationRepository,
    private val clock: () -> Instant,
) {
    suspend operator fun invoke(medicationId: String, currentlyActive: Boolean) {
        val now = clock()
        if (currentlyActive) {
            repository.stop(medicationId, now)
        } else {
            repository.activate(medicationId, now)
        }
    }
}
