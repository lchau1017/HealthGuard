package com.healthguard.detail.domain

import com.healthguard.activity.AdherenceResult
import com.healthguard.activity.DayCount
import com.healthguard.activity.DoseDayStatus
import com.healthguard.activity.dayCounts
import com.healthguard.activity.doseDayStatusByDay
import com.healthguard.activity.mondayOf
import com.healthguard.detail.HistoryEntry
import com.healthguard.detail.SLOT_MATCH_WINDOW
import com.healthguard.detail.historyWithGaps
import com.healthguard.domain.model.DoseStatus
import com.healthguard.domain.repository.DoseLogRepository
import com.healthguard.domain.model.MedicationWithSchedule
import com.healthguard.domain.model.StoredDoseLog
import com.healthguard.domain.model.StoredSchedule
import com.healthguard.domain.schedule.expectedDoseTimes
import com.healthguard.domain.schedule.nextDose
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

/** History rows shown on the detail page. */
private const val HISTORY_LIMIT = 30

/** Heat-map columns on the detail page (current week included). */
private const val HEAT_MAP_WEEKS = 16

/** How far back derived "Not recorded" rows reach. */
private val GAP_LOOKBACK = 14.days

/**
 * The tracked detail-screen model for one medication: everything the view
 * derives continuously from the repository, minus the editable form fields and
 * any presentation strings (the ViewModel keeps the form state and formats the
 * display). Mirrors the shape [HomeContent] plays for Home.
 */
data class DetailContent(
    val item: MedicationWithSchedule,
    /** When the next dose is due; past = overdue; null = no frequency. */
    val nextDoseAt: Instant?,
    val lastTakenAt: Instant?,
    /**
     * Latest dose logs newest first, interleaved with derived "Not recorded"
     * rows for recent expected slots nothing answered, capped at 30 entries.
     */
    val history: List<HistoryEntry>,
    /** Per-day status against the schedule over the heat-map window. */
    val dayStatuses: Map<LocalDate, DoseDayStatus>,
    /** Per-day take counts over the window — the as-needed heat-map fallback. */
    val dayTakeCounts: List<DayCount>,
    /** Schedule-based adherence over the heat-map window. */
    val adherence: AdherenceResult,
    /** First day of the heat-map window (a Monday). */
    val historyFrom: LocalDate,
    /**
     * The single wall-clock instant this content was computed against, so any
     * presentation formatting stays on the same "now" as the derived facts.
     */
    val now: Instant,
)

/**
 * Computes the tracked [DetailContent] for one medication from the repository
 * and the wall clock. Ports `DetailViewModel`'s init-collect calculation
 * without its form seeding or presentation strings: last take, next dose, the
 * gap-interleaved history, the heat-map day statuses/counts and adherence over
 * the 16-week window.
 */
class ComputeDetailStateUseCase(
    private val repository: DoseLogRepository,
    private val clock: () -> Instant,
    private val zone: TimeZone,
) {
    suspend operator fun invoke(item: MedicationWithSchedule): DetailContent {
        // Last take = the newest TAKEN log by effective time. Skipped and
        // missed rows must never delay the next dose or read as a take.
        val latest = repository.latestTakenDose(item.schedule.id)
        val lastTaken = latest?.let { it.takenAt ?: it.plannedAt }
        // One wall-clock reading keeps every derived instant on the same "now".
        val now = clock()
        val today = now.toLocalDateTime(zone).date
        val historyFrom = mondayOf(today).minus(HEAT_MAP_WEEKS - 1, DateTimeUnit.WEEK)
        val windowFrom = historyFrom.atStartOfDayIn(zone)
        // Range query is on plannedAt (exclusive upper bound, hence the pad);
        // statuses are then bucketed by their effective day.
        val windowLogs = repository.dosesInRange(
            scheduleId = item.schedule.id,
            from = windowFrom,
            to = now + 1.minutes,
        )
        // What the schedule owed over the window; `to = now` so today's future
        // slots are never counted against the user.
        val expected = expectedDoseTimes(item.schedule, windowFrom, now, zone)
        return DetailContent(
            item = item,
            nextDoseAt = nextDose(item.schedule, lastTaken, now, zone),
            lastTakenAt = lastTaken,
            history = historyEntries(item.schedule, windowLogs, now),
            dayStatuses = doseDayStatusByDay(expected, windowLogs, zone),
            dayTakeCounts = dayCounts(
                windowLogs.filter { it.status == DoseStatus.TAKEN }
                    .map { it.takenAt ?: it.plannedAt },
                zone,
            ),
            adherence = AdherenceResult(
                taken = windowLogs.count { it.status == DoseStatus.TAKEN },
                expected = expected.size,
                skipped = windowLogs.count { it.status == DoseStatus.SKIPPED },
            ),
            historyFrom = historyFrom,
            now = now,
        )
    }

    /**
     * The recent-list rows, newest first, capped at [HISTORY_LIMIT]: the last
     * 14 days' logs interleaved with derived "Not recorded" slots, then older
     * logs from the capped recent query. Gap slots are matched against the
     * complete window logs, not just the displayed recent ones: a dose up to
     * [SLOT_MATCH_WINDOW] before the 14-day cutoff can answer a slot just
     * after it and must not leave a phantom gap row at the boundary.
     */
    private suspend fun historyEntries(
        schedule: StoredSchedule,
        windowLogs: List<StoredDoseLog>,
        now: Instant,
    ): List<HistoryEntry> {
        val cutoff = now - GAP_LOOKBACK
        val recentLogs = windowLogs.filter { (it.takenAt ?: it.plannedAt) >= cutoff }
        val gapSlots = expectedDoseTimes(schedule, cutoff, now - SLOT_MATCH_WINDOW, zone)
        val older = repository.recentDoses(schedule.id, HISTORY_LIMIT)
            .filter { (it.takenAt ?: it.plannedAt) < cutoff }
            .map { HistoryEntry.Logged(it) }
        return (historyWithGaps(recentLogs, gapSlots, matchLogs = windowLogs) + older)
            .take(HISTORY_LIMIT)
    }
}
