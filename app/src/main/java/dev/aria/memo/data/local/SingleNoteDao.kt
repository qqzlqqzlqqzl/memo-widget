package dev.aria.memo.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Row-per-file (Obsidian style) access layer. Mirrors the sync semantics of
 * [EventDao]: pin first, newest next, tombstones excluded from read queries
 * but still visible to [pending] so the push worker can drive a DELETE.
 */
@Dao
interface SingleNoteDao {

    @Query(
        "SELECT * FROM single_notes " +
            "WHERE tombstoned = 0 " +
            "ORDER BY isPinned DESC, date DESC, time DESC"
    )
    fun observeAll(): Flow<List<SingleNoteEntity>>

    /** Newest [limit] notes for the widget / today screen. */
    @Query(
        "SELECT * FROM single_notes " +
            "WHERE tombstoned = 0 " +
            "ORDER BY isPinned DESC, date DESC, time DESC " +
            "LIMIT :limit"
    )
    fun observeRecent(limit: Int): Flow<List<SingleNoteEntity>>

    @Query("SELECT * FROM single_notes WHERE uid = :uid LIMIT 1")
    suspend fun get(uid: String): SingleNoteEntity?

    /** Locate a row by remote path — `filePath` carries a UNIQUE index. */
    @Query("SELECT * FROM single_notes WHERE filePath = :path LIMIT 1")
    suspend fun getByPath(path: String): SingleNoteEntity?

    @Query("SELECT * FROM single_notes WHERE dirty = 1")
    suspend fun pending(): List<SingleNoteEntity>

    @Query("SELECT * FROM single_notes")
    suspend fun snapshotAll(): List<SingleNoteEntity>

    @Upsert
    suspend fun upsert(entity: SingleNoteEntity)

    @Query(
        "UPDATE single_notes SET dirty = 0, githubSha = :sha, remoteUpdatedAt = :remoteAt " +
            "WHERE uid = :uid"
    )
    suspend fun markClean(uid: String, sha: String?, remoteAt: Long)

    @Query(
        "UPDATE single_notes SET tombstoned = 1, dirty = 1, localUpdatedAt = :updatedAt " +
            "WHERE uid = :uid"
    )
    suspend fun tombstone(uid: String, updatedAt: Long)

    @Query("DELETE FROM single_notes WHERE uid = :uid")
    suspend fun hardDelete(uid: String)

    /**
     * Toggle the pin flag and mark dirty so the push worker re-uploads the
     * file with the `pinned: true` front matter. Note that the front-matter
     * mutation happens in the repository layer — this DAO only persists the
     * already-computed body.
     */
    @Query(
        "UPDATE single_notes SET isPinned = :pinned, body = :body, dirty = 1, " +
            "localUpdatedAt = :updatedAt WHERE uid = :uid"
    )
    suspend fun togglePin(uid: String, pinned: Boolean, body: String, updatedAt: Long)
}
