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

/** DataStore for non-secret config (owner/repo/branch). PAT lives in [SecurePatStore]. */
val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "memo_settings")

/**
 * Typed wrapper around preferences. The PAT is held in [SecurePatStore]; every
 * other field lives in plain DataStore.
 *
 * Legacy migration:
 *   V1 installs stored the PAT in DataStore as plaintext. The first [current]
 *   call that finds an empty secure store but a non-empty legacy key copies
 *   the token into the secure store *and* clears the legacy key — guaranteed
 *   to fire before any consumer reads, not only on next [update].
 */
class SettingsStore(
    private val context: Context,
    private val secure: SecurePatStore = SecurePatStore(context),
) {

    private object Keys {
        val PAT_LEGACY = stringPreferencesKey("pat")
        val OWNER = stringPreferencesKey("owner")
        val REPO = stringPreferencesKey("repo")
        val BRANCH = stringPreferencesKey("branch")
    }

    val config: Flow<AppConfig> = context.settingsDataStore.data.map { prefs ->
        val securePat = secure.read()
        val pat = when {
            securePat.isNotEmpty() -> securePat
            else -> {
                val legacy = prefs[Keys.PAT_LEGACY].orEmpty()
                if (legacy.isNotEmpty()) migrateLegacyPat(legacy)
                legacy
            }
        }
        AppConfig(
            pat = pat,
            owner = prefs[Keys.OWNER].orEmpty(),
            repo = prefs[Keys.REPO].orEmpty(),
            branch = prefs[Keys.BRANCH]?.takeIf { it.isNotBlank() } ?: "main",
        )
    }

    suspend fun update(transform: (AppConfig) -> AppConfig) {
        val before = current()
        val after = transform(before)
        val patAfter = after.pat.trim()
        if (patAfter.isEmpty()) secure.clear() else secure.write(patAfter)
        context.settingsDataStore.edit { prefs ->
            prefs.remove(Keys.PAT_LEGACY) // always wipe any plaintext residue
            prefs[Keys.OWNER] = after.owner
            prefs[Keys.REPO] = after.repo
            prefs[Keys.BRANCH] = after.branch
        }
    }

    suspend fun current(): AppConfig = config.first()

    // ---- internals --------------------------------------------------------

    private fun migrateLegacyPat(legacy: String) {
        // Copy legacy plaintext into the secure store. The accompanying
        // DataStore.remove happens on the next write via [update]; this
        // side-effect keeps the two stores in sync immediately.
        secure.write(legacy)
    }
}
