package com.medguard.confirm

import com.medguard.shared.extraction.ExtractedField
import com.medguard.shared.extraction.ExtractionResult
import com.medguard.shared.extraction.Frequency
import com.medguard.shared.extraction.MedicationExtraction
import com.medguard.shared.extraction.VisionExtractor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConfirmViewModelTest {

    private lateinit var dispatcher: TestDispatcher

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
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
        ConfirmViewModel(FakeExtractor(results.toMutableList()), dispatcher)

    @Test
    fun `initial state is Idle`() {
        assertEquals(ConfirmUiState.Idle, viewModel().state.value)
    }

    @Test
    fun `onImagePicked enters Extracting while extractor is in flight`() = runTest(dispatcher) {
        val vm = ConfirmViewModel(SuspendingExtractor(), dispatcher)
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
        val vm = ConfirmViewModel(extractor, dispatcher)
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
}
