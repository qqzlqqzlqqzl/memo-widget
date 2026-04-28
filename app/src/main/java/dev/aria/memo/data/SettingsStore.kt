package dev.aria.memo.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.aria.memo.data.local.AppDatabase
import dev.aria.memo.data.sync.SyncScheduler
import dev.aria.memo.data.sync.SyncStatus
import dev.aria.memo.data.sync.SyncStatusBus
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
 * Functional hook for clearing every `dirty = 1` row across the local Room
 * tables. Extracted to a SAM so unit tests can inject a counter without
 * needing Robolectric / a real Android `Context` (Review-W #3 fix).
 *
 * Default production wiring uses raw SQL on the live [AppDatabase] singleton
 * (writes through `openHelper.writableDatabase`); the production code path
 * never touches the DAO interfaces, so this lives entirely inside
 * SettingsStore without forcing edits to `NoteDao` / `EventDao` /
 * `SingleNoteDao` (file-ownership constraint of the fix).
 */
fun interface DirtyFlagClearer {
    /** Reset `dirty = 0` on every row of every synced table. */
    suspend fun clearAllDirty()
}

/**
 * Minimal abstraction over the PAT storage. Production resolves to
 * [SecurePatStore]; tests inject an in-memory fake because Robolectric on
 * pure JVM has no Android Keystore service for the EncryptedSharedPreferences
 * MasterKey to bind against.
 */
interface PatStorage {
    fun read(): String
    fun write(pat: String)
    fun clear()

    companion object {
        /** Wrap a real [SecurePatStore] (final class) into this interface. */
        fun from(secure: SecurePatStore): PatStorage = object : PatStorage {
            override fun read(): String = secure.read()
            override fun write(pat: String) { secure.write(pat) }
            override fun clear() { secure.clear() }
        }
    }
}

/**
 * Typed wrapper around preferences. The PAT is held in [SecurePatStore]; every
 * other field lives in plain DataStore.
 *
 * Legacy migration:
 *   V1 installs stored the PAT in DataStore as plaintext. The first [current]
 *   call that finds an empty secure store but a non-empty legacy key copies
 *   the token into the secure store *and* clears the legacy key — guaranteed
 *   to fire before any consumer reads, not only on next [update].
 *
 * Account switching (Review-W #3 fix):
 *   Calling [update] with a different `pat`/`owner`/`repo` does NOT clear
 *   local dirty rows — that path is reserved for "the user just refreshed an
 *   expired PAT", where the local sync queue is still legitimately bound to
 *   the same GitHub identity. To swap GitHub identities use [switchAccount];
 *   it wipes the dirty queue first so notes typed under the previous account
 *   never get pushed into the new account's repo.
 */
class SettingsStore(
    private val context: Context,
    private val secure: PatStorage,
    private val dirtyClearer: DirtyFlagClearer = DefaultDirtyFlagClearer,
) {

    /**
     * Production-friendly secondary constructor. Existing call sites that
     * pass a `SecurePatStore` (final class) keep working through the
     * [PatStorage.from] adapter.
     */
    constructor(
        context: Context,
        secure: SecurePatStore = SecurePatStore(context),
        dirtyClearer: DirtyFlagClearer = DefaultDirtyFlagClearer,
    ) : this(
        context = context,
        secure = PatStorage.from(secure),
        dirtyClearer = dirtyClearer,
    )

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

    /**
     * Update PAT / owner / repo / branch in place. **Does NOT clear local
     * dirty rows** — this is the "I rotated my expired PAT" path. If the
     * caller intends to swap GitHub identities (different owner/repo/PAT for
     * a different account) they MUST call [switchAccount] instead, which
     * wipes the local sync queue before swapping credentials so notes
     * authored under the previous account never get pushed to the new
     * account's repo (Review-W #3).
     */
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

        // Fixes #113 (Bug-1 H10): when the user finishes typing a fresh PAT
        // (typically after a previous push hit 401 UNAUTHORIZED and parked
        // the dirty queue), kick a push retry with the new credentials. We
        // scope this strictly to "credentials actually changed AND the new
        // config is push-eligible" — branch-only edits or clearing the PAT
        // shouldn't fire a push.
        val credentialsChanged =
            patAfter != before.pat.trim() ||
                after.owner != before.owner ||
                after.repo != before.repo
        if (credentialsChanged && after.isConfigured) {
            // runCatching: in unit tests WorkManager isn't initialised; the
            // production path (default WorkManagerInitializer) always has it.
            // We don't want a missing test env to fail the credential write.
            runCatching {
                SyncScheduler.enqueuePushAfterCredentialChange(context.applicationContext)
            }
        }
    }

    /**
     * Atomic "swap GitHub account" path (Review-W #3).
     *
     * Steps, in order, so the dirty queue can never observe a half-swapped
     * identity:
     *   1. Clear every `dirty = 1` row via the injected [DirtyFlagClearer].
     *      Notes the user typed under the previous PAT/owner/repo are now
     *      considered local-only — still readable, just no longer eligible
     *      for push. The PushWorker's `WHERE dirty = 1` filter skips them.
     *   2. Reset transient sync status to [SyncStatus.Idle] so any error
     *      banner left over from the old account's last failed push doesn't
     *      bleed across the swap.
     *   3. Persist the new credentials (PAT in secure store, owner/repo/
     *      branch in DataStore).
     *   4. Refresh widgets so their cached config (and rendered list)
     *      catches up with the new identity.
     *
     * Order matters: clearing dirty BEFORE writing the new PAT means there is
     * no window during which a Worker waking up could read the new PAT and
     * still see dirty rows authored against the old one.
     */
    suspend fun switchAccount(
        owner: String,
        repo: String,
        pat: String,
        branch: String = "main",
    ) {
        // Step 1: drop every pending push from the previous account's queue.
        // Stays in IO because raw SQL writes hit the SQLite writer thread.
        withContext(Dispatchers.IO) { dirtyClearer.clearAllDirty() }

        // Step 2: clear any stale error banner from the previous account's
        // last sync attempt. Idle is the correct neutral state for a freshly
        // swapped identity — Syncing/Ok would be a lie (we haven't tried
        // anything against the new repo yet).
        SyncStatusBus.emit(SyncStatus.Idle)

        // Step 3: write the new credentials. Same IO discipline as [update].
        val patAfter = pat.trim()
        val branchAfter = branch.trim().ifBlank { "main" }
        withContext(Dispatchers.IO) {
            if (patAfter.isEmpty()) secure.clear() else secure.write(patAfter)
        }
        context.settingsDataStore.edit { prefs ->
            prefs.remove(Keys.PAT_LEGACY)
            prefs[Keys.OWNER] = owner.trim()
            prefs[Keys.REPO] = repo.trim()
            prefs[Keys.BRANCH] = branchAfter
        }

        // Step 4: kick the widgets so they re-read the new config.
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

    /**
     * Production [DirtyFlagClearer]. Walks the live [AppDatabase] singleton
     * and resets `dirty` to 0 on every synced table via raw SQL — bypassing
     * the DAO layer keeps this fix isolated to SettingsStore (Review-W #3
     * file ownership).
     *
     * If the DB hasn't been built yet (e.g. unit test that skipped
     * `ServiceLocator.init`) this is a no-op — the production app always
     * builds the DB in `MemoApplication.onCreate` before any UI can call
     * `switchAccount`, so the null branch only ever triggers in tests that
     * already have their own injected clearer.
     */
    private object DefaultDirtyFlagClearer : DirtyFlagClearer {
        override suspend fun clearAllDirty() {
            val db = AppDatabase.instance() ?: return
            val writable = db.openHelper.writableDatabase
            writable.execSQL("UPDATE note_files SET dirty = 0 WHERE dirty = 1")
            writable.execSQL("UPDATE events SET dirty = 0 WHERE dirty = 1")
            writable.execSQL("UPDATE single_notes SET dirty = 0 WHERE dirty = 1")
        }
    }
}
