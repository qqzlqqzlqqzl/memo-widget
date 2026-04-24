package dev.aria.memo.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted storage for the GitHub Personal Access Token.
 *
 * Lives outside the main DataStore (which holds non-secret config). Uses
 * AES256-GCM with a hardware-backed master key when available. The only
 * caller is [SettingsStore] — this class is not exposed to UI.
 */
class SecurePatStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val appContext = context.applicationContext
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun read(): String = prefs.getString(KEY_PAT, "").orEmpty()

    fun write(pat: String) {
        prefs.edit().putString(KEY_PAT, pat).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_PAT).apply()
    }

    private companion object {
        const val FILE_NAME = "memo_secure_prefs"
        const val KEY_PAT = "pat"
    }
}
