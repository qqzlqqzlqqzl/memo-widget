package dev.aria.memo.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {

    @Query("SELECT * FROM note_files ORDER BY date DESC")
    fun observeAll(): Flow<List<NoteFileEntity>>

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
}
