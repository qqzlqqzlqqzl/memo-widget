package dev.aria.memo.data

import android.content.Context
import dev.aria.memo.data.local.SingleNoteDao
import dev.aria.memo.data.local.SingleNoteEntity
import dev.aria.memo.data.notes.NoteSlugger
import dev.aria.memo.data.sync.SyncScheduler
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Obsidian-style one-file-per-note repository. Coexists with [MemoRepository]:
 *  - New writes go here (`notes/<YYYY-MM-DD-HHMM-slug>.md`, one per entry).
 *  - Reads can merge this with day-file parses in the UI layer; this class
 *    doesn't touch the legacy table.
 *
 * Contract is identical to [EventRepository] — CRUD touches Room first
 * (always succeeds when configured), marks the row dirty, and lets the
 * [dev.aria.memo.data.sync.PushWorker] handle the actual PUT/DELETE against
 * GitHub. Nothing here throws on configuration problems; callers receive
 * [MemoResult.Err] instead.
 */
class SingleNoteRepository(
    private val appContext: Context,
    private val settings: SettingsStore,
    private val dao: SingleNoteDao,
) {

    fun observeAll(): Flow<List<SingleNoteEntity>> = dao.observeAll()

    fun observeRecent(limit: Int = 20): Flow<List<SingleNoteEntity>> =
        dao.observeRecent(limit)

    suspend fun get(uid: String): SingleNoteEntity? = dao.get(uid)

    /**
     * Create a fresh note. The new row is upserted immediately so the UI can
     * reflect it without waiting for a network round-trip; the push worker
     * picks up the dirty flag on the next cycle.
     *
     * [body] is the raw markdown the user typed. The title is derived from
     * the body's first non-blank line, the slug from [NoteSlugger.slugOf].
     */
    suspend fun create(
        body: String,
        now: LocalDateTime = LocalDateTime.now(),
    ): MemoResult<SingleNoteEntity> {
        val config = settings.current()
        if (!config.isConfigured) {
            return MemoResult.Err(ErrorCode.NOT_CONFIGURED, "not configured")
        }
        val entity = buildEntityForCreate(
            body = body,
            now = now,
            uid = UUID.randomUUID().toString(),
            nowMs = System.currentTimeMillis(),
        )
        dao.upsert(entity)
        SyncScheduler.enqueuePush(appContext)
        return MemoResult.Ok(entity)
    }

    /**
     * Update an existing note's body. Filename is **not** changed — once the
     * remote path is chosen, renaming would produce a DELETE + PUT which we
     * want to avoid for the default edit flow. A future explicit "rename"
     * action can do that.
     */
    suspend fun update(uid: String, body: String): MemoResult<SingleNoteEntity> {
        val existing = dao.get(uid)
            ?: return MemoResult.Err(ErrorCode.NOT_FOUND, "note not found: $uid")
        val updated = existing.copy(
            body = body,
            title = extractTitle(body),
            localUpdatedAt = System.currentTimeMillis(),
            dirty = true,
        )
        dao.upsert(updated)
        SyncScheduler.enqueuePush(appContext)
        return MemoResult.Ok(updated)
    }

    /** Soft-delete so the push worker can drive a remote DELETE. */
    suspend fun delete(uid: String): MemoResult<Unit> {
        val existing = dao.get(uid)
            ?: return MemoResult.Err(ErrorCode.NOT_FOUND, "note not found: $uid")
        dao.tombstone(existing.uid, System.currentTimeMillis())
        SyncScheduler.enqueuePush(appContext)
        return MemoResult.Ok(Unit)
    }

    /**
     * Toggle the pin flag. Rewrites the body to carry `pinned: true` front
     * matter so the flag round-trips through GitHub (mirroring the day-file
     * pin implementation). `pinned: false` strips any existing block.
     */
    suspend fun togglePin(uid: String, pinned: Boolean): MemoResult<SingleNoteEntity> {
        val existing = dao.get(uid)
            ?: return MemoResult.Err(ErrorCode.NOT_FOUND, "note not found: $uid")
        val newBody = MemoRepository.applyPinFrontMatter(existing.body, pinned)
        dao.togglePin(
            uid = existing.uid,
            pinned = pinned,
            body = newBody,
            updatedAt = System.currentTimeMillis(),
        )
        SyncScheduler.enqueuePush(appContext)
        return MemoResult.Ok(existing.copy(isPinned = pinned, body = newBody))
    }

    // --- helpers -----------------------------------------------------------

    companion object {
        private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmm")

        internal fun formatDate(date: LocalDate): String = DATE_FMT.format(date)
        internal fun formatTime(time: LocalTime): String = TIME_FMT.format(time)

        /**
         * Derive a human-readable title from the body. Matches the slug logic
         * but keeps the raw characters — the slug is filename-safe, the title
         * is display-safe. Leading `#` heading markers are stripped.
         */
        internal fun extractTitle(body: String): String {
            val first = body.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                ?: return ""
            // Strip leading markdown heading hashes and list/quote markers.
            var t = first
            t = t.replace(Regex("^#+\\s*"), "")
            t = t.replace(Regex("^[>\\-*+]\\s*"), "")
            t = t.replace(Regex("^\\d+[.)\\]]\\s*"), "")
            return t.trim()
        }

        /**
         * Pure helper: produce the [SingleNoteEntity] a fresh create would
         * upsert. Exposed so pure-JVM tests can exercise filename / title /
         * slug derivation without constructing a live repository (which would
         * require a Context for WorkManager).
         */
        internal fun buildEntityForCreate(
            body: String,
            now: LocalDateTime,
            uid: String,
            nowMs: Long,
        ): SingleNoteEntity {
            val date = now.toLocalDate()
            // Zero out seconds/nanos — the filename component is HHMM, and we
            // don't want to leak unused precision to the DB either.
            val time = now.toLocalTime().withNano(0).withSecond(0)
            val slug = NoteSlugger.slugOf(body)
            val fileName = "${formatDate(date)}-${formatTime(time)}-$slug.md"
            return SingleNoteEntity(
                uid = uid,
                filePath = "notes/$fileName",
                title = extractTitle(body),
                body = body,
                date = date,
                time = time,
                isPinned = false,
                githubSha = null,
                localUpdatedAt = nowMs,
                remoteUpdatedAt = null,
                dirty = true,
            )
        }
    }
}
