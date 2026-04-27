package dev.aria.memo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.aria.memo.data.AppConfig
import dev.aria.memo.data.ErrorCode
import dev.aria.memo.data.GitHubApi
import dev.aria.memo.data.MemoResult
import dev.aria.memo.data.ServiceLocator
import dev.aria.memo.data.SettingsStore
import dev.aria.memo.data.ai.AiClient
import dev.aria.memo.data.ai.AiSettingsStore
import dev.aria.memo.data.sync.SyncStatus
import dev.aria.memo.data.sync.SyncStatusBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Liveness state of the saved GitHub PAT.
 *
 * Fixes Review-X #1 — the green "✓ 已就绪" banner used to look only at "are the
 * PAT/owner/repo fields non-blank", so a token that GitHub had revoked still
 * read as configured while the SyncBanner screamed "拒绝访问". Now we keep a
 * five-state machine that the StatusCard can render distinctly, and the
 * SettingsScreen can act on (focus the PAT field, etc.).
 */
sealed interface PatStatus {
    /** Never tested in this session; UI shows the neutral "已配置" hint. */
    data object Unknown : PatStatus

    /** [SettingsViewModel.testConnection] in flight — UI shows a spinner. */
    data object Verifying : PatStatus

    /** Last verification call succeeded at [checkedAt] (epoch ms). */
    data class Valid(val checkedAt: Long) : PatStatus

    /**
     * GitHub returned 401/403 during a verification call OR a worker pushed an
     * `UNAUTHORIZED` SyncStatus. Either way, the PAT is no longer trusted —
     * UI surfaces "PAT 已失效" and offers a re-login affordance.
     */
    data class Invalid(val message: String, val checkedAt: Long) : PatStatus

    /**
     * The verification *itself* failed (network, rate limit, unknown). The
     * token might still be fine — we just couldn't prove it. Shown as a soft
     * warning with a "重试" button so users don't panic-rotate a good token.
     */
    data class CheckFailed(val message: String, val checkedAt: Long) : PatStatus
}

/**
 * UI state for the settings screen. Mirrors AppConfig but keeps PAT and editor
 * state in-memory only.
 */
data class SettingsUiState(
    val pat: String = "",
    val owner: String = "",
    val repo: String = "",
    val branch: String = "main",
    val loaded: Boolean = false,
    val isSaving: Boolean = false,
    /**
     * Review-W #3: independent loading flag for the dedicated "切换账号"
     * button so its spinner doesn't muddle the regular 保存 button state.
     */
    val isSwitchingAccount: Boolean = false,
    val lastSavedAt: Long? = null,
    /**
     * Review-W #3: one-shot timestamp the screen reads to fire its post-switch
     * snackbar. Cleared via [SettingsViewModel.consumeAccountSwitchedEvent].
     */
    val accountSwitchedAt: Long? = null,
    val errorMessage: String? = null,
    val aiProviderUrl: String = "",
    val aiModel: String = "",
    val aiApiKey: String = "",
    val isSavingAi: Boolean = false,
    val isTestingAi: Boolean = false,
    val aiTestResult: AiTestOutcome? = null,
    // Fix-X1: live PAT status. Kept independent from `isSaving` etc. so a
    // background SyncStatusBus flip to UNAUTHORIZED can re-paint the
    // StatusCard without disturbing the Save button's spinner.
    val patStatus: PatStatus = PatStatus.Unknown,
    /**
     * Fix-X1 one-shot signal: when an upstream caller (OAuth dialog, SyncBanner
     * "修复" button) wants the screen to direct attention at the PAT input, it
     * bumps this counter. The screen observes the counter changing and runs
     * its focus + scroll side effect once. Counter form (vs Boolean) avoids
     * the "consume" handshake the screen would otherwise need.
     */
    val highlightPatRequest: Long = 0L,
) {
    val isConfigured: Boolean
        get() = pat.isNotBlank() && owner.isNotBlank() && repo.isNotBlank()

    val isAiConfigured: Boolean
        get() = aiProviderUrl.isNotBlank() && aiModel.isNotBlank() && aiApiKey.isNotBlank()

    val missingFields: List<String>
        get() = buildList {
            if (pat.isBlank()) add("PAT")
            if (owner.isBlank()) add("Owner")
            if (repo.isBlank()) add("Repo")
        }
}

sealed class AiTestOutcome {
    data object Success : AiTestOutcome()
    data class Failure(val message: String) : AiTestOutcome()
}

