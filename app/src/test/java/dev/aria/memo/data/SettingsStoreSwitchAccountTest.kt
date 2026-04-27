package dev.aria.memo.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.aria.memo.data.sync.SyncStatus
import dev.aria.memo.data.sync.SyncStatusBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * Review-W #3 — verify the privacy-critical contract that
 * [SettingsStore.switchAccount] clears the local dirty-row queue while plain
 * [SettingsStore.update] leaves it alone.
 *
 * Strategy:
 *  - Robolectric is only used to get an `applicationContext` for the
 *    DataStore-backed non-secret prefs. The keystore-backed [SecurePatStore]
 *    is bypassed via the test-only [SettingsStore] constructor that takes a
 *    [PatStorage] fake — Robolectric's JVM lacks an AndroidKeystore, so
 *    calling the real one would throw `KeyStoreException: AndroidKeyStore
 *    not found`.
 *  - The [DirtyFlagClearer] hook is replaced with a counter so we can verify
 *    call-count without standing up a Room db. The production wiring lives in
 *    `DefaultDirtyFlagClearer` and is covered by androidTest (it talks raw
 *    SQLite via `openHelper.writableDatabase`).
 *  - SyncStatusBus is process-singleton; we reset it between cases so a
 *    leaked emit can't pollute neighboring tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33], application = android.app.Application::class)
class SettingsStoreSwitchAccountTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** In-memory [PatStorage]. The real one round-trips through Keystore. */
    private class InMemoryPatStorage : PatStorage {
        private var value: String = ""
        override fun read(): String = value
        override fun write(pat: String) { value = pat }
        override fun clear() { value = "" }
    }

    /** Hand-rolled counter — every call to clearAllDirty bumps the int. */
    private class CountingClearer : DirtyFlagClearer {
        val calls = AtomicInteger(0)
        override suspend fun clearAllDirty() {
            calls.incrementAndGet()
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        SyncStatusBus.emit(SyncStatus.Idle)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        SyncStatusBus.emit(SyncStatus.Idle)
    }

    private fun newStore(clearer: DirtyFlagClearer): SettingsStore =
        SettingsStore(
            context = context,
            secure = InMemoryPatStorage(),
            dirtyClearer = clearer,
        )

    @Test
    fun `update with new pat does NOT clear dirty rows`() = runTest {
        // Setup: seed a baseline config so the update is a "PAT rotation"
        // rather than first-time setup.
        val clearer = CountingClearer()
        val store = newStore(clearer)
        store.update {
            it.copy(pat = "ghp_old", owner = "alice", repo = "memos", branch = "main")
        }
        advanceUntilIdle()
        // Sanity: setup itself does not invoke the clearer.
        assertEquals(0, clearer.calls.get())

        // Action: rotate just the PAT (the typical "expired token" flow).
        store.update {
            it.copy(pat = "ghp_rotated_same_account")
        }
        advanceUntilIdle()

        // Assertion: dirty queue is untouched. This is the Review-W #3
        // invariant — a same-account PAT rotation must keep the user's
        // pending notes syncable (otherwise rotating an expired PAT silently
        // throws away unsynced edits).
        assertEquals(
            "update() must NOT call DirtyFlagClearer; only switchAccount() may",
            0,
            clearer.calls.get(),
        )
        // And the new PAT was actually persisted.
        assertEquals("ghp_rotated_same_account", store.current().pat)
    }

    @Test
    fun `switchAccount clears dirty rows exactly once`() = runTest {
        val clearer = CountingClearer()
        val store = newStore(clearer)
        store.update {
            it.copy(pat = "ghp_alice", owner = "alice", repo = "alice-memos", branch = "main")
        }
        advanceUntilIdle()
        assertEquals(0, clearer.calls.get())

        // Action: switch to a different GitHub identity.
        store.switchAccount(
            owner = "bob",
            repo = "bob-memos",
            pat = "ghp_bob",
            branch = "main",
        )
        advanceUntilIdle()

        // Assertion: clearer fired exactly once. This proves that the dirty
        // queue authored under Alice's PAT was wiped before Bob's
        // credentials started driving sync — i.e. Bob's repo will never
        // receive Alice's unsynced notes.
        assertEquals(
            "switchAccount() must invoke DirtyFlagClearer exactly once",
            1,
            clearer.calls.get(),
        )
        // And the new identity is persisted end-to-end.
        val after = store.current()
        assertEquals("ghp_bob", after.pat)
        assertEquals("bob", after.owner)
        assertEquals("bob-memos", after.repo)
    }

    @Test
    fun `switchAccount resets SyncStatusBus to Idle so stale errors do not bleed`() = runTest {
        val clearer = CountingClearer()
        val store = newStore(clearer)
        store.update {
            it.copy(pat = "ghp_alice", owner = "alice", repo = "memos", branch = "main")
        }
        advanceUntilIdle()
        // Pre-condition: simulate a left-over UNAUTHORIZED banner from the
        // previous account's last sync attempt.
        SyncStatusBus.emit(SyncStatus.Error(ErrorCode.UNAUTHORIZED, "GitHub 拒绝访问"))
        assertNotEquals(SyncStatus.Idle, SyncStatusBus.status.value)

        // Action: swap to a fresh account.
        store.switchAccount(owner = "bob", repo = "memos", pat = "ghp_bob")
        advanceUntilIdle()

        // Assertion: the error banner was cleared. A persistent "拒绝访问"
        // would mislead the user into thinking the new PAT is also dead
        // before the next worker has even tried.
        assertEquals(
            "switchAccount() must reset SyncStatusBus to Idle",
            SyncStatus.Idle,
            SyncStatusBus.status.value,
        )
    }

    @Test
    fun `switchAccount with empty pat clears the secure store`() = runTest {
        // Edge case: a switch path that explicitly provides an empty PAT
        // (e.g. user logged out before logging back in) must wipe the
        // EncryptedSharedPreferences entry — otherwise the previous token
        // would stay readable on disk.
        val clearer = CountingClearer()
        val store = newStore(clearer)
        store.update {
            it.copy(pat = "ghp_alice", owner = "alice", repo = "memos")
        }
        advanceUntilIdle()
        assertTrue(store.current().pat.isNotBlank())

        store.switchAccount(owner = "", repo = "", pat = "")
        advanceUntilIdle()

        // Both the cleared dirty queue and the wiped PAT are post-conditions.
        assertEquals(1, clearer.calls.get())
        assertEquals("", store.current().pat)
        assertEquals("", store.current().owner)
        assertEquals("", store.current().repo)
    }

    @Test
    fun `switchAccount uses default branch main when blank passed`() = runTest {
        val clearer = CountingClearer()
        val store = newStore(clearer)
        store.update { it.copy(pat = "p", owner = "o", repo = "r", branch = "develop") }
        advanceUntilIdle()

        // Caller passes blank — switchAccount should normalize to "main"
        // (matches the default-arg behavior so callers don't end up with
        // an empty branch field that breaks GitHub API URLs).
        store.switchAccount(owner = "new", repo = "newr", pat = "newp", branch = "")
        advanceUntilIdle()

        assertEquals("main", store.current().branch)
    }

    @Test
    fun `update with same pat does not invoke clearer`() = runTest {
        // Idempotent update (no-op transform) is a fast-path that some UI
        // layers may exercise — confirm it never accidentally triggers a
        // dirty-row wipe.
        val clearer = CountingClearer()
        val store = newStore(clearer)
        store.update { it.copy(pat = "p", owner = "o", repo = "r") }
        advanceUntilIdle()
        store.update { it } // identity transform
        advanceUntilIdle()

        assertEquals(0, clearer.calls.get())
    }
}
