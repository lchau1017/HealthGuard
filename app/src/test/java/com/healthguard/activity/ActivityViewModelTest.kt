@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)

package com.healthguard.activity

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.healthguard.shared.data.DoseStatus
import com.healthguard.shared.data.MedicationRepository
import com.healthguard.shared.data.StoredDoseLog
import com.healthguard.shared.data.StoredMedication
import com.healthguard.shared.data.StoredSchedule
import com.healthguard.shared.db.HealthGuardDb
import java.util.Properties
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
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
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ActivityViewModelTest {

    private lateinit var dispatcher: TestDispatcher
    private lateinit var repository: MedicationRepository

    /** 2024-07-03T10:00:00Z — a Wednesday. */
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

    private fun viewModel() = ActivityViewModel(
        repository = repository,
        clock = { fixedNow },
        zone = TimeZone.UTC,
    )

    private suspend fun insert(id: String, drugName: String) {
        repository.insertMedication(
            StoredMedication(
                id = id,
                drugName = drugName,
                label = null,
                activeIngredients = emptyList(),
                dosage = null,
                form = null,
                extractionConfidence = 1.0,
                createdAt = Instant.fromEpochMilliseconds(1_000),
            ),
            StoredSchedule(
                id = "sched-$id",
                medicationId = id,
                frequency = null,
                withFood = null,
                startedAt = null,
                stoppedAt = null,
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

    @Test
    fun `all filter loads the last twelve months of takes`() = runTest(dispatcher) {
        insert("a", "Ibuprofen")
        logTaken("a", fixedNow - 370.days) // outside the 12-month cap
        logTaken("a", fixedNow - 300.days)
        logTaken("a", fixedNow - 1.hours)
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.state.value
        assertEquals(ActivityFilter.ALL, state.filter)
        assertEquals(2, state.stats.totalEvents)
        // The heat map is a fixed 16-week record: only the recent take shows.
        assertEquals(1, state.dayCounts.sumOf { it.count })
        // 16 heat-map week columns ending this week start Monday 2024-03-18.
        assertEquals(LocalDate(2024, 3, 18), state.heatFrom)
    }

    @Test
    fun `seven day filter narrows the window`() = runTest(dispatcher) {
        insert("a", "Ibuprofen")
        logTaken("a", fixedNow - 8.days)
        logTaken("a", fixedNow - 2.days)
        logTaken("a", fixedNow - 1.hours)
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(3, vm.state.value.stats.totalEvents)

        vm.setFilter(ActivityFilter.DAYS_7)
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.state.value
        assertEquals(ActivityFilter.DAYS_7, state.filter)
        assertEquals(2, state.stats.totalEvents)
        // Window shows exactly the last 7 days: 2024-06-27..2024-07-03.
        assertEquals(LocalDate(2024, 6, 27), state.from)
    }

    @Test
    fun `thirty day filter keeps a month of takes`() = runTest(dispatcher) {
        insert("a", "Ibuprofen")
        logTaken("a", fixedNow - 31.days)
        logTaken("a", fixedNow - 15.days)
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        vm.setFilter(ActivityFilter.DAYS_30)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, vm.state.value.stats.totalEvents)
        assertEquals(LocalDate(2024, 6, 4), vm.state.value.from)
    }

    private suspend fun logMissed(medicationId: String, plannedAt: Instant) {
        repository.logDose(
            StoredDoseLog(
                id = "missed-$medicationId-${plannedAt.toEpochMilliseconds()}",
                scheduleId = "sched-$medicationId",
                plannedAt = plannedAt,
                takenAt = null,
                status = DoseStatus.MISSED,
            ),
        )
    }

    @Test
    fun `breakdown is adherence per medicine best first`() = runTest(dispatcher) {
        insert("a", "Ibuprofen")
        insert("b", "Cetirizine")
        // Ibuprofen: 1 taken, 1 missed -> 50%. Cetirizine: 3 taken -> 100%.
        logTaken("a", fixedNow - 3.hours)
        logMissed("a", fixedNow - 10.hours)
        logTaken("b", fixedNow - 2.hours)
        logTaken("b", fixedNow - 1.hours)
        logTaken("b", fixedNow - 30.days)
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            listOf(
                MedicationAdherence("Cetirizine", 100),
                MedicationAdherence("Ibuprofen", 50),
            ),
            vm.state.value.breakdown,
        )
    }

    @Test
    fun `breakdown omits medicines without taken or missed doses in the window`() = runTest(dispatcher) {
        insert("a", "Ibuprofen")
        insert("b", "Cetirizine")
        logTaken("a", fixedNow - 1.hours)
        // Cetirizine's only dose predates the 7-day window.
        logTaken("b", fixedNow - 10.days)
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        vm.setFilter(ActivityFilter.DAYS_7)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            listOf(MedicationAdherence("Ibuprofen", 100)),
            vm.state.value.breakdown,
        )
    }

    @Test
    fun `no takes yield an empty state`() = runTest(dispatcher) {
        insert("a", "Ibuprofen")
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.state.value
        assertEquals(0, state.stats.totalEvents)
        assertTrue(state.dayCounts.isEmpty())
        assertTrue(state.breakdown.isEmpty())
    }

    @Test
    fun `reload picks up takes recorded since the last load`() = runTest(dispatcher) {
        insert("a", "Ibuprofen")
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, vm.state.value.stats.totalEvents)

        logTaken("a", fixedNow - 1.hours)
        vm.reload()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, vm.state.value.stats.totalEvents)
    }
}
