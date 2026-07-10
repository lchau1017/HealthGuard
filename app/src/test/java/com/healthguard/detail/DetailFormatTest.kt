@file:OptIn(ExperimentalTime::class)

package com.healthguard.detail

import com.healthguard.detail.format.dayTimeLabel
import com.healthguard.detail.format.doseAnnotation
import com.healthguard.detail.format.lastTakenLabel
import com.healthguard.detail.format.mediumDateLabel
import com.healthguard.domain.model.DoseStatus
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class DetailFormatTest {

    private val zone = TimeZone.UTC

    /** Wednesday 2026-07-08, 10:00 UTC. */
    private val now = Instant.parse("2026-07-08T10:00:00Z")

    // --- mediumDateLabel ---

    @Test
    fun `mediumDateLabel renders day short month and year`() {
        assertEquals("27 Jun 2026", mediumDateLabel(LocalDate(2026, 6, 27)))
    }

    // --- dayTimeLabel / lastTakenLabel ---

    @Test
    fun `dayTimeLabel uses Today Yesterday then the dated form`() {
        assertEquals("Today, 8:02 AM", dayTimeLabel(Instant.parse("2026-07-08T08:02:00Z"), now, zone))
        assertEquals("Yesterday, 9:00 PM", dayTimeLabel(Instant.parse("2026-07-07T21:00:00Z"), now, zone))
        assertEquals("Mon 6 Jul, 8:00 AM", dayTimeLabel(Instant.parse("2026-07-06T08:00:00Z"), now, zone))
    }

    @Test
    fun `lastTakenLabel lowercases the day word`() {
        assertEquals(
            "Last taken today, 8:02 AM",
            lastTakenLabel(Instant.parse("2026-07-08T08:02:00Z"), now, zone),
        )
        assertEquals(
            "Last taken yesterday, 9:00 PM",
            lastTakenLabel(Instant.parse("2026-07-07T21:00:00Z"), now, zone),
        )
        assertEquals(
            "Last taken Mon 6 Jul, 8:00 AM",
            lastTakenLabel(Instant.parse("2026-07-06T08:00:00Z"), now, zone),
        )
    }

    // --- doseAnnotation ---

    private val planned = Instant.parse("2026-07-08T08:00:00Z")

    @Test
    fun `taken within ten minutes of planned is on time`() {
        assertEquals("Taken · on time", doseAnnotation(DoseStatus.TAKEN, planned, planned + 4.minutes))
        assertEquals("Taken · on time", doseAnnotation(DoseStatus.TAKEN, planned, planned + 10.minutes))
        assertEquals("Taken · on time", doseAnnotation(DoseStatus.TAKEN, planned, planned - 10.minutes))
    }

    @Test
    fun `taken late or early names the minutes`() {
        assertEquals("Taken · 11 min late", doseAnnotation(DoseStatus.TAKEN, planned, planned + 11.minutes))
        assertEquals("Taken · 125 min late", doseAnnotation(DoseStatus.TAKEN, planned, planned + 125.minutes))
        assertEquals("Taken · 15 min early", doseAnnotation(DoseStatus.TAKEN, planned, planned - 15.minutes))
    }

    @Test
    fun `taken without a takenAt is plain taken`() {
        assertEquals("Taken", doseAnnotation(DoseStatus.TAKEN, planned, null))
    }

    @Test
    fun `missed and skipped are plain labels`() {
        assertEquals("Missed", doseAnnotation(DoseStatus.MISSED, planned, null))
        assertEquals("Skipped", doseAnnotation(DoseStatus.SKIPPED, planned, null))
    }
}
