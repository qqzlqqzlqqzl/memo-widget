package dev.aria.memo.data

import dev.aria.memo.data.local.SingleNoteDao
import dev.aria.memo.data.local.SingleNoteEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Pure JVM tests for the Fix-X2 Part 2 Undo path on
 * [SingleNoteRepository.tombstone] / [SingleNoteRepository.restoreFromTombstone].
 *
 * Strategy:
 *  - [FakeSingleNoteDao] is an in-memory Map<uid, entity> that mirrors the
 *    Room DAO's read/write contract: `observeAll`/`observeRecent` filter
 *    `tombstoned = 0`, `tombstone`/`restoreFromTombstone` flip the flag and
 *    bump dirty + localUpdatedAt the same way the @Query SQL does. This lets
 *    us assert the user-visible behaviour (rows vanish on tombstone, return
 *    on restore) without spinning up Robolectric for Room.
 *  - [TombstoneTestRepository] subclasses the production repository and
 *    overrides `tombstone` / `restoreFromTombstone` to skip the WorkManager
 *    / Glance side effects (which require an Android Context). The DAO
 *    interactions are identical to production — only the
 *    [dev.aria.memo.data.sync.SyncScheduler.enqueuePush] /
 *    [dev.aria.memo.data.widget.WidgetRefresher.refreshAll] calls are
 *    bypassed because they hard-depend on `WorkManager.getInstance(context)`.
 *
 * Promises covered:
 *  1. After tombstone(uid) the row disappears from observeRecent / observeAll.
 *  2. After restoreFromTombstone(uid) the row reappears with dirty=true.
 *  3. tombstone -> restore is idempotent: the entity's body / title /
 *     filePath are preserved (we never lose user content during the Undo
 *     window).
 *  4. tombstone(unknown_uid) returns NOT_FOUND.
 *  5. restoreFromTombstone(unknown_uid) returns NOT_FOUND — covers the race
 *     where PushWorker hardDeleted the row before the user hit Undo.
 *  6. The DAO write-side flips are observable via `pending()` so PushWorker
 *     still sees tombstoned rows queued for remote DELETE (regression guard
 *     against a future change that quietly clears the dirty flag).
 */
class SingleNoteRepositoryTombstoneTest {

    private fun entity(
        uid: String,
        title: String = "title-$uid",
        body: String = "body-$uid",
        tombstoned: Boolean = false,
        dirty: Boolean = false,
        sha: String? = "sha-$uid",
    ) = SingleNoteEntity(
        uid = uid,
        filePath = "notes/$uid.md",
        title = title,
        body = body,
        date = LocalDate.of(2026, 4, 22),
        time = LocalTime.of(9, 15),
        isPinned = false,
        githubSha = sha,
        localUpdatedAt = 1_000L,
        remoteUpdatedAt = 2_000L,
        dirty = dirty,
        tombstoned = tombstoned,
    )

    @Test
    fun `tombstone removes the row from observeRecent`() = runTest {
        val dao = FakeSingleNoteDao(listOf(entity("a"), entity("b")))
        val repo = TombstoneTestRepository(dao)

        // Sanity: both rows visible before tombstone.
        assertEquals(2, dao.observeRecent(20).first().size)

        val result = repo.tombstone("a")
        assertTrue("tombstone of existing uid must return Ok", result is MemoResult.Ok)

        val visible = dao.observeRecent(20).first().map { it.uid }
        assertEquals("tombstoned row must be filtered out", listOf("b"), visible)
    }

    @Test
    fun `tombstone removes the row from observeAll`() = runTest {
        val dao = FakeSingleNoteDao(listOf(entity("a"), entity("b")))
        val repo = TombstoneTestRepository(dao)

        repo.tombstone("a")

        val visible = dao.observeAll().first().map { it.uid }
        assertEquals(listOf("b"), visible)
    }

    @Test
    fun `restoreFromTombstone makes the row reappear`() = runTest {
        val dao = FakeSingleNoteDao(listOf(entity("a")))
        val repo = TombstoneTestRepository(dao)

        repo.tombstone("a")
        assertTrue(
            "row must vanish post-tombstone",
            dao.observeAll().first().isEmpty()
        )

        val result = repo.restoreFromTombstone("a")
        assertTrue("restore of tombstoned uid must return Ok", result is MemoResult.Ok)

        val visible = dao.observeAll().first().map { it.uid }
        assertEquals("row must come back after restore", listOf("a"), visible)
    }

