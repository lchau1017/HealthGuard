@file:OptIn(ExperimentalTime::class)

package com.healthguard.activity

import com.healthguard.detail.unansweredSlots
import com.healthguard.format.timeLabel
import com.healthguard.shared.data.DoseLogWithMedication
import com.healthguard.shared.data.DoseStatus
import kotlin.time.ExperimentalTime
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

/** "Cetirizine 10 mg — 2 taken (9:04 AM · 9:12 PM)"; no times when none. */
fun dayLineTitle(line: DayMedicineLine): String {
    val count = line.takenTimes.size
    val times = if (count == 0) {
        ""
    } else {
        " (${line.takenTimes.joinToString(" · ") { timeLabel(it) }})"
    }
    return "${line.name} — $count taken$times"
}

/** The muted lines under a title: "1 skipped", "2 missed", "1 not recorded". */
fun dayLineAnnotations(line: DayMedicineLine): List<String> = buildList {
    if (line.skipped > 0) add("${line.skipped} skipped")
    if (line.missed > 0) add("${line.missed} missed")
    if (line.notRecorded > 0) add("${line.notRecorded} not recorded")
}

/** The aggregate line for expected doses of medicines with no record at all. */
fun expectedNotRecordedText(count: Int): String = "$count expected but not recorded"
