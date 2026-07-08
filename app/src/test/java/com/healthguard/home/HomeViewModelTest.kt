@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)

package com.healthguard.home

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.healthguard.shared.data.DoseStatus
import com.healthguard.shared.data.MedicationRepository
import com.healthguard.shared.data.StoredDoseLog
import com.healthguard.shared.data.StoredMedication
import com.healthguard.shared.data.StoredSchedule
import com.healthguard.shared.db.HealthGuardDb
import com.healthguard.shared.extraction.Frequency
import java.util.Properties
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
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
import org.junit.Assert.assertFalse
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
        HealthGuardDb.Schema.create(driver)
        repository = MedicationRepository(HealthGuardDb(driver), dispatcher)
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
    fun `dueAlert is null when nothing is due`() = runTest(dispatcher) {
        insert("nofreq", frequency = null, startedAt = fixedNow - 1.hours)
        insert("later", frequency = Frequency.EveryHours(4), startedAt = fixedNow - 6.hours)
        logTaken("later", fixedNow - 1.hours) // next dose in 3h
        val vm = viewModel()
        collectState(vm)

        assertNull(vm.state.value.dueAlert)
        assertEquals(2, vm.state.value.taking.size)
    }

    @Test
    fun `dueAlert carries the single due card with no others`() = runTest(dispatcher) {
        insert("due", frequency = Frequency.EveryHours(4), startedAt = fixedNow - 1.hours)
        insert("later", frequency = Frequency.EveryHours(4), startedAt = fixedNow - 6.hours)
        logTaken("later", fixedNow - 1.hours)
        val vm = viewModel()
        collectState(vm)

        val alert = vm.state.value.dueAlert!!
        assertEquals("due", alert.card.item.medication.id)
        assertEquals(0, alert.othersDueCount)
    }

    @Test
    fun `dueAlert picks the most overdue card and counts the rest`() = runTest(dispatcher) {
        insert("od1", frequency = Frequency.EveryHours(6), startedAt = fixedNow - 1.hours)
        insert("od2", frequency = Frequency.EveryHours(6), startedAt = fixedNow - 3.hours)
        insert("od3", frequency = Frequency.EveryHours(6), startedAt = fixedNow - 2.hours)
        val vm = viewModel()
        collectState(vm)

        val alert = vm.state.value.dueAlert!!
        assertEquals("od2", alert.card.item.medication.id)
        assertEquals(2, alert.othersDueCount)
    }

    @Test
    fun `activity aggregates recent takes into day counts streak and today count`() = runTest(dispatcher) {
        insert("a", frequency = Frequency.EveryHours(6), startedAt = fixedNow - 72.hours)
        logTaken("a", fixedNow - 26.hours) // yesterday 08:00 UTC
        logTaken("a", fixedNow - 2.hours) // today 08:00
        logTaken("a", fixedNow - 1.hours) // today 09:00
        val vm = viewModel()
        collectState(vm)

        val state = vm.state.value
        assertEquals(2, state.activityStreakDays)
        assertEquals(2, state.activityTodayCount)
        assertEquals(
            listOf(1, 2),
            state.activityDayCounts.map { it.count },
        )
    }

    @Test
    fun `activity ignores takes older than the sixteen week window`() = runTest(dispatcher) {
        insert("a", frequency = Frequency.EveryHours(6), startedAt = fixedNow - 5000.hours)
        // Window starts Monday 2024-03-18 (16 heat-map week columns).
        logTaken("a", Instant.parse("2024-03-17T12:00:00Z")) // just outside
        logTaken("a", Instant.parse("2024-03-18T00:00:00Z")) // first instant inside
        val vm = viewModel()
        collectState(vm)

        assertEquals(1, vm.state.value.activityDayCounts.sumOf { it.count })
    }

    @Test
    fun `takeNow refresh folds the new take into activity`() = runTest(dispatcher) {
        insert("a", frequency = Frequency.EveryHours(6), startedAt = fixedNow - 2.hours)
        val vm = viewModel()
        collectState(vm)
        assertEquals(0, vm.state.value.activityTodayCount)

        vm.takeNow(vm.state.value.taking.single())
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, vm.state.value.activityTodayCount)
        assertEquals(1, vm.state.value.activityStreakDays)
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
    fun `takeNow within 30 minutes of the last take raises the double dose guard`() = runTest(dispatcher) {
        insert("a", frequency = Frequency.EveryHours(6), startedAt = fixedNow - 5.hours)
        logTaken("a", fixedNow - 29.minutes)
        val vm = viewModel()
        collectState(vm)

        vm.takeNow(vm.state.value.taking.single())
        dispatcher.scheduler.advanceUntilIdle()

        val confirm = vm.takeConfirm.value
        assertEquals(29L, confirm?.minutesAgo)
        assertEquals("a", confirm?.card?.item?.medication?.id)
        // Nothing was logged.
        assertEquals(fixedNow - 29.minutes, repository.latestDose("sched-a")?.takenAt)
        assertNull(vm.recentTake.value)
    }

    @Test
    fun `takeNow more than 30 minutes after the last take logs directly`() = runTest(dispatcher) {
        insert("a", frequency = Frequency.EveryHours(6), startedAt = fixedNow - 5.hours)
        logTaken("a", fixedNow - 31.minutes)
        val vm = viewModel()
        collectState(vm)

        vm.takeNow(vm.state.value.taking.single())
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.takeConfirm.value)
        assertEquals(fixedNow, repository.latestDose("sched-a")?.takenAt)
    }

    @Test
    fun `confirmTakeAnyway records despite the guard`() = runTest(dispatcher) {
        insert("a", frequency = Frequency.EveryHours(6), startedAt = fixedNow - 5.hours)
        logTaken("a", fixedNow - 10.minutes)
        val vm = viewModel()
        collectState(vm)
        vm.takeNow(vm.state.value.taking.single())
        dispatcher.scheduler.advanceUntilIdle()

        vm.confirmTakeAnyway()
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.takeConfirm.value)
        assertEquals(fixedNow, repository.latestDose("sched-a")?.takenAt)
        assertEquals(fixedNow + 6.hours, vm.state.value.taking.single().nextDoseAt)
    }

    @Test
    fun `dismissTakeConfirm clears the guard without logging`() = runTest(dispatcher) {
        insert("a", frequency = Frequency.EveryHours(6), startedAt = fixedNow - 5.hours)
        logTaken("a", fixedNow - 10.minutes)
        val vm = viewModel()
        collectState(vm)
        vm.takeNow(vm.state.value.taking.single())
        dispatcher.scheduler.advanceUntilIdle()

        vm.dismissTakeConfirm()

        assertNull(vm.takeConfirm.value)
        assertEquals(fixedNow - 10.minutes, repository.latestDose("sched-a")?.takenAt)
    }

    @Test
    fun `takeNow exposes an undoable recent take`() = runTest(dispatcher) {
        insert("a", drugName = "Ibuprofen", frequency = Frequency.EveryHours(6), startedAt = fixedNow - 2.hours)
        val vm = viewModel()
        collectState(vm)

        vm.takeNow(vm.state.value.taking.single())
        dispatcher.scheduler.advanceUntilIdle()

        val recent = vm.recentTake.value
        assertEquals("Ibuprofen", recent?.drugName)
        assertEquals(repository.latestDose("sched-a")?.id, recent?.doseId)
    }

    @Test
    fun `undoTake removes the log and restores the previous next dose`() = runTest(dispatcher) {
        insert("a", frequency = Frequency.EveryHours(6), startedAt = fixedNow - 2.hours)
        val vm = viewModel()
        collectState(vm)
        vm.takeNow(vm.state.value.taking.single())
        dispatcher.scheduler.advanceUntilIdle()
        val doseId = vm.recentTake.value!!.doseId
        assertEquals(fixedNow + 6.hours, vm.state.value.taking.single().nextDoseAt)

        vm.undoTake(doseId)
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(repository.latestDose("sched-a"))
        assertNull(vm.recentTake.value)
        // Back to the untaken first dose, due at startedAt.
        assertEquals(fixedNow - 2.hours, vm.state.value.taking.single().nextDoseAt)
    }

    @Test
    fun `dueCount counts due and overdue taking cards`() = runTest(dispatcher) {
        insert("overdue", frequency = Frequency.EveryHours(6), startedAt = fixedNow - 2.hours)
        insert("due-now", frequency = Frequency.EveryHours(6), startedAt = fixedNow - 6.hours)
        logTaken("due-now", fixedNow - 6.hours) // next dose exactly now
        insert("later", frequency = Frequency.EveryHours(6), startedAt = fixedNow - 8.hours)
        logTaken("later", fixedNow - 2.hours) // next dose in 4h
        insert("nofreq", frequency = null, startedAt = fixedNow - 1.hours)
        val vm = viewModel()
        collectState(vm)

        assertEquals(2, vm.state.value.dueCount)
        val byId = vm.state.value.taking.associateBy { it.item.medication.id }
        assertTrue(byId.getValue("overdue").isDue)
        assertTrue(byId.getValue("due-now").isDue)
        assertFalse(byId.getValue("later").isDue)
        assertFalse(byId.getValue("nofreq").isDue)
    }

    @Test
    fun `taking resorts after the most urgent dose is taken`() = runTest(dispatcher) {
        insert("urgent", frequency = Frequency.EveryHours(6), startedAt = fixedNow - 2.hours)
        insert("later", frequency = Frequency.EveryHours(2), startedAt = fixedNow - 8.hours)
        logTaken("later", fixedNow - 1.hours) // next dose in 1h
        val vm = viewModel()
        collectState(vm)
        assertEquals(
            listOf("urgent", "later"),
            vm.state.value.taking.map { it.item.medication.id },
        )

        vm.takeNow(vm.state.value.taking.first())
        dispatcher.scheduler.advanceUntilIdle()

        // urgent's next dose is now in 6h, behind later's 1h.
        assertEquals(
            listOf("later", "urgent"),
            vm.state.value.taking.map { it.item.medication.id },
        )
    }

    @Test
    fun `ticker re-emission recomputes against the current clock`() = runTest(dispatcher) {
        // TimesPerDay(2) slots at 09:00 and 21:00 UTC. Taken at 09:05,
        // now 10:00 -> next slot today 21:00.
        insert("a", frequency = Frequency.TimesPerDay(2), startedAt = Instant.parse("2024-07-03T08:30:00Z"))
        logTaken("a", Instant.parse("2024-07-03T09:05:00Z"))
        val vm = viewModel()
        collectState(vm)
        assertEquals(Instant.parse("2024-07-03T21:00:00Z"), vm.state.value.taking.single().nextDoseAt)

        // The 21:00 slot passes untaken; a tick must roll to tomorrow 09:00.
        now = Instant.parse("2024-07-03T22:30:00Z")
        ticker.emit(Unit)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(Instant.parse("2024-07-04T09:00:00Z"), vm.state.value.taking.single().nextDoseAt)
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
