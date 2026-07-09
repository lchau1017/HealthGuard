@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)

package com.healthguard.activity

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.healthguard.activity.domain.ComputeActivityStateUseCase
import com.healthguard.activity.domain.LoadActivityDayDetailUseCase
import com.healthguard.home.MedicationPhase
import com.healthguard.shared.data.DoseStatus
import com.healthguard.shared.data.MedicationRepository
import com.healthguard.shared.data.SqlDelightMedicationRepository
import com.healthguard.shared.data.StoredDoseLog
import com.healthguard.shared.data.StoredMedication
import com.healthguard.shared.data.StoredSchedule
import com.healthguard.shared.db.HealthGuardDb
import com.healthguard.shared.extraction.Frequency
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
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        repository = SqlDelightMedicationRepository(HealthGuardDb(driver), dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): ActivityViewModel {
        val clock: () -> Instant = { fixedNow }
        return ActivityViewModel(
            computeActivityState = ComputeActivityStateUseCase(repository, clock, TimeZone.UTC),
            loadActivityDayDetail = LoadActivityDayDetailUseCase(repository, clock, TimeZone.UTC),
            repository = repository,
            zone = TimeZone.UTC,
        )
    }

    private suspend fun insert(
        id: String,
        drugName: String,
        frequency: Frequency? = null,
        startedAt: Instant? = null,
        stoppedAt: Instant? = null,
    ) {
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
                frequency = frequency,
                withFood = null,
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

    @Test
    fun `thirty days is the default window`() = runTest(dispatcher) {
        insert("a", "Ibuprofen")
        logTaken("a", fixedNow - 31.days)
        logTaken("a", fixedNow - 15.days)
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.state.value
        assertEquals(ActivityFilter.DAYS_30, state.filter)
        assertEquals(1, state.stats.totalEvents)
        assertEquals(LocalDate(2024, 6, 4), state.from)
        // The grid follows the window: only the in-window take shows.
        assertEquals(1, state.dayCounts.sumOf { it.count })
    }

    @Test
    fun `twelve month filter loads a year of takes`() = runTest(dispatcher) {
        insert("a", "Ibuprofen")
        logTaken("a", fixedNow - 370.days) // outside the 12-month window
        logTaken("a", fixedNow - 300.days)
        logTaken("a", fixedNow - 1.hours)
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        vm.onIntent(ActivityIntent.SetFilter(ActivityFilter.MONTHS_12))
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.state.value
        assertEquals(ActivityFilter.MONTHS_12, state.filter)
        assertEquals(2, state.stats.totalEvents)
        assertEquals(LocalDate(2023, 7, 3), state.from)
        // The grid covers the whole window now, not a fixed 16-week record.
        assertEquals(2, state.dayCounts.sumOf { it.count })
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

        vm.onIntent(ActivityIntent.SetFilter(ActivityFilter.DAYS_7))
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.state.value
        assertEquals(ActivityFilter.DAYS_7, state.filter)
        assertEquals(2, state.stats.totalEvents)
        // Window shows exactly the last 7 days: 2024-06-27..2024-07-03.
        assertEquals(LocalDate(2024, 6, 27), state.from)
        assertEquals(2, state.dayCounts.sumOf { it.count })
    }

    private suspend fun logSkipped(medicationId: String, plannedAt: Instant) {
        repository.logDose(
            StoredDoseLog(
                id = "skip-$medicationId-${plannedAt.toEpochMilliseconds()}",
                scheduleId = "sched-$medicationId",
                plannedAt = plannedAt,
                takenAt = null,
                status = DoseStatus.SKIPPED,
            ),
        )
    }

    @Test
    fun `breakdown measures each medicine against its schedule best first`() = runTest(dispatcher) {
        // Ibuprofen 1x/day since 2024-06-30: slots 09:00 on 6/30..7/3 = 4
        // expected. 2 taken, 1 skipped, 7/3 never recorded -> 2/(4-1) = 67%.
        insert("a", "Ibuprofen", Frequency.TimesPerDay(1), Instant.parse("2024-06-30T00:00:00Z"))
        logTaken("a", Instant.parse("2024-06-30T09:00:00Z"))
        logTaken("a", Instant.parse("2024-07-01T09:00:00Z"))
        logSkipped("a", Instant.parse("2024-07-02T09:00:00Z"))
        // Cetirizine 1x/day since 2024-07-02: both slots taken -> 100%.
        insert("b", "Cetirizine", Frequency.TimesPerDay(1), Instant.parse("2024-07-02T00:00:00Z"))
        logTaken("b", Instant.parse("2024-07-02T09:00:00Z"))
        logTaken("b", Instant.parse("2024-07-03T09:00:00Z"))
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            listOf(
                takingRow("Cetirizine", percent = 100, taken = 2, meetsTarget = true),
                takingRow("Ibuprofen", percent = 67, taken = 2, skipped = 1, meetsTarget = false),
            ),
            vm.state.value.breakdown,
        )
    }

    @Test
    fun `a scheduled medicine with silent days scores below 100`() = runTest(dispatcher) {
        // 2x/day since 2024-07-01, only 7/1 fully logged; 7/2 has no rows at
        // all and 7/3's 09:00 passed silently: 2 taken of 5 expected = 40%.
        insert("a", "Ibuprofen", Frequency.TimesPerDay(2), Instant.parse("2024-07-01T00:00:00Z"))
        logTaken("a", Instant.parse("2024-07-01T09:00:00Z"))
        logTaken("a", Instant.parse("2024-07-01T21:00:00Z"))
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            listOf(takingRow("Ibuprofen", percent = 40, taken = 2, meetsTarget = false)),
            vm.state.value.breakdown,
        )
    }

    @Test
    fun `rows cover taking as-needed and stopped medicines but never not-started ones`() =
        runTest(dispatcher) {
            // Taking + scheduled: a percent row.
            insert("a", "Cetirizine", Frequency.TimesPerDay(1), Instant.parse("2024-07-02T00:00:00Z"))
            logTaken("a", Instant.parse("2024-07-02T09:00:00Z"))
            logTaken("a", Instant.parse("2024-07-03T09:00:00Z"))
            // Taking + interval with takes: an as-needed count row.
            insert("b", "Ibuprofen", Frequency.EveryHours(6), Instant.parse("2024-07-01T00:00:00Z"))
            logTaken("b", fixedNow - 3.hours)
            // Never activated: phase noise, not activity — no row. The phase
            // still lives on the home cabinet chip and the detail header.
            insert("c", "Amoxicillin", Frequency.TimesPerDay(3))
            // Stopped yesterday noon after three owed slots, two answered
            // (both in the window): 2 of 3 = 67% while taking.
            insert(
                "d", "Loratadine", Frequency.TimesPerDay(1),
                startedAt = Instant.parse("2024-06-30T00:00:00Z"),
                stoppedAt = Instant.parse("2024-07-02T12:00:00Z"),
            )
            logTaken("d", Instant.parse("2024-06-30T09:00:00Z"))
            logTaken("d", Instant.parse("2024-07-01T09:00:00Z"))
            val vm = viewModel()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                listOf(
                    takingRow("Cetirizine", percent = 100, taken = 2, meetsTarget = true),
                    takingRow("Ibuprofen", percent = null, taken = 1, asNeeded = true),
                    MedicationAdherence(
                        name = "Loratadine",
                        phase = MedicationPhase.STOPPED,
                        asNeeded = false,
                        percent = 67,
                        taken = 2,
                        skipped = 0,
                        meetsTarget = false,
                        stoppedText = "Stopped yesterday",
                    ),
                ),
                vm.state.value.breakdown,
            )
        }

    @Test
    fun `medicines without activity in the window hide their rows`() = runTest(dispatcher) {
        // Dormant and never taken: hidden. As-needed with takes only before
        // the 7-day window: hidden. Stopped with all logs before the window:
        // hidden. Only the medicine with in-window activity keeps a row.
        insert("a", "Ibuprofen")
        insert("b", "Cetirizine", Frequency.EveryHours(6), fixedNow - 30.days)
        logTaken("b", fixedNow - 10.days)
        insert(
            "d", "Paracetamol", Frequency.TimesPerDay(1),
            startedAt = fixedNow - 30.days,
            stoppedAt = fixedNow - 20.days,
        )
        logTaken("d", fixedNow - 25.days)
        insert("c", "Loratadine", Frequency.TimesPerDay(1), Instant.parse("2024-07-02T00:00:00Z"))
        logTaken("c", Instant.parse("2024-07-02T09:00:00Z"))
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        vm.onIntent(ActivityIntent.SetFilter(ActivityFilter.DAYS_7))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            listOf(takingRow("Loratadine", percent = 50, taken = 1, meetsTarget = false)),
            vm.state.value.breakdown,
        )
    }

    @Test
    fun `a stopped medicine with in-window logs keeps its clipped percent row`() =
        runTest(dispatcher) {
            insert(
                "d", "Paracetamol", Frequency.TimesPerDay(1),
                startedAt = fixedNow - 30.days,
                stoppedAt = fixedNow - 20.days,
            )
            logTaken("d", fixedNow - 25.days)
            val vm = viewModel()
            dispatcher.scheduler.advanceUntilIdle()

            vm.onIntent(ActivityIntent.SetFilter(ActivityFilter.MONTHS_12))
            dispatcher.scheduler.advanceUntilIdle()

            val row = vm.state.value.breakdown.single()
            assertEquals("Paracetamol", row.name)
            assertEquals(MedicationPhase.STOPPED, row.phase)
            assertEquals(1, row.taken)
        }

    @Test
    fun `no takes yield an empty state and no breakdown rows`() = runTest(dispatcher) {
        insert("a", "Ibuprofen")
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.state.value
        assertEquals(0, state.stats.totalEvents)
        assertTrue(state.dayCounts.isEmpty())
        assertTrue(state.breakdown.isEmpty())
    }

    /** A TAKING-phase row; percent == null means an as-needed count row. */
    private fun takingRow(
        name: String,
        percent: Int?,
        taken: Int,
        skipped: Int = 0,
        asNeeded: Boolean = false,
        meetsTarget: Boolean? = null,
    ) = MedicationAdherence(
        name = name,
        phase = MedicationPhase.TAKING,
        asNeeded = asNeeded,
        percent = percent,
        taken = taken,
        skipped = skipped,
        meetsTarget = meetsTarget,
        stoppedText = null,
    )

    @Test
    fun `selecting a day builds its detail sheet and dismissing clears it`() = runTest(dispatcher) {
        // Cetirizine 2x/day (09:00/21:00): the 09:04 take answers the morning
        // slot, the evening slot goes unanswered -> 1 not recorded.
        insert("a", "Cetirizine", Frequency.TimesPerDay(2), Instant.parse("2024-07-01T00:00:00Z"))
        logTaken("a", Instant.parse("2024-07-02T09:04:00Z"))
        // As-needed the same day: a count line, never a not-recorded count.
        insert("b", "Ibuprofen", Frequency.EveryHours(6), Instant.parse("2024-07-01T00:00:00Z"))
        logTaken("b", Instant.parse("2024-07-02T14:00:00Z"))
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        vm.onIntent(ActivityIntent.SelectDay(LocalDate(2024, 7, 2)))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            DayDetail(
                date = LocalDate(2024, 7, 2),
                lines = listOf(
                    DayMedicineLine(
                        medicationId = "a",
                        name = "Cetirizine",
                        takenTimes = listOf(LocalTime(9, 4)),
                        skipped = 0,
                        missed = 0,
                        notRecorded = 1,
                    ),
                    DayMedicineLine(
                        medicationId = "b",
                        name = "Ibuprofen",
                        takenTimes = listOf(LocalTime(14, 0)),
                        skipped = 0,
                        missed = 0,
                        notRecorded = 0,
                    ),
                ),
                expectedNotRecorded = 0,
            ),
            vm.state.value.dayDetail,
        )

        vm.onIntent(ActivityIntent.DismissDayDetail)
        assertNull(vm.state.value.dayDetail)
    }

    @Test
    fun `an empty day with expectations reports them as not recorded`() = runTest(dispatcher) {
        insert("a", "Cetirizine", Frequency.TimesPerDay(2), Instant.parse("2024-07-01T00:00:00Z"))
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        vm.onIntent(ActivityIntent.SelectDay(LocalDate(2024, 7, 2)))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            DayDetail(
                date = LocalDate(2024, 7, 2),
                lines = emptyList(),
                expectedNotRecorded = 2,
            ),
            vm.state.value.dayDetail,
        )
    }

    @Test
    fun `reload picks up takes recorded since the last load`() = runTest(dispatcher) {
        insert("a", "Ibuprofen")
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, vm.state.value.stats.totalEvents)

        logTaken("a", fixedNow - 1.hours)
        vm.onIntent(ActivityIntent.Reload)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, vm.state.value.stats.totalEvents)
    }
}
