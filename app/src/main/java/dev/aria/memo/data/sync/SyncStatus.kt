package dev.aria.memo.data.sync

import dev.aria.memo.data.ErrorCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Current sync state, consumed by UI to surface errors (Fixes #11). */
sealed class SyncStatus {
    data object Idle : SyncStatus()
    data object Syncing : SyncStatus()
    data object Ok : SyncStatus()
    data class Error(val code: ErrorCode, val message: String) : SyncStatus()
}

/**
 * Process-wide observable sync status. Workers post updates, UI collects them.
 * Stored in a singleton (not DataStore) because the info is purely in-memory
 * — transient across process restart, which is fine for a status banner.
 */
object SyncStatusBus {
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    fun emit(next: SyncStatus) { _status.value = next }

    fun clearError() {
        if (_status.value is SyncStatus.Error) _status.value = SyncStatus.Idle
    }
}
