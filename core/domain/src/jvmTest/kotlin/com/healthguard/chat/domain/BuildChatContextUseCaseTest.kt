package com.healthguard.chat.domain

import com.healthguard.chat.ChatContext
import com.healthguard.domain.extraction.Frequency
import com.healthguard.domain.model.DoseStatus
import com.healthguard.home.MedicationPhase
import com.healthguard.testing.FakeMedicationRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

class BuildChatContextUseCaseTest {

    /** A Wednesday noon, so the week under test has known past and future slots. */
    private val now = Instant.parse("2025-06-18T12:00:00Z")
    private val zone = TimeZone.of("UTC")
    private val repository = FakeMedicationRepository()
    private val useCase = BuildChatContextUseCase(repository, repository, { now }, zone)

    /** Monday of the current week. */
    private val monday = Instant.parse("2025-06-16T00:00:00Z")

    @Test
    fun `medication facts carry adherence, phase and next dose`() = runTest {
        repository.seedMedication(
            "a",
            drugName = "Aspirin",
            frequency = Frequency.TimesPerDay(1),
            startedAt = monday,
        )
        repository.seedDose("sched-a", Instant.parse("2025-06-16T09:00:00Z"))
        repository.seedDose("sched-a", Instant.parse("2025-06-17T09:00:00Z"))

        val context = useCase()
        val facts = context.medications.single()

        assertEquals(now, context.generatedAt)
        assertEquals("Aspirin", facts.name)
        assertEquals(MedicationPhase.TAKING, facts.phase)
        assertEquals(2, facts.adherence.taken)
        // 1x/day owes the 09:00 slot on the 16th, 17th and 18th.
        assertEquals(3, facts.adherence.expected)
        assertEquals(67, facts.adherence.percent)
        assertEquals(Instant.parse("2025-06-19T09:00:00Z"), facts.nextDoseAt)
    }

    @Test
    fun `weeks cover the last five monday starts oldest first, current week clipped to now`() = runTest {
        repository.seedMedication(
            "a",
            frequency = Frequency.TimesPerDay(1),
            startedAt = monday,
        )
        repository.seedDose("sched-a", Instant.parse("2025-06-16T09:00:00Z"))

        val weeks = useCase().weeks

        assertEquals(
            listOf("2025-05-19", "2025-05-26", "2025-06-02", "2025-06-09", "2025-06-16"),
            weeks.map { it.weekStart.toString() },
        )
        val current = weeks.last()
        // Clipped to now: only the 16th-18th slots are owed so far.
        assertEquals(3, current.expected)
        assertEquals(1, current.taken)
        assertEquals(2, current.missed)
        // The schedule started this week, so earlier weeks owe nothing.
        assertEquals(0, weeks.first().expected)
    }

    @Test
    fun `events resolve to local dates with medication names`() = runTest {
        repository.seedMedication(
            "a",
            drugName = "Aspirin",
            frequency = Frequency.TimesPerDay(1),
            startedAt = monday,
        )
        repository.seedDose("sched-a", Instant.parse("2025-06-17T09:00:00Z"))

        val events = useCase().events

        assertEquals(
            listOf(ChatContext.DoseEventFact(LocalDate(2025, 6, 17), "Aspirin", DoseStatus.TAKEN)),
            events,
        )
    }

    @Test
    fun `dormant medication reports NOT_STARTED with nothing expected`() = runTest {
        repository.seedMedication("b", drugName = "Vitamin D", startedAt = null)

        val facts = useCase().medications.single()

        assertEquals(MedicationPhase.NOT_STARTED, facts.phase)
        assertEquals(0, facts.adherence.expected)
        assertNull(facts.adherence.percent)
        assertNull(facts.nextDoseAt)
    }
}
