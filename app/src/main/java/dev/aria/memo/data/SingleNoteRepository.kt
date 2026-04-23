package dev.aria.memo.data

import android.content.Context
import dev.aria.memo.data.local.SingleNoteDao
import dev.aria.memo.data.local.SingleNoteEntity
import dev.aria.memo.data.notes.FrontMatterCodec
import dev.aria.memo.data.notes.NoteSlugger
import dev.aria.memo.data.sync.PathLocker
import dev.aria.memo.data.sync.SyncScheduler
import dev.aria.memo.data.sync.SyncStatus
import dev.aria.memo.data.sync.SyncStatusBus
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
open class SingleNoteRepository(
    // Nullable-with-null-defaults so unit tests can subclass with overrides
    // for the read-only methods the AI chat VM uses (observeAll / get) without
    // having to construct a real Context / SettingsStore / SingleNoteDao.
    // Runtime callers always pass real instances via ServiceLocator.
    appContext: Context? = null,
    settings: SettingsStore? = null,
    dao: SingleNoteDao? = null,
) {
    private val appContext: Context by lazy { requireNotNull(_appContext) { "appContext not provided" } }
    private val settings: SettingsStore by lazy { requireNotNull(_settings) { "settings not provided" } }
    private val dao: SingleNoteDao by lazy { requireNotNull(_dao) { "dao not provided" } }

    private val _appContext: Context? = appContext
    private val _settings: SettingsStore? = settings
    private val _dao: SingleNoteDao? = dao

    open fun observeAll(): Flow<List<SingleNoteEntity>> = dao.observeAll()

    open fun observeRecent(limit: Int = 20): Flow<List<SingleNoteEntity>> =
        dao.observeRecent(limit)

    open suspend fun get(uid: String): SingleNoteEntity? = dao.get(uid)

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
        // Build entity first so we know the filePath — needed to scope the lock.
        val entity = buildEntityForCreate(
            body = body,
            now = now,
            uid = UUID.randomUUID().toString(),
            nowMs = System.currentTimeMillis(),
        )
        // Fixes #40 (P6.1): serialise the upsert against any in-flight
        // PushWorker on the same filePath.
        return PathLocker.withLock(entity.filePath) {
            // Fixes #57 (P6.1.1): write to Room FIRST, even when PAT is not
            // configured. Otherwise a first-install user tapping "+" before
            // configuring GitHub would silently lose the body. The row stays
            // `dirty=true` and PushWorker picks it up once the user configures
            // a PAT (SyncScheduler.enqueuePush is a no-op on unconfigured
            // state; the periodic worker reconciles on next run).
            dao.upsert(entity)
            if (config.isConfigured) {
                SyncScheduler.enqueuePush(appContext)
                MemoResult.Ok(entity)
            } else {
                // Let the UI know the body is safe locally but nothing will
                // upload until the user configures GitHub. Returns Ok because
                // from the user's point of view the save succeeded — the push
                // is deferred, not failed.
                SyncStatusBus.emit(
                    SyncStatus.Error(
                        ErrorCode.NOT_CONFIGURED,
                        "笔记已存本地 · 待配置 GitHub 后自动同步",
                    )
                )
                MemoResult.Ok(entity)
            }
        }
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
        // Fixes #40 (P6.1): same PathLocker guard as create/delete.
        return PathLocker.withLock(existing.filePath) {
            val updated = existing.copy(
                body = body,
                title = extractTitle(body),
                localUpdatedAt = System.currentTimeMillis(),
                dirty = true,
            )
            dao.upsert(updated)
            SyncScheduler.enqueuePush(appContext)
            MemoResult.Ok(updated)
        }
    }

    /** Soft-delete so the push worker can drive a remote DELETE. */
    suspend fun delete(uid: String): MemoResult<Unit> {
        val existing = dao.get(uid)
            ?: return MemoResult.Err(ErrorCode.NOT_FOUND, "note not found: $uid")
        // Fixes #40 (P6.1).
        return PathLocker.withLock(existing.filePath) {
            dao.tombstone(existing.uid, System.currentTimeMillis())
            SyncScheduler.enqueuePush(appContext)
            MemoResult.Ok(Unit)
        }
    }

    /**
     * Toggle the pin flag. Rewrites the body to carry `pinned: true` front
     * matter so the flag round-trips through GitHub (mirroring the day-file
     * pin implementation). `pinned: false` strips any existing block.
     *
     * Fixes #47 (P6.1): calls [FrontMatterCodec.applyPin] directly rather than
     * the legacy [MemoRepository.applyPinFrontMatter] shim.
     */
    suspend fun togglePin(uid: String, pinned: Boolean): MemoResult<SingleNoteEntity> {
        val existing = dao.get(uid)
            ?: return MemoResult.Err(ErrorCode.NOT_FOUND, "note not found: $uid")
        // Fixes #40 (P6.1).
        return PathLocker.withLock(existing.filePath) {
            val newBody = FrontMatterCodec.applyPin(existing.body, pinned)
            dao.togglePin(
                uid = existing.uid,
                pinned = pinned,
                body = newBody,
                updatedAt = System.currentTimeMillis(),
            )
            SyncScheduler.enqueuePush(appContext)
            MemoResult.Ok(existing.copy(isPinned = pinned, body = newBody))
        }
    }

    /**
     * Fallback for [NoteListViewModel.togglePin] when the UI's cached items
     * haven't hydrated yet (rare — requires a pin tap within ~100ms of cold
     * start). Looks up uid by path via the DAO, then delegates. Returns
     * NOT_FOUND when neither Room nor the UI cache has the row.
     *
     * Fixes #46 (P6.1).
     */
    suspend fun togglePinByPath(path: String, pinned: Boolean): MemoResult<SingleNoteEntity> {
        val existing = dao.getByPath(path)
            ?: return MemoResult.Err(ErrorCode.NOT_FOUND, "note not found at path: $path")
        return togglePin(existing.uid, pinned)
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
