@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)

package com.healthguard.detail

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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DetailViewModelTest {

    private lateinit var dispatcher: TestDispatcher
    private lateinit var repository: MedicationRepository

    private val fixedNow = Instant.parse("2024-07-03T10:00:00Z")

    @Before
    fun setUp() {
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

    private fun viewModel(id: String = "med-1") = DetailViewModel(
        repository = repository,
        clock = { fixedNow },
        medicationId = id,
        zone = TimeZone.UTC,
    )

    private suspend fun insert(
        frequency: Frequency? = Frequency.TimesPerDay(2),
        withFood: Boolean? = true,
        startedAt: Instant? = null,
        stoppedAt: Instant? = null,
    ) {
        repository.insertMedication(
            StoredMedication(
                id = "med-1",
                drugName = "Cetirizine",
                label = "Allergy",
                activeIngredients = listOf("cetirizine hydrochloride", "lactose"),
                dosage = "10 mg",
                form = "tablet",
                extractionConfidence = 0.95,
                createdAt = Instant.fromEpochMilliseconds(1_000),
            ),
            StoredSchedule(
                id = "sch-1",
                medicationId = "med-1",
                frequency = frequency,
                withFood = withFood,
                startedAt = startedAt,
                stoppedAt = stoppedAt,
            ),
        )
    }

    private fun TestScope.collectState(vm: DetailViewModel) {
        backgroundScope.launch { vm.state.collect {} }
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `loads the medication and seeds the editable fields`() = runTest(dispatcher) {
        insert()
        val vm = viewModel()
        collectState(vm)

        val state = vm.state.value
        assertEquals("Cetirizine", state.name)
        assertEquals("10 mg", state.dosage)
        assertEquals("tablet", state.form)
        assertEquals("Allergy", state.label)
        assertEquals("cetirizine hydrochloride, lactose", state.ingredients)
        assertEquals("2 times a day", state.frequencyText)
        assertEquals(true, state.withFood)
        assertNotNull(state.item)
    }

    @Test
    fun `save maps every field including a changed frequency`() = runTest(dispatcher) {
        insert(frequency = Frequency.TimesPerDay(2))
        val vm = viewModel()
        collectState(vm)

        vm.onNameChange(" Loratadine ")
        vm.onDosageChange("5 mg")
        vm.onFormChange("capsule")
        vm.onLabelChange("Hay fever")
        vm.onIngredientsChange("loratadine, , cellulose ")
        vm.onFrequencyTextChange("every 8 hours")
        vm.onWithFoodChange(false)
        vm.save()
        dispatcher.scheduler.advanceUntilIdle()

        val stored = repository.getMedication("med-1")!!
        assertEquals("Loratadine", stored.medication.drugName)
        assertEquals("5 mg", stored.medication.dosage)
        assertEquals("capsule", stored.medication.form)
        assertEquals("Hay fever", stored.medication.label)
        assertEquals(listOf("loratadine", "cellulose"), stored.medication.activeIngredients)
        assertEquals(Frequency.EveryHours(8), stored.schedule.frequency)
        assertEquals(false, stored.schedule.withFood)
        assertEquals(DetailFinished.SAVED, vm.state.value.finished)
    }

    @Test
    fun `blank frequency text saves a null frequency`() = runTest(dispatcher) {
        insert(frequency = Frequency.TimesPerDay(2))
        val vm = viewModel()
        collectState(vm)

        vm.onFrequencyTextChange("   ")
        assertFalse(vm.state.value.frequencyError)
        vm.save()
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(repository.getMedication("med-1")!!.schedule.frequency)
    }

    @Test
    fun `blank label form and dosage save as null`() = runTest(dispatcher) {
        insert()
        val vm = viewModel()
        collectState(vm)

        vm.onLabelChange("")
        vm.onDosageChange(" ")
        vm.onFormChange("")
        vm.onIngredientsChange("")
        vm.save()
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
        collectState(vm)

        vm.onNameChange("   ")
        assertTrue(vm.state.value.nameError)
        assertFalse(vm.state.value.canSave)
        vm.save()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Cetirizine", repository.getMedication("med-1")!!.medication.drugName)
        assertNull(vm.state.value.finished)
    }

    @Test
    fun `unparseable frequency flags an error and blocks save`() = runTest(dispatcher) {
        insert(frequency = Frequency.TimesPerDay(2))
        val vm = viewModel()
        collectState(vm)

        vm.onFrequencyTextChange("whenever I remember")
        assertTrue(vm.state.value.frequencyError)
        assertFalse(vm.state.value.canSave)
        vm.save()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            Frequency.TimesPerDay(2),
            repository.getMedication("med-1")!!.schedule.frequency,
        )
        assertNull(vm.state.value.finished)
    }

    @Test
    fun `save leaves startedAt and stoppedAt untouched`() = runTest(dispatcher) {
        insert(startedAt = fixedNow - 5.hours, stoppedAt = fixedNow - 1.hours)
        val vm = viewModel()
        collectState(vm)

        vm.onNameChange("Renamed")
        vm.onFrequencyTextChange("every 6 hours")
        vm.save()
        dispatcher.scheduler.advanceUntilIdle()

        val schedule = repository.getMedication("med-1")!!.schedule
        assertEquals(fixedNow - 5.hours, schedule.startedAt)
        assertEquals(fixedNow - 1.hours, schedule.stoppedAt)
    }

    @Test
    fun `toggleTaking starts a dormant schedule and stops an active one`() = runTest(dispatcher) {
        insert()
        val vm = viewModel()
        collectState(vm)
        assertFalse(vm.state.value.isActive)

        vm.toggleTaking()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value.isActive)
        assertEquals(fixedNow, vm.state.value.item?.schedule?.startedAt)

        vm.toggleTaking()
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.state.value.isActive)
        assertEquals(fixedNow, vm.state.value.item?.schedule?.stoppedAt)
    }

    @Test
    fun `active schedule exposes a next dose`() = runTest(dispatcher) {
        insert(frequency = Frequency.EveryHours(6), startedAt = fixedNow - 2.hours)
        val vm = viewModel()
        collectState(vm)

        // No dose logged: the first dose was due at startedAt.
        assertEquals(fixedNow - 2.hours, vm.state.value.nextDoseAt)
    }

    @Test
    fun `dormant schedule has no next dose`() = runTest(dispatcher) {
        insert()
        val vm = viewModel()
        collectState(vm)

        assertNull(vm.state.value.nextDoseAt)
    }

    @Test
    fun `delete removes the medication and finishes`() = runTest(dispatcher) {
        insert()
        val vm = viewModel()
        collectState(vm)

        vm.delete()
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(repository.getMedication("med-1"))
        assertEquals(DetailFinished.DELETED, vm.state.value.finished)
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

    @Test
    fun `history lists recent dose logs newest planned first`() = runTest(dispatcher) {
        insert(startedAt = fixedNow - 5.hours)
        logDose("d-1", takenAt = fixedNow - 4.hours, plannedAt = fixedNow - 4.hours, status = DoseStatus.TAKEN)
        logDose("d-2", takenAt = null, plannedAt = fixedNow - 2.hours, status = DoseStatus.SKIPPED)
        logDose("d-3", takenAt = fixedNow - 1.hours, plannedAt = fixedNow - 1.hours, status = DoseStatus.TAKEN)
        val vm = viewModel()
        collectState(vm)

        assertEquals(listOf("d-3", "d-2", "d-1"), vm.state.value.history.map { it.id })
    }

    @Test
    fun `day statuses classify completeness and adherence counts taken vs missed`() = runTest(dispatcher) {
        insert(startedAt = fixedNow - 48.hours)
        // Yesterday: one take, one miss -> SOME. Today: takes only -> ALL.
        logDose("d-1", takenAt = fixedNow - 26.hours, plannedAt = fixedNow - 26.hours, status = DoseStatus.TAKEN)
        logDose("d-m", takenAt = null, plannedAt = fixedNow - 25.hours, status = DoseStatus.MISSED)
        logDose("d-2", takenAt = fixedNow - 2.hours, plannedAt = fixedNow - 2.hours, status = DoseStatus.TAKEN)
        logDose("d-3", takenAt = fixedNow - 1.hours, plannedAt = fixedNow - 1.hours, status = DoseStatus.TAKEN)
        // Skips demote a day to SOME (not everything scheduled was taken)
        // but never count against the adherence percentage.
        logDose("d-skip", takenAt = null, plannedAt = fixedNow - 3.hours, status = DoseStatus.SKIPPED)
        val vm = viewModel()
        collectState(vm)

        val state = vm.state.value
        val today = kotlinx.datetime.LocalDate(2024, 7, 3)
        val yesterday = kotlinx.datetime.LocalDate(2024, 7, 2)
        assertEquals(DayDoseStatus.SOME, state.doseDayStatuses[yesterday])
        assertEquals(DayDoseStatus.SOME, state.doseDayStatuses[today])
        // 3 taken, 1 missed -> 75%.
        assertEquals(75, state.adherencePercent)
        assertNotNull(state.historyFrom)
    }

    @Test
    fun `adherence is null with no takes or misses`() = runTest(dispatcher) {
        insert(startedAt = fixedNow - 48.hours)
        val vm = viewModel()
        collectState(vm)
        assertNull(vm.state.value.adherencePercent)
    }

    @Test
    fun `takeNow logs a taken dose and refreshes the status`() = runTest(dispatcher) {
        // EveryHours(6), never taken: due since startedAt.
        insert(frequency = Frequency.EveryHours(6), startedAt = fixedNow - 2.hours)
        val vm = viewModel()
        collectState(vm)
        assertEquals(fixedNow - 2.hours, vm.state.value.nextDoseAt)

        vm.takeNow()
        dispatcher.scheduler.advanceUntilIdle()

        val logged = repository.latestDose("sch-1")!!
        assertEquals(DoseStatus.TAKEN, logged.status)
        assertEquals(fixedNow, logged.takenAt)
        assertEquals(fixedNow - 2.hours, logged.plannedAt)
        // The status card and history refresh without an external write.
        assertEquals(fixedNow + 6.hours, vm.state.value.nextDoseAt)
        assertEquals(fixedNow, vm.state.value.lastTakenAt)
        assertEquals(listOf(logged.id), vm.state.value.history.map { it.id })
        assertEquals("Cetirizine", vm.recentTake.value?.drugName)
    }

    @Test
    fun `takeNow within the double dose window raises the guard without logging`() = runTest(dispatcher) {
        insert(frequency = Frequency.EveryHours(6), startedAt = fixedNow - 5.hours)
        logDose("d-1", takenAt = fixedNow - 20.minutes, plannedAt = fixedNow - 20.minutes, status = DoseStatus.TAKEN)
        val vm = viewModel()
        collectState(vm)

        vm.takeNow()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(20L, vm.takeConfirm.value)
        assertEquals("d-1", repository.latestDose("sch-1")?.id)
        assertNull(vm.recentTake.value)
    }

    @Test
    fun `confirmTakeAnyway records despite the guard and dismiss does not`() = runTest(dispatcher) {
        insert(frequency = Frequency.EveryHours(6), startedAt = fixedNow - 5.hours)
        logDose("d-1", takenAt = fixedNow - 20.minutes, plannedAt = fixedNow - 20.minutes, status = DoseStatus.TAKEN)
        val vm = viewModel()
        collectState(vm)

        vm.takeNow()
        dispatcher.scheduler.advanceUntilIdle()
        vm.dismissTakeConfirm()
        assertNull(vm.takeConfirm.value)
        assertEquals("d-1", repository.latestDose("sch-1")?.id)

        vm.takeNow()
        dispatcher.scheduler.advanceUntilIdle()
        vm.confirmTakeAnyway()
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.takeConfirm.value)
        assertEquals(fixedNow, repository.latestDose("sch-1")?.takenAt)
    }

    @Test
    fun `undoTake removes the log and restores the previous status`() = runTest(dispatcher) {
        insert(frequency = Frequency.EveryHours(6), startedAt = fixedNow - 2.hours)
        val vm = viewModel()
        collectState(vm)
        vm.takeNow()
        dispatcher.scheduler.advanceUntilIdle()
        val doseId = vm.recentTake.value!!.doseId
        assertEquals(fixedNow + 6.hours, vm.state.value.nextDoseAt)

        vm.undoTake(doseId)
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(repository.latestDose("sch-1"))
        assertNull(vm.recentTake.value)
        assertEquals(fixedNow - 2.hours, vm.state.value.nextDoseAt)
    }

    @Test
    fun `a take recorded elsewhere appears after any repository re-emission`() = runTest(dispatcher) {
        insert(startedAt = fixedNow - 5.hours)
        val vm = viewModel()
        collectState(vm)
        assertTrue(vm.state.value.history.isEmpty())

        logDose("d-1", takenAt = fixedNow, plannedAt = fixedNow, status = DoseStatus.TAKEN)
        // Dose logs alone do not retrigger the medications flow; any
        // medication write does (same trigger the home refresh relies on).
        repository.activate("med-1", fixedNow)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("d-1"), vm.state.value.history.map { it.id })
    }

    @Test
    fun `external repository changes reflect in the loaded item`() = runTest(dispatcher) {
        insert()
        val vm = viewModel()
        collectState(vm)
        assertFalse(vm.state.value.isActive)

        repository.activate("med-1", fixedNow)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.state.value.isActive)
    }

    @Test
    fun `edits are not clobbered by a repository re-emission`() = runTest(dispatcher) {
        insert()
        val vm = viewModel()
        collectState(vm)

        vm.onNameChange("Edited name")
        // Any repository write re-emits the medications flow.
        repository.activate("med-1", fixedNow)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Edited name", vm.state.value.name)
    }
}
