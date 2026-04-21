package dev.aria.memo.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per calendar event, mirroring a single iCalendar file on GitHub at
 * `events/<uid>.ics`.
 *
 * Fixes #2: `filePath` has a unique index so [PullWorker] can locate rows by
 * remote filename even after a rename where filename != uid.
 *
 * `uid` is the stable iCalendar UID. `startEpochMs` / `endEpochMs` are UTC
 * milliseconds — UI renders in the device local zone. `dirty` + `githubSha`
 * mirror [NoteFileEntity]'s sync semantics.
 */
@Entity(tableName = "events", indices = [Index(value = ["filePath"], unique = true)])
data class EventEntity(
    @PrimaryKey val uid: String,
    val summary: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val allDay: Boolean,
    val filePath: String,
    val githubSha: String?,
    val localUpdatedAt: Long,
    val remoteUpdatedAt: Long?,
    val dirty: Boolean,
    /** Soft-delete flag so a deletion can be pushed (GitHub file removed) later. */
    val tombstoned: Boolean = false,
    /**
     * P4 recurring events. Minimal iCalendar RRULE subset:
     *   - `FREQ=WEEKLY` — every 7 days starting at [startEpochMs]
     *   - `FREQ=MONTHLY` — every calendar month on the same day-of-month
     * null = single occurrence. Expansion horizon is 1 year forward to keep
     * state bounded; rendering beyond that rolls at the next pull.
     */
    val rrule: String? = null,
)
