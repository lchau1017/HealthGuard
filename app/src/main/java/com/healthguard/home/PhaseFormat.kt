@file:OptIn(ExperimentalTime::class)

package com.healthguard.home

import com.healthguard.shared.data.StoredSchedule
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
        MedicationPhase.STOPPED -> {
            val date = schedule.stoppedAt!!.toLocalDateTime(zone).date
            val today = now.toLocalDateTime(zone).date
            val day = when (date) {
                today -> "today"
                today.minus(1, DateTimeUnit.DAY) -> "yesterday"
                else -> {
                    val month = date.month.name.lowercase()
                        .replaceFirstChar { it.uppercase() }.take(3)
                    "${date.day} $month"
                }
            }
            "Stopped $day"
        }
    }
