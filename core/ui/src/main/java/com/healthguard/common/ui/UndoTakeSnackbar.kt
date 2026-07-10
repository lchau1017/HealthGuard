package com.healthguard.common.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import com.healthguard.dose.RecordedTake

/**
 * The "<drug> recorded" snackbar with its Undo action, shared by every
 * screen that records a take (home and detail) so the wording and the undo
 * contract never drift. Returns true when Undo was tapped — timing out or
 * dismissing keeps the dose, only the explicit action removes it.
 */
suspend fun SnackbarHostState.showUndoTakeSnackbar(take: RecordedTake): Boolean =
    showSnackbar(
        message = "${take.drugName} recorded",
        actionLabel = "Undo",
        duration = SnackbarDuration.Short,
    ) == SnackbarResult.ActionPerformed
