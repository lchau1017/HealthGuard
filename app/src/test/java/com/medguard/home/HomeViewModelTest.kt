@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)

package com.medguard.home

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.medguard.shared.data.MedicationRepository
import com.medguard.shared.data.StoredMedication
import com.medguard.shared.data.StoredSchedule
import com.medguard.shared.db.MedGuardDb
import com.medguard.shared.extraction.Frequency
import java.util.Properties
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HomeViewModelTest {

    private lateinit var dispatcher: TestDispatcher
    private lateinit var repository: MedicationRepository

    private val fixedNow = Instant.fromEpochMilliseconds(1_720_000_000_000)

    @Before
    fun setUp() {
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

    private fun viewModel() = HomeViewModel(repository) { fixedNow }

    private suspend fun insert(
        id: String,
        drugName: String = "Ibuprofen",
        createdAtMillis: Long = 1_000,
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
                frequency = Frequency.TimesPerDay(2),
                withFood = true,
                startedAt = startedAt,
                stoppedAt = stoppedAt,
            ),
        )
    }

    private fun TestScope.collectMedications(vm: HomeViewModel) {
        backgroundScope.launch { vm.medications.collect {} }
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `medications exposes stored rows`() = runTest(dispatcher) {
        insert("a", drugName = "Ibuprofen")
        val vm = viewModel()
        collectMedications(vm)

        val rows = vm.medications.value
        assertEquals(listOf("Ibuprofen"), rows.map { it.medication.drugName })
        assertEquals(Frequency.TimesPerDay(2), rows.single().schedule.frequency)
    }

    @Test
    fun `active medications sort above dormant ones`() = runTest(dispatcher) {
        // "newer" would list first by creation date; the older-but-active row
        // must still win the top spot.
        insert("newer", drugName = "Newer", createdAtMillis = 2_000)
        insert("older-active", drugName = "OlderActive", createdAtMillis = 1_000, startedAt = fixedNow)
        val vm = viewModel()
        collectMedications(vm)

        assertEquals(
            listOf("OlderActive", "Newer"),
            vm.medications.value.map { it.medication.drugName },
        )
    }

    @Test
    fun `onPlay activates the schedule at the clock time`() = runTest(dispatcher) {
        insert("a")
        val vm = viewModel()
        collectMedications(vm)

        vm.onPlay("a")
        dispatcher.scheduler.advanceUntilIdle()

        val schedule = vm.medications.value.single().schedule
        assertEquals(fixedNow, schedule.startedAt)
        assertNull(schedule.stoppedAt)
    }

    @Test
    fun `onStop stops the schedule at the clock time`() = runTest(dispatcher) {
        insert("a", startedAt = Instant.fromEpochMilliseconds(1))
        val vm = viewModel()
        collectMedications(vm)

        vm.onStop("a")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(fixedNow, vm.medications.value.single().schedule.stoppedAt)
    }

    @Test
    fun `onDelete removes the medication from the list`() = runTest(dispatcher) {
        insert("a")
        insert("b")
        val vm = viewModel()
        collectMedications(vm)
        assertEquals(2, vm.medications.value.size)

        vm.onDelete("a")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("b"), vm.medications.value.map { it.medication.id })
    }

    @Test
    fun `isActive is true only for started and not stopped schedules`() = runTest(dispatcher) {
        insert("dormant")
        insert("active", startedAt = fixedNow)
        insert("stopped", startedAt = Instant.fromEpochMilliseconds(1), stoppedAt = fixedNow)
        val vm = viewModel()
        collectMedications(vm)

        val byId = vm.medications.value.associateBy { it.medication.id }
        assertTrue(byId.getValue("active").isActive)
        assertTrue(!byId.getValue("dormant").isActive)
        assertTrue(!byId.getValue("stopped").isActive)
    }
}
