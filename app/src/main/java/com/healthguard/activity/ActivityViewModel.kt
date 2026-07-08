@file:OptIn(ExperimentalTime::class)

package com.healthguard.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthguard.home.MedicationPhase
import com.healthguard.home.phase
import com.healthguard.home.phaseChipText
import com.healthguard.detail.SLOT_MATCH_WINDOW
import com.healthguard.shared.data.MedicationRepository
import com.healthguard.shared.domain.expectedDoseTimes
import com.healthguard.shared.extraction.Frequency
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * One "Adherence by medicine" row: every medication gets one, typed by its
 * treatment [phase] — nothing is silently omitted. [percent] measures the
 * medicine against its *own* schedule over the window (never a share of
 * total doses); it is null for medications without an expected-dose schedule
 * — interval ("every N hours") medications are as-needed and tracked by
 * count, never by percent. For stopped medications the expectation is
 * clipped to the active stretch, so [percent] reads "while taking".
 */
data class MedicationAdherence(
    val name: String,
    val phase: MedicationPhase,
    val asNeeded: Boolean,
    val percent: Int?,
    val taken: Int,
    val skipped: Int,
    /** Whether [percent] reaches the 80% PDC target; null without a percent. */
    val meetsTarget: Boolean?,
    /** "Stopped 3 Jul" while [phase] is STOPPED, else null. */
    val stoppedText: String?,
)

private val EMPTY_STATS = ActivityStats(0, 0, 0, 0, null, null)

data class ActivityUiState(
    val filter: ActivityFilter = ActivityFilter.DAYS_30,
    /** First day of the selected window; the record grid starts here too. */
    val from: LocalDate? = null,
    val stats: ActivityStats = EMPTY_STATS,
    /** Per-day take counts over the selected window. */
    val dayCounts: List<DayCount> = emptyList(),
    /** Best adherence first; ties break alphabetically. */
    val breakdown: List<MedicationAdherence> = emptyList(),
    /** Non-null while the day-detail sheet for a tapped grid day shows. */
    val dayDetail: DayDetail? = null,
)

/**
 * Backs the Activity dashboard. Everything — stat tiles, the record grid,
 * and the per-medicine adherence — recomputes for the selected window
 * ([activityWindowStart]). Data loads once per [filter] change plus explicit
 * [reload]s (the host calls it whenever the screen is (re)entered, catching
 * takes recorded elsewhere since the last load); the filter itself lives in
 * the retained view-model state, so it survives tab switches.
 *
 * The record grid deliberately stays count-based raw activity across all
 * medications (how much was logged, GitHub-style) — schedule-based
 * completeness only drives the per-medication views (detail heat map, week
 * circles) where "which schedule?" has a single answer.
 */
class ActivityViewModel(
    private val repository: MedicationRepository,
    private val clock: () -> Instant,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) : ViewModel() {

    private val _state = MutableStateFlow(ActivityUiState())
    val state: StateFlow<ActivityUiState> = _state.asStateFlow()

    init {
        reload()
    }

    fun setFilter(filter: ActivityFilter) {
        if (filter == _state.value.filter) return
        load(filter)
    }

    /** Re-queries the current window. */
    fun reload() = load(_state.value.filter)

    /**
     * Loads the tapped grid day's detail sheet: that day's logs grouped per
     * medicine, plus what in-treatment schedules expected of it. Slots still
     * inside the 90-minute answer window are not "not recorded" yet.
     */
    fun selectDay(date: LocalDate) {
        viewModelScope.launch {
            val now = clock()
            val dayStart = date.atStartOfDayIn(zone)
            val dayEnd = date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone)
            val logs = repository.doseLogsWithMedicationInRange(dayStart, dayEnd)
            val expectedByMedication = repository.medications().first()
                .associate { row ->
                    row.medication.id to expectedDoseTimes(
                        row.schedule,
                        dayStart,
                        minOf(dayEnd, now - SLOT_MATCH_WINDOW),
                        zone,
                    )
                }
                .filterValues { it.isNotEmpty() }
            _state.update { it.copy(dayDetail = dayDetail(date, logs, expectedByMedication, zone)) }
        }
    }

    /** The day-detail sheet was dismissed. */
    fun dismissDayDetail() = _state.update { it.copy(dayDetail = null) }

    private fun load(filter: ActivityFilter) {
        viewModelScope.launch {
            val now = clock()
            val today = now.toLocalDateTime(zone).date
            val from = activityWindowStart(filter, today)
            // Exclusive upper bounds: pad past `now` so a take recorded at
            // this exact instant still counts.
            val taken = repository.takenDosesInRange(
                from = from.atStartOfDayIn(zone),
                to = now + 1.minutes,
            )
            _state.value = ActivityUiState(
                filter = filter,
                from = from,
                stats = activityStats(
                    taken.map { ActivityEvent(it.drugName, it.takenAt) },
                    now,
                    zone,
                ),
                dayCounts = dayCounts(taken.map { it.takenAt }, zone),
                breakdown = breakdown(from.atStartOfDayIn(zone), now),
            )
        }
    }

    /**
     * One row per medication — every medication, typed by phase; silently
     * dropping rows made the list read as broken. Adherence is measured
     * against each schedule over the window: silent days count, so a
     * visible heat-map gap can never coexist with a 100% figure (expected
     * doses clip themselves to the active stretch, so stopped rows score
     * their while-taking window). Actively-taken percent rows sort best
     * first; percent-less taking rows, stopped, then never-started rows
     * follow, each group alphabetical.
     */
    private suspend fun breakdown(from: Instant, now: Instant): List<MedicationAdherence> =
        repository.medications().first()
            .map { row ->
                // Padded past `now` like the queries above; expected doses
                // are computed against `now` so future slots never count.
                val logs = repository.dosesInRange(row.schedule.id, from, now + 1.minutes)
                val result = adherenceResult(row.schedule, logs, from, now, zone)
                val phase = row.schedule.phase
                MedicationAdherence(
                    name = row.medication.drugName,
                    phase = phase,
                    asNeeded = row.schedule.frequency is Frequency.EveryHours,
                    percent = result.percent,
                    taken = result.taken,
                    skipped = result.skipped,
                    meetsTarget = result.meetsTarget,
                    stoppedText = if (phase == MedicationPhase.STOPPED) {
                        phaseChipText(row.schedule, now, zone)
                    } else {
                        null
                    },
                )
            }
            .sortedWith(
                compareBy<MedicationAdherence> { it.rank }
                    .thenByDescending { it.percent ?: 0 }
                    .thenBy { it.name },
            )

    /** Group order of the breakdown list; see [breakdown]. */
    private val MedicationAdherence.rank: Int
        get() = when {
            phase == MedicationPhase.NOT_STARTED -> 3
            phase == MedicationPhase.STOPPED -> 2
            percent == null -> 1
            else -> 0
        }
}
