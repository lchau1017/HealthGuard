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
 * Where a medication stands in its treatment lifecycle — the initiation and
 * persistence axes of adherence, derived entirely from the schedule's
 * activation timestamps (no schema of its own). Implementation (doses versus
 * schedule while taking) is measured separately by [com.healthguard.activity.AdherenceResult].
 */
enum class MedicationPhase {
    /** Scanned but never activated: treatment not initiated. */
    NOT_STARTED,

    /** Currently active. */
    TAKING,

    /** Started once, then stopped (discontinued); resuming clears the stop. */
    STOPPED,
}

/**
 * Derives the phase. A stop always wins — reactivation clears `stoppedAt`,
 * so a non-null stop means the medication is discontinued right now.
 */
val StoredSchedule.phase: MedicationPhase
    get() = when {
        stoppedAt != null -> MedicationPhase.STOPPED
        startedAt != null -> MedicationPhase.TAKING
        else -> MedicationPhase.NOT_STARTED
    }

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
