@file:OptIn(ExperimentalTime::class)

package com.healthguard.detail

import com.healthguard.detail.domain.DetailContent
import kotlin.time.ExperimentalTime

/**
 * Folds the tracked [DetailContent] facts into [current], the fields the
 * ViewModel copies on every emission. The editable form fields are seeded
 * separately by the ViewModel (a background re-emission must not clobber
 * typing), so this mapper touches only the tracked, always-refreshed portion.
 */
internal fun DetailContent.toTrackedState(current: DetailUiState): DetailUiState = current.copy(
    item = item,
    nextDoseAt = nextDoseAt,
    lastTakenAt = lastTakenAt,
    history = history,
    dayStatuses = dayStatuses,
    dayTakeCounts = dayTakeCounts,
    adherence = adherence,
    historyFrom = historyFrom,
)
