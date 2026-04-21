package dev.aria.memo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.aria.memo.data.AppConfig
import dev.aria.memo.data.ServiceLocator
import dev.aria.memo.data.SettingsStore
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
) {
    val isConfigured: Boolean
        get() = pat.isNotBlank() && owner.isNotBlank() && repo.isNotBlank()

    /** Human-readable list of missing required fields, empty when all present. */
    val missingFields: List<String>
        get() = buildList {
            if (pat.isBlank()) add("PAT")
            if (owner.isBlank()) add("Owner")
            if (repo.isBlank()) add("Repo")
        }
}

class SettingsViewModel(
    private val settings: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val current = settings.config.first()
            _state.value = _state.value.copy(
                pat = current.pat,
                owner = current.owner,
                repo = current.repo,
                branch = current.branch.ifBlank { "main" },
                loaded = true,
            )
        }
    }

    fun onPatChange(v: String) = _state.update { it.copy(pat = v, errorMessage = null) }
    fun onOwnerChange(v: String) = _state.update { it.copy(owner = v, errorMessage = null) }
    fun onRepoChange(v: String) = _state.update { it.copy(repo = v, errorMessage = null) }
    fun onBranchChange(v: String) = _state.update { it.copy(branch = v, errorMessage = null) }

    /**
     * Re-pull config from DataStore + secure PAT store and overwrite every
     * field. Used after OAuth completes so the UI reflects the freshly-written
     * token without relying on a manual `onPatChange` fire-and-forget.
     */
    fun reload() {
        viewModelScope.launch {
            val current = settings.config.first()
            _state.value = _state.value.copy(
                pat = current.pat,
                owner = current.owner,
                repo = current.repo,
                branch = current.branch.ifBlank { "main" },
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

    fun consumeSavedEvent() {
        _state.value = _state.value.copy(lastSavedAt = null)
    }

    fun consumeError() {
        _state.value = _state.value.copy(errorMessage = null)
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
                return SettingsViewModel(ServiceLocator.settingsStore) as T
            }
        }
    }
}
