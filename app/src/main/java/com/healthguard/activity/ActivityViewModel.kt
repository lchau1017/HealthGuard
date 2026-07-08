@file:OptIn(ExperimentalTime::class)

package com.healthguard.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthguard.shared.data.MedicationRepository
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

/** Time windows on the Activity dashboard. */
enum class ActivityFilter { ALL, DAYS_30, DAYS_7 }

/** One "By medicine" row: adherence over the current window. */
data class MedicationAdherence(val name: String, val percent: Int)

private val EMPTY_STATS = ActivityStats(0, 0, 0, 0, null, null)

data class ActivityUiState(
    val filter: ActivityFilter = ActivityFilter.ALL,
    /** First day of the stats window (not the heat map's). */
    val from: LocalDate? = null,
    val stats: ActivityStats = EMPTY_STATS,
    /** First day of the fixed 16-week heat-map record (a Monday). */
    val heatFrom: LocalDate? = null,
    /** Per-day take counts over the 16-week heat-map record. */
    val dayCounts: List<DayCount> = emptyList(),
    /** Best adherence first; ties break alphabetically. */
    val breakdown: List<MedicationAdherence> = emptyList(),
)

/** Days shown by the 7-day window, today included. */
private const val WEEK_WINDOW_DAYS = 7

/** Days shown by the 30-day window, today included. */
private const val MONTH_WINDOW_DAYS = 30

/**
 * "All" is capped at the last 12 months rather than "since the first take":
 * one bounded query, bounded stats, and a year is the natural ceiling for a
 * streak/history dashboard.
 */
private const val ALL_WINDOW_MONTHS = 12

/** The heat map always shows this fixed record, whatever the filter. */
private const val HEAT_MAP_WEEKS = 16

/**
 * Backs the Activity dashboard. The stat tiles and per-medicine adherence
 * follow the selected window; the heat map is a fixed 16-week record. Data
 * loads once per [filter] change plus explicit [reload]s (the host calls it
 * whenever the screen is (re)entered, catching takes recorded elsewhere
 * since the last load).
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

    private fun load(filter: ActivityFilter) {
        viewModelScope.launch {
            val now = clock()
            val today = now.toLocalDateTime(zone).date
            val from = when (filter) {
                ActivityFilter.ALL -> today.minus(ALL_WINDOW_MONTHS, DateTimeUnit.MONTH)
                ActivityFilter.DAYS_30 -> today.minus(MONTH_WINDOW_DAYS - 1, DateTimeUnit.DAY)
                ActivityFilter.DAYS_7 -> today.minus(WEEK_WINDOW_DAYS - 1, DateTimeUnit.DAY)
            }
            // Exclusive upper bounds: pad past `now` so a take recorded at
            // this exact instant still counts.
            val taken = repository.takenDosesInRange(
                from = from.atStartOfDayIn(zone),
                to = now + 1.minutes,
            )
            val heatFrom = mondayOf(today).minus(HEAT_MAP_WEEKS - 1, DateTimeUnit.WEEK)
            val heatTaken = repository.takenDosesInRange(
                from = heatFrom.atStartOfDayIn(zone),
                to = now + 1.minutes,
            )
            val tallies = repository.adherenceTallies(
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
                heatFrom = heatFrom,
                dayCounts = dayCounts(heatTaken.map { it.takenAt }, zone),
                breakdown = tallies
                    .mapNotNull { tally ->
                        adherencePercent(tally.taken, tally.missed)
                            ?.let { MedicationAdherence(tally.drugName, it) }
                    }
                    .sortedWith(
                        compareByDescending<MedicationAdherence> { it.percent }.thenBy { it.name },
                    ),
            )
        }
    }
}
