@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)

package com.healthguard.home

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.healthguard.activity.DoseDayStatus
import com.healthguard.home.domain.ActivateMedicationUseCase
import com.healthguard.home.domain.ComputeHomeStateUseCase
import com.healthguard.home.domain.RecordDoseUseCase
import com.healthguard.home.domain.RemoveDemoDataUseCase
import com.healthguard.home.domain.SeedDemoDataUseCase
import com.healthguard.home.domain.UndoDoseUseCase
import com.healthguard.shared.data.DoseStatus
import com.healthguard.shared.data.MedicationRepository
import com.healthguard.shared.data.SqlDelightMedicationRepository
import com.healthguard.shared.domain.ObserveMedicationsUseCase
import com.healthguard.shared.data.StoredDoseLog
import com.healthguard.shared.data.StoredMedication
import com.healthguard.shared.data.StoredSchedule
import com.healthguard.shared.db.HealthGuardDb
import com.healthguard.shared.extraction.Frequency
import java.util.Properties
import kotlin.time.Duration.Companion.days
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
        repository = SqlDelightMedicationRepository(HealthGuardDb(driver), dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = HomeViewModel(
        computeHomeState = ComputeHomeStateUseCase(repository, clock = { now }, zone = TimeZone.UTC),
        recordDose = RecordDoseUseCase(repository, clock = { now }),
        undoDose = UndoDoseUseCase(repository),
        activateMedication = ActivateMedicationUseCase(repository, clock = { now }),
        observeMedications = ObserveMedicationsUseCase(repository),
        seedDemoData = SeedDemoDataUseCase(repository, clock = { now }, zone = TimeZone.UTC),
        removeDemoData = RemoveDemoDataUseCase(repository),
        clock = { now },
        zone = TimeZone.UTC,
        ticker = ticker,
    )

    /** Records every effect the view model emits, for the undo-snackbar assertions. */
    private fun TestScope.collectEffects(vm: HomeViewModel): List<HomeEffect> {
        val effects = mutableListOf<HomeEffect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }
        return effects
    }

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
    fun `week card measures each day against the schedule and captions it`() = runTest(dispatcher) {
        // 2x/day since Sunday 6/30 10:00: owed 21:00 that day, both slots
        // on 7/1 and 7/2, and 09:00 today so far.
        insert("a", frequency = Frequency.TimesPerDay(2), startedAt = fixedNow - 72.hours)
        logTaken("a", Instant.parse("2024-06-30T21:00:00Z"))
        logTaken("a", Instant.parse("2024-07-01T09:00:00Z"))
        logTaken("a", Instant.parse("2024-07-01T21:05:00Z"))
        logTaken("a", Instant.parse("2024-07-02T09:00:00Z")) // 21:00 silent
        logTaken("a", Instant.parse("2024-07-03T09:02:00Z"))
        val vm = viewModel()
        collectState(vm)

        val state = vm.state.value
        assertEquals(7, state.weekDays.size)
        assertEquals(
            listOf(
                DoseDayStatus.OUT_OF_TREATMENT, DoseDayStatus.OUT_OF_TREATMENT, DoseDayStatus.OUT_OF_TREATMENT,
                DoseDayStatus.MET, DoseDayStatus.MET, DoseDayStatus.PARTIAL,
                DoseDayStatus.MET,
            ),
            state.weekDays.map { it.state },
        )
        // Today's 21:00 slot is still ahead and the day is on pace so far:
        // excluded from the tally, as are the days before the schedule began.
        assertEquals("2 of 3 days on track. Today still to come.", state.weekCaption)
    }

    @Test
    fun `week caption counts today once no slot is left today`() = runTest(dispatcher) {
        // 1x/day slot at 09:00; taken today at 09:05 -> nothing pending.
        // 7/1 and 7/2 passed silently: they count against the week now.
        insert("a", frequency = Frequency.TimesPerDay(1), startedAt = fixedNow - 72.hours)
        logTaken("a", fixedNow - 55.minutes) // today 09:05
        val vm = viewModel()
        collectState(vm)

        assertEquals("1 of 3 days on track.", vm.state.value.weekCaption)
    }

    @Test
    fun `as-needed schedules leave the week card unscheduled`() = runTest(dispatcher) {
        insert("a", frequency = Frequency.EveryHours(6), startedAt = fixedNow - 5000.hours)
        logTaken("a", fixedNow - 2.hours)
        val vm = viewModel()
        collectState(vm)

        assertTrue(vm.state.value.weekDays.all { it.state == DoseDayStatus.OUT_OF_TREATMENT })
        assertEquals("No scheduled doses this week.", vm.state.value.weekCaption)
    }

    @Test
    fun `takeNow refresh updates the week card and the row status`() = runTest(dispatcher) {
        // 2x/day slots 09:00/21:00; overdue 09:00 dose pending.
        insert("a", frequency = Frequency.TimesPerDay(2), startedAt = fixedNow - 26.hours)
        val vm = viewModel()
        collectState(vm)
        val before = vm.state.value.taking.single()
        assertTrue(before.isDue)
        assertEquals(DoseRowStatus.Due, before.status)

        vm.onIntent(HomeIntent.TakeNow(before))
        dispatcher.scheduler.advanceUntilIdle()

        // The row is no longer a stale "Take": next slot today is 21:00, 11h out.
        val after = vm.state.value.taking.single()
        assertFalse(after.isDue)
        assertEquals(DoseRowStatus.Next("Next at 9:00 PM"), after.status)
        assertEquals(DoseDayStatus.MET, vm.state.value.weekDays.last().state)
    }

    @Test
    fun `row status becomes TakenForToday after the last slot of the day`() = runTest(dispatcher) {
        // 1x/day slot 09:00, taken at 09:30 -> nothing left today.
        insert("a", frequency = Frequency.TimesPerDay(1), startedAt = fixedNow - 26.hours)
        logTaken("a", fixedNow - 30.minutes)
        val vm = viewModel()
        collectState(vm)

        assertEquals(DoseRowStatus.TakenForToday, vm.state.value.taking.single().status)
    }

    @Test
    fun `takeNow logs a taken dose and advances the next dose`() = runTest(dispatcher) {
        insert("a", frequency = Frequency.EveryHours(6), startedAt = fixedNow - 2.hours)
        val vm = viewModel()
        collectState(vm)
        val card = vm.state.value.taking.single()
        assertEquals(fixedNow - 2.hours, card.nextDoseAt) // overdue first dose

        vm.onIntent(HomeIntent.TakeNow(card))
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

        vm.onIntent(HomeIntent.TakeNow(vm.state.value.taking.single()))
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
        val effects = collectEffects(vm)

        vm.onIntent(HomeIntent.TakeNow(vm.state.value.taking.single()))
        dispatcher.scheduler.advanceUntilIdle()

        val confirm = vm.state.value.takeConfirm
        assertEquals(29L, confirm?.minutesAgo)
        assertEquals("a", confirm?.card?.item?.medication?.id)
        // Nothing was logged.
        assertEquals(fixedNow - 29.minutes, repository.latestDose("sched-a")?.takenAt)
        assertTrue(effects.filterIsInstance<HomeEffect.ShowUndoSnackbar>().isEmpty())
    }

    @Test
    fun `takeNow more than 30 minutes after the last take logs directly`() = runTest(dispatcher) {
        insert("a", frequency = Frequency.EveryHours(6), startedAt = fixedNow - 5.hours)
        logTaken("a", fixedNow - 31.minutes)
        val vm = viewModel()
        collectState(vm)

        vm.onIntent(HomeIntent.TakeNow(vm.state.value.taking.single()))
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.state.value.takeConfirm)
        assertEquals(fixedNow, repository.latestDose("sched-a")?.takenAt)
    }

    @Test
    fun `confirmTakeAnyway records despite the guard`() = runTest(dispatcher) {
        insert("a", frequency = Frequency.EveryHours(6), startedAt = fixedNow - 5.hours)
        logTaken("a", fixedNow - 10.minutes)
        val vm = viewModel()
        collectState(vm)
        vm.onIntent(HomeIntent.TakeNow(vm.state.value.taking.single()))
        dispatcher.scheduler.advanceUntilIdle()

        vm.onIntent(HomeIntent.ConfirmTakeAnyway)
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.state.value.takeConfirm)
        assertEquals(fixedNow, repository.latestDose("sched-a")?.takenAt)
        assertEquals(fixedNow + 6.hours, vm.state.value.taking.single().nextDoseAt)
    }

    @Test
    fun `dismissTakeConfirm clears the guard without logging`() = runTest(dispatcher) {
        insert("a", frequency = Frequency.EveryHours(6), startedAt = fixedNow - 5.hours)
        logTaken("a", fixedNow - 10.minutes)
        val vm = viewModel()
        collectState(vm)
        vm.onIntent(HomeIntent.TakeNow(vm.state.value.taking.single()))
        dispatcher.scheduler.advanceUntilIdle()

        vm.onIntent(HomeIntent.DismissTakeConfirm)

        assertNull(vm.state.value.takeConfirm)
        assertEquals(fixedNow - 10.minutes, repository.latestDose("sched-a")?.takenAt)
    }

    @Test
    fun `takeNow exposes an undoable recent take`() = runTest(dispatcher) {
        insert("a", drugName = "Ibuprofen", frequency = Frequency.EveryHours(6), startedAt = fixedNow - 2.hours)
        val vm = viewModel()
        collectState(vm)
        val effects = collectEffects(vm)

        vm.onIntent(HomeIntent.TakeNow(vm.state.value.taking.single()))
        dispatcher.scheduler.advanceUntilIdle()

        val recent = effects.filterIsInstance<HomeEffect.ShowUndoSnackbar>().last().take
        assertEquals("Ibuprofen", recent.drugName)
        assertEquals(repository.latestDose("sched-a")?.id, recent.doseId)
    }

    @Test
    fun `undoTake removes the log and restores the previous next dose`() = runTest(dispatcher) {
        insert("a", frequency = Frequency.EveryHours(6), startedAt = fixedNow - 2.hours)
        val vm = viewModel()
        collectState(vm)
        val effects = collectEffects(vm)
        vm.onIntent(HomeIntent.TakeNow(vm.state.value.taking.single()))
        dispatcher.scheduler.advanceUntilIdle()
        val doseId = effects.filterIsInstance<HomeEffect.ShowUndoSnackbar>().last().take.doseId
        assertEquals(fixedNow + 6.hours, vm.state.value.taking.single().nextDoseAt)

        vm.onIntent(HomeIntent.UndoTake(doseId))
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(repository.latestDose("sched-a"))
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

        vm.onIntent(HomeIntent.TakeNow(vm.state.value.taking.first()))
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
    fun `every tick propagates a fresh now even when nothing else changed`() = runTest(dispatcher) {
        // An overdue dose whose computed facts are identical between ticks:
        // without `now` in the state the recomputation produced an EQUAL
        // HomeUiState that MutableStateFlow conflated away, freezing every
        // clock-derived string on screen (the "overdue Xm" countdown).
        insert("a", frequency = Frequency.EveryHours(6), startedAt = fixedNow - 2.hours)
        val vm = viewModel()
        collectState(vm)
        assertEquals(fixedNow, vm.state.value.now)
        val before = vm.state.value

        now = fixedNow + 5.minutes
        ticker.emit(Unit)
        dispatcher.scheduler.advanceUntilIdle()

        val after = vm.state.value
        assertEquals(fixedNow + 5.minutes, after.now)
        // The only difference is the clock — proving equal-content states
        // relied on `now` to get past the StateFlow's equality conflation.
        assertEquals(before.copy(now = fixedNow + 5.minutes), after)
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

        vm.onIntent(HomeIntent.Play("a"))
        dispatcher.scheduler.advanceUntilIdle()

        val card = vm.state.value.taking.single()
        assertEquals(fixedNow, card.item.schedule.startedAt)
        assertTrue(vm.state.value.cabinet.isEmpty())
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
