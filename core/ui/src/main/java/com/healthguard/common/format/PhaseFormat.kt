@file:OptIn(ExperimentalTime::class)

package com.healthguard.common.format

import com.healthguard.home.MedicationPhase
import com.healthguard.home.phase
import com.healthguard.domain.model.StoredSchedule
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

/**
 * The status-chip text for a schedule's [phase]: "Not started", or
 * "Stopped 3 Jul" ("Stopped today"/"Stopped yesterday" while fresh, matching
 * the app's other relative timestamps). Null while TAKING — activity is
 * evident everywhere else, a chip would be noise.
 */
fun phaseChipText(schedule: StoredSchedule, now: Instant, zone: TimeZone): String? =
    when (schedule.phase) {
        MedicationPhase.TAKING -> null
        MedicationPhase.NOT_STARTED -> "Not started"
        MedicationPhase.STOPPED -> stoppedLabel(schedule.stoppedAt!!, now, zone)
    }

/**
 * "Stopped 3 Jul" for a stop at [stoppedAt] ("Stopped today"/"Stopped
 * yesterday" while fresh, matching the app's other relative timestamps). The
 * schedule-free half of [phaseChipText]: the Activity breakdown folds it over a
 * domain row that carries only the raw stop instant.
 */
fun stoppedLabel(stoppedAt: Instant, now: Instant, zone: TimeZone): String {
    val date = stoppedAt.toLocalDateTime(zone).date
    val today = now.toLocalDateTime(zone).date
    val day = when (date) {
        today -> "today"
        today.minus(1, DateTimeUnit.DAY) -> "yesterday"
        else -> "${date.day} ${date.month.shortName()}"
    }
    return "Stopped $day"
}
