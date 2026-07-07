@file:OptIn(ExperimentalTime::class)

package com.medguard.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.medguard.shared.data.DoseStatus
import com.medguard.shared.data.MedicationRepository
import com.medguard.shared.data.MedicationWithSchedule
import com.medguard.shared.data.StoredDoseLog
import com.medguard.shared.domain.nextDose
import java.util.UUID
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone

/** A schedule the user has started and not stopped. */
val MedicationWithSchedule.isActive: Boolean
    get() = schedule.startedAt != null && schedule.stoppedAt == null

/** One "Taking now" entry: the medication plus its dose timing. */
data class DoseCard(
    val item: MedicationWithSchedule,
    /** When the next dose is due; past = overdue; null = no frequency. */
    val nextDoseAt: Instant?,
    val lastTaken: Instant?,
)

data class HomeUiState(
    /** The hero card: the first taking entry with a known next dose. */
    val nextUp: DoseCard? = null,
    /** Active schedules: overdue first, then soonest next dose, no-frequency last. */
    val taking: List<DoseCard> = emptyList(),
    /** Dormant or stopped medications, newest first. */
    val cabinet: List<MedicationWithSchedule> = emptyList(),
)

/**
 * Backs the home screen. State recomputes on any of three triggers: the
 * medication list changing, the minute [ticker] (countdowns and slot
 * rollovers depend on wall time), and a manual refresh bumped after
 * [takeNow] — dose logs don't retrigger the medications query.
 */
class HomeViewModel(
    private val repository: MedicationRepository,
    private val clock: () -> Instant,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
    ticker: Flow<Unit> = minuteTicker(),
) : ViewModel() {

    private val refresh = MutableStateFlow(0)

    val state: StateFlow<HomeUiState> =
        combine(repository.medications(), ticker, refresh) { rows, _, _ -> rows }
            .map(::buildState)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    private suspend fun buildState(rows: List<MedicationWithSchedule>): HomeUiState {
        val now = clock()
        val taking = rows.filter { it.isActive }
            .map { row ->
                // Only TAKEN doses are ever logged in this slice, so the
                // latest log is the last take; takenAt can only be null on
                // rows written by other paths — plannedAt is the best stand-in.
                val latest = repository.latestDose(row.schedule.id)
                val lastTaken = latest?.let { it.takenAt ?: it.plannedAt }
                DoseCard(
                    item = row,
                    nextDoseAt = nextDose(row.schedule, lastTaken, now, zone),
                    lastTaken = lastTaken,
                )
            }
            // Ascending nextDoseAt puts the most overdue (earliest) instant
            // first and upcoming doses after, exactly the triage order.
            .sortedWith(compareBy(nullsLast()) { it.nextDoseAt })
        return HomeUiState(
            nextUp = taking.firstOrNull { it.nextDoseAt != null },
            taking = taking,
            cabinet = rows.filterNot { it.isActive },
        )
    }

    /** Records the dose as taken now and recomputes the schedule. */
    fun takeNow(card: DoseCard) {
        viewModelScope.launch {
            repository.logDose(
                StoredDoseLog(
                    id = UUID.randomUUID().toString(),
                    scheduleId = card.item.schedule.id,
                    plannedAt = card.nextDoseAt ?: clock(),
                    takenAt = clock(),
                    status = DoseStatus.TAKEN,
                ),
            )
            refresh.update { it + 1 }
        }
    }

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

private const val TICK_MILLIS = 60_000L

/** Emits immediately, then every minute, while the state flow is collected. */
private fun minuteTicker(): Flow<Unit> = flow {
    while (true) {
        emit(Unit)
        delay(TICK_MILLIS)
    }
}
