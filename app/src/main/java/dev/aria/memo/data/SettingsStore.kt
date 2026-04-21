package dev.aria.memo.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Named DataStore; sandboxed to the app — safe for storing the PAT in V1. */
val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "memo_settings")

/**
 * Typed wrapper around the preferences DataStore.
 *
 * Contract (AGENT_SPEC.md section 4.1):
 *  - [config] — reactive stream of [AppConfig]; never throws, never logs the PAT.
 *  - [update] — atomic read-modify-write of the config via a pure transform.
 *  - [current] — convenience snapshot; equivalent to `config.first()`.
 *
 * Thread safety: DataStore serializes writes; multiple concurrent [update]
 * calls are applied in order. `pathTemplate` is not user-editable in V1 — we
 * always materialize the default from [AppConfig].
 *
 * WARNING: Never pass [AppConfig.pat] through logs/toasts/exception messages.
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val PAT = stringPreferencesKey("pat")
        val OWNER = stringPreferencesKey("owner")
        val REPO = stringPreferencesKey("repo")
        val BRANCH = stringPreferencesKey("branch")
    }

    val config: Flow<AppConfig> = context.settingsDataStore.data.map { prefs ->
        AppConfig(
            pat = prefs[Keys.PAT].orEmpty(),
            owner = prefs[Keys.OWNER].orEmpty(),
            repo = prefs[Keys.REPO].orEmpty(),
            branch = prefs[Keys.BRANCH]?.takeIf { it.isNotBlank() } ?: "main",
            // pathTemplate not exposed in V1 — always use default.
        )
    }

    suspend fun update(transform: (AppConfig) -> AppConfig) {
        context.settingsDataStore.edit { prefs ->
            val current = AppConfig(
                pat = prefs[Keys.PAT].orEmpty(),
                owner = prefs[Keys.OWNER].orEmpty(),
                repo = prefs[Keys.REPO].orEmpty(),
                branch = prefs[Keys.BRANCH]?.takeIf { it.isNotBlank() } ?: "main",
            )
            val next = transform(current)
            prefs[Keys.PAT] = next.pat
            prefs[Keys.OWNER] = next.owner
            prefs[Keys.REPO] = next.repo
            prefs[Keys.BRANCH] = next.branch
        }
    }

    suspend fun current(): AppConfig = config.first()
}
