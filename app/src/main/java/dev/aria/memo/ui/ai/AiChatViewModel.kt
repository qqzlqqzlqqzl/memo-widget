package dev.aria.memo.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.aria.memo.data.MemoRepository
import dev.aria.memo.data.MemoResult
import dev.aria.memo.data.ServiceLocator
import dev.aria.memo.data.SingleNoteRepository
import dev.aria.memo.data.ai.AiClient
import dev.aria.memo.data.ai.AiContextBuilder
import dev.aria.memo.data.ai.AiContextMode
import dev.aria.memo.data.ai.AiMessage
import dev.aria.memo.data.ai.AiSettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/**
 * Drives [AiChatScreen]. Holds a rolling in-memory chat transcript — history is
 * deliberately NOT persisted for this first cut (matches HANDOFF §P7 "先做后者"
 * ephemeral session policy; a future Room table can lift this).
 *
 * Context assembly is delegated to [AiContextBuilder]: the UI picks a mode
 * (none / current note / all notes) and the ViewModel fetches the right bodies
 * right before sending. That keeps the prompt fresh if the user edits a note
 * in another tab mid-conversation.
 *
 * [initialNoteUid] comes from the nav arg `noteUid` — when non-null the screen
 * defaults to `CURRENT_NOTE` mode and looks up the body through
 * [SingleNoteRepository.get]. Legacy day-file notes don't have a uid entry
 * point, so the "当前笔记" chip is hidden when [initialNoteUid] is null.
 */
