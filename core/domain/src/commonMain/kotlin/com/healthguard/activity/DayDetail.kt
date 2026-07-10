package com.healthguard.activity

import com.healthguard.detail.unansweredSlots
import com.healthguard.domain.model.DoseLogWithMedication
import com.healthguard.domain.model.DoseStatus
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * One medicine's answer to one day, on the day-detail sheet: the recorded
 * takes with their times, plus the day's non-taken outcomes as counts.
 * [notRecorded] is the schedule's unanswered slots — expected doses nothing
 * was logged against ([unansweredSlots]), the same derivation behind the
 * detail history's "Not recorded" rows.
 */
data class DayMedicineLine(
    val medicationId: String,
    /** "Cetirizine 10 mg" — drug name plus dosage when known. */
    val name: String,
    /** Take times ascending, local to the display zone. */
    val takenTimes: List<LocalTime>,
    val skipped: Int,
    val missed: Int,
    val notRecorded: Int,
)

/**
 * Everything the day-detail sheet says about one tapped day: per-medicine
 * lines for medicines with any record that day, and the count of expected
 * doses belonging to medicines that recorded nothing at all —
 * [expectedNotRecorded] keeps a fully silent (but in-treatment) medicine
 * from disappearing from the day entirely.
 */
data class DayDetail(
    val date: LocalDate,
    /** Alphabetical by medicine name; empty when nothing was recorded. */
    val lines: List<DayMedicineLine>,
    val expectedNotRecorded: Int,
)

/**
 * Derives the sheet model for [date]. [logs] are that day's logs across
 * medicines (effective time within the day); [expectedByMedication] maps a
 * medication id to the dose instants its schedule owed that day, already
 * clipped so today's future (or still-in-grace) slots are absent. Pure —
 * repositories and clocks stay with the callers.
 */
fun dayDetail(
    date: LocalDate,
    logs: List<DoseLogWithMedication>,
    expectedByMedication: Map<String, List<Instant>>,
    zone: TimeZone,
): DayDetail {
    val byMedication = logs
        .filter { it.status != DoseStatus.PENDING }
        .groupBy { it.medicationId }
    val lines = byMedication.map { (medicationId, medLogs) ->
        val first = medLogs.first()
        DayMedicineLine(
            medicationId = medicationId,
            name = listOfNotNull(first.drugName, first.dosage).joinToString(" "),
            takenTimes = medLogs
                .filter { it.status == DoseStatus.TAKEN }
                .map { (it.takenAt ?: it.plannedAt).toLocalDateTime(zone).time }
                .sorted(),
            skipped = medLogs.count { it.status == DoseStatus.SKIPPED },
            missed = medLogs.count { it.status == DoseStatus.MISSED },
            notRecorded = unansweredSlots(
                expectedByMedication[medicationId].orEmpty(),
                medLogs.map { it.plannedAt },
            ).size,
        )
    }.sortedBy { it.name }
    val silentExpected = expectedByMedication
        .filterKeys { it !in byMedication }
        .values.sumOf { it.size }
    return DayDetail(date = date, lines = lines, expectedNotRecorded = silentExpected)
}
