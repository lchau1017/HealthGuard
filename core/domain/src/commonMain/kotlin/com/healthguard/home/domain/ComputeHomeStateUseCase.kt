@file:OptIn(ExperimentalTime::class)

package com.healthguard.home.domain

import com.healthguard.activity.DAYS_PER_WEEK
import com.healthguard.home.WeekDay
import com.healthguard.home.isActive
import com.healthguard.home.todayHasPendingSlots
import com.healthguard.home.weekDayStates
import com.healthguard.domain.repository.DoseLogRepository
import com.healthguard.domain.model.MedicationWithSchedule
import com.healthguard.domain.schedule.nextDose
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

/** One "Taking now" entry's data: the medication plus its computed dose timing. */
data class DoseCardContent(
    val item: MedicationWithSchedule,
    /** When the next dose is due; past = overdue; null = no frequency. */
    val nextDoseAt: Instant?,
    val lastTaken: Instant?,
    /** Due now or overdue (as of [now]). */
    val isDue: Boolean,
)

/**
 * The pure home-screen model: everything the view needs except the
 * presentation strings (row status, week caption, due banner), which the
 * ViewModel formats. Ordering and counts are settled here.
 */
data class HomeContent(
    /** Active schedules: overdue first, then soonest next dose, no-frequency last. */
    val taking: List<DoseCardContent>,
    /** Dormant or stopped medications, in the order [rows] arrived (newest first). */
    val cabinet: List<MedicationWithSchedule>,
    /** How many taking entries are due now or overdue. */
    val dueCount: Int,
    /** The last seven days ending today, oldest first (the week circles). */
    val weekDays: List<WeekDay>,
    /** Whether any schedule still owes a slot later today (feeds the week caption). */
    val todayPending: Boolean,
    /**
     * The single wall-clock instant this content was computed against. The
     * presentation layer formats row status from this same [now], so the
     * displayed countdowns can't drift from the [DoseCardContent.isDue] and
     * week-strip facts computed here.
     */
    val now: Instant,
)

/**
 * Computes [HomeContent] from the current medication [rows] and the wall clock.
 * Ports the ViewModel's `buildState` calculation without its presentation
 * strings: active rows become dose cards (next dose, last take, due), sorted
 * overdue-first; inactive rows fall to the cabinet in arrival order; the week
 * strip and today-pending flag come from every schedule's owed slots.
 */
class ComputeHomeStateUseCase(
    private val repository: DoseLogRepository,
    private val clock: () -> Instant,
    private val zone: TimeZone,
) {
    suspend operator fun invoke(rows: List<MedicationWithSchedule>): HomeContent {
        // One wall-clock reading per computation keeps every derived instant
        // (next dose, due window, week strip) on the same "now".
        val now = clock()
        val today = now.toLocalDateTime(zone).date
        val taking = rows.filter { it.isActive }
            .map { row ->
                // Last take = the newest TAKEN log by effective time. Skipped
                // and missed rows (demo data seeds them) must never delay the
                // next dose or trip the double-dose guard. takenAt can only be
                // null on rows written by other paths — plannedAt stands in.
                val latest = repository.latestTakenDose(row.schedule.id)
                val lastTaken = latest?.let { it.takenAt ?: it.plannedAt }
                val nextDoseAt = nextDose(row.schedule, lastTaken, now, zone)
                val isDue = nextDoseAt != null && nextDoseAt - now < 1.minutes
                DoseCardContent(
                    item = row,
                    nextDoseAt = nextDoseAt,
                    lastTaken = lastTaken,
                    isDue = isDue,
                )
            }
            // Ascending nextDoseAt puts the most overdue (earliest) instant
            // first and upcoming doses after, exactly the triage order.
            .sortedWith(compareBy(nullsLast()) { it.nextDoseAt })
        val dueCount = taking.count { it.isDue }

        // Week card window: the last seven days ending today. Query upper bound
        // is exclusive, so pad past `now` to include a take at this exact instant.
        val weekLogs = repository.doseLogsInRange(
            from = today.minus(DAYS_PER_WEEK - 1, DateTimeUnit.DAY).atStartOfDayIn(zone),
            to = now + 1.minutes,
        )
        // Every schedule goes in: expected doses clip themselves to each
        // schedule's active stretch, so a medication stopped mid-week still
        // counts for the days it was owed.
        val schedules = rows.map { it.schedule }
        val weekDays = weekDayStates(schedules, weekLogs, now, zone)

        return HomeContent(
            taking = taking,
            cabinet = rows.filterNot { it.isActive },
            dueCount = dueCount,
            weekDays = weekDays,
            todayPending = todayHasPendingSlots(schedules, now, zone),
            now = now,
        )
    }
}
