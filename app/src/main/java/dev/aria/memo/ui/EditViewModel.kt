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
