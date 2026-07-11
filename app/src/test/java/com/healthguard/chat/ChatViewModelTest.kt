@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)

package com.healthguard.chat

import com.healthguard.chat.domain.BuildChatContextUseCase
import com.healthguard.chat.state.ChatIntent
import com.healthguard.domain.usecase.ObserveMedicationsUseCase
import com.healthguard.home.domain.ComputeHomeStateUseCase
import com.healthguard.data.SqlDelightMedicationRepository
import com.healthguard.domain.extraction.Frequency
import com.healthguard.testing.collectState
import com.healthguard.testing.inMemoryRepository
import com.healthguard.testing.logTaken
import com.healthguard.testing.seedMedication
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatViewModelTest {

    private lateinit var dispatcher: TestDispatcher
    private lateinit var repository: SqlDelightMedicationRepository
    private lateinit var assistant: FakeChatAssistant

    private val fixedNow = Instant.parse("2024-07-03T10:00:00Z")

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        repository = inMemoryRepository(dispatcher)
        assistant = FakeChatAssistant()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = ChatViewModel(
        buildChatContext = BuildChatContextUseCase(
            medicationRepository = repository,
            doseLogRepository = repository,
            clock = { fixedNow },
            zone = TimeZone.of("UTC"),
        ),
        assistant = assistant,
        observeMedications = ObserveMedicationsUseCase(repository),
        computeHomeState = ComputeHomeStateUseCase(
            repository = repository,
            clock = { fixedNow },
            zone = TimeZone.of("UTC"),
        ),
        zone = TimeZone.of("UTC"),
    )

    @Test
    fun `send appends both bubbles, clears input and passes the adherence context`() = runTest {
        repository.seedMedication(
            "a",
            drugName = "Aspirin",
            frequency = Frequency.TimesPerDay(1),
            startedAt = fixedNow - 5.days,
        )
        repository.logTaken("a", fixedNow - 1.days)
        assistant.result = ChatResultOf("Aspirin: 20%.")
        val viewModel = viewModel()

        viewModel.onIntent(ChatIntent.InputChanged("What's my adherence?"))
        viewModel.onIntent(ChatIntent.Send)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(listOf("What's my adherence?", "Aspirin: 20%."), state.messages.map { it.text })
        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT), state.messages.map { it.role })
        assertEquals("", state.input)
        assertFalse(state.sending)
        assertFalse(state.failed)
        val (message, history, context) = assistant.sent.single()
        assertEquals("What's my adherence?", message)
        assertTrue(history.isEmpty())
        assertEquals("Aspirin", context.medications.single().name)
    }

    @Test
    fun `later sends carry the prior turns as history`() = runTest {
        assistant.result = ChatResultOf("hello")
        val viewModel = viewModel()

        viewModel.onIntent(ChatIntent.SendSuggestion("first"))
        advanceUntilIdle()
        viewModel.onIntent(ChatIntent.InputChanged("second"))
        viewModel.onIntent(ChatIntent.Send)
        advanceUntilIdle()

        val history = assistant.sent.last().second
        assertEquals(
            listOf(ChatRole.USER to "first", ChatRole.ASSISTANT to "hello"),
            history.map { it.role to it.text },
        )
    }

    @Test
    fun `unavailable flags failure and retry resends without duplicating the bubble`() = runTest {
        assistant.result = ChatResult.Unavailable
        val viewModel = viewModel()

        viewModel.onIntent(ChatIntent.SendSuggestion("hello?"))
        advanceUntilIdle()

        assertTrue(viewModel.state.value.failed)
        assertEquals(1, viewModel.state.value.messages.size)

        assistant.result = ChatResultOf("back online")
        viewModel.onIntent(ChatIntent.Retry)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.failed)
        assertEquals(listOf("hello?", "back online"), state.messages.map { it.text })
        assertEquals("hello?", assistant.sent.last().first)
        // The retried turn is not its own history entry.
        assertTrue(assistant.sent.last().second.isEmpty())
    }

    @Test
    fun `snapshot is null while nothing is tracked`() = runTest {
        val viewModel = viewModel()
        collectState(viewModel.state)

        assertEquals(null, viewModel.state.value.snapshot)
    }

    @Test
    fun `snapshot shows the due dose and refreshes when it is taken elsewhere`() = runTest {
        // Started yesterday, 1x/day: today's 09:00 slot is due at fixedNow (10:00).
        repository.seedMedication(
            "a",
            drugName = "Aspirin",
            frequency = Frequency.TimesPerDay(1),
            startedAt = fixedNow - 1.days,
        )
        val viewModel = viewModel()
        collectState(viewModel.state)

        assertEquals("1 dose due now", viewModel.state.value.snapshot?.headline)

        // A take recorded on another screen fires dataChanges; the card follows.
        repository.logTaken("a", fixedNow)
        advanceUntilIdle()

        assertEquals("All caught up", viewModel.state.value.snapshot?.headline)
    }

    @Test
    fun `blank input and double-send are no-ops`() = runTest {
        val viewModel = viewModel()

        viewModel.onIntent(ChatIntent.InputChanged("   "))
        viewModel.onIntent(ChatIntent.Send)
        advanceUntilIdle()
        assertTrue(assistant.sent.isEmpty())

        viewModel.onIntent(ChatIntent.InputChanged("real question"))
        viewModel.onIntent(ChatIntent.Send)
        // Second Send while the first is still in flight must not double-submit.
        viewModel.onIntent(ChatIntent.Send)
        advanceUntilIdle()
        assertEquals(1, assistant.sent.size)
    }
}

private fun ChatResultOf(text: String): ChatResult = ChatResult.Reply(text)

/** Records every send and answers with the scripted [result]. */
private class FakeChatAssistant : ChatAssistant {
    val sent = mutableListOf<Triple<String, List<ChatTurn>, ChatContext>>()
    var result: ChatResult = ChatResult.Reply("ok")

    override suspend fun send(
        message: String,
        history: List<ChatTurn>,
        context: ChatContext,
    ): ChatResult {
        sent += Triple(message, history, context)
        return result
    }
}
