@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)

package com.medguard.confirm

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.medguard.shared.data.MedicationRepository
import com.medguard.shared.data.MedicationWithSchedule
import com.medguard.shared.db.MedGuardDb
import com.medguard.shared.extraction.ExtractedField
import com.medguard.shared.extraction.ExtractionResult
import com.medguard.shared.extraction.Frequency
import com.medguard.shared.extraction.MedicationExtraction
import com.medguard.shared.extraction.VisionExtractor
import java.util.Properties
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConfirmViewModelTest {

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

    private class FakeExtractor(
        private val results: MutableList<ExtractionResult>,
    ) : VisionExtractor {
        val calls = mutableListOf<String>()
        override suspend fun extract(imageJpegBase64: String): ExtractionResult {
            calls += imageJpegBase64
            return results.removeAt(0)
        }
    }

    private class SuspendingExtractor : VisionExtractor {
        val gate = CompletableDeferred<ExtractionResult>()
        override suspend fun extract(imageJpegBase64: String): ExtractionResult = gate.await()
    }

    private fun goodField(value: String) = ExtractedField(value, 0.95)

    private fun extraction(
        drugName: ExtractedField<String> = goodField("Ibuprofen"),
        ingredients: List<ExtractedField<String>> = listOf(goodField("ibuprofen")),
        dosage: ExtractedField<String> = goodField("200 mg"),
        form: ExtractedField<String> = goodField("tablet"),
        frequency: ExtractedField<Frequency> = ExtractedField(Frequency.TimesPerDay(2), 0.9),
        withFood: ExtractedField<Boolean> = ExtractedField(true, 0.9),
    ) = MedicationExtraction(drugName, ingredients, dosage, form, frequency, withFood)

    private fun viewModel(vararg results: ExtractionResult) =
        ConfirmViewModel(FakeExtractor(results.toMutableList()), repository, dispatcher) { fixedNow }

    private suspend fun storedMedications(): List<MedicationWithSchedule> =
        repository.medications().first()

    @Test
    fun `initial state is Idle`() {
        assertEquals(ConfirmUiState.Idle, viewModel().state.value)
    }

    @Test
    fun `onImagePicked enters Extracting while extractor is in flight`() = runTest(dispatcher) {
        val vm = ConfirmViewModel(SuspendingExtractor(), repository, dispatcher) { fixedNow }
        vm.onImagePicked("img")
        dispatcher.scheduler.runCurrent()
        assertEquals(ConfirmUiState.Extracting, vm.state.value)
    }

    @Test
    fun `success maps extraction to review fields with human readable values`() = runTest(dispatcher) {
        val vm = viewModel(ExtractionResult.Success(extraction()))
        vm.onImagePicked("img")
        dispatcher.scheduler.advanceUntilIdle()

        val review = vm.state.value as ConfirmUiState.Review
        val byKey = review.fields.associateBy { it.key }
        assertEquals("Ibuprofen", byKey.getValue(ConfirmViewModel.KEY_DRUG_NAME).value)
        assertEquals("200 mg", byKey.getValue(ConfirmViewModel.KEY_DOSAGE).value)
        assertEquals("tablet", byKey.getValue(ConfirmViewModel.KEY_FORM).value)
        assertEquals("2 times a day", byKey.getValue(ConfirmViewModel.KEY_FREQUENCY).value)
        assertEquals("Yes", byKey.getValue(ConfirmViewModel.KEY_WITH_FOOD).value)
        assertEquals("ibuprofen", byKey.getValue(ConfirmViewModel.KEY_INGREDIENTS).value)
        assertTrue(review.fields.none { it.needsReview })
    }

    @Test
    fun `frequency renders once a day, every N hours, and No for withFood`() = runTest(dispatcher) {
        val vm = viewModel(
            ExtractionResult.Success(
                extraction(
                    frequency = ExtractedField(Frequency.TimesPerDay(1), 0.9),
                    withFood = ExtractedField(false, 0.9),
                ),
            ),
        )
        vm.onImagePicked("img")
        dispatcher.scheduler.advanceUntilIdle()
        var byKey = (vm.state.value as ConfirmUiState.Review).fields.associateBy { it.key }
        assertEquals("once a day", byKey.getValue(ConfirmViewModel.KEY_FREQUENCY).value)
        assertEquals("No", byKey.getValue(ConfirmViewModel.KEY_WITH_FOOD).value)

        val vm2 = viewModel(
            ExtractionResult.Success(
                extraction(frequency = ExtractedField(Frequency.EveryHours(6), 0.9)),
            ),
        )
        vm2.onImagePicked("img")
        dispatcher.scheduler.advanceUntilIdle()
        byKey = (vm2.state.value as ConfirmUiState.Review).fields.associateBy { it.key }
        assertEquals("every 6 hours", byKey.getValue(ConfirmViewModel.KEY_FREQUENCY).value)
    }

    @Test
    fun `multiple ingredients are joined with commas`() = runTest(dispatcher) {
        val vm = viewModel(
            ExtractionResult.Success(
                extraction(ingredients = listOf(goodField("paracetamol"), goodField("caffeine"))),
            ),
        )
        vm.onImagePicked("img")
        dispatcher.scheduler.advanceUntilIdle()
        val byKey = (vm.state.value as ConfirmUiState.Review).fields.associateBy { it.key }
        assertEquals("paracetamol, caffeine", byKey.getValue(ConfirmViewModel.KEY_INGREDIENTS).value)
    }

    @Test
    fun `null field value renders as empty string and needs review`() = runTest(dispatcher) {
        val vm = viewModel(
            ExtractionResult.Success(extraction(drugName = ExtractedField(null, 0.0))),
        )
        vm.onImagePicked("img")
        dispatcher.scheduler.advanceUntilIdle()
        val field = (vm.state.value as ConfirmUiState.Review)
            .fields.first { it.key == ConfirmViewModel.KEY_DRUG_NAME }
        assertEquals("", field.value)
        assertTrue(field.needsReview)
    }

    @Test
    fun `low confidence field needs review and blocks accept until confirmed`() = runTest(dispatcher) {
        val vm = viewModel(
            ExtractionResult.Success(extraction(dosage = ExtractedField("200 mg", 0.5))),
        )
        vm.onImagePicked("img")
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.canAccept)
        vm.onFieldConfirmed(ConfirmViewModel.KEY_DOSAGE)
        assertTrue(vm.canAccept)
        val field = (vm.state.value as ConfirmUiState.Review)
            .fields.first { it.key == ConfirmViewModel.KEY_DOSAGE }
        assertTrue(field.userConfirmed)
    }

    @Test
    fun `editing a field updates value, marks confirmed and clears needsReview`() = runTest(dispatcher) {
        val vm = viewModel(
            ExtractionResult.Success(extraction(dosage = ExtractedField("2OO mg", 0.4))),
        )
        vm.onImagePicked("img")
        dispatcher.scheduler.advanceUntilIdle()

        vm.onFieldEdited(ConfirmViewModel.KEY_DOSAGE, "200 mg")
        val field = (vm.state.value as ConfirmUiState.Review)
            .fields.first { it.key == ConfirmViewModel.KEY_DOSAGE }
        assertEquals("200 mg", field.value)
        assertTrue(field.userConfirmed)
        assertFalse(field.needsReview)
        assertTrue(vm.canAccept)
    }

    @Test
    fun `blank edit does not confirm a flagged field and keeps accept blocked`() = runTest(dispatcher) {
        val vm = viewModel(
            ExtractionResult.Success(extraction(dosage = ExtractedField("2OO mg", 0.4))),
        )
        vm.onImagePicked("img")
        dispatcher.scheduler.advanceUntilIdle()

        vm.onFieldEdited(ConfirmViewModel.KEY_DOSAGE, "   ")
        val field = (vm.state.value as ConfirmUiState.Review)
            .fields.first { it.key == ConfirmViewModel.KEY_DOSAGE }
        assertEquals("   ", field.value)
        assertFalse(field.userConfirmed)
        assertTrue(field.needsReview)
        assertFalse(vm.canAccept)
    }

    @Test
    fun `non-blank edit after a blank edit confirms the field again`() = runTest(dispatcher) {
        val vm = viewModel(
            ExtractionResult.Success(extraction(dosage = ExtractedField("2OO mg", 0.4))),
        )
        vm.onImagePicked("img")
        dispatcher.scheduler.advanceUntilIdle()

        vm.onFieldEdited(ConfirmViewModel.KEY_DOSAGE, "")
        assertFalse(vm.canAccept)
        vm.onFieldEdited(ConfirmViewModel.KEY_DOSAGE, "200 mg")
        val field = (vm.state.value as ConfirmUiState.Review)
            .fields.first { it.key == ConfirmViewModel.KEY_DOSAGE }
        assertEquals("200 mg", field.value)
        assertTrue(field.userConfirmed)
        assertFalse(field.needsReview)
        assertTrue(vm.canAccept)
    }

    @Test
    fun `accept stays blocked until every flagged field is confirmed`() = runTest(dispatcher) {
        val vm = viewModel(
            ExtractionResult.Success(
                extraction(
                    drugName = ExtractedField("Ibuprofen", 0.3),
                    dosage = ExtractedField("200 mg", 0.5),
                    form = ExtractedField(null, 0.0),
                ),
            ),
        )
        vm.onImagePicked("img")
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.canAccept)

        vm.onFieldConfirmed(ConfirmViewModel.KEY_DRUG_NAME)
        assertFalse(vm.canAccept)
        vm.onFieldConfirmed(ConfirmViewModel.KEY_DOSAGE)
        assertFalse(vm.canAccept)
        vm.onFieldEdited(ConfirmViewModel.KEY_FORM, "tablet")
        assertTrue(vm.canAccept)
    }

    @Test
    fun `canAccept is true when no field needs review`() = runTest(dispatcher) {
        val vm = viewModel(ExtractionResult.Success(extraction()))
        vm.onImagePicked("img")
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.canAccept)
    }

    @Test
    fun `canAccept is false outside review state`() {
        assertFalse(viewModel().canAccept)
    }

    @Test
    fun `malformed result maps to retriable error with photo hint`() = runTest(dispatcher) {
        val vm = viewModel(ExtractionResult.Malformed("bad json"))
        vm.onImagePicked("img")
        dispatcher.scheduler.advanceUntilIdle()
        val error = vm.state.value as ConfirmUiState.Error
        assertEquals("Couldn't read the label — try another photo", error.message)
        assertTrue(error.retriable)
    }

    @Test
    fun `unavailable result maps to retriable connection error`() = runTest(dispatcher) {
        val vm = viewModel(ExtractionResult.Unavailable)
        vm.onImagePicked("img")
        dispatcher.scheduler.advanceUntilIdle()
        val error = vm.state.value as ConfirmUiState.Error
        assertEquals("Service unavailable — check connection", error.message)
        assertTrue(error.retriable)
    }

    @Test
    fun `retry after error re-extracts the same image`() = runTest(dispatcher) {
        val extractor = FakeExtractor(
            mutableListOf(ExtractionResult.Unavailable, ExtractionResult.Success(extraction())),
        )
        val vm = ConfirmViewModel(extractor, repository, dispatcher) { fixedNow }
        vm.onImagePicked("img")
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value is ConfirmUiState.Error)

        vm.onRetry()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value is ConfirmUiState.Review)
        assertEquals(listOf("img", "img"), extractor.calls)
    }

    @Test
    fun `reset returns to Idle`() = runTest(dispatcher) {
        val vm = viewModel(ExtractionResult.Success(extraction()))
        vm.onImagePicked("img")
        dispatcher.scheduler.advanceUntilIdle()
        vm.reset()
        assertEquals(ConfirmUiState.Idle, vm.state.value)
    }

    @Test
    fun `accept persists medication with dormant schedule and enters Saved`() = runTest(dispatcher) {
        val vm = viewModel(ExtractionResult.Success(extraction()))
        vm.onImagePicked("img")
        dispatcher.scheduler.advanceUntilIdle()

        vm.onAccept("Heart")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(ConfirmUiState.Saved, vm.state.value)
        val stored = storedMedications().single()
        assertEquals("Ibuprofen", stored.medication.drugName)
        assertEquals("200 mg", stored.medication.dosage)
        assertEquals("tablet", stored.medication.form)
        assertEquals("Heart", stored.medication.label)
        assertEquals(listOf("ibuprofen"), stored.medication.activeIngredients)
        assertEquals(fixedNow, stored.medication.createdAt)
        assertEquals(stored.medication.id, stored.schedule.medicationId)
        assertEquals(true, stored.schedule.withFood)
        assertNull(stored.schedule.startedAt)
        assertNull(stored.schedule.stoppedAt)
    }

    @Test
    fun `accept preserves the typed frequency rather than re-parsing display text`() = runTest(dispatcher) {
        val vm = viewModel(
            ExtractionResult.Success(
                extraction(frequency = ExtractedField(Frequency.EveryHours(6), 0.9)),
            ),
        )
        vm.onImagePicked("img")
        dispatcher.scheduler.advanceUntilIdle()

        vm.onAccept(null)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(Frequency.EveryHours(6), storedMedications().single().schedule.frequency)
    }

    @Test
    fun `edited field values win over the original extraction`() = runTest(dispatcher) {
        val vm = viewModel(ExtractionResult.Success(extraction()))
        vm.onImagePicked("img")
        dispatcher.scheduler.advanceUntilIdle()

        vm.onFieldEdited(ConfirmViewModel.KEY_DRUG_NAME, "Paracetamol")
        vm.onFieldEdited(ConfirmViewModel.KEY_DOSAGE, "500 mg")
        vm.onAccept(null)
        dispatcher.scheduler.advanceUntilIdle()

        val stored = storedMedications().single()
        assertEquals("Paracetamol", stored.medication.drugName)
        assertEquals("500 mg", stored.medication.dosage)
    }

    @Test
    fun `blank label is stored as null`() = runTest(dispatcher) {
        val vm = viewModel(ExtractionResult.Success(extraction()))
        vm.onImagePicked("img")
        dispatcher.scheduler.advanceUntilIdle()

        vm.onAccept("   ")
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(storedMedications().single().medication.label)
    }

    @Test
    fun `editing the frequency text updates the typed frequency`() = runTest(dispatcher) {
        val vm = viewModel(ExtractionResult.Success(extraction()))
        vm.onImagePicked("img")
        dispatcher.scheduler.advanceUntilIdle()

        vm.onFieldEdited(ConfirmViewModel.KEY_FREQUENCY, "every 8 hours")
        vm.onAccept(null)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(Frequency.EveryHours(8), storedMedications().single().schedule.frequency)
    }

    @Test
    fun `unparseable frequency edit stores a null frequency`() = runTest(dispatcher) {
        val vm = viewModel(ExtractionResult.Success(extraction()))
        vm.onImagePicked("img")
        dispatcher.scheduler.advanceUntilIdle()

        vm.onFieldEdited(ConfirmViewModel.KEY_FREQUENCY, "whenever it hurts")
        vm.onAccept(null)
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(storedMedications().single().schedule.frequency)
    }

    @Test
    fun `editing withFood text updates the typed value`() = runTest(dispatcher) {
        val vm = viewModel(ExtractionResult.Success(extraction()))
        vm.onImagePicked("img")
        dispatcher.scheduler.advanceUntilIdle()

        vm.onFieldEdited(ConfirmViewModel.KEY_WITH_FOOD, "No")
        vm.onAccept(null)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, storedMedications().single().schedule.withFood)
    }

    @Test
    fun `accept is ignored while a flagged field is unconfirmed`() = runTest(dispatcher) {
        val vm = viewModel(
            ExtractionResult.Success(extraction(dosage = ExtractedField("2OO mg", 0.4))),
        )
        vm.onImagePicked("img")
        dispatcher.scheduler.advanceUntilIdle()

        vm.onAccept(null)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.state.value is ConfirmUiState.Review)
        assertTrue(storedMedications().isEmpty())
    }
}
