@file:OptIn(ExperimentalTime::class)

package com.healthguard.chat

import com.healthguard.activity.DoseDayStatus
import com.healthguard.chat.state.toAssistantSnapshot
import com.healthguard.domain.extraction.Frequency
import com.healthguard.domain.model.MedicationId
import com.healthguard.domain.model.MedicationWithSchedule
import com.healthguard.domain.model.ScheduleId
import com.healthguard.domain.model.StoredMedication
import com.healthguard.domain.model.StoredSchedule
import com.healthguard.home.WeekDay
import com.healthguard.home.domain.DoseCardContent
import com.healthguard.home.domain.HomeContent
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantSnapshotTest {

    private val zone = TimeZone.of("UTC")

    /** Wednesday noon UTC. */
    private val now = Instant.parse("2025-06-18T12:00:00Z")

    private fun medication(id: String, name: String): MedicationWithSchedule =
        MedicationWithSchedule(
            medication = StoredMedication(
                id = MedicationId(id),
                drugName = name,
                label = null,
                activeIngredients = emptyList(),
                dosage = null,
                form = null,
                extractionConfidence = 1.0,
                createdAt = now - 10.days,
            ),
            schedule = StoredSchedule(
                id = ScheduleId("sched-$id"),
                medicationId = MedicationId(id),
                frequency = Frequency.TimesPerDay(1),
                withFood = null,
                startedAt = now - 10.days,
                stoppedAt = null,
            ),
        )

    private fun weekDays(state: DoseDayStatus = DoseDayStatus.MET): List<WeekDay> {
        val today = LocalDate(2025, 6, 18)
        return (6 downTo 0).map { back ->
            WeekDay(today.minus(back, DateTimeUnit.DAY), state)
        }
    }

    private fun content(
        taking: List<DoseCardContent> = emptyList(),
        cabinet: List<MedicationWithSchedule> = emptyList(),
        dueCount: Int = 0,
        todayPending: Boolean = false,
    ): HomeContent = HomeContent(
        taking = taking,
        cabinet = cabinet,
        dueCount = dueCount,
        weekDays = weekDays(),
        todayPending = todayPending,
        now = now,
    )

    @Test
    fun `no medications maps to no snapshot`() {
        assertNull(content().toAssistantSnapshot(zone))
    }

    @Test
    fun `due doses lead the headline`() {
        val card = DoseCardContent(
            item = medication("a", "Aspirin"),
            nextDoseAt = now - 1.hours,
            lastTaken = null,
            isDue = true,
        )
        val snapshot = content(taking = listOf(card), dueCount = 2).toAssistantSnapshot(zone)!!

        assertEquals("2 doses due now", snapshot.headline)
        assertTrue(snapshot.caption!!.contains("days on track"))
    }

    @Test
    fun `upcoming dose names the medication and slot`() {
        val card = DoseCardContent(
            item = medication("a", "Aspirin"),
            nextDoseAt = Instant.parse("2025-06-18T21:00:00Z"),
            lastTaken = Instant.parse("2025-06-18T09:00:00Z"),
            isDue = false,
        )
        val snapshot = content(taking = listOf(card)).toAssistantSnapshot(zone)!!

        assertEquals("Aspirin — next at 9:00 PM", snapshot.headline)
    }

    @Test
    fun `nothing left today reads all caught up`() {
        val card = DoseCardContent(
            item = medication("a", "Aspirin"),
            nextDoseAt = Instant.parse("2025-06-19T09:00:00Z"),
            lastTaken = Instant.parse("2025-06-18T09:00:00Z"),
            isDue = false,
        )
        val snapshot = content(taking = listOf(card)).toAssistantSnapshot(zone)!!

        assertEquals("All caught up", snapshot.headline)
    }

    @Test
    fun `cabinet-only medications still show a snapshot`() {
        val snapshot = content(cabinet = listOf(medication("b", "Vitamin D")))
            .toAssistantSnapshot(zone)!!

        assertEquals("All caught up", snapshot.headline)
    }
}
