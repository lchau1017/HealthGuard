package com.healthguard.home.domain

import com.healthguard.domain.model.DoseId
import com.healthguard.domain.model.ScheduleId
import com.healthguard.activity.DoseDayStatus
import com.healthguard.domain.model.DoseStatus
import com.healthguard.domain.model.MedicationWithSchedule
import com.healthguard.domain.model.StoredDoseLog
import com.healthguard.domain.extraction.Frequency
import com.healthguard.testing.FakeMedicationRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone

class ComputeHomeStateUseCaseTest {

    /** 2024-07-03T10:00:00Z — mid-morning UTC so TimesPerDay slots are easy. */
    private val now = Instant.parse("2024-07-03T10:00:00Z")

    private fun useCase(repo: FakeMedicationRepository) =
        ComputeHomeStateUseCase(repo, clock = { now }, zone = TimeZone.UTC)

    /** rows as the medications() flow yields them: newest created first. */
    private fun FakeMedicationRepository.rows(): List<MedicationWithSchedule> =
        currentMedications().sortedByDescending { it.medication.createdAt }

    @Test
    fun `taking sorts overdue first then ascending with null frequency last`() = runTest {
        val repo = FakeMedicationRepository()
        // No dose logged -> nextDoseAt = startedAt, which is in the past.
        repo.seedMedication("od1", frequency = Frequency.EveryHours(6), startedAt = now - 1.hours)
        repo.seedMedication("od2", frequency = Frequency.EveryHours(6), startedAt = now - 3.hours)
        repo.seedMedication("fut", frequency = Frequency.EveryHours(6), startedAt = now - 8.hours)
        repo.seedDose("sched-fut", takenAt = now - 2.hours) // next dose in 4h
        repo.seedMedication("nofreq", frequency = null, startedAt = now - 1.hours)

        val content = useCase(repo)(repo.rows())

        assertEquals(listOf("od2", "od1", "fut", "nofreq"), content.taking.map { it.item.medication.id.value })
        assertEquals(now - 3.hours, content.taking[0].nextDoseAt)
        assertEquals(now + 4.hours, content.taking[2].nextDoseAt)
        assertNull(content.taking[3].nextDoseAt)
    }

    @Test
    fun `dueCount counts due and overdue taking cards`() = runTest {
        val repo = FakeMedicationRepository()
        repo.seedMedication("overdue", frequency = Frequency.EveryHours(6), startedAt = now - 2.hours)
        repo.seedMedication("due-now", frequency = Frequency.EveryHours(6), startedAt = now - 6.hours)
        repo.seedDose("sched-due-now", takenAt = now - 6.hours) // next dose exactly now
        repo.seedMedication("later", frequency = Frequency.EveryHours(6), startedAt = now - 8.hours)
        repo.seedDose("sched-later", takenAt = now - 2.hours) // next dose in 4h
        repo.seedMedication("nofreq", frequency = null, startedAt = now - 1.hours)

        val content = useCase(repo)(repo.rows())

        assertEquals(2, content.dueCount)
        val byId = content.taking.associateBy { it.item.medication.id.value }
        assertTrue(byId.getValue("overdue").isDue)
        assertTrue(byId.getValue("due-now").isDue)
        assertFalse(byId.getValue("later").isDue)
        assertFalse(byId.getValue("nofreq").isDue)
    }

    @Test
    fun `week card measures each day against the schedule`() = runTest {
        val repo = FakeMedicationRepository()
        // 2x/day since Sunday 6/30 10:00: owed 21:00 that day, both slots on
        // 7/1 and 7/2, and 09:00 today so far.
        repo.seedMedication("a", frequency = Frequency.TimesPerDay(2), startedAt = now - 72.hours)
        repo.seedDose("sched-a", takenAt = Instant.parse("2024-06-30T21:00:00Z"))
        repo.seedDose("sched-a", takenAt = Instant.parse("2024-07-01T09:00:00Z"))
        repo.seedDose("sched-a", takenAt = Instant.parse("2024-07-01T21:05:00Z"))
        repo.seedDose("sched-a", takenAt = Instant.parse("2024-07-02T09:00:00Z")) // 21:00 silent
        repo.seedDose("sched-a", takenAt = Instant.parse("2024-07-03T09:02:00Z"))

        val content = useCase(repo)(repo.rows())

        assertEquals(7, content.weekDays.size)
        assertEquals(
            listOf(
                DoseDayStatus.OUT_OF_TREATMENT, DoseDayStatus.OUT_OF_TREATMENT, DoseDayStatus.OUT_OF_TREATMENT,
                DoseDayStatus.MET, DoseDayStatus.MET, DoseDayStatus.PARTIAL,
                DoseDayStatus.MET,
            ),
            content.weekDays.map { it.state },
        )
        // Today's 21:00 slot is still ahead and the day is on pace: today is
        // still pending.
        assertTrue(content.todayPending)
    }

    @Test
    fun `todayPending is false once no slot is left today`() = runTest {
        val repo = FakeMedicationRepository()
        // 1x/day slot at 09:00; taken today at 09:05 -> nothing pending.
        repo.seedMedication("a", frequency = Frequency.TimesPerDay(1), startedAt = now - 72.hours)
        repo.seedDose("sched-a", takenAt = now - 55.minutes) // today 09:05

        val content = useCase(repo)(repo.rows())

        assertFalse(content.todayPending)
    }

    @Test
    fun `a newer skipped log never shifts lastTaken or the next dose`() = runTest {
        val repo = FakeMedicationRepository()
        repo.seedMedication("a", frequency = Frequency.EveryHours(6), startedAt = now - 10.hours)
        repo.seedDose("sched-a", takenAt = now - 4.hours)
        // A skipped dose logged after the last take (demo data seeds these):
        // it must not delay the next dose or read as a recent take that could
        // trip the double-dose guard.
        repo.logDose(
            StoredDoseLog(
                id = DoseId("skip-1"),
                scheduleId = ScheduleId("sched-a"),
                plannedAt = now - 1.hours,
                takenAt = null,
                status = DoseStatus.SKIPPED,
            ),
        )

        val card = useCase(repo)(repo.rows()).taking.single()

        assertEquals(now - 4.hours, card.lastTaken)
        assertEquals(now + 2.hours, card.nextDoseAt)
        assertFalse(card.isDue)
    }

    @Test
    fun `dormant and stopped items are in the cabinet newest first and never in taking`() = runTest {
        val repo = FakeMedicationRepository()
        repo.seedMedication("older-dormant", createdAt = Instant.fromEpochMilliseconds(1_000))
        repo.seedMedication("newer-dormant", createdAt = Instant.fromEpochMilliseconds(2_000))
        repo.seedMedication(
            "stopped",
            createdAt = Instant.fromEpochMilliseconds(3_000),
            startedAt = now - 2.hours,
            stoppedAt = now - 1.hours,
        )
        repo.seedMedication(
            "active",
            createdAt = Instant.fromEpochMilliseconds(4_000),
            startedAt = now - 1.hours,
        )

        val content = useCase(repo)(repo.rows())

        assertEquals(
            listOf("stopped", "newer-dormant", "older-dormant"),
            content.cabinet.map { it.medication.id.value },
        )
        assertEquals(listOf("active"), content.taking.map { it.item.medication.id.value })
    }
}
