@file:OptIn(ExperimentalTime::class)

package com.healthguard.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthguard.demo.DemoDataSeeder
import com.healthguard.dose.RecordedTake
import com.healthguard.dose.isDoubleDose
import com.healthguard.dose.recordTakenDose
import com.healthguard.format.DoseRowStatus
import com.healthguard.format.doseRowStatus
import com.healthguard.shared.data.MedicationRepository
import com.healthguard.shared.data.MedicationWithSchedule
import com.healthguard.shared.domain.nextDose
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

/** A schedule the user has started and not stopped. */
val MedicationWithSchedule.isActive: Boolean
    get() = schedule.startedAt != null && schedule.stoppedAt == null

/** One "Taking now" entry: the medication plus its dose timing. */
data class DoseCard(
    val item: MedicationWithSchedule,
    /** When the next dose is due; past = overdue; null = no frequency. */
    val nextDoseAt: Instant?,
    val lastTaken: Instant?,
    /** Due now or overdue (as of the state's computation time). */
    val isDue: Boolean = false,
    /** What the row's trailing status shows (Take button, "Taken ✓", next time). */
    val status: DoseRowStatus = DoseRowStatus.None,
)

/** The due-now banner: the most overdue card plus how many others are due. */
data class DueAlert(val card: DoseCard, val othersDueCount: Int)

data class HomeUiState(
    /** Non-null only while at least one taking entry is due now or overdue. */
    val dueAlert: DueAlert? = null,
    /** Active schedules: overdue first, then soonest next dose, no-frequency last. */
    val taking: List<DoseCard> = emptyList(),
    /** Dormant or stopped medications, newest first. */
    val cabinet: List<MedicationWithSchedule> = emptyList(),
    /** How many taking entries are due now or overdue. */
    val dueCount: Int = 0,
    /** The last seven days ending today, oldest first (the week circles). */
    val weekDays: List<WeekDay> = emptyList(),
    /** Pre-formatted line under the week circles. */
    val weekCaption: String = "",
)

/** A takeNow blocked by the double-dose window, awaiting user confirmation. */
data class TakeConfirmation(val card: DoseCard, val minutesAgo: Long)

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

    private val _takeConfirm = MutableStateFlow<TakeConfirmation?>(null)

    /** Non-null while a double-dose confirmation dialog should be showing. */
    val takeConfirm: StateFlow<TakeConfirmation?> = _takeConfirm.asStateFlow()

    private val _recentTake = MutableStateFlow<RecordedTake?>(null)

    /** Non-null while an undo snackbar for the last take should be showing. */
    val recentTake: StateFlow<RecordedTake?> = _recentTake.asStateFlow()

    val state: StateFlow<HomeUiState> =
        combine(repository.medications(), ticker, refresh) { rows, _, _ -> rows }
            .map(::buildState)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    private suspend fun buildState(rows: List<MedicationWithSchedule>): HomeUiState {
        val now = clock()
        val today = now.toLocalDateTime(zone).date
        val taking = rows.filter { it.isActive }
            .map { row ->
                // Only TAKEN doses are ever logged in this slice, so the
                // latest log is the last take; takenAt can only be null on
                // rows written by other paths — plannedAt is the best stand-in.
                val latest = repository.latestDose(row.schedule.id)
                val lastTaken = latest?.let { it.takenAt ?: it.plannedAt }
                val nextDoseAt = nextDose(row.schedule, lastTaken, now, zone)
                val isDue = nextDoseAt != null && nextDoseAt - now < 1.minutes
                DoseCard(
                    item = row,
                    nextDoseAt = nextDoseAt,
                    lastTaken = lastTaken,
                    isDue = isDue,
                    status = doseRowStatus(nextDoseAt, lastTaken, now, zone, isDue),
                )
            }
            // Ascending nextDoseAt puts the most overdue (earliest) instant
            // first and upcoming doses after, exactly the triage order.
            .sortedWith(compareBy(nullsLast()) { it.nextDoseAt })
        val dueCount = taking.count { it.isDue }

        // Week card window: the last seven days ending today. Query upper
        // bound is exclusive, so pad past `now` to include a take recorded
        // at this exact instant.
        val weekLogs = repository.doseLogsInRange(
            from = today.minus(WEEK_WINDOW_DAYS - 1, DateTimeUnit.DAY).atStartOfDayIn(zone),
            to = now + 1.minutes,
        )
        val weekDays = weekDayStates(weekLogs, today, zone)
        // Today is complete once no active schedule has a dose left to take
        // today (due/overdue doses included).
        val todayComplete = taking.none { card ->
            val next = card.nextDoseAt ?: return@none false
            next.toLocalDateTime(zone).date <= today
        }

        return HomeUiState(
            dueAlert = taking.firstOrNull { it.isDue }?.let { DueAlert(it, dueCount - 1) },
            taking = taking,
            cabinet = rows.filterNot { it.isActive },
            dueCount = dueCount,
            weekDays = weekDays,
            weekCaption = weekCaption(weekDays, todayComplete),
        )
    }

    /**
     * Records the dose as taken now — unless the last take was within the
     * double-dose window, in which case [takeConfirm] is raised instead and
     * nothing is logged until [confirmTakeAnyway].
     */
    fun takeNow(card: DoseCard) {
        val now = clock()
        val lastTaken = card.lastTaken
        if (isDoubleDose(lastTaken, now)) {
            _takeConfirm.value = TakeConfirmation(
                card = card,
                minutesAgo = (now - lastTaken!!).inWholeMinutes,
            )
            return
        }
        record(card)
    }

    /** User accepted the double-dose warning: record it after all. */
    fun confirmTakeAnyway() {
        val pending = _takeConfirm.value ?: return
        _takeConfirm.value = null
        record(pending.card)
    }

    fun dismissTakeConfirm() {
        _takeConfirm.value = null
    }

    /** Undoes a take recorded via [takeNow]/[confirmTakeAnyway]. */
    fun undoTake(doseId: String) {
        viewModelScope.launch {
            repository.deleteDoseLog(doseId)
            _recentTake.value = null
            refresh.update { it + 1 }
        }
    }

    /** The undo snackbar timed out or was dismissed. */
    fun clearRecentTake() {
        _recentTake.value = null
    }

    private fun record(card: DoseCard) {
        viewModelScope.launch {
            val doseId = recordTakenDose(
                repository = repository,
                scheduleId = card.item.schedule.id,
                plannedAt = card.nextDoseAt,
                now = clock(),
            )
            _recentTake.value = RecordedTake(doseId, card.item.medication.drugName)
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

    /** Debug builds only: populate/remove demo history so the dashboard demos well. */
    fun loadDemoData() {
        viewModelScope.launch {
            DemoDataSeeder.seed(repository, clock(), zone)
            refresh.update { it + 1 }
        }
    }

    fun removeDemoData() {
        viewModelScope.launch {
            DemoDataSeeder.remove(repository)
            refresh.update { it + 1 }
        }
    }
}

/** Days covered by the home "This week" card, today included. */
private const val WEEK_WINDOW_DAYS = 7

private const val TICK_MILLIS = 60_000L

/** Emits immediately, then every minute, while the state flow is collected. */
private fun minuteTicker(): Flow<Unit> = flow {
    while (true) {
        emit(Unit)
        delay(TICK_MILLIS)
    }
}
