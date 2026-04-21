package dev.aria.memo.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {

    @Query("SELECT * FROM events WHERE tombstoned = 0 ORDER BY startEpochMs ASC")
    fun observeAll(): Flow<List<EventEntity>>

    @Query("SELECT * FROM events")
    suspend fun snapshotAll(): List<EventEntity>

    @Query("SELECT * FROM events WHERE tombstoned = 0 AND startEpochMs < :endMs AND endEpochMs >= :startMs ORDER BY startEpochMs ASC")
    fun observeBetween(startMs: Long, endMs: Long): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE uid = :uid LIMIT 1")
    suspend fun get(uid: String): EventEntity?

    @Query("SELECT * FROM events WHERE dirty = 1")
    suspend fun pending(): List<EventEntity>

    @Upsert
    suspend fun upsert(entity: EventEntity)

    @Query("UPDATE events SET dirty = 0, githubSha = :sha, remoteUpdatedAt = :remoteAt WHERE uid = :uid")
    suspend fun markClean(uid: String, sha: String?, remoteAt: Long)

    @Query("UPDATE events SET tombstoned = 1, dirty = 1, localUpdatedAt = :updatedAt WHERE uid = :uid")
    suspend fun tombstone(uid: String, updatedAt: Long)

    @Query("DELETE FROM events WHERE uid = :uid")
    suspend fun hardDelete(uid: String)
}
