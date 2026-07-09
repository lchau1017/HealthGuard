@file:OptIn(ExperimentalTime::class)

package com.healthguard.home

import com.healthguard.home.domain.HomeContent
import kotlin.time.ExperimentalTime
import kotlinx.datetime.TimeZone

/**
 * Folds the pure [HomeContent] into the [HomeUiState], applying this layer's
 * presentation formatters (row status, week caption, due banner). [current]
 * carries the parts the fold preserves — the pending take confirmation.
 */
internal fun HomeContent.toUiState(current: HomeUiState, zone: TimeZone): HomeUiState {
    val cards = taking.map { dc ->
        DoseCard(
            item = dc.item,
            nextDoseAt = dc.nextDoseAt,
            lastTaken = dc.lastTaken,
            isDue = dc.isDue,
            // Same `now` the content was computed against — no formatting drift.
            status = doseRowStatus(dc.nextDoseAt, dc.lastTaken, now, zone, dc.isDue),
        )
    }
    return current.copy(
        taking = cards,
        cabinet = cabinet,
        dueCount = dueCount,
        weekDays = weekDays,
        weekCaption = weekCaption(weekDays, todayPending),
        dueAlert = cards.firstOrNull { it.isDue }?.let { DueAlert(it, dueCount - 1) },
        // takeConfirm is preserved by copy().
    )
}
