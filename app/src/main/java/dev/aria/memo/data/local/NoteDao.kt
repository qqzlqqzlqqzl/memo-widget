package dev.aria.memo.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {

    @Query("SELECT * FROM note_files ORDER BY isPinned DESC, date DESC")
    fun observeAll(): Flow<List<NoteFileEntity>>

    /**
     * LIMIT 下推版本。widget / today-屏幕用，避免 observeAll().first() 全表读
     * 再内存截断。HANDOFF.md P6.1 第 4 项。
     */
    @Query("SELECT * FROM note_files ORDER BY isPinned DESC, date DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<NoteFileEntity>>

    @Query("SELECT * FROM note_files WHERE path = :path LIMIT 1")
    suspend fun get(path: String): NoteFileEntity?

    @Query("SELECT * FROM note_files WHERE dirty = 1")
    suspend fun pending(): List<NoteFileEntity>

    /** Fixes #5: cheap emptiness check to decide whether we need an initial full pull. */
    @Query("SELECT COUNT(*) FROM note_files")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(entity: NoteFileEntity)

    @Query("UPDATE note_files SET dirty = 0, githubSha = :sha, remoteUpdatedAt = :remoteAt WHERE path = :path")
    suspend fun markClean(path: String, sha: String?, remoteAt: Long)

    /** Toggle the pin flag on a single day-file. Also marks dirty for push. */
    @Query("UPDATE note_files SET isPinned = :pinned, content = :content, dirty = 1, localUpdatedAt = :updatedAt WHERE path = :path")
    suspend fun togglePin(path: String, pinned: Boolean, content: String, updatedAt: Long)
}
