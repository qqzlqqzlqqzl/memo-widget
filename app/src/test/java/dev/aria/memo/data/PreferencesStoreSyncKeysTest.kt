package dev.aria.memo.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Locks down the contract that [PreferencesStore.lastPullEpochMs] and
 * [PreferencesStore.lastPushEpochMs] are stored under DISTINCT DataStore
 * keys. A typo that collapses both onto the same key would silently break
 * the SyncStatusCard — push timestamps would overwrite pull timestamps and
 * vice-versa, and the user would see "已上传到 GitHub：刚刚" even when only
 * a pull happened.
 *
 * Cheap to run, catches a class of silent regression that wouldn't surface
 * in any other unit test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class PreferencesStoreSyncKeysTest {

    @After
    fun teardown() {
        // Wipe the DataStore file between runs so the next test starts at 0L.
        // DataStore writes to filesDir/datastore/memo_preferences.preferences_pb.
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        File(ctx.filesDir, "datastore/memo_preferences.preferences_pb").delete()
    }

    @Test
    fun `push and pull timestamps occupy independent DataStore keys`() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = PreferencesStore(ctx)

        store.setLastPullTime(1_000L)
        store.setLastPushTime(2_000L)

        assertEquals(1_000L, store.lastPullEpochMs.first())
        assertEquals(2_000L, store.lastPushEpochMs.first())
    }

    @Test
    fun `default value for both keys is 0`() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = PreferencesStore(ctx)
        assertEquals(0L, store.lastPullEpochMs.first())
        assertEquals(0L, store.lastPushEpochMs.first())
    }

    @Test
    fun `overwriting one timestamp does not affect the other`() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = PreferencesStore(ctx)

        store.setLastPullTime(100L)
        store.setLastPushTime(200L)
        store.setLastPullTime(300L) // overwrite pull only

        assertEquals(300L, store.lastPullEpochMs.first())
        assertEquals(200L, store.lastPushEpochMs.first()) // push untouched
    }
}
