package com.healthguard.home.domain

import com.healthguard.domain.model.DoseStatus
import com.healthguard.testing.FakeMedicationRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

class MutationUseCasesTest {

    private val now = Instant.parse("2024-07-03T10:00:00Z")

    @Test
    fun `RecordDose logs a taken dose at now and returns its handle`() = runTest {
        val repo = FakeMedicationRepository()
        val useCase = RecordDoseUseCase(repo, clock = { now })

        val planned = now - kotlin.time.Duration.parse("2h")
        val take = useCase("sched-a", plannedAt = planned, drugName = "Ibuprofen")

        val logged = repo.loggedDoses.single()
        assertEquals(DoseStatus.TAKEN, logged.status)
        assertEquals(now, logged.takenAt)
        assertEquals(planned, logged.plannedAt)
        assertEquals("sched-a", logged.scheduleId)
        // Returned handle mirrors the persisted log.
        assertEquals(logged.id, take.doseId)
        assertEquals("Ibuprofen", take.drugName)
    }

    @Test
    fun `RecordDose falls back to now when plannedAt is null`() = runTest {
        val repo = FakeMedicationRepository()
        val useCase = RecordDoseUseCase(repo, clock = { now })

        useCase("sched-a", plannedAt = null, drugName = "Ibuprofen")

        val logged = repo.loggedDoses.single()
        assertEquals(now, logged.plannedAt)
        assertEquals(now, logged.takenAt)
    }

    @Test
    fun `UndoDose deletes the given dose log`() = runTest {
        val repo = FakeMedicationRepository()
        val useCase = UndoDoseUseCase(repo)

        useCase("dose-42")

        assertEquals(listOf("dose-42"), repo.deletedDoseIds)
    }

    @Test
    fun `ActivateMedication activates with the clock instant`() = runTest {
        val repo = FakeMedicationRepository()
        val useCase = ActivateMedicationUseCase(repo, clock = { now })

        useCase("med-a")

        assertEquals(listOf("med-a" to now), repo.activations)
    }

    @Test
    fun `StopMedication stops with the clock instant`() = runTest {
        val repo = FakeMedicationRepository()
        val useCase = StopMedicationUseCase(repo, clock = { now })

        useCase("med-a")

        assertEquals(listOf("med-a" to now), repo.stops)
    }

    @Test
    fun `DeleteMedication deletes the medication`() = runTest {
        val repo = FakeMedicationRepository()
        repo.seedMedication("med-a")
        val useCase = DeleteMedicationUseCase(repo)

        useCase("med-a")

        assertEquals(listOf("med-a"), repo.deletedMedicationIds)
        assertNull(repo.currentMedications().firstOrNull { it.medication.id == "med-a" })
    }
}
