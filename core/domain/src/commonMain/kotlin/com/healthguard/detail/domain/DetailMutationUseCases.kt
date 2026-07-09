package com.healthguard.detail.domain

import com.healthguard.shared.data.MedicationRepository
import com.healthguard.shared.data.StoredMedication
import com.healthguard.shared.data.StoredSchedule

/**
 * Persists the edited medication and schedule. Ports `DetailViewModel.save`'s
 * repository writes: both tables, in order. Activation state is never touched —
 * that goes through the shared `ActivateMedicationUseCase`/`StopMedicationUseCase` —
 * and the repository ignores any startedAt/stoppedAt on the passed [schedule].
 */
class SaveMedicationUseCase(
    private val repository: MedicationRepository,
) {
    suspend operator fun invoke(medication: StoredMedication, schedule: StoredSchedule) {
        repository.updateMedication(medication)
        repository.updateSchedule(schedule)
    }
}
