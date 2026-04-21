package dev.aria.memo.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate

/**
 * One row per day-file stored in the GitHub repo.
 *
 * `path` is the repo-relative filename (e.g. "2026-04-21.md"). It is the
 * primary key — matches GitHub's natural identity for a file. `content`
 * holds the full markdown body so the UI can parse `## HH:MM` entries
 * without another network call. `dirty` marks files whose local content
 * has diverged from what's on GitHub — the push worker picks these up.
 *
 * Fixes #29: index on `date` — calendar/today widget queries filter by date
 * range, so a B-tree on this column turns those scans into seeks.
 */
@Entity(
    tableName = "note_files",
    indices = [Index(value = ["date"])],
)
data class NoteFileEntity(
    @PrimaryKey val path: String,
    val date: LocalDate,
    val content: String,
    /** Last known remote blob SHA, null until first pull/push succeeds. */
    val githubSha: String?,
    val localUpdatedAt: Long,
    val remoteUpdatedAt: Long?,
    /** True when local content hasn't been pushed yet. */
    val dirty: Boolean,
)
