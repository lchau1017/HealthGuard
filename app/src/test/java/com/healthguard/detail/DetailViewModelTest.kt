@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)

package com.healthguard.detail

import com.healthguard.activity.AdherenceResult
import com.healthguard.activity.DayDetail
import com.healthguard.activity.DayMedicineLine
import com.healthguard.activity.DoseDayStatus
import com.healthguard.detail.domain.ComputeDetailStateUseCase
import com.healthguard.detail.domain.LoadDayDetailUseCase
import com.healthguard.detail.domain.SaveMedicationUseCase
import com.healthguard.detail.state.DetailEffect
import com.healthguard.detail.state.DetailFinished
import com.healthguard.detail.state.DetailIntent
import com.healthguard.detail.state.HistoryRowKind
import com.healthguard.home.MedicationPhase
import com.healthguard.home.domain.ActivateMedicationUseCase
import com.healthguard.home.domain.DeleteMedicationUseCase
import com.healthguard.home.domain.RecordDoseUseCase
import com.healthguard.home.domain.StopMedicationUseCase
import com.healthguard.home.domain.UndoDoseUseCase
import com.healthguard.domain.model.DoseStatus
import com.healthguard.data.SqlDelightMedicationRepository
import com.healthguard.domain.model.StoredDoseLog
import com.healthguard.domain.usecase.ObserveMedicationsUseCase
import com.healthguard.domain.extraction.Frequency
import com.healthguard.testing.collectEffects
import com.healthguard.testing.collectState
import com.healthguard.testing.inMemoryRepository
import com.healthguard.testing.seedMedication
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DetailViewModelTest {

    private lateinit var dispatcher: TestDispatcher
    private lateinit var repository: SqlDelightMedicationRepository

    private val fixedNow = Instant.parse("2024-07-03T10:00:00Z")

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        repository = inMemoryRepository(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(id: String = "med-1") = DetailViewModel(
        computeDetailState = ComputeDetailStateUseCase(repository, clock = { fixedNow }, zone = TimeZone.UTC),
        loadDayDetail = LoadDayDetailUseCase(repository, clock = { fixedNow }, zone = TimeZone.UTC),
        saveMedication = SaveMedicationUseCase(repository),
        recordDose = RecordDoseUseCase(repository, clock = { fixedNow }),
        undoDose = UndoDoseUseCase(repository),
        activateMedication = ActivateMedicationUseCase(repository, clock = { fixedNow }),
        stopMedication = StopMedicationUseCase(repository, clock = { fixedNow }),
        deleteMedication = DeleteMedicationUseCase(repository),
        observeMedications = ObserveMedicationsUseCase(repository),
        clock = { fixedNow },
        medicationId = id,
        zone = TimeZone.UTC,
    )

    /** The one detail medication under test: Cetirizine on schedule "sch-1". */
    private suspend fun insert(
        frequency: Frequency? = Frequency.TimesPerDay(2),
        withFood: Boolean? = true,
        startedAt: Instant? = null,
        stoppedAt: Instant? = null,
    ) = repository.seedMedication(
        id = "med-1",
        drugName = "Cetirizine",
        scheduleId = "sch-1",
        label = "Allergy",
        activeIngredients = listOf("cetirizine hydrochloride", "lactose"),
        dosage = "10 mg",
        form = "tablet",
        extractionConfidence = 0.95,
        frequency = frequency,
        withFood = withFood,
        startedAt = startedAt,
        stoppedAt = stoppedAt,
    )

    @Test
    fun `loads the medication and seeds the editable fields`() = runTest(dispatcher) {
        insert()
        val vm = viewModel()
        collectState(vm.state)

        val state = vm.state.value
        assertEquals("Cetirizine", state.name)
        assertEquals("10 mg", state.dosage)
        assertEquals("tablet", state.form)
        assertEquals("Allergy", state.label)
        assertEquals("cetirizine hydrochloride, lactose", state.ingredients)
        assertEquals("2 times a day", state.frequencyText)
        assertEquals(true, state.withFood)
        assertTrue(state.isLoaded)
    }

    @Test
    fun `state carries the wall clock the content was computed against`() = runTest(dispatcher) {
        // Minute-grained rendering (phase chip, last-taken line, history
        // rows) reads state.now; only the countdown owns a live ticker.
        insert()
        val vm = viewModel()
        collectState(vm.state)

        assertEquals(fixedNow, vm.state.value.now)
    }

    @Test
    fun `save maps every field including a changed frequency`() = runTest(dispatcher) {
        insert(frequency = Frequency.TimesPerDay(2))
        val vm = viewModel()
        collectState(vm.state)
        val effects = collectEffects(vm.effects)

        vm.onIntent(DetailIntent.NameChanged(" Loratadine "))
        vm.onIntent(DetailIntent.DosageChanged("5 mg"))
        vm.onIntent(DetailIntent.FormChanged("capsule"))
        vm.onIntent(DetailIntent.LabelChanged("Hay fever"))
        vm.onIntent(DetailIntent.IngredientsChanged("loratadine, , cellulose "))
        vm.onIntent(DetailIntent.FrequencyChanged("every 8 hours"))
        vm.onIntent(DetailIntent.WithFoodChanged(false))
        vm.onIntent(DetailIntent.Save)
        dispatcher.scheduler.advanceUntilIdle()

        val stored = repository.getMedication("med-1")!!
        assertEquals("Loratadine", stored.medication.drugName)
        assertEquals("5 mg", stored.medication.dosage)
        assertEquals("capsule", stored.medication.form)
        assertEquals("Hay fever", stored.medication.label)
        assertEquals(listOf("loratadine", "cellulose"), stored.medication.activeIngredients)
        assertEquals(Frequency.EveryHours(8), stored.schedule.frequency)
        assertEquals(false, stored.schedule.withFood)
        assertTrue(effects.contains(DetailEffect.Finished(DetailFinished.SAVED)))
    }

    @Test
    fun `blank frequency text saves a null frequency`() = runTest(dispatcher) {
        insert(frequency = Frequency.TimesPerDay(2))
        val vm = viewModel()
        collectState(vm.state)

        vm.onIntent(DetailIntent.FrequencyChanged("   "))
        assertFalse(vm.state.value.frequencyError)
        vm.onIntent(DetailIntent.Save)
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(repository.getMedication("med-1")!!.schedule.frequency)
    }

    @Test
    fun `blank label form and dosage save as null`() = runTest(dispatcher) {
        insert()
        val vm = viewModel()
        collectState(vm.state)

        vm.onIntent(DetailIntent.LabelChanged(""))
        vm.onIntent(DetailIntent.DosageChanged(" "))
        vm.onIntent(DetailIntent.FormChanged(""))
        vm.onIntent(DetailIntent.IngredientsChanged(""))
        vm.onIntent(DetailIntent.Save)
        dispatcher.scheduler.advanceUntilIdle()

        val stored = repository.getMedication("med-1")!!.medication
        assertNull(stored.label)
        assertNull(stored.dosage)
        assertNull(stored.form)
        assertEquals(emptyList<String>(), stored.activeIngredients)
    }

    @Test
    fun `blank name blocks save`() = runTest(dispatcher) {
        insert()
        val vm = viewModel()
        collectState(vm.state)
        val effects = collectEffects(vm.effects)

        vm.onIntent(DetailIntent.NameChanged("   "))
        assertTrue(vm.state.value.nameError)
        assertFalse(vm.state.value.canSave)
        vm.onIntent(DetailIntent.Save)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Cetirizine", repository.getMedication("med-1")!!.medication.drugName)
        assertTrue(effects.filterIsInstance<DetailEffect.Finished>().isEmpty())
    }

    @Test
    fun `unparseable frequency flags an error and blocks save`() = runTest(dispatcher) {
        insert(frequency = Frequency.TimesPerDay(2))
        val vm = viewModel()
        collectState(vm.state)
        val effects = collectEffects(vm.effects)

        vm.onIntent(DetailIntent.FrequencyChanged("whenever I remember"))
        assertTrue(vm.state.value.frequencyError)
        assertFalse(vm.state.value.canSave)
        vm.onIntent(DetailIntent.Save)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            Frequency.TimesPerDay(2),
            repository.getMedication("med-1")!!.schedule.frequency,
        )
        assertTrue(effects.filterIsInstance<DetailEffect.Finished>().isEmpty())
    }

    @Test
    fun `save leaves startedAt and stoppedAt untouched`() = runTest(dispatcher) {
        insert(startedAt = fixedNow - 5.hours, stoppedAt = fixedNow - 1.hours)
        val vm = viewModel()
        collectState(vm.state)

        vm.onIntent(DetailIntent.NameChanged("Renamed"))
        vm.onIntent(DetailIntent.FrequencyChanged("every 6 hours"))
        vm.onIntent(DetailIntent.Save)
        dispatcher.scheduler.advanceUntilIdle()

        val schedule = repository.getMedication("med-1")!!.schedule
        assertEquals(fixedNow - 5.hours, schedule.startedAt)
        assertEquals(fixedNow - 1.hours, schedule.stoppedAt)
    }

    @Test
    fun `toggleTaking starts a dormant schedule and stops an active one`() = runTest(dispatcher) {
        insert()
        val vm = viewModel()
        collectState(vm.state)
        assertFalse(vm.state.value.isActive)
        assertEquals(MedicationPhase.NOT_STARTED, vm.state.value.phase)

        vm.onIntent(DetailIntent.ToggleTaking)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value.isActive)
        assertEquals(fixedNow, repository.getMedication("med-1")!!.schedule.startedAt)
        assertEquals(MedicationPhase.TAKING, vm.state.value.phase)

        vm.onIntent(DetailIntent.ToggleTaking)
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.state.value.isActive)
        assertEquals(fixedNow, repository.getMedication("med-1")!!.schedule.stoppedAt)
        assertEquals(MedicationPhase.STOPPED, vm.state.value.phase)
    }

    @Test
    fun `active schedule exposes a next dose`() = runTest(dispatcher) {
        insert(frequency = Frequency.EveryHours(6), startedAt = fixedNow - 2.hours)
        val vm = viewModel()
        collectState(vm.state)

        // No dose logged: the first dose was due at startedAt.
        assertEquals(fixedNow - 2.hours, vm.state.value.nextDoseAt)
    }

    @Test
    fun `dormant schedule has no next dose`() = runTest(dispatcher) {
        insert()
        val vm = viewModel()
        collectState(vm.state)

        assertNull(vm.state.value.nextDoseAt)
    }

    @Test
    fun `delete removes the medication and finishes`() = runTest(dispatcher) {
        insert()
        val vm = viewModel()
        collectState(vm.state)
        val effects = collectEffects(vm.effects)

        vm.onIntent(DetailIntent.Delete)
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(repository.getMedication("med-1"))
        assertTrue(effects.contains(DetailEffect.Finished(DetailFinished.DELETED)))
    }

    @Test
    fun `selecting a day scopes the sheet to this medication`() = runTest(dispatcher) {
        insert(startedAt = Instant.parse("2024-07-01T00:00:00Z"))
        logDose(
            "d-1",
            takenAt = Instant.parse("2024-07-02T09:04:00Z"),
            plannedAt = Instant.parse("2024-07-02T09:00:00Z"),
            status = DoseStatus.TAKEN,
        )
        // Another medication's same-day take must not leak into the sheet.
        repository.seedMedication(
            id = "med-2",
            drugName = "Ibuprofen",
            scheduleId = "sch-2",
            createdAtMillis = 2_000,
            frequency = Frequency.EveryHours(6),
            startedAt = Instant.parse("2024-07-01T00:00:00Z"),
        )
        repository.logDose(
            StoredDoseLog(
                id = "d-other",
                scheduleId = "sch-2",
                plannedAt = Instant.parse("2024-07-02T14:00:00Z"),
                takenAt = Instant.parse("2024-07-02T14:00:00Z"),
                status = DoseStatus.TAKEN,
            ),
        )
        val vm = viewModel()
        collectState(vm.state)

        vm.onIntent(DetailIntent.SelectDay(LocalDate(2024, 7, 2)))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            DayDetail(
                date = LocalDate(2024, 7, 2),
                lines = listOf(
                    DayMedicineLine(
                        medicationId = "med-1",
                        name = "Cetirizine 10 mg",
                        takenTimes = listOf(LocalTime(9, 4)),
                        skipped = 0,
                        missed = 0,
                        // The evening (21:00) slot went unanswered.
                        notRecorded = 1,
                    ),
                ),
                expectedNotRecorded = 0,
            ),
            vm.state.value.dayDetail,
        )

        vm.onIntent(DetailIntent.DismissDayDetail)
        assertNull(vm.state.value.dayDetail)
    }

    private suspend fun logDose(
        id: String,
        takenAt: Instant?,
        plannedAt: Instant,
        status: DoseStatus,
    ) {
        repository.logDose(
            StoredDoseLog(
                id = id,
                scheduleId = "sch-1",
                plannedAt = plannedAt,
                takenAt = takenAt,
                status = status,
            ),
        )
    }

    private fun loggedIds(vm: DetailViewModel): List<String> =
        vm.state.value.history
            .filterNot { it.kind == HistoryRowKind.NOT_RECORDED }
            .map { it.id }

    @Test
    fun `history lists recent dose logs newest first`() = runTest(dispatcher) {
        insert(startedAt = fixedNow - 5.hours)
        logDose("d-1", takenAt = fixedNow - 4.hours, plannedAt = fixedNow - 4.hours, status = DoseStatus.TAKEN)
        logDose("d-2", takenAt = null, plannedAt = fixedNow - 2.hours, status = DoseStatus.SKIPPED)
        logDose("d-3", takenAt = fixedNow - 1.hours, plannedAt = fixedNow - 1.hours, status = DoseStatus.TAKEN)
        val vm = viewModel()
        collectState(vm.state)

        assertEquals(listOf("d-3", "d-2", "d-1"), loggedIds(vm))
    }

    @Test
    fun `history synthesizes not-recorded rows for silent expected slots`() = runTest(dispatcher) {
        // 2x/day since 2024-06-30: slots 09:00/21:00. Fully logged on 6/30,
        // morning-only on 7/1, silent on 7/2. Today's 09:00 slot is within
        // the 90-minute answer window (now is 10:00), so it is not a gap yet.
        insert(startedAt = Instant.parse("2024-06-30T00:00:00Z"))
        logDose("d-1", takenAt = Instant.parse("2024-06-30T09:05:00Z"), plannedAt = Instant.parse("2024-06-30T09:00:00Z"), status = DoseStatus.TAKEN)
        logDose("d-2", takenAt = Instant.parse("2024-06-30T21:00:00Z"), plannedAt = Instant.parse("2024-06-30T21:00:00Z"), status = DoseStatus.TAKEN)
        logDose("d-3", takenAt = Instant.parse("2024-07-01T09:10:00Z"), plannedAt = Instant.parse("2024-07-01T09:00:00Z"), status = DoseStatus.TAKEN)
        val vm = viewModel()
        collectState(vm.state)

        assertEquals(
            listOf(
                "slot-2024-07-02T21:00:00Z",
                "slot-2024-07-02T09:00:00Z",
                "slot-2024-07-01T21:00:00Z",
            ),
            vm.state.value.history
                .filter { it.kind == HistoryRowKind.NOT_RECORDED }
                .map { it.id },
        )
        assertEquals(listOf("d-3", "d-2", "d-1"), loggedIds(vm))
        // Interleaved chronologically: the three gaps precede the logged rows.
        assertEquals(
            listOf(true, true, true, false, false, false),
            vm.state.value.history.map { it.kind == HistoryRowKind.NOT_RECORDED },
        )
    }

    @Test
    fun `day completeness and adherence measure against the schedule`() = runTest(dispatcher) {
        // 2x/day started 7/1 at 10:00: owed 21:00 that day, both slots on
        // 7/2, and 09:00 today = 4 expected. Taken 2, one logged miss, one
        // silent slot -> 50%; the silent day counts exactly like the miss.
        insert(startedAt = fixedNow - 48.hours)
        logDose("d-1", takenAt = fixedNow - 37.hours, plannedAt = fixedNow - 37.hours, status = DoseStatus.TAKEN)
        logDose("d-2", takenAt = fixedNow - 25.hours, plannedAt = fixedNow - 25.hours, status = DoseStatus.TAKEN)
        logDose("d-m", takenAt = null, plannedAt = fixedNow - 13.hours, status = DoseStatus.MISSED)
        val vm = viewModel()
        collectState(vm.state)

        val state = vm.state.value
        assertEquals(AdherenceResult(taken = 2, expected = 4, skipped = 0), state.adherence)
        assertEquals(50, state.adherence.percent)
        assertEquals(
            mapOf(
                kotlinx.datetime.LocalDate(2024, 7, 1) to DoseDayStatus.MET,
                kotlinx.datetime.LocalDate(2024, 7, 2) to DoseDayStatus.PARTIAL,
                kotlinx.datetime.LocalDate(2024, 7, 3) to DoseDayStatus.NOT_TAKEN,
            ),
            state.dayStatuses,
        )
        assertFalse(state.isAsNeeded)
        assertNotNull(state.historyFrom)
    }

    @Test
    fun `a fully skipped day is a visible choice not a lapse`() = runTest(dispatcher) {
        // Both of yesterday's slots deliberately skipped; today's 09:00
        // taken. Skips leave the denominator: 1 of (3-2) = 100%.
        insert(startedAt = Instant.parse("2024-07-02T00:00:00Z"))
        logDose("d-s1", takenAt = null, plannedAt = Instant.parse("2024-07-02T09:00:00Z"), status = DoseStatus.SKIPPED)
        logDose("d-s2", takenAt = null, plannedAt = Instant.parse("2024-07-02T21:00:00Z"), status = DoseStatus.SKIPPED)
        logDose("d-t", takenAt = Instant.parse("2024-07-03T09:00:00Z"), plannedAt = Instant.parse("2024-07-03T09:00:00Z"), status = DoseStatus.TAKEN)
        val vm = viewModel()
        collectState(vm.state)

        val state = vm.state.value
        assertEquals(AdherenceResult(taken = 1, expected = 3, skipped = 2), state.adherence)
        assertEquals(100, state.adherence.percent)
        // The skipped day stays visible as a deliberate choice.
        assertEquals(
            mapOf(
                kotlinx.datetime.LocalDate(2024, 7, 2) to DoseDayStatus.SKIPPED,
                kotlinx.datetime.LocalDate(2024, 7, 3) to DoseDayStatus.MET,
            ),
            state.dayStatuses,
        )
    }

    @Test
    fun `a scheduled stretch with no records scores zero not null`() = runTest(dispatcher) {
        insert(startedAt = fixedNow - 48.hours)
        val vm = viewModel()
        collectState(vm.state)

        assertEquals(0, vm.state.value.adherence.percent)
    }

    @Test
    fun `an interval medication is as-needed with counts instead of a percent`() = runTest(dispatcher) {
        insert(frequency = Frequency.EveryHours(6), startedAt = fixedNow - 48.hours)
        logDose("d-1", takenAt = fixedNow - 26.hours, plannedAt = fixedNow - 26.hours, status = DoseStatus.TAKEN)
        logDose("d-2", takenAt = fixedNow - 2.hours, plannedAt = fixedNow - 2.hours, status = DoseStatus.TAKEN)
        val vm = viewModel()
        collectState(vm.state)

        val state = vm.state.value
        assertTrue(state.isAsNeeded)
        assertNull(state.adherence.percent)
        assertEquals(2, state.adherence.taken)
        // No expectations: no completeness cells and no not-recorded rows —
        // the heat map falls back to take counts per day.
        assertTrue(state.dayStatuses.isEmpty())
        assertTrue(state.history.none { it.kind == HistoryRowKind.NOT_RECORDED })
        assertEquals(
            listOf(1, 1),
            state.dayTakeCounts.map { it.count },
        )
    }

    @Test
    fun `dormant schedule has a null percent`() = runTest(dispatcher) {
        insert()
        val vm = viewModel()
        collectState(vm.state)
        assertNull(vm.state.value.adherence.percent)
    }

    @Test
    fun `takeNow logs a taken dose and refreshes the status`() = runTest(dispatcher) {
        // EveryHours(6), never taken: due since startedAt.
        insert(frequency = Frequency.EveryHours(6), startedAt = fixedNow - 2.hours)
        val vm = viewModel()
        collectState(vm.state)
        val effects = collectEffects(vm.effects)
        assertEquals(fixedNow - 2.hours, vm.state.value.nextDoseAt)

        vm.onIntent(DetailIntent.TakeNow)
        dispatcher.scheduler.advanceUntilIdle()

        val logged = repository.latestTakenDose("sch-1")!!
        assertEquals(DoseStatus.TAKEN, logged.status)
        assertEquals(fixedNow, logged.takenAt)
        assertEquals(fixedNow - 2.hours, logged.plannedAt)
        // The status card and history refresh without an external write.
        assertEquals(fixedNow + 6.hours, vm.state.value.nextDoseAt)
        assertEquals(fixedNow, vm.state.value.lastTakenAt)
        assertEquals(listOf(logged.id), loggedIds(vm))
        assertEquals(
            "Cetirizine",
            effects.filterIsInstance<DetailEffect.ShowUndoSnackbar>().last().take.drugName,
        )
    }

    @Test
    fun `takeNow within the double dose window raises the guard without logging`() = runTest(dispatcher) {
        insert(frequency = Frequency.EveryHours(6), startedAt = fixedNow - 5.hours)
        logDose("d-1", takenAt = fixedNow - 20.minutes, plannedAt = fixedNow - 20.minutes, status = DoseStatus.TAKEN)
        val vm = viewModel()
        collectState(vm.state)
        val effects = collectEffects(vm.effects)

        vm.onIntent(DetailIntent.TakeNow)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(20L, vm.state.value.takeConfirm)
        assertEquals("d-1", repository.latestTakenDose("sch-1")?.id)
        assertTrue(effects.filterIsInstance<DetailEffect.ShowUndoSnackbar>().isEmpty())
    }

    @Test
    fun `confirmTakeAnyway records despite the guard and dismiss does not`() = runTest(dispatcher) {
        insert(frequency = Frequency.EveryHours(6), startedAt = fixedNow - 5.hours)
        logDose("d-1", takenAt = fixedNow - 20.minutes, plannedAt = fixedNow - 20.minutes, status = DoseStatus.TAKEN)
        val vm = viewModel()
        collectState(vm.state)

        vm.onIntent(DetailIntent.TakeNow)
        dispatcher.scheduler.advanceUntilIdle()
        vm.onIntent(DetailIntent.DismissTakeConfirm)
        assertNull(vm.state.value.takeConfirm)
        assertEquals("d-1", repository.latestTakenDose("sch-1")?.id)

        vm.onIntent(DetailIntent.TakeNow)
        dispatcher.scheduler.advanceUntilIdle()
        vm.onIntent(DetailIntent.ConfirmTakeAnyway)
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.state.value.takeConfirm)
        assertEquals(fixedNow, repository.latestTakenDose("sch-1")?.takenAt)
    }

    @Test
    fun `undoTake removes the log and restores the previous status`() = runTest(dispatcher) {
        insert(frequency = Frequency.EveryHours(6), startedAt = fixedNow - 2.hours)
        val vm = viewModel()
        collectState(vm.state)
        val effects = collectEffects(vm.effects)
        vm.onIntent(DetailIntent.TakeNow)
        dispatcher.scheduler.advanceUntilIdle()
        val doseId = effects.filterIsInstance<DetailEffect.ShowUndoSnackbar>().last().take.doseId
        assertEquals(fixedNow + 6.hours, vm.state.value.nextDoseAt)

        vm.onIntent(DetailIntent.UndoTake(doseId))
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(repository.latestTakenDose("sch-1"))
        assertEquals(fixedNow - 2.hours, vm.state.value.nextDoseAt)
    }

    @Test
    fun `refresh picks up dose logs written since the last emission`() = runTest(dispatcher) {
        insert(startedAt = fixedNow - 5.hours)
        val vm = viewModel()
        collectState(vm.state)
        assertTrue(vm.state.value.history.isEmpty())

        // Dose logs alone never retrigger the medications flow; the screen
        // calls refresh on entry so a retained view model catches up.
        logDose("d-1", takenAt = fixedNow, plannedAt = fixedNow, status = DoseStatus.TAKEN)
        vm.onIntent(DetailIntent.Refresh)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("d-1"), loggedIds(vm))
        assertEquals(fixedNow, vm.state.value.lastTakenAt)
    }

    @Test
    fun `a take recorded elsewhere appears after any repository re-emission`() = runTest(dispatcher) {
        insert(startedAt = fixedNow - 5.hours)
        val vm = viewModel()
        collectState(vm.state)
        assertTrue(vm.state.value.history.isEmpty())

        logDose("d-1", takenAt = fixedNow, plannedAt = fixedNow, status = DoseStatus.TAKEN)
        // Dose logs alone do not retrigger the medications flow; any
        // medication write does (same trigger the home refresh relies on).
        repository.activate("med-1", fixedNow)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("d-1"), loggedIds(vm))
    }

    @Test
    fun `external repository changes reflect in the loaded item`() = runTest(dispatcher) {
        insert()
        val vm = viewModel()
        collectState(vm.state)
        assertFalse(vm.state.value.isActive)

        repository.activate("med-1", fixedNow)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.state.value.isActive)
    }

    @Test
    fun `edits are not clobbered by a repository re-emission`() = runTest(dispatcher) {
        insert()
        val vm = viewModel()
        collectState(vm.state)

        vm.onIntent(DetailIntent.NameChanged("Edited name"))
        // Any repository write re-emits the medications flow.
        repository.activate("med-1", fixedNow)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Edited name", vm.state.value.name)
    }
}
