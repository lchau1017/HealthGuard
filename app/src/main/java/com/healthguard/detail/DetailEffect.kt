package com.healthguard.detail

import com.healthguard.dose.RecordedTake

/** One-shot navigation results: the host pops back to Home on either. */
enum class DetailFinished { SAVED, DELETED }

/** One-shot events the detail screen consumes once; never part of the rendered state. */
sealed interface DetailEffect {
    /** The edit was saved or the medication deleted; the host navigates back. */
    data class Finished(val result: DetailFinished) : DetailEffect

    /** A take was recorded; show the undoable snackbar. */
    data class ShowUndoSnackbar(val take: RecordedTake) : DetailEffect
}
