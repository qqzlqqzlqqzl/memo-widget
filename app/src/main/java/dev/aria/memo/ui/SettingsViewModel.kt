package dev.aria.memo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.aria.memo.data.AppConfig
import dev.aria.memo.data.ErrorCode
import dev.aria.memo.data.MemoResult
import dev.aria.memo.data.ServiceLocator
import dev.aria.memo.data.SettingsStore
import dev.aria.memo.data.ai.AiClient
import dev.aria.memo.data.ai.AiSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * UI state for the settings screen. Mirrors AppConfig but keeps PAT and editor
 * state in-memory only. We deliberately do NOT log or serialize the PAT beyond
 * the DataStore-backed SettingsStore (spec §7 rule 5: no logging PAT).
 */
data class SettingsUiState(
    val pat: String = "",
    val owner: String = "",
    val repo: String = "",
    val branch: String = "main",
    val loaded: Boolean = false,
    val isSaving: Boolean = false,
    val lastSavedAt: Long? = null,
    val errorMessage: String? = null,
    // AI section — same pattern as the GitHub fields but routed through a
    // separate store. The api key is held in-memory only during editing and
    // cleared from state as soon as the user leaves the screen (the VM is
    // disposed with the composable, see DisposableEffect in SettingsScreen).
    val aiProviderUrl: String = "",
    val aiModel: String = "",
    val aiApiKey: String = "",
    val isSavingAi: Boolean = false,
    val isTestingAi: Boolean = false,
    val aiTestResult: AiTestOutcome? = null,
) {
    val isConfigured: Boolean
        get() = pat.isNotBlank() && owner.isNotBlank() && repo.isNotBlank()

    val isAiConfigured: Boolean
        get() = aiProviderUrl.isNotBlank() && aiModel.isNotBlank() && aiApiKey.isNotBlank()

    /** Human-readable list of missing required fields, empty when all present. */
    val missingFields: List<String>
        get() = buildList {
            if (pat.isBlank()) add("PAT")
            if (owner.isBlank()) add("Owner")
            if (repo.isBlank()) add("Repo")
        }
}

/**
 * One-shot event fired after "测试连接" returns. Consumed by the screen to
 * surface a snackbar, then cleared via [SettingsViewModel.consumeAiTestResult].
 */
sealed class AiTestOutcome {
    data object Success : AiTestOutcome()
    data class Failure(val message: String) : AiTestOutcome()
}

class SettingsViewModel(
    private val settings: SettingsStore,
    private val aiSettings: AiSettingsStore,
    private val aiClient: AiClient,
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
    }

    fun onPatChange(v: String) = _state.update { it.copy(pat = v, errorMessage = null) }
    fun onOwnerChange(v: String) = _state.update { it.copy(owner = v, errorMessage = null) }
    fun onRepoChange(v: String) = _state.update { it.copy(repo = v, errorMessage = null) }
    fun onBranchChange(v: String) = _state.update { it.copy(branch = v, errorMessage = null) }

    fun onAiProviderUrlChange(v: String) =
        _state.update { it.copy(aiProviderUrl = v, errorMessage = null) }
    fun onAiModelChange(v: String) =
        _state.update { it.copy(aiModel = v, errorMessage = null) }
    fun onAiApiKeyChange(v: String) =
        _state.update { it.copy(aiApiKey = v, errorMessage = null) }

    /**
     * Re-pull config from DataStore + secure PAT store and overwrite every
     * field. Used after OAuth completes so the UI reflects the freshly-written
     * token without relying on a manual `onPatChange` fire-and-forget.
     */
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
            )
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
                // Do NOT include PAT-bearing state in the message.
                _state.value = _state.value.copy(
                    isSaving = false,
                    errorMessage = "保存失败：${t.message ?: "未知错误"}",
                )
            }
        }
    }

    /**
     * Persist the AI section. Intentionally separate from [save] so users who
     * only touched one block don't overwrite the other with stale state.
     */
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
                // Never echo the api key in the error — fall back to class name + message.
                _state.value = _state.value.copy(
                    isSavingAi = false,
                    errorMessage = "保存 AI 配置失败：${t.message ?: "未知错误"}",
                )
            }
        }
    }

    /**
     * Smoke-test the saved configuration with a tiny "ping" chat. Callers get
     * back a [AiTestOutcome] via state; the screen maps Success/Failure to a
     * snackbar and then calls [consumeAiTestResult].
     */
    fun testAiConnection() {
        val snapshot = _state.value
        if (snapshot.isTestingAi) return
        // Use the saved config, not the in-memory draft — "测试连接" should
        // validate what's actually persisted. Users who want to test unsaved
        // drafts should press 保存 first (matches GitHub PAT flow).
        _state.value = snapshot.copy(isTestingAi = true, aiTestResult = null, errorMessage = null)
        viewModelScope.launch {
            val outcome = when (val result = aiClient.chat(systemPrompt = "", userMessage = "ping")) {
                is MemoResult.Ok -> AiTestOutcome.Success
                is MemoResult.Err -> AiTestOutcome.Failure(mapAiError(result.code, result.message))
            }
            _state.value = _state.value.copy(isTestingAi = false, aiTestResult = outcome)
        }
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
        /**
         * Factory hooks the ViewModel into the ServiceLocator-provided
         * SettingsStore. Used via `viewModels { SettingsViewModel.Factory }`.
         */
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
                ) as T
            }
        }
    }
}
