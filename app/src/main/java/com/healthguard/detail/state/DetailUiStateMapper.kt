@file:OptIn(ExperimentalTime::class)

package com.healthguard.detail.state

import com.healthguard.common.format.phaseChipText
import com.healthguard.common.format.timeLabel
import com.healthguard.common.format.toHumanText
import com.healthguard.detail.HistoryEntry
import com.healthguard.detail.domain.DetailContent
import com.healthguard.detail.format.dayTimeLabel
import com.healthguard.detail.format.doseAnnotation
import com.healthguard.detail.format.mediumDateLabel
import com.healthguard.home.isActive
import com.healthguard.home.phase
import com.healthguard.shared.data.DoseStatus
import com.healthguard.shared.domain.doseSlots
import com.healthguard.shared.extraction.Frequency
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Folds the tracked [DetailContent] facts into [current], the fields the
 * ViewModel copies on every emission — every one render-ready view data
 * (pre-formatted strings and value types; no domain entities). The editable
 * form fields are seeded separately by the ViewModel (a background
 * re-emission must not clobber typing), so this mapper touches only the
 * tracked, always-refreshed portion.
 */
internal fun DetailContent.toTrackedState(
    current: DetailUiState,
    zone: TimeZone,
): DetailUiState {
    val medication = item.medication
    val schedule = item.schedule
    return current.copy(
        isLoaded = true,
        drugName = medication.drugName,
        subtitle = listOfNotNull(
            medication.dosage,
            medication.form?.replaceFirstChar { it.uppercase() },
        ).joinToString(" · "),
        categoryLabel = medication.label,
        // Same `now` the content was computed against — no formatting drift.
        phaseChipText = phaseChipText(schedule, now, zone),
        phase = schedule.phase,
        isActive = item.isActive,
        isAsNeeded = schedule.frequency is Frequency.EveryHours,
        scheduleTimesText = scheduleTimesText(schedule.frequency),
        scheduleStartedText = schedule.startedAt
            ?.takeIf { item.isActive }
            ?.let { mediumDateLabel(it.toLocalDateTime(zone).date) },
        now = now,
        nextDoseAt = nextDoseAt,
        lastTakenAt = lastTakenAt,
        history = history.map { it.toRowData(now, zone) },
        dayStatuses = dayStatuses,
        dayTakeCounts = dayTakeCounts,
        adherence = adherence,
        historyFrom = historyFrom,
    )
}

/** The schedule card's "Times" row: named slots, or the interval ceiling. */
private fun scheduleTimesText(frequency: Frequency?): String? = when (frequency) {
    null -> null
    is Frequency.EveryHours -> frequency.toHumanText().replaceFirstChar { it.uppercase() }
    is Frequency.TimesPerDay ->
        doseSlots(frequency).joinToString(" · ") { timeLabel(it) }
}

/** One history entry as a pre-formatted view row. */
private fun HistoryEntry.toRowData(now: Instant, zone: TimeZone): HistoryRowData = when (this) {
    is HistoryEntry.Logged -> HistoryRowData(
        id = log.id,
        title = dayTimeLabel(log.takenAt ?: log.plannedAt, now, zone),
        annotation = doseAnnotation(log.status, log.plannedAt, log.takenAt),
        kind = when (log.status) {
            DoseStatus.TAKEN -> HistoryRowKind.TAKEN
            DoseStatus.MISSED -> HistoryRowKind.MISSED
            else -> HistoryRowKind.LOGGED
        },
    )
    is HistoryEntry.NotRecorded -> HistoryRowData(
        id = "slot-$slotAt",
        title = dayTimeLabel(slotAt, now, zone),
        annotation = "Not recorded",
        kind = HistoryRowKind.NOT_RECORDED,
    )
}