class AiChatViewModel(
    private val aiClient: AiClient,
    private val memoRepo: MemoRepository,
    private val singleNoteRepo: SingleNoteRepository,
    private val aiSettings: AiSettingsStore,
    private val initialNoteUid: String? = null,
) : ViewModel() {

    /** A single turn in the rolling transcript. `role` is "user" or "assistant". */
    data class ChatMessage(
        val role: String,
        val content: String,
        val timestamp: Long = System.currentTimeMillis(),
    )

    data class UiState(
        val messages: List<ChatMessage> = emptyList(),
        val input: String = "",
        val isSending: Boolean = false,
        val error: String? = null,
        val contextMode: AiContextMode = AiContextMode.NONE,
        val isConfigured: Boolean = false,
        /**
         * True when the chat was opened with a pinned note — the UI uses this
         * to decide whether to show the "当前笔记" FilterChip.
         */
        val hasCurrentNote: Boolean = false,
    )

    private val _state = MutableStateFlow(
        UiState(
            contextMode = if (initialNoteUid != null) AiContextMode.CURRENT_NOTE else AiContextMode.NONE,
            hasCurrentNote = initialNoteUid != null,
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        // Gate the send button on AiSettingsStore; re-check on every emission so
        // the user filling in the API key in Settings flips the empty state here
        // live without requiring a navigation round-trip.
        viewModelScope.launch {
            aiSettings.observe().collect { cfg ->
                _state.value = _state.value.copy(isConfigured = cfg.isConfigured)
            }
        }
    }

    fun setInput(v: String) {
        _state.value = _state.value.copy(input = v)
    }

    fun setContextMode(mode: AiContextMode) {
        _state.value = _state.value.copy(contextMode = mode)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    /**
     * Send the current [UiState.input]:
     *  1. Append it as a user message and clear the input field.
     *  2. Gather context bodies for the active mode.
     *  3. Build the system prompt via [AiContextBuilder.buildSystemPrompt].
     *  4. Pass prior transcript (excluding the just-added user turn) as
     *     `priorMessages` so the assistant sees multi-turn state.
     *  5. On Ok append an assistant message; on Err surface the message on the
     *     Snackbar via [UiState.error].
     *
     * No-op when the input is blank or a send is already in flight. Returns
     * the launched [Job] so tests can `.join()` to wait for completion — the
     * UI ignores the return (Kotlin's Unit-compatible discard).
     */
    fun send(): Job {
        val snapshot = _state.value
        val text = snapshot.input.trim()
        if (text.isEmpty() || snapshot.isSending) {
            return viewModelScope.launch { /* no-op */ }
        }

        val userTurn = ChatMessage(role = "user", content = text)
        val newMessages = snapshot.messages + userTurn
        // Clear the input + flip the sending flag in one publish so the composer
        // can't double-send while the network call is in flight.
        _state.value = snapshot.copy(
            messages = newMessages,
            input = "",
            isSending = true,
            error = null,
        )

        return viewModelScope.launch {
            try {
                val mode = snapshot.contextMode
                // Gather body sources lazily — don't pay the Flow-first cost
                // for NONE mode. Legacy day-files contribute their full
                // markdown body; single notes contribute their raw body
                // (front-matter intact so the prompt can still see
                // `pinned: true` if present).
                //
                // Fixes #60 (P7.0.1): use firstOrNull so a Flow that never
                // emits (DAO exception, cancelled collector, etc.) degrades
                // gracefully to an empty note list instead of crashing the
                // VM with NoSuchElementException.
                val currentNoteBody: String? = when {
                    mode == AiContextMode.CURRENT_NOTE && initialNoteUid != null ->
                        singleNoteRepo.get(initialNoteUid)?.body
                    else -> null
                }
                val allNoteBodies: List<String> = when (mode) {
                    AiContextMode.ALL_NOTES -> {
                        val legacy = (memoRepo.observeNotes().firstOrNull() ?: emptyList())
                            .map { it.content }
                        val singles = (singleNoteRepo.observeAll().firstOrNull() ?: emptyList())
                            .map { it.body }
                        legacy + singles
                    }
                    else -> emptyList()
                }

                val systemPrompt = AiContextBuilder.buildSystemPrompt(
                    mode = mode,
                    currentNoteBody = currentNoteBody,
                    allNoteBodies = allNoteBodies,
                )

                // Fixes #72 (P7.0.1): `snapshot.messages` is already the
                // transcript BEFORE the userTurn we appended to newMessages
                // — it's naturally the prior list without any extra filter.
                val prior = snapshot.messages.map {
                    AiMessage(role = it.role, content = it.content)
                }

                when (val result = aiClient.chat(systemPrompt, text, prior)) {
                    is MemoResult.Ok -> {
                        val reply = ChatMessage(role = "assistant", content = result.value)
                        _state.value = _state.value.copy(
                            messages = newMessages + reply,
                            isSending = false,
                        )
                    }
                    is MemoResult.Err -> {
                        _state.value = _state.value.copy(
                            isSending = false,
                            error = result.message,
                        )
                    }
                }
            } catch (t: Throwable) {
                // Belt on top of the firstOrNull() suspenders: any other
                // unexpected throwable from the repo / codec / client path
                // is mapped to a visible error instead of escaping
                // viewModelScope and crashing the chat.
                _state.value = _state.value.copy(
                    isSending = false,
                    error = t.message ?: t.javaClass.simpleName,
                )
            }
        }
    }

    companion object {
        /**
         * Build a factory scoped to a specific [noteUid]. The nav-arg route
         * passes `null` for the tab-level entry and the note's uid for the
         * per-note "向 AI 提问" entry — the factory snapshots that into the
         * ViewModel so the default context mode is set correctly on first
         * emission.
         */
        fun factoryFor(noteUid: String?): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(AiChatViewModel::class.java)) {
                        "Unknown ViewModel class: $modelClass"
                    }
                    return AiChatViewModel(
                        aiClient = ServiceLocator.aiClient(),
                        memoRepo = ServiceLocator.repository,
                        singleNoteRepo = ServiceLocator.singleNoteRepo,
                        aiSettings = ServiceLocator.aiSettingsStore(),
                        initialNoteUid = noteUid,
                    ) as T
                }
            }
    }
}