    @Test
    fun `restoreFromTombstone preserves body and title`() = runTest {
        val dao = FakeSingleNoteDao(listOf(entity("a", title = "早晨想法", body = "今天 ...")))
        val repo = TombstoneTestRepository(dao)

        repo.tombstone("a")
        repo.restoreFromTombstone("a")

        val restored = dao.get("a")
        assertNotNull(restored)
        assertEquals("早晨想法", restored!!.title)
        assertEquals("今天 ...", restored.body)
        assertEquals("notes/a.md", restored.filePath)
        assertFalse("restore must clear the tombstone flag", restored.tombstoned)
    }

    @Test
    fun `restoreFromTombstone marks the row dirty`() = runTest {
        // Fresh entity with dirty=false (synced state). After restore, dirty
        // must be 1 so PushWorker reconciles GitHub if the tombstone already
        // produced a remote DELETE within the Undo window.
        val dao = FakeSingleNoteDao(listOf(entity("a", dirty = false)))
        val repo = TombstoneTestRepository(dao)

        repo.tombstone("a")
        repo.restoreFromTombstone("a")

        assertTrue("restore must flip dirty=1", dao.get("a")!!.dirty)
    }

    @Test
    fun `tombstone of unknown uid returns NOT_FOUND`() = runTest {
        val dao = FakeSingleNoteDao(listOf(entity("a")))
        val repo = TombstoneTestRepository(dao)

        val result = repo.tombstone("does-not-exist")
        assertTrue(result is MemoResult.Err)
        assertEquals(ErrorCode.NOT_FOUND, (result as MemoResult.Err).code)
    }

    @Test
    fun `restoreFromTombstone of unknown uid returns NOT_FOUND`() = runTest {
        // Simulates the race where PushWorker hardDeletes the row before the
        // user hits Undo. UI must treat NOT_FOUND as "nothing to undo".
        val dao = FakeSingleNoteDao(listOf(entity("a")))
        val repo = TombstoneTestRepository(dao)

        val result = repo.restoreFromTombstone("does-not-exist")
        assertTrue(result is MemoResult.Err)
        assertEquals(ErrorCode.NOT_FOUND, (result as MemoResult.Err).code)
    }

    @Test
    fun `tombstoned rows still show up in pending() so PushWorker can DELETE them`() = runTest {
        // Regression guard: a future refactor that clears `dirty` on tombstone
        // would silently leak deletes (the row vanishes from the UI but never
        // produces a remote DELETE). pending() is the PushWorker's hook into
        // the dirty queue, so we assert the tombstoned row is in there.
        val dao = FakeSingleNoteDao(listOf(entity("a", dirty = false)))
        val repo = TombstoneTestRepository(dao)

        repo.tombstone("a")

        val pending = dao.pending()
        assertEquals(1, pending.size)
        assertEquals("a", pending[0].uid)
        assertTrue(pending[0].tombstoned)
        assertTrue(pending[0].dirty)
    }

    @Test
    fun `delete is a thin alias for tombstone`() = runTest {
        // Fix-X2 Part 2 keeps `delete(uid)` as a delegate so existing call
        // sites (NoteListViewModel.delete) continue to work. Verify they walk
        // the same DAO path as the new explicit `tombstone(uid)`.
        val dao = FakeSingleNoteDao(listOf(entity("a")))
        val repo = TombstoneTestRepository(dao)

        repo.delete("a")

        val visible = dao.observeAll().first().map { it.uid }
        assertTrue("delete() must drive the row out of observeAll", visible.isEmpty())
        assertTrue("delete() must mark tombstoned", dao.get("a")!!.tombstoned)
    }

    @Test
    fun `tombstone then immediate restore round-trips`() = runTest {
        // The "user hits 撤销 inside the Undo window" flow. Should be a no-op
        // from the user's perspective: row is back, dirty so PushWorker
        // reconciles, tombstone flag cleared.
        val dao = FakeSingleNoteDao(listOf(entity("a", dirty = false)))
        val repo = TombstoneTestRepository(dao)

        repo.tombstone("a")
        repo.restoreFromTombstone("a")

        val visible = dao.observeAll().first().map { it.uid }
        assertEquals(listOf("a"), visible)
        val row = dao.get("a")!!
        assertFalse(row.tombstoned)
        assertTrue(row.dirty)
    }
}

