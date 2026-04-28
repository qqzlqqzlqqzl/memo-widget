package dev.aria.memo.ui

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.aria.memo.data.ErrorCode
import dev.aria.memo.data.MemoRepository
import dev.aria.memo.data.MemoResult
import dev.aria.memo.data.ServiceLocator
import dev.aria.memo.data.SingleNoteRepository
import dev.aria.memo.data.local.SingleNoteEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Edit-screen state machine. Starts in [Idle], moves to [Saving] while the
 * repository talks to GitHub, and lands in either [Success] or [Error].
 */
sealed class SaveState {
    object Idle : SaveState()
    object Saving : SaveState()
    object Success : SaveState()
    data class Error(val code: ErrorCode, val message: String) : SaveState()
}

/**
 * Functional-style dependencies extracted from [MemoRepository] +
 * [SingleNoteRepository]. Lets tests inject fakes without constructing a real
 * Android [android.content.Context] / DataStore / Room stack.
 *
 *  - [appendToday]: legacy day-file append (kept for backwards compatibility;
 *    never invoked by the production save path anymore, but the function-type
 *    is retained so existing tests that construct [EditViewModel] directly can
 *    keep compiling).
 *  - [toggleTodoLine]: in-file checkbox flip on the legacy path.
 *  - [createSingleNote]: fresh single-note file under `notes/`.
 *  - [updateSingleNote]: rewrite an existing single-note body.
 *  - [loadSingleNote]: fetch the entity when entering edit mode.
 */
private typealias AppendToday = suspend (String) -> MemoResult<Unit>
private typealias ToggleTodoLine = suspend (String, Int, String, Boolean) -> MemoResult<Unit>
private typealias CreateSingleNote = suspend (String) -> MemoResult<SingleNoteEntity>
private typealias UpdateSingleNote = suspend (String, String) -> MemoResult<SingleNoteEntity>
private typealias LoadSingleNote = suspend (String) -> SingleNoteEntity?
private typealias DeleteSingleNote = suspend (String) -> MemoResult<Unit>

