@file:OptIn(ExperimentalTime::class, ExperimentalCoroutinesApi::class)

package com.medguard.shared.data

import com.medguard.shared.db.MedGuardDb
import com.medguard.shared.extraction.Frequency
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class MedicationRepositoryTest {

    private fun repository() = MedicationRepository(
        db = MedGuardDb(DriverFactory().createDriver()),
        dispatcher = UnconfinedTestDispatcher(),
    )

    private fun medication(
        id: String = "med-1",
        label: String? = "Allergy",
        createdAtMillis: Long = 1_000,
    ) = StoredMedication(
        id = id,
        drugName = "Cetirizine Hydrochloride",
        label = label,
        activeIngredients = listOf("cetirizine hydrochloride", "lactose, spray-dried"),
        dosage = "10 mg",
        form = "tablet",
        extractionConfidence = 0.96,
        createdAt = Instant.fromEpochMilliseconds(createdAtMillis),
    )

    private fun schedule(
        id: String = "sch-1",
        medicationId: String = "med-1",
        frequency: Frequency? = Frequency.TimesPerDay(2),
        withFood: Boolean? = true,
    ) = StoredSchedule(
        id = id,
        medicationId = medicationId,
        frequency = frequency,
        withFood = withFood,
        startedAt = null,
        stoppedAt = null,
    )

    private fun dose(id: String, scheduleId: String = "sch-1", plannedAtMillis: Long) =
        StoredDoseLog(
            id = id,
            scheduleId = scheduleId,
            plannedAt = Instant.fromEpochMilliseconds(plannedAtMillis),
            takenAt = null,
            status = DoseStatus.PENDING,
        )

    @Test
    fun `insert and list roundtrips every field`() = runTest {
        val repo = repository()
        repo.insertMedication(medication(), schedule())

        val listed = repo.medications().first().single()
        assertEquals(medication(), listed.medication)
        assertEquals(schedule(), listed.schedule)
    }

    @Test
    fun `everyHours and null frequency roundtrip`() = runTest {
        val repo = repository()
        repo.insertMedication(
            medication(id = "med-a"),
            schedule(id = "sch-a", medicationId = "med-a", frequency = Frequency.EveryHours(6)),
        )
        repo.insertMedication(
            medication(id = "med-b"),
            schedule(id = "sch-b", medicationId = "med-b", frequency = null),
        )

        assertEquals(Frequency.EveryHours(6), repo.getMedication("med-a")?.schedule?.frequency)
        assertNull(repo.getMedication("med-b")?.schedule?.frequency)
    }

    @Test
    fun `withFood roundtrips null true and false`() = runTest {
        val repo = repository()
        listOf("t" to true, "f" to false, "n" to null).forEach { (suffix, value) ->
            repo.insertMedication(
                medication(id = "med-$suffix"),
                schedule(id = "sch-$suffix", medicationId = "med-$suffix", withFood = value),
            )
        }
        assertEquals(true, repo.getMedication("med-t")?.schedule?.withFood)
        assertEquals(false, repo.getMedication("med-f")?.schedule?.withFood)
        assertNull(repo.getMedication("med-n")?.schedule?.withFood)
    }

    @Test
    fun `delete cascades to schedule and dose logs`() = runTest {
        val repo = repository()
        repo.insertMedication(medication(), schedule())
        repo.logDose(dose("dose-1", plannedAtMillis = 5_000))

        repo.delete("med-1")

        assertTrue(repo.medications().first().isEmpty())
        assertNull(repo.latestDose("sch-1"))
        assertTrue(repo.dosesInRange("sch-1", Instant.fromEpochMilliseconds(0), Instant.fromEpochMilliseconds(10_000)).isEmpty())
    }

    @Test
    fun `activate makes medication active and stop removes it`() = runTest {
        val repo = repository()
        repo.insertMedication(medication(), schedule())
        assertTrue(repo.activeMedications().first().isEmpty())

        repo.activate("med-1", Instant.fromEpochMilliseconds(2_000))
        val active = repo.activeMedications().first().single()
        assertEquals("med-1", active.medication.id)
        assertEquals(Instant.fromEpochMilliseconds(2_000), active.schedule.startedAt)

        repo.stop("med-1", Instant.fromEpochMilliseconds(3_000))
        assertTrue(repo.activeMedications().first().isEmpty())
        assertEquals(
            Instant.fromEpochMilliseconds(3_000),
            repo.getMedication("med-1")?.schedule?.stoppedAt,
        )
    }

    @Test
    fun `reactivating a stopped medication clears stoppedAt`() = runTest {
        val repo = repository()
        repo.insertMedication(medication(), schedule())
        repo.activate("med-1", Instant.fromEpochMilliseconds(2_000))
        repo.stop("med-1", Instant.fromEpochMilliseconds(3_000))

        repo.activate("med-1", Instant.fromEpochMilliseconds(4_000))

        val active = repo.activeMedications().first().single()
        assertEquals(Instant.fromEpochMilliseconds(4_000), active.schedule.startedAt)
        assertNull(active.schedule.stoppedAt)
    }

    @Test
    fun `dosesInRange is half open from inclusive to exclusive`() = runTest {
        val repo = repository()
        repo.insertMedication(medication(), schedule())
        repo.logDose(dose("d-before", plannedAtMillis = 999))
        repo.logDose(dose("d-from", plannedAtMillis = 1_000))
        repo.logDose(dose("d-mid", plannedAtMillis = 1_500))
        repo.logDose(dose("d-to", plannedAtMillis = 2_000))

        val inRange = repo.dosesInRange(
            "sch-1",
            from = Instant.fromEpochMilliseconds(1_000),
            to = Instant.fromEpochMilliseconds(2_000),
        )
        assertEquals(listOf("d-from", "d-mid"), inRange.map { it.id })
    }

    @Test
    fun `latestDose picks max plannedAt`() = runTest {
        val repo = repository()
        repo.insertMedication(medication(), schedule())
        repo.logDose(dose("d-1", plannedAtMillis = 1_000))
        repo.logDose(dose("d-3", plannedAtMillis = 3_000))
        repo.logDose(dose("d-2", plannedAtMillis = 2_000))

        assertEquals("d-3", repo.latestDose("sch-1")?.id)
    }

    @Test
    fun `updateDoseStatus records status and takenAt`() = runTest {
        val repo = repository()
        repo.insertMedication(medication(), schedule())
        repo.logDose(dose("d-1", plannedAtMillis = 1_000))

        repo.updateDoseStatus("d-1", DoseStatus.TAKEN, Instant.fromEpochMilliseconds(1_100))

        val updated = repo.latestDose("sch-1")
        assertEquals(DoseStatus.TAKEN, updated?.status)
        assertEquals(Instant.fromEpochMilliseconds(1_100), updated?.takenAt)
    }

    @Test
    fun `list orders newest first`() = runTest {
        val repo = repository()
        repo.insertMedication(
            medication(id = "med-old", createdAtMillis = 1_000),
            schedule(id = "sch-old", medicationId = "med-old"),
        )
        repo.insertMedication(
            medication(id = "med-new", createdAtMillis = 2_000),
            schedule(id = "sch-new", medicationId = "med-new"),
        )

        assertEquals(
            listOf("med-new", "med-old"),
            repo.medications().first().map { it.medication.id },
        )
    }
}