class SettingsViewModel(
    private val settings: SettingsStore,
    private val aiSettings: AiSettingsStore,
    private val aiClient: AiClient,
    // Fix-X1: GitHub API needed to verify the saved PAT against a real call.
    // Default to ServiceLocator so production wiring stays untouched; tests
    // pass in a fake GitHubApi backed by MockEngine.
    private val githubApi: GitHubApi = ServiceLocator.api(),
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val current = settings.config.first()
            val ai = aiSettings.current()
            _state.value = _state.value.copy(
                pat = current.pat,
                owner = current.owner,
                repo = current.repo,
                branch = current.branch.ifBlank { "main" },
                aiProviderUrl = ai.providerUrl,
                aiModel = ai.model,
                aiApiKey = ai.apiKey,
                loaded = true,
            )
        }
        // Fix-X1: keep the StatusCard and the SyncBanner singing the same tune.
        // When any worker emits SyncStatus.Error(UNAUTHORIZED) — i.e. the token
        // got revoked between sessions — flip patStatus to Invalid so the
        // StatusCard turns red without making the user re-press "重新验证".
        viewModelScope.launch {
            SyncStatusBus.status.collect { status ->
                if (status is SyncStatus.Error && status.code == ErrorCode.UNAUTHORIZED) {
                    _state.value = _state.value.copy(
                        patStatus = PatStatus.Invalid(
                            message = status.message.ifBlank { "GitHub 拒绝了这枚 PAT" },
                            checkedAt = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }
    }

    fun onPatChange(v: String) = _state.update {
        // Fix-X1: user started editing — the previous Valid/Invalid mark
        // applied to the *old* token, so reset back to Unknown (we'll learn
        // about the new value once they hit 保存 and 重新验证).
        it.copy(pat = v, errorMessage = null, patStatus = PatStatus.Unknown)
    }
    fun onOwnerChange(v: String) = _state.update { it.copy(owner = v, errorMessage = null) }
    fun onRepoChange(v: String) = _state.update { it.copy(repo = v, errorMessage = null) }
    fun onBranchChange(v: String) = _state.update { it.copy(branch = v, errorMessage = null) }

    fun onAiProviderUrlChange(v: String) =
        _state.update { it.copy(aiProviderUrl = v, errorMessage = null) }
    fun onAiModelChange(v: String) =
        _state.update { it.copy(aiModel = v, errorMessage = null) }
    fun onAiApiKeyChange(v: String) =
        _state.update { it.copy(aiApiKey = v, errorMessage = null) }

    fun reload() {
        viewModelScope.launch {
            val current = settings.config.first()
            val ai = aiSettings.current()
            _state.value = _state.value.copy(
                pat = current.pat,
                owner = current.owner,
                repo = current.repo,
                branch = current.branch.ifBlank { "main" },
                aiProviderUrl = ai.providerUrl,
                aiModel = ai.model,
                aiApiKey = ai.apiKey,
                loaded = true,
                errorMessage = null,
                // Fix-X1: reload typically follows a fresh OAuth login → token
                // is brand new, so any stale Valid/Invalid mark must go (it
                // described the pre-login token). The next testConnection()
                // call (auto-fired below) repaints with the truth.
                patStatus = PatStatus.Unknown,
            )
            // Fix-X1: auto-verify after OAuth so the user sees "✓ 已就绪"
            // without having to press 重新验证 themselves.
            if (_state.value.isConfigured) {
                testConnection()
            }
        }
    }

    fun save() {
        val snapshot = _state.value
        if (snapshot.isSaving) return
        _state.value = snapshot.copy(isSaving = true, errorMessage = null)
        viewModelScope.launch {
            try {
                settings.update { existing: AppConfig ->
                    existing.copy(
                        pat = snapshot.pat.trim(),
                        owner = snapshot.owner.trim(),
                        repo = snapshot.repo.trim(),
                        branch = snapshot.branch.trim().ifBlank { "main" },
                    )
                }
                _state.value = _state.value.copy(
                    isSaving = false,
                    lastSavedAt = System.currentTimeMillis(),
                )
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    isSaving = false,
                    errorMessage = "保存失败：${t.message ?: "未知错误"}",
                )
            }
        }
    }

    /**
     * Review-W #3: explicit "swap GitHub identity" path. Wraps
     * [SettingsStore.switchAccount] which clears every dirty row and resets
     * SyncStatusBus before writing the new credentials.
     */
    fun switchAccount() {
        val snapshot = _state.value
        if (snapshot.isSwitchingAccount) return
        _state.value = snapshot.copy(
            isSwitchingAccount = true,
            errorMessage = null,
        )
        viewModelScope.launch {
            try {
                settings.switchAccount(
                    owner = snapshot.owner.trim(),
                    repo = snapshot.repo.trim(),
                    pat = snapshot.pat.trim(),
                    branch = snapshot.branch.trim().ifBlank { "main" },
                )
                _state.value = _state.value.copy(
                    isSwitchingAccount = false,
                    accountSwitchedAt = System.currentTimeMillis(),
                    // Fix-X1: fresh identity → stale Valid/Invalid mark is meaningless.
                    patStatus = PatStatus.Unknown,
                )
                // Fix-X1: auto-verify the new credentials immediately.
                if (_state.value.isConfigured) {
                    testConnection()
                }
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    isSwitchingAccount = false,
                    errorMessage = "切换账号失败：${t.message ?: "未知错误"}",
                )
            }
        }
    }

    fun consumeAccountSwitchedEvent() {
        _state.value = _state.value.copy(accountSwitchedAt = null)
    }

    fun saveAiConfig() {
        val snapshot = _state.value
        if (snapshot.isSavingAi) return
        _state.value = snapshot.copy(isSavingAi = true, errorMessage = null)
        viewModelScope.launch {
            try {
                aiSettings.save(
                    providerUrl = snapshot.aiProviderUrl.trim(),
                    model = snapshot.aiModel.trim(),
                    apiKey = snapshot.aiApiKey.trim(),
                )
                _state.value = _state.value.copy(
                    isSavingAi = false,
                    lastSavedAt = System.currentTimeMillis(),
                )
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    isSavingAi = false,
                    errorMessage = "保存 AI 配置失败：${t.message ?: "未知错误"}",
                )
            }
        }
    }

    fun testAiConnection() {
        val snapshot = _state.value
        if (snapshot.isTestingAi) return
        _state.value = snapshot.copy(isTestingAi = true, aiTestResult = null, errorMessage = null)
        viewModelScope.launch {
            val outcome = when (val result = aiClient.chat(systemPrompt = "", userMessage = "ping")) {
                is MemoResult.Ok -> AiTestOutcome.Success
                is MemoResult.Err -> AiTestOutcome.Failure(mapAiError(result.code, result.message))
            }
            _state.value = _state.value.copy(isTestingAi = false, aiTestResult = outcome)
        }
    }

    /**
     * Fix-X1: verify the saved PAT actually works against GitHub. We call
     * `listDir` on the configured repo root because:
     *  - it's the cheapest read endpoint (no GraphQL, no /user round-trip),
     *  - it exercises the same auth path the sync workers use,
     *  - 200 = token + scope OK, 401/403 = revoked / wrong scope, 404 = repo
     *    name typo (still configured, just wrong), 5xx/network = inconclusive.
     *
     * Result lands in [SettingsUiState.patStatus]; the StatusCard renders it
     * accordingly.
     */
    fun testConnection() {
        val snapshot = _state.value
        if (snapshot.patStatus is PatStatus.Verifying) return
        if (!snapshot.isConfigured) {
            // Nothing to test — leave patStatus as Unknown so the UI keeps
            // saying "还缺：PAT/Owner/…" instead of pretending we checked.
            return
        }
        _state.value = snapshot.copy(patStatus = PatStatus.Verifying)
        viewModelScope.launch {
            val config = AppConfig(
                pat = snapshot.pat.trim(),
                owner = snapshot.owner.trim(),
                repo = snapshot.repo.trim(),
                branch = snapshot.branch.trim().ifBlank { "main" },
            )
            val now = System.currentTimeMillis()
            val next = when (val result = githubApi.listDir(config, "")) {
                is MemoResult.Ok -> PatStatus.Valid(checkedAt = now)
                is MemoResult.Err -> when (result.code) {
                    ErrorCode.UNAUTHORIZED -> PatStatus.Invalid(
                        message = "GitHub 拒绝了这枚 PAT — 多半已过期或被回收",
                        checkedAt = now,
                    )
                    // 404 means the repo path is wrong but the token itself
                    // authenticated successfully — credit it as Valid so the
                    // user fixes the right field. The repo name typo is
                    // separately surfaced via the Owner/Repo inputs.
                    ErrorCode.NOT_FOUND -> PatStatus.Valid(checkedAt = now)
                    ErrorCode.NETWORK -> PatStatus.CheckFailed(
                        message = "网络异常，请检查连接后重试",
                        checkedAt = now,
                    )
                    else -> PatStatus.CheckFailed(
                        message = result.message.ifBlank { "未知错误" },
                        checkedAt = now,
                    )
                }
            }
            _state.value = _state.value.copy(patStatus = next)
        }
    }

    /**
     * Fix-X1: external bumper for the "请把视线挪到 PAT 这里" signal. Called by
     * the OAuth dialog on failure and (in a follow-up wiring) by the SyncBanner
     * "修复" button after it navigates to this screen.
     */
    fun requestPatHighlight() {
        _state.value = _state.value.copy(
            highlightPatRequest = System.currentTimeMillis(),
        )
    }

    fun consumeAiTestResult() {
        _state.value = _state.value.copy(aiTestResult = null)
    }

    fun consumeSavedEvent() {
        _state.value = _state.value.copy(lastSavedAt = null)
    }

    fun consumeError() {
        _state.value = _state.value.copy(errorMessage = null)
    }

    private fun mapAiError(code: ErrorCode, message: String): String = when (code) {
        ErrorCode.NOT_CONFIGURED -> "请先保存 AI 配置再测试"
        ErrorCode.UNAUTHORIZED -> "鉴权失败，请检查 API Key"
        ErrorCode.NETWORK -> "网络错误：$message"
        else -> "测试失败：$message"
    }

    private inline fun MutableStateFlow<SettingsUiState>.update(block: (SettingsUiState) -> SettingsUiState) {
        value = block(value)
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
                    "Unknown ViewModel class: $modelClass"
                }
                return SettingsViewModel(
                    settings = ServiceLocator.settingsStore,
                    aiSettings = ServiceLocator.aiSettings,
                    aiClient = ServiceLocator.ai,
                    githubApi = ServiceLocator.api(),
                ) as T
            }
        }
    }
}
