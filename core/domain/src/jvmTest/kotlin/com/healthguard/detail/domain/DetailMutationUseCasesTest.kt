@file:OptIn(ExperimentalTime::class)

package com.healthguard.detail.domain

import com.healthguard.shared.extraction.Frequency
import com.healthguard.testing.FakeMedicationRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

class DetailMutationUseCasesTest {

    private val now = Instant.parse("2024-07-03T10:00:00Z")
    private val started = Instant.parse("2024-07-01T00:00:00Z")

    @Test
    fun `save writes both the medication and schedule tables`() = runTest {
        val repo = FakeMedicationRepository()
        val item = repo.seedMedication(
            "m",
            drugName = "Old",
            frequency = Frequency.TimesPerDay(2),
            startedAt = started,
        )
        val editedMedication = item.medication.copy(drugName = "New", dosage = "500 mg")
        // A stray stoppedAt on the schedule copy must be ignored by the write.
        val editedSchedule = item.schedule.copy(
            frequency = Frequency.TimesPerDay(3),
            withFood = false,
            stoppedAt = now,
        )

        SaveMedicationUseCase(repo)(editedMedication, editedSchedule)

        assertEquals(listOf(editedMedication), repo.updatedMedications)
        assertEquals(listOf(editedSchedule), repo.updatedSchedules)
        val stored = repo.currentMedications().single()
        assertEquals("New", stored.medication.drugName)
        assertEquals("500 mg", stored.medication.dosage)
        assertEquals(Frequency.TimesPerDay(3), stored.schedule.frequency)
        assertEquals(false, stored.schedule.withFood)
        // Activation state is left to activate/stop; the stray stoppedAt is dropped.
        assertEquals(started, stored.schedule.startedAt)
        assertNull(stored.schedule.stoppedAt)
    }

    @Test
    fun `toggle stops an active medication`() = runTest {
        val repo = FakeMedicationRepository()
        repo.seedMedication("act", startedAt = started)

        ToggleTakingUseCase(repo, clock = { now })("act", currentlyActive = true)

        assertEquals(listOf("act" to now), repo.stops)
        assertEquals(now, repo.currentMedications().single().schedule.stoppedAt)
    }

    @Test
    fun `toggle activates a dormant medication`() = runTest {
        val repo = FakeMedicationRepository()
        repo.seedMedication("dorm", startedAt = null)

        ToggleTakingUseCase(repo, clock = { now })("dorm", currentlyActive = false)

        assertEquals(listOf("dorm" to now), repo.activations)
        val schedule = repo.currentMedications().single().schedule
        assertEquals(now, schedule.startedAt)
        assertNull(schedule.stoppedAt)
    }
}
