package dev.aria.memo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.aria.memo.data.ErrorCode
import dev.aria.memo.data.MemoRepository
import dev.aria.memo.data.MemoResult
import dev.aria.memo.data.ServiceLocator
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

class EditViewModel(
    private val repository: MemoRepository,
) : ViewModel() {

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
     * This is the only place EditActivity hits to wire the path — without it,
     * the checklist-toggle path stays blank and every Checkbox tap is a no-op.
     */
    fun prime(extraPath: String?, extraBody: String?) {
        viewModelScope.launch {
            val config = ServiceLocator.settingsStore.current()
            val resolvedPath = extraPath?.takeIf { it.isNotBlank() }
                ?: config.filePathFor(java.time.LocalDate.now())
            val resolvedBody = extraBody
                ?: ServiceLocator.noteDao().get(resolvedPath)?.content
                ?: ""
            loadFor(resolvedPath, resolvedBody)
        }
    }

    /** Two-way binding for the TextField in edit mode. */
    fun setBody(body: String) {
        _body.value = body
    }

    /**
     * Append `body` to today's file via the repository. Result lands in
     * [state]; callers observe via collectAsState and react to Success/Error.
     *
     * Guards against concurrent double-tap saves by short-circuiting while
     * in Saving.
     */
    fun save(body: String) {
        if (_state.value is SaveState.Saving) return
        val trimmed = body.trim()
        if (trimmed.isEmpty()) {
            _state.value = SaveState.Error(ErrorCode.UNKNOWN, "内容不能为空")
            return
        }

        _state.value = SaveState.Saving
        viewModelScope.launch {
            _state.value = when (val res = repository.appendToday(trimmed)) {
                is MemoResult.Ok -> SaveState.Success
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
            // No path primed — nothing to persist. Most likely the screen was
            // opened for a fresh write (no existing file yet), in which case a
            // checklist toggle has nothing to toggle against.
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
            when (val res = repository.toggleTodoLine(currentPath, lineIndex, rawLine, newChecked)) {
                is MemoResult.Ok -> Unit
                is MemoResult.Err -> when (res.code) {
                    ErrorCode.CONFLICT -> {
                        // Room content drifted under us. Re-read the latest
                        // body so the renderer snaps back to the truth, then
                        // surface a gentle notice.
                        val latest = ServiceLocator.noteDao().get(currentPath)?.content.orEmpty()
                        _body.value = latest
                        _state.value = SaveState.Error(
                            ErrorCode.CONFLICT,
                            "内容已更新，已重置视图",
                        )
                    }
                    else -> {
                        // Don't flip state to Error for network — the
                        // repository already returned Ok for those and enqueued
                        // a retry. Only surface hard failures.
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

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(EditViewModel::class.java)) {
                    "Unknown ViewModel class: $modelClass"
                }
                return EditViewModel(ServiceLocator.repository) as T
            }
        }
    }
}