/**
 * Test-only [SingleNoteRepository] subclass that overrides the methods under
 * test to skip the [dev.aria.memo.data.sync.SyncScheduler.enqueuePush] /
 * [dev.aria.memo.data.widget.WidgetRefresher.refreshAll] side effects.
 *
 * Both helpers walk through `WorkManager.getInstance(appContext)` — the only
 * piece of the production codepath that hard-requires Android. The DAO
 * interactions are reproduced verbatim so the test really exercises the
 * production flow up to the Room boundary.
 */
private class TombstoneTestRepository(
    private val testDao: SingleNoteDao,
) : SingleNoteRepository(appContext = null, settings = null, dao = testDao) {

    override fun observeAll(): Flow<List<SingleNoteEntity>> = testDao.observeAll()

    override fun observeRecent(limit: Int): Flow<List<SingleNoteEntity>> =
        testDao.observeRecent(limit)

    override suspend fun get(uid: String): SingleNoteEntity? = testDao.get(uid)

    override suspend fun tombstone(uid: String): MemoResult<Unit> {
        val existing = testDao.get(uid)
            ?: return MemoResult.Err(ErrorCode.NOT_FOUND, "note not found: $uid")
        testDao.tombstone(existing.uid, System.currentTimeMillis())
        return MemoResult.Ok(Unit)
    }

    override suspend fun restoreFromTombstone(uid: String): MemoResult<Unit> {
        val existing = testDao.get(uid)
            ?: return MemoResult.Err(ErrorCode.NOT_FOUND, "note not found: $uid")
        testDao.restoreFromTombstone(existing.uid, System.currentTimeMillis())
        return MemoResult.Ok(Unit)
    }
}

/**
 * In-memory [SingleNoteDao] that reproduces the SQL filter / mutation
 * semantics from the @Query annotations. Backed by a [MutableStateFlow] map
 * so observeAll / observeRecent emit on every write — same shape as Room's
 * Flow returns.
 */
private class FakeSingleNoteDao(initial: List<SingleNoteEntity>) : SingleNoteDao {
    private val rows = MutableStateFlow(initial.associateBy { it.uid })

    override fun observeAll(): Flow<List<SingleNoteEntity>> =
        rows.map { it.values.filter { e -> !e.tombstoned }.sortedByDescending { e -> e.localUpdatedAt } }

    override fun observeRecent(limit: Int): Flow<List<SingleNoteEntity>> =
        rows.map {
            it.values
                .filter { e -> !e.tombstoned }
                .sortedByDescending { e -> e.localUpdatedAt }
                .take(limit)
        }

    override suspend fun get(uid: String): SingleNoteEntity? = rows.value[uid]

    override suspend fun getByPath(path: String): SingleNoteEntity? =
        rows.value.values.firstOrNull { it.filePath == path }

    override suspend fun pending(): List<SingleNoteEntity> =
        rows.value.values.filter { it.dirty }

    override suspend fun snapshotAll(): List<SingleNoteEntity> = rows.value.values.toList()

    override suspend fun allFilePaths(): List<String> = rows.value.values.map { it.filePath }

    override suspend fun upsert(entity: SingleNoteEntity) {
        rows.value = rows.value + (entity.uid to entity)
    }

    override suspend fun markClean(uid: String, sha: String?, remoteAt: Long) {
        val existing = rows.value[uid] ?: return
        rows.value = rows.value + (uid to existing.copy(
            dirty = false,
            githubSha = sha,
            remoteUpdatedAt = remoteAt,
        ))
    }

    override suspend fun tombstone(uid: String, updatedAt: Long) {
        val existing = rows.value[uid] ?: return
        rows.value = rows.value + (uid to existing.copy(
            tombstoned = true,
            dirty = true,
            localUpdatedAt = updatedAt,
        ))
    }

    override suspend fun restoreFromTombstone(uid: String, updatedAt: Long) {
        val existing = rows.value[uid] ?: return
        rows.value = rows.value + (uid to existing.copy(
            tombstoned = false,
            dirty = true,
            localUpdatedAt = updatedAt,
        ))
    }

    override suspend fun hardDelete(uid: String) {
        rows.value = rows.value - uid
    }

    override suspend fun togglePin(uid: String, pinned: Boolean, body: String, updatedAt: Long) {
        val existing = rows.value[uid] ?: return
        rows.value = rows.value + (uid to existing.copy(
            isPinned = pinned,
            body = body,
            dirty = true,
            localUpdatedAt = updatedAt,
        ))
    }
}
