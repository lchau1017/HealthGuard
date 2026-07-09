package com.healthguard.confirm

/** One-shot events the confirm flow consumes once; never part of the rendered state. */
sealed interface ConfirmEffect {
    /** The medication was persisted; the host shows a toast and resets the flow. */
    data object Saved : ConfirmEffect
}
