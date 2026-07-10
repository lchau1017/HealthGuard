package com.healthguard.dose

import com.healthguard.domain.model.DoseId

/** A just-recorded take the UI can offer to undo. */
data class RecordedTake(val doseId: DoseId, val drugName: String)
