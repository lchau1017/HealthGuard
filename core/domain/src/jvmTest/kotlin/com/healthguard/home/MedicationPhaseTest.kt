@file:OptIn(ExperimentalTime::class)

package com.healthguard.home

import com.healthguard.shared.data.StoredSchedule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class MedicationPhaseTest {

    private fun schedule(
        startedAt: Instant? = null,
        stoppedAt: Instant? = null,
    ) = StoredSchedule(
        id = "sch-1",
        medicationId = "med-1",
        frequency = null,
        withFood = null,
        startedAt = startedAt,
        stoppedAt = stoppedAt,
    )

    // --- phase derivation ---

    @Test
    fun `a never-activated schedule is not started`() {
        assertEquals(MedicationPhase.NOT_STARTED, schedule().phase)
    }

    @Test
    fun `an active schedule is taking`() {
        assertEquals(
            MedicationPhase.TAKING,
            schedule(startedAt = Instant.parse("2026-06-01T00:00:00Z")).phase,
        )
    }

    @Test
    fun `a stopped schedule is stopped even though it once started`() {
        assertEquals(
            MedicationPhase.STOPPED,
            schedule(
                startedAt = Instant.parse("2026-06-01T00:00:00Z"),
                stoppedAt = Instant.parse("2026-07-03T12:00:00Z"),
            ).phase,
        )
    }
}
