package com.healthguard.home

import com.healthguard.domain.model.MedicationWithSchedule

/** A schedule the user has started and not stopped. */
val MedicationWithSchedule.isActive: Boolean
    get() = schedule.startedAt != null && schedule.stoppedAt == null