class EditViewModel @VisibleForTesting internal constructor(
    // Fixes #321 (Arch-1 #9): no longer captured into a private field —
    // production save() uses createSingleNote/updateSingleNote
    // exclusively. The parameter is kept only so ~10 existing
    // EditViewModelSingleNoteTest call sites that still pass it
    // continue to compile; rewrite scheduled separately to keep this
    // change mechanical.
    @Suppress("UNUSED_PARAMETER")
    appendToday: AppendToday = { MemoResult.Err(ErrorCode.UNKNOWN, "appendToday no longer wired (#321)") },
    private val toggleTodoLine: ToggleTodoLine,
    private val createSingleNote: CreateSingleNote,
    private val updateSingleNote: UpdateSingleNote,
    private val loadSingleNote: LoadSingleNote,
    private val deleteSingleNote: DeleteSingleNote = { MemoResult.Err(ErrorCode.UNKNOWN, "not wired") },
    private val noteUid: String? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {

    /** Production binding — routes to the real repositories. */
    constructor(
        repository: MemoRepository,
        singleNoteRepo: SingleNoteRepository,
        noteUid: String? = null,
    ) : this(
        // appendToday omitted — uses the parameter default, no longer
        // bound to repository.appendToday since save() doesn't call it.
        toggleTodoLine = { p, i, raw, c -> repository.toggleTodoLine(p, i, raw, c) },
        createSingleNote = { body -> singleNoteRepo.create(body) },
        updateSingleNote = { uid, body -> singleNoteRepo.update(uid, body) },
        loadSingleNote = { uid -> singleNoteRepo.get(uid) },
        deleteSingleNote = { uid -> singleNoteRepo.delete(uid) },
        noteUid = noteUid,
    )

    // Fixes #321 (Arch-1 #8): the deprecated 3-arg legacy constructor
    // is gone — DoubleTapSaveTest now wires the primary 6-deps ctor
    // through a local makeVm() helper. The `AppendToday` typealias and
    // the `appendToday` parameter on the primary ctor remain only as
    // an injection hook for the production constructor's
    // `repository.appendToday` binding; save() never calls it.

    private val _state = MutableStateFlow<SaveState>(SaveState.Idle)
    val state: StateFlow<SaveState> = _state.asStateFlow()

    /**
     * Current note body, owned by the ViewModel so checklist toggles can
     * rewrite a single line in place without the EditScreen TextField losing
     * its cursor / undo stack. Editor mode still uses its own saveable state;
     * [setBody] is the bridge that lets the TextField push edits back here.
     */
    private val _body = MutableStateFlow("")
    val body: StateFlow<String> = _body.asStateFlow()

    /**
     * Repo-relative path of the note currently being edited. Empty until
     * [loadFor] has been called (or until a save picks the today-path). The
     * checklist toggle path requires this to be set — a checkbox click with an
     * empty path is a no-op (logged as UNKNOWN).
     */
    private val _path = MutableStateFlow("")
    val path: StateFlow<String> = _path.asStateFlow()

    /**
     * Idempotency guard for the save path. After a Success we remember the
     * trimmed body we just pushed and the wall-clock time we pushed it; a
     * repeat call within [DUPLICATE_SAVE_WINDOW_MS] with the same body
     * short-circuits back into [SaveState.Success] without invoking the
     * repository a second time.
     */
    private var lastCommittedBody: String? = null
    private var lastCommittedAtMs: Long = 0L

    init {
        // When an existing single-note's uid was handed in, load its body so
        // the editor can render the saved content. Keeps initialization off the
        // main thread; _body fires the update once the DAO call completes.
        //
        // Fixes #44 (P6.1): only seed _body when it's still empty. If the user
        // managed to start typing before the async load returned, their input
        // won out — we must not clobber it with the just-loaded saved copy.
        if (noteUid != null) {
            viewModelScope.launch {
                // Bug-1 M14 fix: 包 try/catch 防 loadSingleNote crash 拖垮 ViewModel。
                // DAO 在迁移失败 / DB 损坏 / Room 未初始化时可能抛 IllegalStateException
                // 或 SQLiteException;以前 unhandled exception → 崩 viewModelScope →
                // Editor 永远空白没法 recover。现在静默 fallback 到 empty body,用户能继续打字
                // 自己 save 一条新的(虽然 uid 可能是旧 zombie,但比 crash 强)。
                val entity = runCatching { loadSingleNote(noteUid) }.getOrNull()
                if (entity != null) {
                    _path.value = entity.filePath
                    if (_body.value.isEmpty()) {
                        _body.value = entity.body
                    }
                }
            }
        }
    }

    /**
     * Prime the ViewModel with a path and an initial body. Called from
     * EditActivity on the first composition. Subsequent invocations with the
     * same path are idempotent; a different path replaces state entirely.
     */
    fun loadFor(path: String, initialBody: String) {
        if (_path.value == path && _body.value == initialBody) return
        _path.value = path
        _body.value = initialBody
    }

    /**
     * Resolve intent extras and prime [_path] / [_body] asynchronously. When
     * either extra is null/blank we fall back to today's day-file path and to
     * whatever Room has cached for it (empty if no cache yet).
     *
     * No-op when [noteUid] is set — the [init] block already loaded the
     * single-note content, and we must not clobber it with today's legacy path.
     */
    fun prime(extraPath: String?, extraBody: String?) {
        if (noteUid != null) return
        viewModelScope.launch {
            val config = ServiceLocator.settingsStore.current()
            val resolvedPath = extraPath?.takeIf { it.isNotBlank() }
                ?: config.filePathFor(java.time.LocalDate.now())
            // Fixes #56 (P6.1.1): go through the repository instead of the
            // DAO so UI → Repository → DAO layering is preserved.
            val resolvedBody = extraBody
                ?: ServiceLocator.repository.getContentForPath(resolvedPath)
                ?: ""
            loadFor(resolvedPath, resolvedBody)
        }
    }

    /** Two-way binding for the TextField in edit mode. */
    fun setBody(body: String) {
        _body.value = body
    }

    /**
     * Persist the current body. Routing:
     *  - [noteUid] == null → create a fresh single-note under `notes/`.
     *  - [noteUid] != null → rewrite the existing single-note's body.
     *
     * The legacy `appendToday` path is retained as a suspend injector for
     * tests but no longer called from production — every save now lands as a
     * single-note file. Same two layers of guard as before:
     *   1. `_state == Saving` short-circuit for concurrent double-taps.
     *   2. `lastCommittedBody` dedup covers the post-reset tap window.
     */
    fun save(body: String) {
        if (_state.value is SaveState.Saving) return
        val trimmed = body.trim()
        if (trimmed.isEmpty()) {
            _state.value = SaveState.Error(ErrorCode.UNKNOWN, "内容不能为空")
            return
        }

        // Post-reset idempotency: same body just accepted → replay Success.
        val lastBody = lastCommittedBody
        if (lastBody != null &&
            lastBody == trimmed &&
            clock() - lastCommittedAtMs < DUPLICATE_SAVE_WINDOW_MS
        ) {
            _state.value = SaveState.Success
            return
        }

        _state.value = SaveState.Saving
        viewModelScope.launch {
            val res: MemoResult<*> = if (noteUid == null) {
                createSingleNote(trimmed)
            } else {
                updateSingleNote(noteUid, trimmed)
            }
            _state.value = when (res) {
                is MemoResult.Ok<*> -> {
                    lastCommittedBody = trimmed
                    lastCommittedAtMs = clock()
                    // Reflect the persisted body back so subsequent edits
                    // start from the latest view — matches what the single-
                    // note repo wrote (update()) or what create() produced.
                    _body.value = trimmed
                    SaveState.Success
                }
                is MemoResult.Err -> SaveState.Error(res.code, humanMessage(res.code, res.message))
            }
        }
    }

    /**
     * Toggle a single checklist line in the currently-loaded note. The
     * in-memory body is flipped optimistically so the UI stays responsive; the
     * repository call runs in the background with [rawLine] as the concurrency
     * guard so we refuse to write the wrong line if Room content drifted
     * mid-tap (e.g. a PullWorker landed a revision that shifted lines up).
     *
     * On [ErrorCode.CONFLICT] we refresh `_body` from the latest Room row and
     * show a "已更新，已重置视图" snackbar so the user re-taps against the new
     * content. On other hard failures we surface the message via [state];
     * network / other soft failures are absorbed by the repository's Ok branch.
     */
    fun toggleChecklist(lineIndex: Int, rawLine: String, newChecked: Boolean) {
        val currentPath = _path.value
        if (currentPath.isBlank()) {
            // Perf-1 H4 fix (#128): 之前 silent return 让用户勾 checkbox 完全无反应。
            // 现在 emit Error 让 UI 显示 "笔记还在加载,请稍候再试" 反馈。
            _state.value = SaveState.Error(ErrorCode.UNKNOWN, "笔记还在加载,请稍候再试")
            return
        }
        val currentBody = _body.value
        val lines = currentBody.split("\n").toMutableList()
        if (lineIndex !in lines.indices) return
        val line = lines[lineIndex]
        if (line != rawLine) return
        val match = TOGGLE_REGEX.matchEntire(line) ?: return
        val indent = match.groupValues[1]
        val text = match.groupValues[3]
        val mark = if (newChecked) "x" else " "
        lines[lineIndex] = "$indent- [$mark] $text"
        _body.value = lines.joinToString("\n")

        viewModelScope.launch {
            when (val res = toggleTodoLine(currentPath, lineIndex, rawLine, newChecked)) {
                is MemoResult.Ok -> Unit
                is MemoResult.Err -> when (res.code) {
                    ErrorCode.CONFLICT -> {
                        // Fixes #56 (P6.1.1): repository delegate.
                val latest = ServiceLocator.repository.getContentForPath(currentPath).orEmpty()
                        _body.value = latest
                        _state.value = SaveState.Error(
                            ErrorCode.CONFLICT,
                            "内容已更新，已重置视图",
                        )
                    }
                    else -> {
                        _state.value = SaveState.Error(res.code, humanMessage(res.code, res.message))
                    }
                }
            }
        }
    }

    /** Reset to Idle after the UI has consumed a terminal state. */
    fun reset() {
        _state.value = SaveState.Idle
    }

    /**
     * Fix-6 (Bug-1 C4): delete the currently-loaded single-note and fire
     * [onDone] so EditActivity can finish(). No-op when the VM is in new-note
     * mode ([noteUid] == null) — there's nothing on disk yet. Errors are
     * swallowed here because SingleNoteRepository already surfaces them via
     * SyncStatusBus; the UI flow just needs to return to the list.
     */
    fun delete(onDone: () -> Unit) {
        val uid = noteUid ?: run {
            onDone()
            return
        }
        viewModelScope.launch {
            deleteSingleNote(uid)
            onDone()
        }
    }

    private fun humanMessage(code: ErrorCode, fallback: String): String = when (code) {
        ErrorCode.NOT_CONFIGURED -> "还没配置 PAT / 仓库，请先去设置页"
        ErrorCode.UNAUTHORIZED -> "GitHub 拒绝访问：PAT 无效或权限不足"
        ErrorCode.NOT_FOUND -> "GitHub 找不到目标路径"
        ErrorCode.CONFLICT -> "远程被人改了，请稍后重试"
        ErrorCode.NETWORK -> "网络异常，请检查连接"
        ErrorCode.UNKNOWN -> fallback.ifBlank { "未知错误" }
    }

    companion object {
        private val TOGGLE_REGEX = Regex("""^([ \t]*)- \[([ xX])] (.*)$""")

        /**
         * How long after a successful save a repeat call with the same trimmed
         * body is swallowed instead of appended again. 2 seconds is long
         * enough to cover Compose's Success → reset → finish transition
         * (usually a few hundred ms) but short enough that a genuine re-edit
         * of the same text a moment later still persists.
         */
        @VisibleForTesting
        internal const val DUPLICATE_SAVE_WINDOW_MS: Long = 2_000L

        /**
         * Default factory — creates a fresh-note EditViewModel (new-note mode).
         * Use [factoryFor] when the Activity was launched to edit an existing
         * single-note (carries [EditActivity.EXTRA_NOTE_UID]).
         */
        val Factory: ViewModelProvider.Factory = factoryFor(null)

        /**
         * Build a ViewModelProvider.Factory that scopes the ViewModel to a
         * specific [noteUid] (nullable). When uid is null the VM operates in
         * new-note mode (save → create). When non-null the VM loads the
         * existing single-note on init and save → update.
         */
        fun factoryFor(noteUid: String?): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(EditViewModel::class.java)) {
                        "Unknown ViewModel class: $modelClass"
                    }
                    return EditViewModel(
                        ServiceLocator.repository,
                        ServiceLocator.singleNoteRepo,
                        noteUid = noteUid,
                    ) as T
                }
            }
    }
}
