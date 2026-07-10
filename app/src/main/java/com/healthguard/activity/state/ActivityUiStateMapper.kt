@file:OptIn(ExperimentalTime::class)

package com.healthguard.activity.state

import com.healthguard.activity.domain.ActivityContent
import com.healthguard.activity.domain.MedicationAdherenceContent
import com.healthguard.common.format.stoppedLabel
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/**
 * Folds the tracked [ActivityContent] into the ViewState, applying the
 * stopped-row label. [current] carries the parts the fold preserves — the
 * open day-detail sheet, which a background re-query must never dismiss
 * (mirrors how the home fold preserves its pending take confirmation).
 */
internal fun ActivityContent.toUiState(current: ActivityUiState, zone: TimeZone): ActivityUiState =
    current.copy(
        now = now,
        filter = filter,
        from = from,
        stats = stats,
        dayCounts = dayCounts,
        breakdown = breakdown.map { it.toUi(now, zone) },
        // dayDetail is preserved by copy().
    )

private fun MedicationAdherenceContent.toUi(now: Instant, zone: TimeZone) = MedicationAdherence(
    name = name,
    phase = phase,
    asNeeded = asNeeded,
    percent = percent,
    taken = taken,
    skipped = skipped,
    meetsTarget = meetsTarget,
    // Same `now` the content was computed against — no formatting drift.
    stoppedText = stoppedAt?.let { stoppedLabel(it, now, zone) },
)
