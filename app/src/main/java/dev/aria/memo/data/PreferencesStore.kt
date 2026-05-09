package dev.aria.memo.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Standalone DataStore for lightweight UI toggles that don't belong in
 * [SettingsStore] (which is dedicated to GitHub repo config + PAT migration).
 *
 * Kept intentionally thin — one flow + one setter per key. Adding another
 * toggle here should not require threading state through [AppConfig].
 *
 * Current keys:
 *  - [quickAddEnabled]: whether the ongoing "记一笔" notification should sit in
 *    the status bar. Default false.
 *  - [githubClientId]: the user-registered OAuth app client id used by the
 *    device-flow sign-in dialog. Not secret — this is a public identifier —
 *    and deliberately kept separate from the encrypted PAT store.
 */
private val Context.preferencesDataStore: DataStore<Preferences> by preferencesDataStore(name = "memo_preferences")

class PreferencesStore(private val context: Context) {

    private object Keys {
        val QUICK_ADD_ENABLED = booleanPreferencesKey("quick_add_notification_enabled")
        val GITHUB_CLIENT_ID = stringPreferencesKey("github_client_id")
        // Theme mode: "auto" (跟随系统) / "light" / "dark". Default "auto".
        val THEME_MODE = stringPreferencesKey("theme_mode")
        // Wall-clock ms of the most recent PullWorker / PushWorker success.
        // 0 = never. Surfaced by SyncStatusCard in Settings so the user can
        // answer "我刚改的笔记上传了吗" (push) and "其他设备的改动来了吗"
        // (pull) across process restarts.
        val LAST_PULL_EPOCH_MS = longPreferencesKey("last_pull_epoch_ms")
        val LAST_PUSH_EPOCH_MS = longPreferencesKey("last_push_epoch_ms")
    }

    /**
     * Theme mode preference for the in-app palette.
     *  - `auto` (default): follow the system dark/light setting.
     *  - `light`: force light theme regardless of system.
     *  - `dark`: force dark theme regardless of system.
     *
     * Stored as string (not an enum ordinal) so a future ordering shuffle
     * doesn't break persistence.
     */
    val themeMode: Flow<String> = context.preferencesDataStore.data.map { prefs ->
        prefs[Keys.THEME_MODE] ?: "auto"
    }

    suspend fun setThemeMode(mode: String) {
        context.preferencesDataStore.edit { prefs ->
            prefs[Keys.THEME_MODE] = mode
        }
    }

    val quickAddEnabled: Flow<Boolean> = context.preferencesDataStore.data.map { prefs ->
        prefs[Keys.QUICK_ADD_ENABLED] ?: false
    }

    suspend fun currentQuickAddEnabled(): Boolean = quickAddEnabled.first()

    suspend fun setQuickAddEnabled(enabled: Boolean) {
        context.preferencesDataStore.edit { prefs ->
            prefs[Keys.QUICK_ADD_ENABLED] = enabled
        }
    }

    /**
     * Client id of the GitHub OAuth app registered by the user. Empty string
     * means "not yet configured"; the OAuth dialog prompts for it the first
     * time device-flow sign-in is requested.
     */
    val githubClientId: Flow<String> = context.preferencesDataStore.data.map { prefs ->
        prefs[Keys.GITHUB_CLIENT_ID].orEmpty()
    }

    suspend fun currentGithubClientId(): String = githubClientId.first()

    suspend fun setGithubClientId(clientId: String) {
        context.preferencesDataStore.edit { prefs ->
            val trimmed = clientId.trim()
            if (trimmed.isEmpty()) {
                prefs.remove(Keys.GITHUB_CLIENT_ID)
            } else {
                prefs[Keys.GITHUB_CLIENT_ID] = trimmed
            }
        }
    }

    /**
     * Wall-clock ms of the most recent successful PullWorker.doWork() return —
     * "GitHub was checked and our local copy reconciled". 0L means "never".
     */
    val lastPullEpochMs: Flow<Long> = context.preferencesDataStore.data.map { prefs ->
        prefs[Keys.LAST_PULL_EPOCH_MS] ?: 0L
    }

    suspend fun setLastPullTime(epochMs: Long) {
        context.preferencesDataStore.edit { prefs ->
            prefs[Keys.LAST_PULL_EPOCH_MS] = epochMs
        }
    }

    /**
     * Wall-clock ms of the most recent successful PushWorker.doWork() return
     * with at least one dirty row uploaded — "your edits made it to GitHub".
     * 0L means "never". This is the timestamp the user actually cares about
     * when they ask "我刚改的笔记上传了吗".
     */
    val lastPushEpochMs: Flow<Long> = context.preferencesDataStore.data.map { prefs ->
        prefs[Keys.LAST_PUSH_EPOCH_MS] ?: 0L
    }

    suspend fun setLastPushTime(epochMs: Long) {
        context.preferencesDataStore.edit { prefs ->
            prefs[Keys.LAST_PUSH_EPOCH_MS] = epochMs
        }
    }
}
