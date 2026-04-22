package dev.aria.memo.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.LocalTime

/**
 * One row per Obsidian-style single note — each mirrors a GitHub file at
 * `notes/<YYYY-MM-DD-HHMM-slug>.md`. P5 introduces this table alongside the
 * legacy `note_files` (day-file) table to support "one note per file" without
 * breaking existing reads.
 *
 * `uid` is a freshly-generated UUID string so Room has a stable primary key
 * even if the user (or a remote rename) changes [filePath]. [filePath] carries
 * a UNIQUE index so the pull worker can dedupe by remote filename the same way
 * [EventEntity] does.
 *
 * `title` and `body` let the list screen skip re-parsing markdown each frame;
 * the slug derivation happens at write time (see [dev.aria.memo.data.notes.NoteSlugger]).
 *
 * `date` / `time` are the authoring timestamp — they're also embedded in
 * [filePath] so chronological sort without opening the file is cheap.
 *
 * `isPinned` is local preference only (pin flag is NOT serialised into the
 * remote markdown yet — the single-note format is intentionally minimal).
 */
@Entity(
    tableName = "single_notes",
    indices = [Index(value = ["filePath"], unique = true)],
)
data class SingleNoteEntity(
    @PrimaryKey val uid: String,
    val filePath: String,
    val title: String,
    val body: String,
    val date: LocalDate,
    val time: LocalTime,
    val isPinned: Boolean = false,
    /** Last known remote blob SHA, null until first pull/push succeeds. */
    val githubSha: String?,
    val localUpdatedAt: Long,
    val remoteUpdatedAt: Long?,
    /** True when local content hasn't been pushed yet. */
    val dirty: Boolean,
    /** Soft-delete flag so a deletion can be pushed to GitHub. */
    val tombstoned: Boolean = false,
)
