package com.healthguard.shared.domain

import com.healthguard.shared.data.MedicationRepository
import com.healthguard.shared.data.MedicationWithSchedule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart

/**
 * The medication list, re-emitting on any repository write. Folds the
 * [MedicationRepository.dataChanges] invalidation signal into the medications
 * query inside the domain layer, so presentation observes a single medication
 * stream and never touches the persistence-detail change flow directly.
 *
 * Dose-log writes from any screen must recompute list-derived state too, but
 * SQLDelight's medications query only observes the medication/schedule tables;
 * combining with [MedicationRepository.dataChanges] (started with an immediate
 * tick so the medications query still drives the first emission) covers them.
 */
class ObserveMedicationsUseCase(
    private val repository: MedicationRepository,
) {
    operator fun invoke(): Flow<List<MedicationWithSchedule>> =
        combine(
            repository.medications(),
            repository.dataChanges.onStart { emit(Unit) },
        ) { meds, _ -> meds }
}

/**
 * The repository's post-write invalidation signal, exposed behind a domain
 * contract so presentation re-queries on any change without depending on the
 * [MedicationRepository.dataChanges] implementation detail.
 */
class ObserveDataChangesUseCase(
    private val repository: MedicationRepository,
) {
    operator fun invoke(): Flow<Unit> = repository.dataChanges
}
