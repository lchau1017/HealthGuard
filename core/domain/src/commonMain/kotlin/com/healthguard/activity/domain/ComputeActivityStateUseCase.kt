package com.healthguard.activity.domain

import com.healthguard.activity.ActivityEvent
import com.healthguard.activity.ActivityFilter
import com.healthguard.activity.ActivityStats
import com.healthguard.activity.DayCount
import com.healthguard.activity.activityStats
import com.healthguard.activity.activityWindowStart
import com.healthguard.activity.adherenceResult
import com.healthguard.activity.dayCounts
import com.healthguard.home.MedicationPhase
import com.healthguard.home.phase
import com.healthguard.domain.repository.DoseLogRepository
import com.healthguard.domain.repository.MedicationRepository
import com.healthguard.domain.extraction.Frequency
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

/**
 * One "Adherence by medicine" row's structured facts for a medication with
 * activity in the window (see [ComputeActivityStateUseCase.invoke]'s breakdown
 * for what earns a row). [percent] measures the medicine against its *own*
 * schedule over the window (never a share of total doses); it is null for
 * medications without an expected-dose schedule — interval ("every N hours")
 * medications are as-needed and tracked by count, never by percent. For stopped
 * medications the expectation is clipped to the active stretch, so [percent]
 * reads "while taking".
 *
 * The presentation string for a stopped row ("Stopped 3 Jul") is deferred to
 * the ViewModel; this domain row carries only the raw [stoppedAt] instant, so
 * `:core:domain` stays free of display formatting.
 */
data class MedicationAdherenceContent(
    val name: String,
    val phase: MedicationPhase,
    val asNeeded: Boolean,
    val percent: Int?,
    val taken: Int,
    val skipped: Int,
    /** Whether [percent] reaches the 80% PDC target; null without a percent. */
    val meetsTarget: Boolean?,
    /** When the medicine was stopped while [phase] is STOPPED, else null. */
    val stoppedAt: Instant?,
)

/**
 * The tracked Activity-dashboard model for a selected window: everything the
 * view derives (stat tiles, the record grid, the per-medicine breakdown) minus
 * presentation strings. Mirrors the shape `HomeContent` plays for Home.
 */
data class ActivityContent(
    val filter: ActivityFilter,
    /** First day of the selected window; the record grid starts here too. */
    val from: LocalDate,
    val stats: ActivityStats,
    /** Per-day take counts over the selected window. */
    val dayCounts: List<DayCount>,
    /** Best adherence first; ties break alphabetically. */
    val breakdown: List<MedicationAdherenceContent>,
    /**
     * The single wall-clock instant this content was computed against, so any
     * presentation formatter (the stopped-row label) stays on the same "now"
     * as the derived facts.
     */
    val now: Instant,
)

/**
 * Computes the tracked [ActivityContent] for a selected [ActivityFilter] from
 * the repository and the wall clock. Ports `ActivityViewModel`'s `load(filter)`
 * plus the heavy `breakdown(from, now)` without any presentation strings: the
 * window bounds, stat tiles, record-grid day counts, and the per-medicine
 * adherence list with its sorting/grouping/quiet-row rules.
 */
class ComputeActivityStateUseCase(
    private val medicationRepository: MedicationRepository,
    private val doseLogRepository: DoseLogRepository,
    private val clock: () -> Instant,
    private val zone: TimeZone,
) {
    suspend operator fun invoke(filter: ActivityFilter): ActivityContent {
        val now = clock()
        val today = now.toLocalDateTime(zone).date
        val from = activityWindowStart(filter, today)
        // Exclusive upper bounds: pad past `now` so a take recorded at this
        // exact instant still counts.
        val taken = doseLogRepository.takenDosesInRange(
            from = from.atStartOfDayIn(zone),
            to = now + 1.minutes,
        )
        return ActivityContent(
            filter = filter,
            from = from,
            stats = activityStats(
                taken.map { ActivityEvent(it.drugName, it.takenAt) },
                now,
                zone,
            ),
            dayCounts = dayCounts(taken.map { it.takenAt }, zone),
            breakdown = breakdown(from.atStartOfDayIn(zone), now),
            now = now,
        )
    }

    /**
     * One row per medication with anything to say about the window — the
     * list is an activity breakdown, not a cabinet inventory. Never-started
     * medicines have no row (their phase lives on the home chip and the
     * detail header); as-needed and unscheduled medicines only earn one by
     * being taken in the window; stopped medicines by having any log in it.
     * Actively scheduled percent rows always show — an untouched schedule
     * scoring 0% is exactly the information the list exists for.
     *
     * Adherence is measured against each schedule over the window: silent
     * days count, so a visible grid gap can never coexist with a 100% figure
     * (expected doses clip themselves to the active stretch, so stopped rows
     * score their while-taking window). Actively-taken percent rows sort
     * best first; count rows, then stopped rows follow, each group
     * alphabetical.
     */
    private suspend fun breakdown(from: Instant, now: Instant): List<MedicationAdherenceContent> =
        medicationRepository.medications().first()
            .mapNotNull { row ->
                val phase = row.schedule.phase
                if (phase == MedicationPhase.NOT_STARTED) return@mapNotNull null
                // Padded past `now` like the queries above; expected doses
                // are computed against `now` so future slots never count.
                val logs = doseLogRepository.dosesInRange(row.schedule.id, from, now + 1.minutes)
                val result = adherenceResult(row.schedule, logs, from, now, zone)
                val quiet = when {
                    phase == MedicationPhase.STOPPED -> logs.isEmpty()
                    result.percent == null -> result.taken == 0
                    else -> false
                }
                if (quiet) return@mapNotNull null
                MedicationAdherenceContent(
                    name = row.medication.drugName,
                    phase = phase,
                    asNeeded = row.schedule.frequency is Frequency.EveryHours,
                    percent = result.percent,
                    taken = result.taken,
                    skipped = result.skipped,
                    meetsTarget = result.meetsTarget,
                    stoppedAt = if (phase == MedicationPhase.STOPPED) {
                        row.schedule.stoppedAt
                    } else {
                        null
                    },
                )
            }
            .sortedWith(
                compareBy<MedicationAdherenceContent> { it.rank }
                    .thenByDescending { it.percent ?: 0 }
                    .thenBy { it.name },
            )

    /** Group order of the breakdown list; see [breakdown]. */
    private val MedicationAdherenceContent.rank: Int
        get() = when {
            phase == MedicationPhase.STOPPED -> 2
            percent == null -> 1
            else -> 0
        }
}
