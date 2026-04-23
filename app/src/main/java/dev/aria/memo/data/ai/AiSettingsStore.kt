package dev.aria.memo.data.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Persistence for the user's AI provider configuration.
 *
 * The split mirrors [dev.aria.memo.data.SettingsStore] ↔ [dev.aria.memo.data.SecurePatStore]:
 * - Non-secret fields (`providerUrl`, `model`) live in a Preferences DataStore
 *   that's ergonomic to observe as a [Flow].
 * - The `apiKey` lives in an EncryptedSharedPreferences file (AES256-GCM with
 *   a hardware-backed master key when available). It's read synchronously
 *   inside the DataStore `map` so [observe] still emits a complete [AiConfig]
 *   on every change to either store.
 *
 * The api key is never logged or surfaced in error messages.
 *
 * Shape note:
 *  This is an `open class`, not an interface — the Android-backed
 *  implementation is the real code, and tests subclass this with an
 *  in-memory double. The primary constructor accepts a nullable [Context];
 *  tests pass `null` (via the secondary no-arg constructor) and skip all
 *  storage work by overriding [current] / [observe] / [save].
 */
private val Context.aiSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "memo_ai_settings")

open class AiSettingsStore(context: Context? = null) {

    private val appContext: Context? = context?.applicationContext

    private val securePrefs: SharedPreferences? by lazy {
        val ctx = appContext ?: return@lazy null
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            ctx,
            SECURE_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun readKey(): String = securePrefs?.getString(SECURE_KEY, "").orEmpty()

    private fun writeKey(key: String) {
        val prefs = securePrefs ?: return
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            prefs.edit().remove(SECURE_KEY).apply()
        } else {
            prefs.edit().putString(SECURE_KEY, trimmed).apply()
        }
    }

    /**
     * Reactive view of the full config (url + model + api key). Emits on every
     * DataStore change; the api key is pulled eagerly on each emission.
     *
     * Subclasses can override this to return an in-memory Flow.
     *
     * Fixes #62 (P7.0.1): when [appContext] is null (used by tests that don't
     * need a DataStore), we emit the empty config and then suspend in
     * [awaitCancellation] so the collector behaves like a live Flow — some
     * collectors (e.g. the VM init block) rely on the Flow staying active to
     * re-emit when the config changes; a one-shot `flowOf(...)` would complete
     * collection immediately and turn any future gating logic into a dead
     * branch.
     */
    open fun observe(): Flow<AiConfig> {
        val ctx = appContext ?: return flow {
            emit(AiConfig("", "", ""))
            awaitCancellation()
        }
        return ctx.aiSettingsDataStore.data.map { prefs ->
            AiConfig(
                providerUrl = prefs[Keys.PROVIDER_URL].orEmpty(),
                model = prefs[Keys.MODEL].orEmpty(),
                apiKey = readKey(),
            )
        }
    }

    /** One-shot snapshot. Used by [AiClient] to gate chat() on configuration. */
    open suspend fun current(): AiConfig = observe().first()

    /**
     * Atomic save of every field. Empty strings are allowed — they're how the
     * user clears a partially-filled form.
     */
    open suspend fun save(providerUrl: String, model: String, apiKey: String) {
        val ctx = appContext ?: return
        writeKey(apiKey)
        ctx.aiSettingsDataStore.edit { prefs ->
            prefs[Keys.PROVIDER_URL] = providerUrl.trim()
            prefs[Keys.MODEL] = model.trim()
        }
    }

    private object Keys {
        val PROVIDER_URL = stringPreferencesKey("provider_url")
        val MODEL = stringPreferencesKey("model")
    }

    private companion object {
        const val SECURE_FILE = "memo_ai_secure_prefs"
        const val SECURE_KEY = "ai_api_key"
    }
}
