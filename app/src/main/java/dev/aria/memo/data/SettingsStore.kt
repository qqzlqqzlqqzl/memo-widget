package dev.aria.memo.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.aria.memo.data.widget.WidgetRefresher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

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

    /**
     * Perf-fix C1: `secure.read()` 会在首次访问触发 EncryptedSharedPreferences
     * + MasterKey 的 Keystore 冷路径解锁（200–1500ms），后续调用也是同步磁盘 IO。
     * DataStore.data 本身在 IO，但 `map` 回调会继承 collector 的 dispatcher，
     * 如果下游在 Main（widget provideGlance / VM init / StateFlow 的观察者）
     * 直接 `.first()`，那 `secure.read()` 就跑在 Main。
     *
     * `flowOn(Dispatchers.IO)` 只影响**上游到达这个点为止**的调度，不改变下游
     * collector 的 dispatcher；也就是 `secure.read()` 保证在 IO，而调用 `.first()`
     * 的协程仍然在它本来的上下文里继续。
     */
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
    }.flowOn(Dispatchers.IO)

    suspend fun update(transform: (AppConfig) -> AppConfig) {
        val before = current()
        val after = transform(before)
        val patAfter = after.pat.trim()
        // Perf-fix C1: Keystore-backed secure prefs write 是同步 IO，不能在 Main。
        withContext(Dispatchers.IO) {
            if (patAfter.isEmpty()) secure.clear() else secure.write(patAfter)
        }
        context.settingsDataStore.edit { prefs ->
            prefs.remove(Keys.PAT_LEGACY) // always wipe any plaintext residue
            prefs[Keys.OWNER] = after.owner
            prefs[Keys.REPO] = after.repo
            prefs[Keys.BRANCH] = after.branch
        }
        // P8 widget 自推：isConfigured 可能从 false→true（用户首次填完 PAT/owner/repo）
        // 或 true→false（用户清空 PAT）。两种变化 widget 都要立刻切换态——未配置
        // 显示"先打开 app 配置 GitHub PAT"，已配置显示笔记列表。
        // 即便 isConfigured 没变（比如只改 branch），刷新也无副作用，统一 fire-and-forget。
        WidgetRefresher.refreshAll(context.applicationContext)
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
