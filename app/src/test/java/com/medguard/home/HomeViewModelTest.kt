@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)

package com.medguard.home

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.medguard.shared.data.DoseStatus
import com.medguard.shared.data.MedicationRepository
import com.medguard.shared.data.StoredDoseLog
import com.medguard.shared.data.StoredMedication
import com.medguard.shared.data.StoredSchedule
import com.medguard.shared.db.MedGuardDb
import com.medguard.shared.extraction.Frequency
import java.util.Properties
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HomeViewModelTest {

    private lateinit var dispatcher: TestDispatcher
    private lateinit var repository: MedicationRepository

    /** 2024-07-03T10:00:00Z — mid-morning UTC so TimesPerDay slots are easy. */
    private val fixedNow = Instant.parse("2024-07-03T10:00:00Z")

    /** Mutable so tests can advance time between ticker emissions. */
    private var now = fixedNow

    /** Replay(1) so combine fires immediately; re-emit to simulate a tick. */
    private val ticker = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }

    @Before
    fun setUp() {
        now = fixedNow
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        val driver = JdbcSqliteDriver(
            JdbcSqliteDriver.IN_MEMORY,
            Properties().apply { put("foreign_keys", "true") },
        )
        MedGuardDb.Schema.create(driver)
        repository = MedicationRepository(MedGuardDb(driver), dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = HomeViewModel(
        repository = repository,
        clock = { now },
        zone = TimeZone.UTC,
        ticker = ticker,
    )

    private suspend fun insert(
        id: String,
        drugName: String = "Ibuprofen",
        createdAtMillis: Long = 1_000,
        frequency: Frequency? = Frequency.TimesPerDay(2),
        startedAt: Instant? = null,
        stoppedAt: Instant? = null,
    ) {
        repository.insertMedication(
            StoredMedication(
                id = id,
                drugName = drugName,
                label = null,
                activeIngredients = emptyList(),
                dosage = "200 mg",
                form = "tablet",
                extractionConfidence = 0.9,
                createdAt = Instant.fromEpochMilliseconds(createdAtMillis),
            ),
            StoredSchedule(
                id = "sched-$id",
                medicationId = id,
                frequency = frequency,
                withFood = true,
                startedAt = startedAt,
                stoppedAt = stoppedAt,
            ),
        )
    }

    private suspend fun logTaken(medicationId: String, takenAt: Instant) {
        repository.logDose(
            StoredDoseLog(
                id = "dose-$medicationId-${takenAt.toEpochMilliseconds()}",
                scheduleId = "sched-$medicationId",
                plannedAt = takenAt,
                takenAt = takenAt,
                status = DoseStatus.TAKEN,
            ),
        )
    }

    private fun TestScope.collectState(vm: HomeViewModel) {
        backgroundScope.launch { vm.state.collect {} }
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `taking sorts overdue first then ascending with null frequency last`() = runTest(dispatcher) {
        // No dose logged -> nextDoseAt = startedAt, which is in the past.
        insert("od1", frequency = Frequency.EveryHours(6), startedAt = fixedNow - 1.hours)
        insert("od2", frequency = Frequency.EveryHours(6), startedAt = fixedNow - 3.hours)
        insert("fut", frequency = Frequency.EveryHours(6), startedAt = fixedNow - 8.hours)
        logTaken("fut", fixedNow - 2.hours) // next dose in 4h
        insert("nofreq", frequency = null, startedAt = fixedNow - 1.hours)
        val vm = viewModel()
        collectState(vm)

        val taking = vm.state.value.taking
        assertEquals(listOf("od2", "od1", "fut", "nofreq"), taking.map { it.item.medication.id })
        assertEquals(fixedNow - 3.hours, taking[0].nextDoseAt)
        assertEquals(fixedNow + 4.hours, taking[2].nextDoseAt)
        assertNull(taking[3].nextDoseAt)
    }

    @Test
    fun `nextUp is the first taking entry with a non-null next dose`() = runTest(dispatcher) {
        insert("nofreq", frequency = null, startedAt = fixedNow - 1.hours)
        insert("due", frequency = Frequency.EveryHours(4), startedAt = fixedNow - 6.hours)
        logTaken("due", fixedNow - 1.hours)
        val vm = viewModel()
        collectState(vm)

        assertEquals("due", vm.state.value.nextUp?.item?.medication?.id)
        assertEquals(fixedNow + 3.hours, vm.state.value.nextUp?.nextDoseAt)
    }

    @Test
    fun `nextUp is null when no taking entry has a next dose`() = runTest(dispatcher) {
        insert("nofreq", frequency = null, startedAt = fixedNow - 1.hours)
        insert("dormant")
        val vm = viewModel()
        collectState(vm)

        assertNull(vm.state.value.nextUp)
        assertEquals(1, vm.state.value.taking.size)
    }

    @Test
    fun `takeNow logs a taken dose and advances the next dose`() = runTest(dispatcher) {
        insert("a", frequency = Frequency.EveryHours(6), startedAt = fixedNow - 2.hours)
        val vm = viewModel()
        collectState(vm)
        val card = vm.state.value.taking.single()
        assertEquals(fixedNow - 2.hours, card.nextDoseAt) // overdue first dose

        vm.takeNow(card)
        dispatcher.scheduler.advanceUntilIdle()

        val logged = repository.latestDose("sched-a")!!
        assertEquals(DoseStatus.TAKEN, logged.status)
        assertEquals(fixedNow, logged.takenAt)
        assertEquals(fixedNow - 2.hours, logged.plannedAt)
        // Dose logs do not retrigger the medications query; the refresh bump
        // must recompute the card from the new latest dose.
        assertEquals(fixedNow + 6.hours, vm.state.value.taking.single().nextDoseAt)
    }

    @Test
    fun `takeNow on a card without a next dose plans it at the clock time`() = runTest(dispatcher) {
        insert("nofreq", frequency = null, startedAt = fixedNow - 1.hours)
        val vm = viewModel()
        collectState(vm)

        vm.takeNow(vm.state.value.taking.single())
        dispatcher.scheduler.advanceUntilIdle()

        val logged = repository.latestDose("sched-nofreq")!!
        assertEquals(fixedNow, logged.plannedAt)
        assertEquals(fixedNow, logged.takenAt)
    }

    @Test
    fun `ticker re-emission recomputes against the current clock`() = runTest(dispatcher) {
        // TimesPerDay(2) slots at 08:00 and 22:00 UTC. Taken at 09:00,
        // now 10:00 -> next slot today 22:00.
        insert("a", frequency = Frequency.TimesPerDay(2), startedAt = Instant.parse("2024-07-03T08:30:00Z"))
        logTaken("a", Instant.parse("2024-07-03T09:00:00Z"))
        val vm = viewModel()
        collectState(vm)
        assertEquals(Instant.parse("2024-07-03T22:00:00Z"), vm.state.value.taking.single().nextDoseAt)

        // The 22:00 slot passes untaken; a tick must roll to tomorrow 08:00.
        now = Instant.parse("2024-07-03T22:30:00Z")
        ticker.emit(Unit)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(Instant.parse("2024-07-04T08:00:00Z"), vm.state.value.taking.single().nextDoseAt)
    }

    @Test
    fun `dormant and stopped items are in the cabinet newest first and never in taking`() = runTest(dispatcher) {
        insert("older-dormant", createdAtMillis = 1_000)
        insert("newer-dormant", createdAtMillis = 2_000)
        insert("stopped", createdAtMillis = 3_000, startedAt = fixedNow - 2.hours, stoppedAt = fixedNow - 1.hours)
        insert("active", createdAtMillis = 4_000, startedAt = fixedNow - 1.hours)
        val vm = viewModel()
        collectState(vm)

        val state = vm.state.value
        assertEquals(
            listOf("stopped", "newer-dormant", "older-dormant"),
            state.cabinet.map { it.medication.id },
        )
        assertEquals(listOf("active"), state.taking.map { it.item.medication.id })
    }

    @Test
    fun `onPlay activates and moves the medication into taking`() = runTest(dispatcher) {
        insert("a")
        val vm = viewModel()
        collectState(vm)
        assertTrue(vm.state.value.taking.isEmpty())

        vm.onPlay("a")
        dispatcher.scheduler.advanceUntilIdle()

        val card = vm.state.value.taking.single()
        assertEquals(fixedNow, card.item.schedule.startedAt)
        assertTrue(vm.state.value.cabinet.isEmpty())
    }

    @Test
    fun `onStop moves the medication back to the cabinet`() = runTest(dispatcher) {
        insert("a", startedAt = fixedNow - 1.hours)
        val vm = viewModel()
        collectState(vm)

        vm.onStop("a")
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.state.value.taking.isEmpty())
        assertEquals(fixedNow, vm.state.value.cabinet.single().schedule.stoppedAt)
    }

    @Test
    fun `onDelete removes the medication entirely`() = runTest(dispatcher) {
        insert("a")
        insert("b")
        val vm = viewModel()
        collectState(vm)
        assertEquals(2, vm.state.value.cabinet.size)

        vm.onDelete("a")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("b"), vm.state.value.cabinet.map { it.medication.id })
    }

    @Test
    fun `isActive is true only for started and not stopped schedules`() = runTest(dispatcher) {
        insert("dormant")
        insert("active", startedAt = fixedNow)
        insert("stopped", startedAt = Instant.fromEpochMilliseconds(1), stoppedAt = fixedNow)
        val vm = viewModel()
        collectState(vm)

        val all = vm.state.value.taking.map { it.item } + vm.state.value.cabinet
        val byId = all.associateBy { it.medication.id }
        assertTrue(byId.getValue("active").isActive)
        assertTrue(!byId.getValue("dormant").isActive)
        assertTrue(!byId.getValue("stopped").isActive)
    }
}
