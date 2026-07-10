@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)

package com.healthguard.confirm

import com.healthguard.confirm.domain.ExtractMedicationUseCase
import com.healthguard.confirm.domain.SaveNewMedicationUseCase
import com.healthguard.shared.data.MedicationRepository
import com.healthguard.shared.data.MedicationWithSchedule
import com.healthguard.shared.data.SqlDelightMedicationRepository
import com.healthguard.shared.data.StoredMedication
import com.healthguard.shared.data.StoredSchedule
import com.healthguard.shared.db.HealthGuardDb
import com.healthguard.shared.extraction.ExtractedField
import com.healthguard.shared.extraction.ExtractionResult
import com.healthguard.shared.extraction.Frequency
import com.healthguard.shared.extraction.MedicationExtraction
import com.healthguard.shared.extraction.VisionExtractor
import com.healthguard.testing.inMemoryDriver
import com.healthguard.testing.inMemoryRepository
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
        repository = inMemoryRepository(dispatcher)
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

    private fun viewModelWith(
        extractor: VisionExtractor,
        repository: MedicationRepository = this.repository,
        encoder: ImageEncoder = ImageEncoder { "base64-$it" },
    ) = ConfirmViewModel(
        ExtractMedicationUseCase(extractor, dispatcher),
        SaveNewMedicationUseCase(repository) { fixedNow },
        encoder,
    )

    private fun viewModel(vararg results: ExtractionResult) =
        viewModelWith(FakeExtractor(results.toMutableList()))

    /** Mirrors how the screen reads accept-readiness: false unless in Review. */
    private val ConfirmUiState.canAccept: Boolean
        get() = (this as? ConfirmUiState.Review)?.canAccept ?: false

    private suspend fun storedMedications(): List<MedicationWithSchedule> =
        repository.medications().first()

    @Test
    fun `initial state is Idle`() {
        assertEquals(ConfirmUiState.Idle, viewModel().state.value)
    }

    @Test
    fun `onImagePicked enters Extracting while extractor is in flight`() = runTest(dispatcher) {
        val vm = viewModelWith(SuspendingExtractor())
        vm.onIntent(ConfirmIntent.ImagePicked("img"))
        dispatcher.scheduler.runCurrent()
        assertEquals(ConfirmUiState.Extracting, vm.state.value)
    }

    @Test
    fun `success maps extraction to review fields with human readable values`() = runTest(dispatcher) {
        val vm = viewModel(ExtractionResult.Success(extraction()))
        vm.onIntent(ConfirmIntent.ImagePicked("img"))
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
        vm.onIntent(ConfirmIntent.ImagePicked("img"))
        dispatcher.scheduler.advanceUntilIdle()
        var byKey = (vm.state.value as ConfirmUiState.Review).fields.associateBy { it.key }
        assertEquals("once a day", byKey.getValue(ConfirmViewModel.KEY_FREQUENCY).value)
        assertEquals("No", byKey.getValue(ConfirmViewModel.KEY_WITH_FOOD).value)

        val vm2 = viewModel(
            ExtractionResult.Success(
                extraction(frequency = ExtractedField(Frequency.EveryHours(6), 0.9)),
            ),
        )
        vm2.onIntent(ConfirmIntent.ImagePicked("img"))
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
        vm.onIntent(ConfirmIntent.ImagePicked("img"))
        dispatcher.scheduler.advanceUntilIdle()
        val byKey = (vm.state.value as ConfirmUiState.Review).fields.associateBy { it.key }
        assertEquals("paracetamol, caffeine", byKey.getValue(ConfirmViewModel.KEY_INGREDIENTS).value)
    }

    @Test
    fun `null field value renders as empty string and needs review`() = runTest(dispatcher) {
        val vm = viewModel(
            ExtractionResult.Success(extraction(drugName = ExtractedField(null, 0.0))),
        )
        vm.onIntent(ConfirmIntent.ImagePicked("img"))
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
        vm.onIntent(ConfirmIntent.ImagePicked("img"))
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.state.value.canAccept)
        vm.onIntent(ConfirmIntent.FieldConfirmed(ConfirmViewModel.KEY_DOSAGE))
        assertTrue(vm.state.value.canAccept)
        val field = (vm.state.value as ConfirmUiState.Review)
            .fields.first { it.key == ConfirmViewModel.KEY_DOSAGE }
        assertTrue(field.userConfirmed)
    }

    @Test
    fun `editing a field updates value, marks confirmed and clears needsReview`() = runTest(dispatcher) {
        val vm = viewModel(
            ExtractionResult.Success(extraction(dosage = ExtractedField("2OO mg", 0.4))),
        )
        vm.onIntent(ConfirmIntent.ImagePicked("img"))
        dispatcher.scheduler.advanceUntilIdle()

        vm.onIntent(ConfirmIntent.FieldEdited(ConfirmViewModel.KEY_DOSAGE, "200 mg"))
        val field = (vm.state.value as ConfirmUiState.Review)
            .fields.first { it.key == ConfirmViewModel.KEY_DOSAGE }
        assertEquals("200 mg", field.value)
        assertTrue(field.userConfirmed)
        assertFalse(field.needsReview)
        assertTrue(vm.state.value.canAccept)
    }

    @Test
    fun `blank edit does not confirm a flagged field and keeps accept blocked`() = runTest(dispatcher) {
        val vm = viewModel(
            ExtractionResult.Success(extraction(dosage = ExtractedField("2OO mg", 0.4))),
        )
        vm.onIntent(ConfirmIntent.ImagePicked("img"))
        dispatcher.scheduler.advanceUntilIdle()

        vm.onIntent(ConfirmIntent.FieldEdited(ConfirmViewModel.KEY_DOSAGE, "   "))
        val field = (vm.state.value as ConfirmUiState.Review)
            .fields.first { it.key == ConfirmViewModel.KEY_DOSAGE }
        assertEquals("   ", field.value)
        assertFalse(field.userConfirmed)
        assertTrue(field.needsReview)
        assertFalse(vm.state.value.canAccept)
    }

    @Test
    fun `non-blank edit after a blank edit confirms the field again`() = runTest(dispatcher) {
        val vm = viewModel(
            ExtractionResult.Success(extraction(dosage = ExtractedField("2OO mg", 0.4))),
        )
        vm.onIntent(ConfirmIntent.ImagePicked("img"))
        dispatcher.scheduler.advanceUntilIdle()

        vm.onIntent(ConfirmIntent.FieldEdited(ConfirmViewModel.KEY_DOSAGE, ""))
        assertFalse(vm.state.value.canAccept)
        vm.onIntent(ConfirmIntent.FieldEdited(ConfirmViewModel.KEY_DOSAGE, "200 mg"))
        val field = (vm.state.value as ConfirmUiState.Review)
            .fields.first { it.key == ConfirmViewModel.KEY_DOSAGE }
        assertEquals("200 mg", field.value)
        assertTrue(field.userConfirmed)
        assertFalse(field.needsReview)
        assertTrue(vm.state.value.canAccept)
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
        vm.onIntent(ConfirmIntent.ImagePicked("img"))
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.state.value.canAccept)

        vm.onIntent(ConfirmIntent.FieldConfirmed(ConfirmViewModel.KEY_DRUG_NAME))
        assertFalse(vm.state.value.canAccept)
        vm.onIntent(ConfirmIntent.FieldConfirmed(ConfirmViewModel.KEY_DOSAGE))
        assertFalse(vm.state.value.canAccept)
        vm.onIntent(ConfirmIntent.FieldEdited(ConfirmViewModel.KEY_FORM, "tablet"))
        assertTrue(vm.state.value.canAccept)
    }

    @Test
    fun `canAccept is true when no field needs review`() = runTest(dispatcher) {
        val vm = viewModel(ExtractionResult.Success(extraction()))
        vm.onIntent(ConfirmIntent.ImagePicked("img"))
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value.canAccept)
    }

    @Test
    fun `canAccept is false outside review state`() {
        assertFalse(viewModel().state.value.canAccept)
    }

    @Test
    fun `malformed result maps to retriable error with photo hint`() = runTest(dispatcher) {
        val vm = viewModel(ExtractionResult.Malformed("bad json"))
        vm.onIntent(ConfirmIntent.ImagePicked("img"))
        dispatcher.scheduler.advanceUntilIdle()
        val error = vm.state.value as ConfirmUiState.Error
        assertEquals("Couldn't read the label — try another photo", error.message)
        assertTrue(error.retriable)
    }

    @Test
    fun `unavailable result maps to retriable connection error`() = runTest(dispatcher) {
        val vm = viewModel(ExtractionResult.Unavailable)
        vm.onIntent(ConfirmIntent.ImagePicked("img"))
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
        val vm = viewModelWith(extractor)
        vm.onIntent(ConfirmIntent.ImagePicked("img"))
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value is ConfirmUiState.Error)

        vm.onIntent(ConfirmIntent.Retry)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value is ConfirmUiState.Review)
        assertEquals(listOf("base64-img", "base64-img"), extractor.calls)
    }

    @Test
    fun `image is encoded before extraction and Extracting shows during the encode`() = runTest(dispatcher) {
        val extractor = FakeExtractor(mutableListOf(ExtractionResult.Success(extraction())))
        val gate = CompletableDeferred<String>()
        val vm = viewModelWith(extractor, encoder = { gate.await() })

        vm.onIntent(ConfirmIntent.ImagePicked("content://photo"))
        dispatcher.scheduler.runCurrent()
        // The dialog must be visible for the whole decode, not only extraction.
        assertEquals(ConfirmUiState.Extracting, vm.state.value)

        gate.complete("jpeg-bytes")
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value is ConfirmUiState.Review)
        assertEquals(listOf("jpeg-bytes"), extractor.calls)
    }

    @Test
    fun `undecodable image surfaces a non-retriable error and never extracts`() = runTest(dispatcher) {
        val extractor = FakeExtractor(mutableListOf())
        val vm = viewModelWith(extractor, encoder = { null })

        vm.onIntent(ConfirmIntent.ImagePicked("content://broken"))
        dispatcher.scheduler.advanceUntilIdle()

        val error = vm.state.value as ConfirmUiState.Error
        assertEquals("Couldn't load that image", error.message)
        assertFalse(error.retriable)
        assertTrue(extractor.calls.isEmpty())
    }

    @Test
    fun `dismiss during extraction is final - a late result must not reopen the dialog`() = runTest(dispatcher) {
        val extractor = SuspendingExtractor()
        val vm = viewModelWith(extractor)
        vm.onIntent(ConfirmIntent.ImagePicked("img"))
        dispatcher.scheduler.runCurrent()
        assertEquals(ConfirmUiState.Extracting, vm.state.value)

        vm.onIntent(ConfirmIntent.Reset)
        assertEquals(ConfirmUiState.Idle, vm.state.value)

        // The (up to 60s slow) network call finally returns: still dismissed.
        extractor.gate.complete(ExtractionResult.Success(extraction()))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(ConfirmUiState.Idle, vm.state.value)
    }

    private class SuspendingSaveRepository(
        private val delegate: MedicationRepository,
    ) : MedicationRepository by delegate {
        val gate = CompletableDeferred<Unit>()
        override suspend fun insertMedication(medication: StoredMedication, schedule: StoredSchedule) {
            gate.await()
            delegate.insertMedication(medication, schedule)
        }
    }

    @Test
    fun `dismiss during save is final - a late save completion must not resurrect the dialog`() = runTest(dispatcher) {
        val repo = SuspendingSaveRepository(repository)
        val vm = viewModelWith(
            FakeExtractor(mutableListOf(ExtractionResult.Success(extraction()))),
            repo,
        )
        vm.onIntent(ConfirmIntent.ImagePicked("img"))
        dispatcher.scheduler.advanceUntilIdle()
        vm.onIntent(ConfirmIntent.Accept)
        dispatcher.scheduler.runCurrent()
        assertTrue(vm.state.value is ConfirmUiState.Review)

        vm.onIntent(ConfirmIntent.Reset)
        repo.gate.complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(ConfirmUiState.Idle, vm.state.value)
        // The cancelled save never reached the database.
        assertTrue(storedMedications().isEmpty())
    }

    @Test
    fun `reset returns to Idle`() = runTest(dispatcher) {
        val vm = viewModel(ExtractionResult.Success(extraction()))
        vm.onIntent(ConfirmIntent.ImagePicked("img"))
        dispatcher.scheduler.advanceUntilIdle()
        vm.onIntent(ConfirmIntent.Reset)
        assertEquals(ConfirmUiState.Idle, vm.state.value)
    }

    @Test
    fun `accept persists medication with dormant schedule and emits Saved`() = runTest(dispatcher) {
        val vm = viewModel(ExtractionResult.Success(extraction()))
        vm.onIntent(ConfirmIntent.ImagePicked("img"))
        dispatcher.scheduler.advanceUntilIdle()

        vm.onIntent(ConfirmIntent.LabelChanged("Heart"))
        vm.onIntent(ConfirmIntent.Accept)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(ConfirmEffect.Saved, vm.effects.first())
        // The view model owns its state machine: a successful save returns
        // the flow to Idle by itself, no Reset intent from the host needed.
        assertEquals(ConfirmUiState.Idle, vm.state.value)
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
    fun `a new image after a successful save starts a fresh extraction`() = runTest(dispatcher) {
        val extractor = FakeExtractor(
            mutableListOf(
                ExtractionResult.Success(extraction()),
                ExtractionResult.Success(extraction()),
            ),
        )
        val vm = viewModelWith(extractor)
        vm.onIntent(ConfirmIntent.ImagePicked("img-1"))
        dispatcher.scheduler.advanceUntilIdle()
        vm.onIntent(ConfirmIntent.Accept)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(ConfirmUiState.Idle, vm.state.value)

        // The self-reset left no stale image or retry state behind: the next
        // picked image goes through a full encode + extract.
        vm.onIntent(ConfirmIntent.ImagePicked("img-2"))
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value is ConfirmUiState.Review)
        assertEquals(listOf("base64-img-1", "base64-img-2"), extractor.calls)
    }

    @Test
    fun `accept preserves the typed frequency rather than re-parsing display text`() = runTest(dispatcher) {
        val vm = viewModel(
            ExtractionResult.Success(
                extraction(frequency = ExtractedField(Frequency.EveryHours(6), 0.9)),
            ),
        )
        vm.onIntent(ConfirmIntent.ImagePicked("img"))
        dispatcher.scheduler.advanceUntilIdle()

        vm.onIntent(ConfirmIntent.Accept)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(Frequency.EveryHours(6), storedMedications().single().schedule.frequency)
    }

    @Test
    fun `edited field values win over the original extraction`() = runTest(dispatcher) {
        val vm = viewModel(ExtractionResult.Success(extraction()))
        vm.onIntent(ConfirmIntent.ImagePicked("img"))
        dispatcher.scheduler.advanceUntilIdle()

        vm.onIntent(ConfirmIntent.FieldEdited(ConfirmViewModel.KEY_DRUG_NAME, "Paracetamol"))
        vm.onIntent(ConfirmIntent.FieldEdited(ConfirmViewModel.KEY_DOSAGE, "500 mg"))
        vm.onIntent(ConfirmIntent.Accept)
        dispatcher.scheduler.advanceUntilIdle()

        val stored = storedMedications().single()
        assertEquals("Paracetamol", stored.medication.drugName)
        assertEquals("500 mg", stored.medication.dosage)
    }

    @Test
    fun `blank label is stored as null`() = runTest(dispatcher) {
        val vm = viewModel(ExtractionResult.Success(extraction()))
        vm.onIntent(ConfirmIntent.ImagePicked("img"))
        dispatcher.scheduler.advanceUntilIdle()

        vm.onIntent(ConfirmIntent.LabelChanged("   "))
        vm.onIntent(ConfirmIntent.Accept)
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(storedMedications().single().medication.label)
    }

    @Test
    fun `label edits live in the review state`() = runTest(dispatcher) {
        val vm = viewModel(ExtractionResult.Success(extraction()))
        vm.onIntent(ConfirmIntent.ImagePicked("img"))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("", (vm.state.value as ConfirmUiState.Review).label)

        // The label is business data: it belongs to the ViewState, not to
        // composition state that dies with the dialog.
        vm.onIntent(ConfirmIntent.LabelChanged("Heart"))
        assertEquals("Heart", (vm.state.value as ConfirmUiState.Review).label)
    }

    @Test
    fun `editing the frequency text updates the typed frequency`() = runTest(dispatcher) {
        val vm = viewModel(ExtractionResult.Success(extraction()))
        vm.onIntent(ConfirmIntent.ImagePicked("img"))
        dispatcher.scheduler.advanceUntilIdle()

        vm.onIntent(ConfirmIntent.FieldEdited(ConfirmViewModel.KEY_FREQUENCY, "every 8 hours"))
        vm.onIntent(ConfirmIntent.Accept)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(Frequency.EveryHours(8), storedMedications().single().schedule.frequency)
    }

    @Test
    fun `unparseable frequency edit stores a null frequency`() = runTest(dispatcher) {
        val vm = viewModel(ExtractionResult.Success(extraction()))
        vm.onIntent(ConfirmIntent.ImagePicked("img"))
        dispatcher.scheduler.advanceUntilIdle()

        vm.onIntent(ConfirmIntent.FieldEdited(ConfirmViewModel.KEY_FREQUENCY, "whenever it hurts"))
        vm.onIntent(ConfirmIntent.Accept)
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(storedMedications().single().schedule.frequency)
    }

    @Test
    fun `editing withFood text updates the typed value`() = runTest(dispatcher) {
        val vm = viewModel(ExtractionResult.Success(extraction()))
        vm.onIntent(ConfirmIntent.ImagePicked("img"))
        dispatcher.scheduler.advanceUntilIdle()

        vm.onIntent(ConfirmIntent.FieldEdited(ConfirmViewModel.KEY_WITH_FOOD, "No"))
        vm.onIntent(ConfirmIntent.Accept)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, storedMedications().single().schedule.withFood)
    }

    @Test
    fun `accept is ignored while a flagged field is unconfirmed`() = runTest(dispatcher) {
        val vm = viewModel(
            ExtractionResult.Success(extraction(dosage = ExtractedField("2OO mg", 0.4))),
        )
        vm.onIntent(ConfirmIntent.ImagePicked("img"))
        dispatcher.scheduler.advanceUntilIdle()

        vm.onIntent(ConfirmIntent.Accept)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.state.value is ConfirmUiState.Review)
        assertTrue(storedMedications().isEmpty())
    }

    /** A repository whose driver is already closed: every write throws. */
    private fun failingRepository(): MedicationRepository {
        val deadDriver = inMemoryDriver()
        val repository = SqlDelightMedicationRepository(HealthGuardDb(deadDriver), dispatcher)
        deadDriver.close()
        return repository
    }

    @Test
    fun `failed insert surfaces a retriable error instead of crashing`() = runTest(dispatcher) {
        val vm = viewModelWith(
            FakeExtractor(mutableListOf(ExtractionResult.Success(extraction()))),
            failingRepository(),
        )
        vm.onIntent(ConfirmIntent.ImagePicked("img"))
        dispatcher.scheduler.advanceUntilIdle()

        vm.onIntent(ConfirmIntent.Accept)
        dispatcher.scheduler.advanceUntilIdle()

        val error = vm.state.value as ConfirmUiState.Error
        assertEquals("Couldn't save — try again", error.message)
        assertTrue(error.retriable)
    }

    @Test
    fun `accept can be attempted again after a failed save`() = runTest(dispatcher) {
        val vm = viewModelWith(
            FakeExtractor(mutableListOf(ExtractionResult.Success(extraction()))),
            failingRepository(),
        )
        vm.onIntent(ConfirmIntent.ImagePicked("img"))
        dispatcher.scheduler.advanceUntilIdle()
        vm.onIntent(ConfirmIntent.Accept)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value is ConfirmUiState.Error)

        // Retry restores the review; the retried save must attempt the insert
        // again (failing again here) rather than being swallowed by a stuck
        // saving flag, which would leave the state silently stuck in Review.
        vm.onIntent(ConfirmIntent.Retry)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value is ConfirmUiState.Error)
    }

    private class FailOnceRepository(
        private val delegate: MedicationRepository,
    ) : MedicationRepository by delegate {
        var failuresRemaining = 1
        override suspend fun insertMedication(medication: StoredMedication, schedule: StoredSchedule) {
            if (failuresRemaining > 0) {
                failuresRemaining--
                throw RuntimeException("save failed")
            }
            delegate.insertMedication(medication, schedule)
        }
    }

    @Test
    fun `retry after a failed save restores the edited review and saves it again`() = runTest(dispatcher) {
        val extractor = FakeExtractor(mutableListOf(ExtractionResult.Success(extraction())))
        val vm = viewModelWith(extractor, FailOnceRepository(repository))
        vm.onIntent(ConfirmIntent.ImagePicked("img"))
        dispatcher.scheduler.advanceUntilIdle()

        vm.onIntent(ConfirmIntent.FieldEdited(ConfirmViewModel.KEY_DOSAGE, "500 mg"))
        vm.onIntent(ConfirmIntent.LabelChanged("Heart"))
        vm.onIntent(ConfirmIntent.Accept)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value is ConfirmUiState.Error)

        vm.onIntent(ConfirmIntent.Retry)
        // The review comes back exactly as the user left it — the edit and
        // the category label survive; nothing was re-extracted.
        val review = vm.state.value as ConfirmUiState.Review
        assertEquals(
            "500 mg",
            review.fields.first { it.key == ConfirmViewModel.KEY_DOSAGE }.value,
        )
        assertEquals("Heart", review.label)
        assertEquals(1, extractor.calls.size)

        // And the save was attempted again, succeeding this time.
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(ConfirmEffect.Saved, vm.effects.first())
        val stored = storedMedications().single()
        assertEquals("500 mg", stored.medication.dosage)
        assertEquals("Heart", stored.medication.label)
    }
}
