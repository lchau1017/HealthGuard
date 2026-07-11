package com.healthguard.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthguard.chat.domain.BuildChatContextUseCase
import com.healthguard.chat.state.ChatIntent
import com.healthguard.chat.state.ChatMessage
import com.healthguard.chat.state.ChatUiState
import com.healthguard.chat.state.toAssistantSnapshot
import com.healthguard.domain.usecase.ObserveMedicationsUseCase
import com.healthguard.home.domain.ComputeHomeStateUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone

/**
 * The chat tab's MVI holder. It owns no adherence logic: every send snapshots
 * a fresh [BuildChatContextUseCase] context (so answers always reflect the
 * latest takes) and delegates the reply to the injected [ChatAssistant].
 *
 * The conversation is view-model state only — retained across tab switches
 * and rotation, gone on process death. Nothing is persisted, by design.
 */
class ChatViewModel(
    private val buildChatContext: BuildChatContextUseCase,
    private val assistant: ChatAssistant,
    observeMedications: ObserveMedicationsUseCase,
    computeHomeState: ComputeHomeStateUseCase,
    zone: TimeZone = TimeZone.currentSystemDefault(),
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var nextId = 0L

    init {
        // The landing snapshot reuses Home's math and refreshes on any
        // repository write (observeMedications folds dataChanges in), so a
        // take recorded on Home flips this card without any host plumbing.
        viewModelScope.launch {
            observeMedications().collect { rows ->
                val snapshot = computeHomeState(rows).toAssistantSnapshot(zone)
                _state.update { it.copy(snapshot = snapshot) }
            }
        }
    }

    /** The single MVI entry point: each branch delegates to a send or a state edit. */
    fun onIntent(intent: ChatIntent) {
        when (intent) {
            is ChatIntent.InputChanged -> _state.update { it.copy(input = intent.text) }
            ChatIntent.Send -> send(_state.value.input)
            is ChatIntent.SendSuggestion -> send(intent.text)
            ChatIntent.Retry -> retry()
        }
    }

    private fun send(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || _state.value.sending) return
        _state.update {
            it.copy(
                messages = it.messages + ChatMessage(nextId++, ChatRole.USER, text),
                input = "",
                sending = true,
                failed = false,
            )
        }
        deliver(text)
    }

    /** Resends the last user message after a failure; its bubble already shows. */
    private fun retry() {
        val current = _state.value
        if (current.sending) return
        val last = current.messages.lastOrNull { it.role == ChatRole.USER } ?: return
        _state.update { it.copy(sending = true, failed = false) }
        deliver(last.text)
    }

    private fun deliver(text: String) {
        viewModelScope.launch {
            // History is every completed turn before this message; the
            // in-flight user bubble is the message itself, not history.
            val history = _state.value.messages
                .dropLast(1)
                .map { ChatTurn(it.role, it.text) }
            val result = assistant.send(text, history, buildChatContext())
            _state.update {
                when (result) {
                    is ChatResult.Reply -> it.copy(
                        messages = it.messages +
                            ChatMessage(nextId++, ChatRole.ASSISTANT, result.text),
                        sending = false,
                    )
                    ChatResult.Unavailable -> it.copy(sending = false, failed = true)
                }
            }
        }
    }
}
