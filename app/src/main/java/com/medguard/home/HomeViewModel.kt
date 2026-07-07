@file:OptIn(ExperimentalTime::class)

package com.medguard.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.medguard.shared.data.MedicationRepository
import com.medguard.shared.data.MedicationWithSchedule
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A schedule the user has started and not stopped. */
val MedicationWithSchedule.isActive: Boolean
    get() = schedule.startedAt != null && schedule.stoppedAt == null

/**
 * Backs the home medication list. Rows arrive newest-first from the
 * repository; active medications are hoisted to the top so what the user is
 * currently taking is always visible without scrolling.
 */
class HomeViewModel(
    private val repository: MedicationRepository,
    private val clock: () -> Instant,
) : ViewModel() {

    val medications: StateFlow<List<MedicationWithSchedule>> =
        repository.medications()
            .map { rows -> rows.sortedByDescending { it.isActive } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onPlay(medicationId: String) {
        viewModelScope.launch { repository.activate(medicationId, clock()) }
    }

    fun onStop(medicationId: String) {
        viewModelScope.launch { repository.stop(medicationId, clock()) }
    }

    fun onDelete(medicationId: String) {
        viewModelScope.launch { repository.delete(medicationId) }
    }
}
