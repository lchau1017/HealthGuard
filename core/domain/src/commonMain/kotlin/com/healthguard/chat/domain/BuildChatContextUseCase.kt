package com.healthguard.chat.domain

import com.healthguard.activity.DAYS_PER_WEEK
import com.healthguard.activity.mondayOf
import com.healthguard.activity.adherenceResult
import com.healthguard.chat.ChatContext
import com.healthguard.domain.model.MedicationWithSchedule
import com.healthguard.domain.repository.DoseLogRepository
import com.healthguard.domain.repository.MedicationRepository
import com.healthguard.domain.schedule.nextDose
import com.healthguard.home.phase
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/** How far back the assistant can see: the same 30 days the Activity default shows. */
private val CONTEXT_WINDOW = 30.days

/** Calendar weeks in the weekly summary, current (partial) week included. */
private const val WEEKS_REPORTED = 5

/**
 * Snapshots the adherence facts the chat assistant may answer from, computed
 * by the same domain math that drives the screens ([adherenceResult],
 * [nextDose]) so a chat answer can never contradict the heat map. The model
 * only phrases these numbers; it is never asked to derive them.
 */
class BuildChatContextUseCase(
    private val medicationRepository: MedicationRepository,
    private val doseLogRepository: DoseLogRepository,
    private val clock: () -> Instant,
    private val zone: TimeZone,
) {
    suspend operator fun invoke(): ChatContext {
        val now = clock()
        val from = now - CONTEXT_WINDOW
        // Exclusive upper bounds: pad past `now` so a dose recorded at this
        // exact instant still counts (same rationale as the Activity queries).
        val queryTo = now + 1.minutes
        val rows = medicationRepository.medications().first()
        return ChatContext(
            generatedAt = now,
            medications = rows.map { row -> medicationFacts(row, from, now, queryTo) },
            weeks = weekFacts(rows, now),
            events = doseLogRepository.doseLogsWithMedicationInRange(from, queryTo)
                .map { log ->
                    ChatContext.DoseEventFact(
                        date = (log.takenAt ?: log.plannedAt).toLocalDateTime(zone).date,
                        drugName = log.drugName,
                        status = log.status,
                    )
                },
        )
    }

    private suspend fun medicationFacts(
        row: MedicationWithSchedule,
        from: Instant,
        now: Instant,
        queryTo: Instant,
    ): ChatContext.MedicationFacts {
        val logs = doseLogRepository.dosesInRange(row.schedule.id, from, queryTo)
        val lastTaken = doseLogRepository.latestTakenDose(row.schedule.id)
            ?.let { it.takenAt ?: it.plannedAt }
        return ChatContext.MedicationFacts(
            name = row.medication.drugName,
            dosage = row.medication.dosage,
            frequency = row.schedule.frequency,
            phase = row.schedule.phase,
            adherence = adherenceResult(row.schedule, logs, from, now, zone),
            nextDoseAt = nextDose(row.schedule, lastTaken, now, zone),
        )
    }

    /**
     * Totals per calendar week (Monday starts), oldest first. The current
     * week's expectation is clipped to `now`, so slots later today or later
     * this week never read as missed.
     */
    private suspend fun weekFacts(
        rows: List<MedicationWithSchedule>,
        now: Instant,
    ): List<ChatContext.WeekFacts> {
        val currentMonday = mondayOf(now.toLocalDateTime(zone).date)
        return (WEEKS_REPORTED - 1 downTo 0).map { weeksBack ->
            val weekStart = currentMonday.minus(weeksBack * DAYS_PER_WEEK, DateTimeUnit.DAY)
            val from = weekStart.atStartOfDayIn(zone)
            val weekEnd = weekStart.plus(DAYS_PER_WEEK, DateTimeUnit.DAY).atStartOfDayIn(zone)
            val to = minOf(weekEnd, now)
            // Pad the log query only when the window ends at `now` — a past
            // week's boundary must not pull in the next Monday's first logs.
            val logsTo = if (to == now) to + 1.minutes else to
            var expected = 0
            var taken = 0
            var skipped = 0
            rows.forEach { row ->
                val logs = doseLogRepository.dosesInRange(row.schedule.id, from, logsTo)
                val result = adherenceResult(row.schedule, logs, from, to, zone)
                expected += result.expected
                taken += result.taken
                skipped += result.skipped
            }
            ChatContext.WeekFacts(
                weekStart = weekStart,
                expected = expected,
                taken = taken,
                skipped = skipped,
            )
        }
    }
}
