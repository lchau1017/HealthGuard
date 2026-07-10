@file:OptIn(ExperimentalTime::class)

package com.healthguard.home

import com.healthguard.domain.model.MedicationWithSchedule
import kotlin.time.ExperimentalTime

/** A schedule the user has started and not stopped. */
val MedicationWithSchedule.isActive: Boolean
    get() = schedule.startedAt != null && schedule.stoppedAt == null
