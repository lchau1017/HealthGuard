@file:OptIn(ExperimentalTime::class)

package com.healthguard.common.format

import com.healthguard.shared.data.StoredSchedule
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhaseFormatTest {

    /** Wednesday 2026-07-08 mid-morning. */
    private val now = Instant.parse("2026-07-08T10:00:00Z")
    private val zone = TimeZone.UTC

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

    // --- chip text ---

    @Test
    fun `taking has no chip`() {
        assertNull(
            phaseChipText(schedule(startedAt = Instant.parse("2026-06-01T00:00:00Z")), now, zone),
        )
    }

    @Test
    fun `not started reads plainly`() {
        assertEquals("Not started", phaseChipText(schedule(), now, zone))
    }

    @Test
    fun `stopped names the stop date`() {
        assertEquals(
            "Stopped 3 Jul",
            phaseChipText(
                schedule(
                    startedAt = Instant.parse("2026-06-01T00:00:00Z"),
                    stoppedAt = Instant.parse("2026-07-03T12:00:00Z"),
                ),
                now,
                zone,
            ),
        )
    }

    @Test
    fun `a recent stop reads relatively like other timestamps`() {
        val started = Instant.parse("2026-06-01T00:00:00Z")
        assertEquals(
            "Stopped today",
            phaseChipText(
                schedule(startedAt = started, stoppedAt = Instant.parse("2026-07-08T09:00:00Z")),
                now,
                zone,
            ),
        )
        assertEquals(
            "Stopped yesterday",
            phaseChipText(
                schedule(startedAt = started, stoppedAt = Instant.parse("2026-07-07T20:00:00Z")),
                now,
                zone,
            ),
        )
    }
}
