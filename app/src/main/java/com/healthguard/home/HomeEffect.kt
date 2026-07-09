package com.healthguard.home

import com.healthguard.dose.RecordedTake

/** One-shot events the home screen consumes once; never part of the rendered state. */
sealed interface HomeEffect {
    data class ShowUndoSnackbar(val take: RecordedTake) : HomeEffect
}
