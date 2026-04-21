package dev.aria.memo.ui.oauth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.aria.memo.data.SettingsStore
import dev.aria.memo.data.oauth.DeviceCodeResponse
import dev.aria.memo.data.oauth.GitHubOAuthClient
import dev.aria.memo.data.oauth.OAuthErrorKind
import dev.aria.memo.data.oauth.OAuthResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State machine for the device-flow sign-in dialog.
 *
 * The dialog in `OAuthSignInDialog.kt` is a pure renderer of [OAuthSignInState];
 * all branching (request code → show code → poll → store token) lives here.
 *
 * Token persistence: when the flow completes successfully we call
 * [SettingsStore.update] to write the token into `AppConfig.pat`. The rest of
 * the app already observes that config, so widgets, sync workers, and the
 * settings screen light up the moment we return `Completed`.
 */
sealed interface OAuthSignInState {
    /** Nothing started yet, or dialog just opened. */
    data object Idle : OAuthSignInState

    /** Calling `/login/device/code`. */
    data object RequestingCode : OAuthSignInState

    /**
     * Got a device/user code pair — render it and poll. [retryCount] only
     * exists so the dialog can animate "still waiting" hints.
     */
    data class WaitingForUser(
        val userCode: String,
        val verificationUri: String,
        val expiresInSeconds: Int,
        val intervalSeconds: Int,
    ) : OAuthSignInState

    /** Token exchanged and persisted to SettingsStore. */
    data class Completed(val scope: String) : OAuthSignInState

    /** Terminal failure; dialog shows message + "重试" button that resets to Idle. */
    data class Failed(val kind: OAuthErrorKind, val message: String) : OAuthSignInState
}

class OAuthSignInViewModel(
    private val oauthClient: GitHubOAuthClient,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow<OAuthSignInState>(OAuthSignInState.Idle)
    val state: StateFlow<OAuthSignInState> = _state.asStateFlow()

    private var activeJob: Job? = null

    /**
     * Kick off (or restart) the full device flow. Cancels any in-flight poll
     * so the user can hit "重试" without worrying about orphan coroutines.
     */
    fun start(clientId: String) {
        activeJob?.cancel()
        _state.value = OAuthSignInState.RequestingCode
        activeJob = viewModelScope.launch {
            when (val deviceCode = oauthClient.requestDeviceCode(clientId)) {
                is OAuthResult.Err -> {
                    _state.value = OAuthSignInState.Failed(deviceCode.kind, deviceCode.message)
                }
                is OAuthResult.Ok -> {
                    val code: DeviceCodeResponse = deviceCode.value
                    _state.value = OAuthSignInState.WaitingForUser(
                        userCode = code.userCode,
                        verificationUri = code.verificationUri,
                        expiresInSeconds = code.expiresIn,
                        intervalSeconds = code.interval,
                    )
                    when (val polled = oauthClient.pollAccessToken(
                        clientId = clientId,
                        deviceCode = code.deviceCode,
                        interval = code.interval,
                        expiresIn = code.expiresIn,
                    )) {
                        is OAuthResult.Err -> {
                            _state.value = OAuthSignInState.Failed(polled.kind, polled.message)
                        }
                        is OAuthResult.Ok -> {
                            // Drop the token into SettingsStore via the existing
                            // update(transform) entrypoint. The trim() happens
                            // inside SettingsStore already; we don't log the token.
                            settingsStore.update { existing ->
                                existing.copy(pat = polled.value.accessToken)
                            }
                            _state.value = OAuthSignInState.Completed(polled.value.scope)
                        }
                    }
                }
            }
        }
    }

    /** Cancel and reset to [OAuthSignInState.Idle] — used when dialog is dismissed. */
    fun reset() {
        activeJob?.cancel()
        activeJob = null
        _state.value = OAuthSignInState.Idle
    }

    override fun onCleared() {
        activeJob?.cancel()
        super.onCleared()
    }
}
