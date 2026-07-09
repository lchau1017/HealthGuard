@file:OptIn(ExperimentalTime::class)

package com.healthguard.activity

import com.healthguard.activity.domain.ActivityContent
import com.healthguard.activity.domain.MedicationAdherenceContent
import com.healthguard.home.stoppedLabel
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/** Folds the tracked [ActivityContent] into the ViewState, applying the stopped-row label. */
internal fun ActivityContent.toUiState(zone: TimeZone): ActivityUiState = ActivityUiState(
    filter = filter,
    from = from,
    stats = stats,
    dayCounts = dayCounts,
    breakdown = breakdown.map { it.toUi(now, zone) },
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
