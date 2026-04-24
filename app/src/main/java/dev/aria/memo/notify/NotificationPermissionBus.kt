package dev.aria.memo.notify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide observable for "user denied POST_NOTIFICATIONS" state (Fixes #24).
 *
 * MainActivity writes to [denied] whenever the runtime permission check fails
 * (either at app start or after the user declines the system dialog). The
 * SettingsScreen collects it to surface a yellow "通知权限未开启" warning card
 * with a deep-link button to the app's notification settings.
 *
 * Kept in-memory (not DataStore) — the state is transient by design: when the
 * user grants the permission in Settings and comes back, MainActivity.onResume
 * re-checks and flips [denied] back to false.
 */
object NotificationPermissionBus {
    private val _denied = MutableStateFlow(false)
    val denied: StateFlow<Boolean> = _denied.asStateFlow()

    fun setDenied(value: Boolean) { _denied.value = value }
}
