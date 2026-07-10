@file:OptIn(ExperimentalTime::class, ExperimentalCoroutinesApi::class)

package com.healthguard.data

import com.healthguard.data.db.HealthGuardDb
import com.healthguard.domain.extraction.Frequency
import com.healthguard.domain.model.DoseLogWithMedication
import com.healthguard.domain.model.DoseStatus
import com.healthguard.domain.model.StoredDoseLog
import com.healthguard.domain.model.StoredMedication
import com.healthguard.domain.model.StoredSchedule
import com.healthguard.domain.model.TakenDose
import com.healthguard.domain.repository.MedicationRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class MedicationRepositoryTest {

    private fun repository() = SqlDelightMedicationRepository(
        db = HealthGuardDb(DriverFactory().createDriver()),
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

        repo.activate("med-1", Instant.fromEpochMilliseconds(2_000))
        assertEquals(
            Instant.fromEpochMilliseconds(2_000),
            repo.getMedication("med-1")?.schedule?.startedAt,
        )

        repo.stop("med-1", Instant.fromEpochMilliseconds(3_000))
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

        val schedule = repo.getMedication("med-1")?.schedule
        assertEquals(Instant.fromEpochMilliseconds(4_000), schedule?.startedAt)
        assertNull(schedule?.stoppedAt)
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
    fun `deleteDoseLog removes the dose and latestDose falls back`() = runTest {
        val repo = repository()
        repo.insertMedication(medication(), schedule())
        repo.logDose(dose("d-1", plannedAtMillis = 1_000))
        repo.logDose(dose("d-2", plannedAtMillis = 2_000))
        assertEquals("d-2", repo.latestDose("sch-1")?.id)

        repo.deleteDoseLog("d-2")

        assertEquals("d-1", repo.latestDose("sch-1")?.id)
        repo.deleteDoseLog("d-1")
        assertNull(repo.latestDose("sch-1"))
    }

    @Test
    fun `deleteDoseLog of a missing id is a no-op`() = runTest {
        val repo = repository()
        repo.insertMedication(medication(), schedule())
        repo.logDose(dose("d-1", plannedAtMillis = 1_000))

        repo.deleteDoseLog("d-ghost")

        assertEquals("d-1", repo.latestDose("sch-1")?.id)
    }

    @Test
    fun `updateMedication roundtrips every field`() = runTest {
        val repo = repository()
        repo.insertMedication(medication(), schedule())

        val updated = medication().copy(
            drugName = "Loratadine",
            label = "Hay fever",
            activeIngredients = listOf("loratadine", "microcrystalline cellulose"),
            dosage = "5 mg",
            form = "capsule",
        )
        repo.updateMedication(updated)

        assertEquals(updated, repo.getMedication("med-1")?.medication)
    }

    @Test
    fun `updateMedication can clear nullable fields and empty ingredients`() = runTest {
        val repo = repository()
        repo.insertMedication(medication(), schedule())

        val cleared = medication().copy(
            label = null,
            dosage = null,
            form = null,
            activeIngredients = emptyList(),
        )
        repo.updateMedication(cleared)

        assertEquals(cleared, repo.getMedication("med-1")?.medication)
    }

    @Test
    fun `updateSchedule changes frequency TimesPerDay to EveryHours and to null`() = runTest {
        val repo = repository()
        repo.insertMedication(medication(), schedule(frequency = Frequency.TimesPerDay(2)))

        repo.updateSchedule(schedule().copy(frequency = Frequency.EveryHours(8), withFood = false))
        var stored = repo.getMedication("med-1")?.schedule
        assertEquals(Frequency.EveryHours(8), stored?.frequency)
        assertEquals(false, stored?.withFood)

        repo.updateSchedule(schedule().copy(frequency = null, withFood = null))
        stored = repo.getMedication("med-1")?.schedule
        assertNull(stored?.frequency)
        assertNull(stored?.withFood)
    }

    @Test
    fun `updateSchedule preserves startedAt and stoppedAt`() = runTest {
        val repo = repository()
        repo.insertMedication(medication(), schedule())
        repo.activate("med-1", Instant.fromEpochMilliseconds(2_000))
        repo.stop("med-1", Instant.fromEpochMilliseconds(3_000))

        // The passed-in schedule claims different activation state; update
        // must ignore it — activation changes go through activate/stop.
        repo.updateSchedule(
            schedule().copy(
                frequency = Frequency.EveryHours(6),
                startedAt = Instant.fromEpochMilliseconds(9_000),
                stoppedAt = null,
            ),
        )

        val stored = repo.getMedication("med-1")?.schedule
        assertEquals(Frequency.EveryHours(6), stored?.frequency)
        assertEquals(Instant.fromEpochMilliseconds(2_000), stored?.startedAt)
        assertEquals(Instant.fromEpochMilliseconds(3_000), stored?.stoppedAt)
    }

    @Test
    fun `update of a missing id is a no-op`() = runTest {
        val repo = repository()
        repo.insertMedication(medication(), schedule())

        repo.updateMedication(medication(id = "med-ghost").copy(drugName = "Ghost"))
        repo.updateSchedule(
            schedule(id = "sch-ghost", medicationId = "med-ghost")
                .copy(frequency = Frequency.EveryHours(4)),
        )

        val all = repo.medications().first()
        assertEquals(1, all.size)
        val only = all.single()
        assertEquals(medication(), only.medication)
        assertEquals(schedule(), only.schedule)
    }

    private suspend fun MedicationRepository.logTaken(
        id: String,
        scheduleId: String = "sch-1",
        takenAtMillis: Long,
        plannedAtMillis: Long = takenAtMillis,
    ) = logDose(
        StoredDoseLog(
            id = id,
            scheduleId = scheduleId,
            plannedAt = Instant.fromEpochMilliseconds(plannedAtMillis),
            takenAt = Instant.fromEpochMilliseconds(takenAtMillis),
            status = DoseStatus.TAKEN,
        ),
    )

    @Test
    fun `takenDosesInRange is half open on takenAt`() = runTest {
        val repo = repository()
        repo.insertMedication(medication(), schedule())
        repo.logTaken("d-before", takenAtMillis = 999)
        repo.logTaken("d-from", takenAtMillis = 1_000)
        repo.logTaken("d-mid", takenAtMillis = 1_500)
        repo.logTaken("d-to", takenAtMillis = 2_000)

        val inRange = repo.takenDosesInRange(
            from = Instant.fromEpochMilliseconds(1_000),
            to = Instant.fromEpochMilliseconds(2_000),
        )
        assertEquals(
            listOf(Instant.fromEpochMilliseconds(1_000), Instant.fromEpochMilliseconds(1_500)),
            inRange.map { it.takenAt },
        )
    }

    @Test
    fun `takenDosesInRange only returns TAKEN rows with a takenAt`() = runTest {
        val repo = repository()
        repo.insertMedication(medication(), schedule())
        repo.logTaken("d-taken", takenAtMillis = 1_000)
        // PENDING with null takenAt.
        repo.logDose(dose("d-pending", plannedAtMillis = 1_100))
        repo.logDose(
            dose("d-skipped", plannedAtMillis = 1_200).copy(status = DoseStatus.SKIPPED),
        )
        // MISSED row that oddly carries a takenAt: status must still exclude it.
        repo.logDose(
            StoredDoseLog(
                id = "d-missed",
                scheduleId = "sch-1",
                plannedAt = Instant.fromEpochMilliseconds(1_300),
                takenAt = Instant.fromEpochMilliseconds(1_300),
                status = DoseStatus.MISSED,
            ),
        )

        val inRange = repo.takenDosesInRange(
            from = Instant.fromEpochMilliseconds(0),
            to = Instant.fromEpochMilliseconds(10_000),
        )
        assertEquals(listOf(Instant.fromEpochMilliseconds(1_000)), inRange.map { it.takenAt })
    }

    @Test
    fun `takenDosesInRange joins medication identity through the schedule`() = runTest {
        val repo = repository()
        repo.insertMedication(medication(id = "med-a"), schedule(id = "sch-a", medicationId = "med-a"))
        repo.insertMedication(
            medication(id = "med-b").copy(drugName = "Loratadine"),
            schedule(id = "sch-b", medicationId = "med-b"),
        )
        repo.logTaken("d-a", scheduleId = "sch-a", takenAtMillis = 1_000)
        repo.logTaken("d-b", scheduleId = "sch-b", takenAtMillis = 2_000)

        val inRange = repo.takenDosesInRange(
            from = Instant.fromEpochMilliseconds(0),
            to = Instant.fromEpochMilliseconds(10_000),
        )
        assertEquals(
            listOf(
                TakenDose("med-a", "Cetirizine Hydrochloride", Instant.fromEpochMilliseconds(1_000)),
                TakenDose("med-b", "Loratadine", Instant.fromEpochMilliseconds(2_000)),
            ),
            inRange,
        )
    }

    @Test
    fun `recentDoses returns newest planned first capped at limit across statuses`() = runTest {
        val repo = repository()
        repo.insertMedication(medication(), schedule())
        repo.logTaken("d-1", takenAtMillis = 1_000)
        repo.logDose(dose("d-2", plannedAtMillis = 2_000))
        repo.logDose(dose("d-3", plannedAtMillis = 3_000).copy(status = DoseStatus.SKIPPED))
        repo.logTaken("d-4", takenAtMillis = 4_000)

        assertEquals(
            listOf("d-4", "d-3", "d-2"),
            repo.recentDoses("sch-1", limit = 3).map { it.id },
        )
        assertEquals(4, repo.recentDoses("sch-1", limit = 10).size)
    }

    @Test
    fun `doseLogsInRange returns any status across schedules on effective time`() = runTest {
        val repo = repository()
        repo.insertMedication(medication(id = "med-a"), schedule(id = "sch-a", medicationId = "med-a"))
        repo.insertMedication(medication(id = "med-b"), schedule(id = "sch-b", medicationId = "med-b"))
        repo.logTaken("d-taken", scheduleId = "sch-a", takenAtMillis = 1_000)
        repo.logDose(
            dose("d-missed", scheduleId = "sch-b", plannedAtMillis = 1_500)
                .copy(status = DoseStatus.MISSED),
        )
        // TAKEN late: planned before the range but taken inside it — the
        // effective time (takenAt) decides membership.
        repo.logTaken("d-late", scheduleId = "sch-a", takenAtMillis = 1_800, plannedAtMillis = 500)
        // Outside the range on both ends.
        repo.logTaken("d-before", scheduleId = "sch-a", takenAtMillis = 999)
        repo.logDose(
            dose("d-after", scheduleId = "sch-b", plannedAtMillis = 2_000)
                .copy(status = DoseStatus.MISSED),
        )

        val inRange = repo.doseLogsInRange(
            from = Instant.fromEpochMilliseconds(1_000),
            to = Instant.fromEpochMilliseconds(2_000),
        )
        assertEquals(setOf("d-taken", "d-missed", "d-late"), inRange.map { it.id }.toSet())
    }

    @Test
    fun `doseLogsWithMedicationInRange joins medication identity and keeps every status`() = runTest {
        val repo = repository()
        repo.insertMedication(medication(id = "med-a"), schedule(id = "sch-a", medicationId = "med-a"))
        repo.insertMedication(
            medication(id = "med-b").copy(drugName = "Loratadine", dosage = null),
            schedule(id = "sch-b", medicationId = "med-b"),
        )
        repo.logTaken("d-taken", scheduleId = "sch-a", takenAtMillis = 1_000)
        repo.logDose(
            dose("d-skipped", scheduleId = "sch-b", plannedAtMillis = 1_500)
                .copy(status = DoseStatus.SKIPPED),
        )

        val inRange = repo.doseLogsWithMedicationInRange(
            from = Instant.fromEpochMilliseconds(0),
            to = Instant.fromEpochMilliseconds(10_000),
        )
        assertEquals(
            listOf(
                DoseLogWithMedication(
                    medicationId = "med-a",
                    drugName = "Cetirizine Hydrochloride",
                    dosage = "10 mg",
                    plannedAt = Instant.fromEpochMilliseconds(1_000),
                    takenAt = Instant.fromEpochMilliseconds(1_000),
                    status = DoseStatus.TAKEN,
                ),
                DoseLogWithMedication(
                    medicationId = "med-b",
                    drugName = "Loratadine",
                    dosage = null,
                    plannedAt = Instant.fromEpochMilliseconds(1_500),
                    takenAt = null,
                    status = DoseStatus.SKIPPED,
                ),
            ),
            inRange,
        )
    }

    @Test
    fun `doseLogsWithMedicationInRange is half open on the effective time`() = runTest {
        val repo = repository()
        repo.insertMedication(medication(), schedule())
        // TAKEN late: planned before the range but taken inside it — the
        // effective time (takenAt) decides membership, like doseLogsInRange.
        repo.logTaken("d-late", takenAtMillis = 1_200, plannedAtMillis = 500)
        repo.logTaken("d-before", takenAtMillis = 999)
        repo.logTaken("d-from", takenAtMillis = 1_000)
        repo.logDose(dose("d-at-to", plannedAtMillis = 2_000).copy(status = DoseStatus.MISSED))

        val inRange = repo.doseLogsWithMedicationInRange(
            from = Instant.fromEpochMilliseconds(1_000),
            to = Instant.fromEpochMilliseconds(2_000),
        )
        assertEquals(
            listOf(Instant.fromEpochMilliseconds(1_000), Instant.fromEpochMilliseconds(1_200)),
            inRange.map { it.takenAt },
        )
    }

    @Test
    fun `recentDoses of an unknown schedule is empty`() = runTest {
        val repo = repository()
        repo.insertMedication(medication(), schedule())
        assertTrue(repo.recentDoses("sch-ghost", limit = 5).isEmpty())
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

    @Test
    fun `every mutating call signals data changes`() = runTest {
        val repo = repository()
        val events = mutableListOf<Unit>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.dataChanges.collect { events += it }
        }

        repo.insertMedication(medication(), schedule())
        repo.activate("med-1", Instant.fromEpochMilliseconds(1_000))
        repo.logDose(dose("d-1", plannedAtMillis = 1_000))
        repo.deleteDoseLog("d-1")
        repo.stop("med-1", Instant.fromEpochMilliseconds(2_000))
        repo.updateMedication(medication())
        repo.updateSchedule(schedule())
        repo.delete("med-1")

        assertEquals(8, events.size)
        job.cancel()
    }

    @Test
    fun `batch applies every write under a single change signal`() = runTest {
        val repo = repository()
        val events = mutableListOf<Unit>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.dataChanges.collect { events += it }
        }

        repo.batch {
            insertMedication(medication(), schedule())
            activate("med-1", Instant.fromEpochMilliseconds(1_000))
            logDose(dose("d-1", plannedAtMillis = 1_000))
        }

        assertEquals(1, events.size)
        // All three writes landed.
        val row = repo.medications().first().single()
        assertEquals("med-1", row.medication.id)
        assertEquals(Instant.fromEpochMilliseconds(1_000), row.schedule.startedAt)
        assertEquals("d-1", repo.latestDose("sch-1")?.id)
        job.cancel()
    }

    @Test
    fun `batch is one visible state change for query flows`() = runTest {
        val repo = repository()
        val sizes = mutableListOf<Int>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.medications().collect { sizes += it.size }
        }

        repo.batch {
            insertMedication(
                medication(id = "med-a"),
                schedule(id = "sch-a", medicationId = "med-a"),
            )
            insertMedication(
                medication(id = "med-b"),
                schedule(id = "sch-b", medicationId = "med-b"),
            )
        }

        // Initial empty emission, then straight to the fully applied state:
        // never a partial one-medication snapshot.
        assertTrue(sizes.none { it == 1 }, "saw partial emission: $sizes")
        assertEquals(2, sizes.last())
        job.cancel()
    }
}
