package dev.aria.memo.data

import android.content.Context
import androidx.room.withTransaction
import dev.aria.memo.data.local.AppDatabase
import dev.aria.memo.data.local.NoteDao
import dev.aria.memo.data.local.NoteFileEntity
import dev.aria.memo.data.notes.FrontMatterCodec
import dev.aria.memo.data.sync.PathLocker
import dev.aria.memo.data.sync.SyncScheduler
import dev.aria.memo.data.widget.WidgetRefresher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Business logic orchestrating [SettingsStore] + [GitHubApi] + Room.
 *
 * V2 contract (P1):
 *  - [appendToday] writes the new entry to Room first (always succeeds if the
 *    app is configured), then tries a synchronous push. Any failure enqueues
 *    [SyncScheduler.enqueuePush] so the dirty row is retried in the background.
 *  - [recentEntries] reads from Room (offline-safe). [refreshNow] can be
 *    called to pull remote changes.
 *  - [observeNotes] is a live Flow over every cached day-file, newest first,
 *    for the notes-list screen.
 */
open class MemoRepository(
    // Made nullable with null defaults so unit tests can subclass this with a
    // fake implementation that overrides the two methods it cares about
    // (`observeNotes`, `getContentForPath`) without having to construct a real
    // Context/SettingsStore/GitHubApi/NoteDao. Runtime callers always pass
    // real instances via ServiceLocator; the non-null asserts below only fire
    // when a test fake forgets to override a method that actually exercises a
    // backing field, which is a programmer error rather than a runtime issue.
    appContext: Context? = null,
    settings: SettingsStore? = null,
    api: GitHubApi? = null,
    dao: NoteDao? = null,
) {
    private val appContext: Context by lazy { requireNotNull(_appContext) { "appContext not provided" } }
    private val settings: SettingsStore by lazy { requireNotNull(_settings) { "settings not provided" } }
    private val api: GitHubApi by lazy { requireNotNull(_api) { "api not provided" } }
    private val dao: NoteDao by lazy { requireNotNull(_dao) { "dao not provided" } }

    private val _appContext: Context? = appContext
    private val _settings: SettingsStore? = settings
    private val _api: GitHubApi? = api
    private val _dao: NoteDao? = dao

    open fun observeNotes(): Flow<List<NoteFileEntity>> = dao.observeAll()

    /**
     * Thin read-through for UI layers that need a raw content snapshot
     * without subscribing to a Flow. Returns null when the path isn't cached.
     *
     * Fixes #56 (P6.1.1): removes the direct `ServiceLocator.noteDao().get()`
     * that [EditViewModel.prime] and `toggleChecklist` used — the UI layer
     * now only talks to the repository, restoring UI → Repository → DAO layering.
     */
    open suspend fun getContentForPath(path: String): String? = dao.get(path)?.content

    suspend fun appendToday(
        body: String,
        now: LocalDateTime = LocalDateTime.now(),
    ): MemoResult<Unit> {
        val config = settings.current()
        if (!config.isConfigured) {
            return MemoResult.Err(ErrorCode.NOT_CONFIGURED, "PAT/owner/repo missing")
        }

        val date = now.toLocalDate()
        val time = now.toLocalTime()
        val path = config.filePathFor(date)

        // Fixes #6: hold the per-path lock across read-merge-write-push so a
        // parallel PushWorker cannot race us on the same SHA.
        val result = PathLocker.withLock(path) {
            val nowMs = System.currentTimeMillis()
            // Data-1 R12 fix: wrap the read-then-upsert in a Room transaction
            // so a process kill mid-sequence cannot leave a partially-written
            // row. `newContent` and `existingSha` are captured here to be
            // visible outside the transactional block below (the subsequent
            // network push must stay OUT of the txn so we don't hold the DB
            // lock for 10+ seconds while Ktor waits on GitHub).
            val (newContent, existingSha) = runInTx {
                val existing = dao.get(path)
                val merged = buildNewContent(existing?.content, date, time, body)
                dao.upsert(
                    NoteFileEntity(
                        path = path,
                        date = date,
                        content = merged,
                        githubSha = existing?.githubSha,
                        localUpdatedAt = nowMs,
                        remoteUpdatedAt = existing?.remoteUpdatedAt,
                        dirty = true,
                    )
                )
                merged to existing?.githubSha
            }

            // 2. Best-effort synchronous push. Whatever the outcome, enqueue
            //    the background worker so a stuck dirty row always has a retry.
            val pushResult = pushFile(config, path, newContent, existingSha)
            when (pushResult) {
                is MemoResult.Ok -> {
                    dao.markClean(path, pushResult.value, nowMs)
                    MemoResult.Ok(Unit)
                }
                is MemoResult.Err -> {
                    SyncScheduler.enqueuePush(appContext)
                    when (pushResult.code) {
                        ErrorCode.NETWORK, ErrorCode.CONFLICT -> MemoResult.Ok(Unit)
                        else -> pushResult
                    }
                }
            }
        }
        // P8 widget 自推：只要 Room 写入成功（即使最终返回 Ok-with-queued-push），
        // widget 显示的内容就已经变了。NETWORK/CONFLICT 降级成 Ok 的分支也必须刷新，
        // 因为对用户而言"保存成功"了。只有真实 Err（比如 NOT_CONFIGURED）才跳过 —
        // 那种情况 Room 根本没 upsert。
        if (result is MemoResult.Ok) {
            WidgetRefresher.refreshAll(appContext)
        }
        return result
    }

    suspend fun recentEntries(limit: Int = 3): MemoResult<List<MemoEntry>> {
        val config = settings.current()
        if (!config.isConfigured) {
            return MemoResult.Err(ErrorCode.NOT_CONFIGURED, "PAT/owner/repo missing")
        }
        val today = LocalDate.now()
        val path = config.filePathFor(today)
        val cached = dao.get(path) ?: return MemoResult.Ok(emptyList())
        return MemoResult.Ok(parseEntries(cached.content, today).take(limit))
    }

    /** One-shot refresh from GitHub; schedules a WorkManager job. */
    fun refreshNow() = SyncScheduler.enqueuePullNow(appContext)

    /** Flush any dirty rows left over from a crashed or unauthorized push. */
    fun kickPendingPush() = SyncScheduler.enqueuePush(appContext)

    // --- internals ---------------------------------------------------------

    /**
     * Data-1 R12/R13 helper: run [block] inside a Room transaction when the
     * live [AppDatabase] is reachable (production path). In unit-test fakes
     * that skip [ServiceLocator.init] the db is unreachable, so we just run
     * [block] directly — tests already operate without durability semantics.
     *
     * Suspended so callers can mix in their own `suspend` DAO calls. Room's
     * `withTransaction` guarantees:
     *  - atomic commit when [block] returns normally, or
     *  - rollback of every write performed inside [block] if [block] throws
     *    or the process is killed mid-transaction.
     *
     * Keep the network I/O OUT of [block] — holding a Room transaction across
     * a Ktor request would block the SQLite writer thread for seconds.
     */
    private suspend fun <T> runInTx(block: suspend () -> T): T {
        val db = AppDatabase.instance()
        return if (db != null) db.withTransaction(block) else block()
    }

    /**
     * Return type for the transactional prep step inside [toggleTodoLine].
     * Lets the txn block bail out with a typed error without having to abuse
     * `Any` or throw exceptions (Room's `withTransaction` commits the txn
     * when the block returns normally, so throwing for flow-control would
     * roll back the otherwise-successful upsert).
     */
    private sealed interface TogglePrep {
        data class Ok(val newContent: String, val existingSha: String?) : TogglePrep
        data class Fail(val err: MemoResult.Err) : TogglePrep
    }

    private suspend fun pushFile(
        config: AppConfig,
        path: String,
        content: String,
        knownSha: String?,
    ): MemoResult<String> {
        val sha = knownSha ?: when (val getRes = api.getFile(config, path)) {
            is MemoResult.Ok -> getRes.value.sha
            is MemoResult.Err -> when (getRes.code) {
                ErrorCode.NOT_FOUND -> null
                else -> return MemoResult.Err(getRes.code, getRes.message)
            }
        }
        val encoded = java.util.Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8))
        val request = GhPutRequest(
            message = "memo: $path",
            content = encoded,
            branch = config.branch,
            sha = sha,
        )
        return when (val putRes = api.putFile(config, path, request)) {
            is MemoResult.Ok -> MemoResult.Ok(putRes.value.content.sha)
            is MemoResult.Err -> MemoResult.Err(putRes.code, putRes.message)
        }
    }

    private fun buildNewContent(
        existing: String?,
        date: LocalDate,
        time: LocalTime,
        body: String,
    ): String {
        val hhmm = HHMM.format(time)
        // Data-1 R8 fix: normalize line endings to LF **before** splicing.
        // A pulled-down day-file may carry CRLF (Windows peer wrote it), and
        // `parseEntries`'s regex anchors `^## \d\d:\d\d` at a LF — mixed
        // line endings left residual `\r` bytes on the heading line and
        // silently dropped widget rows. Normalising both the cached content
        // and the incoming body keeps the whole file on one convention.
        val normalizedExisting = existing?.replace("\r\n", "\n")?.replace("\r", "\n")
        val normalizedBody = body.replace("\r\n", "\n").replace("\r", "\n")
        val trimmedBody = normalizedBody.trimEnd()
        return if (normalizedExisting.isNullOrBlank()) {
            "# ${date}\n\n## ${hhmm}\n${trimmedBody}\n"
        } else {
            val base = normalizedExisting.trimEnd('\n')
            "${base}\n\n## ${hhmm}\n${trimmedBody}\n"
        }
    }

    /**
     * Toggle the pin flag on the given day-file. Updates Room, rewrites the
     * markdown front matter so it round-trips through GitHub, marks the row
     * dirty, and enqueues a push. No-op if the file isn't in Room yet.
     */
    suspend fun togglePin(path: String, pinned: Boolean): MemoResult<Unit> {
        var found = false
        PathLocker.withLock(path) {
            // Data-1 R12 fix: the read-modify-write of the pin flag and the
            // content column must be atomic. Without `withTransaction`, a
            // process kill between `dao.get` and `dao.togglePin` leaves the
            // new content-byte pattern half-written (the flag bit set but
            // the body still old), which then pushes a stale body to GitHub
            // on next sync.
            runInTx {
                val existing = dao.get(path) ?: return@runInTx
                found = true
                val newContent = applyPinFrontMatter(existing.content, pinned)
                dao.togglePin(
                    path = path,
                    pinned = pinned,
                    content = newContent,
                    updatedAt = System.currentTimeMillis(),
                )
            }
            if (found) SyncScheduler.enqueuePush(appContext)
        }
        WidgetRefresher.refreshAll(appContext)
        // Bug-1 M10 fix (#131): 不存在路径返 NOT_FOUND 不再静默 Ok,
        // caller 可以 dispatch UX 反馈。
        return if (found) MemoResult.Ok(Unit)
        else MemoResult.Err(ErrorCode.NOT_FOUND, "笔记不存在: $path")
    }

    companion object {
        private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        /**
         * Strip a leading `---\n...\n---\n` YAML block (if any) from [text] and
         * return what remains. Delegates to [FrontMatterCodec.strip] — the codec
         * enforces the "only eat pin blocks with strict bool value" semantic so
         * user-authored YAML is preserved.
         */
        internal fun stripFrontMatter(text: String): String =
            FrontMatterCodec.strip(text)

        /**
         * Return true when [text] currently carries a front matter block with
         * `pinned: true`. Tolerates quoted values and extra whitespace.
         */
        internal fun readPinnedFromFrontMatter(text: String): Boolean {
            val normalized = text.replace("\r\n", "\n")
            if (!normalized.startsWith("---\n")) return false
            val close = normalized.indexOf("\n---", 4)
            if (close < 0) return false
            val block = normalized.substring(4, close)
            for (raw in block.split('\n')) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim().trim('"', '\'')
                if (key == "pinned") return value.equals("true", ignoreCase = true)
            }
            return false
        }

        /**
         * Rewrite [content] so it has exactly the right front matter for
         * [pinned]. Delegates to [FrontMatterCodec.applyPin].
         */
        internal fun applyPinFrontMatter(content: String, pinned: Boolean): String =
            FrontMatterCodec.applyPin(content, pinned)

        fun parseEntries(text: String, date: LocalDate): List<MemoEntry> {
            val body = stripFrontMatter(text)
            val regex = Regex("(?m)^## (\\d{2}):(\\d{2})\\s*\\n([\\s\\S]*?)(?=\\n## |\\z)")
            return regex.findAll(body).mapNotNull { m ->
                val hh = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val mm = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                if (hh !in 0..23 || mm !in 0..59) return@mapNotNull null
                val b = m.groupValues[3].trim('\n').trimEnd()
                MemoEntry(date = date, time = LocalTime.of(hh, mm), body = b)
            }.sortedByDescending { it.time }.toList()
        }

        /**
         * Pure helper for [recentEntriesAcrossDays]: merge entries parsed from
         * every file in [files] into a single newest-first list and take the
         * top [limit]. Exposed on the companion so JVM tests can exercise it
         * without constructing a live [MemoRepository].
         *
         * Files are walked date-descending so the parse can short-circuit once
         * we have enough entries to fill [limit]. Ties on (date, time) retain
         * the file's own parse order (newest-time-within-file wins) — stable
         * ordering the UI can rely on for row keys.
         */
        internal fun mergeRecentAcrossDays(
            files: List<NoteFileEntity>,
            limit: Int,
        ): List<DatedMemoEntry> {
            if (limit <= 0 || files.isEmpty()) return emptyList()
            // Sort by date DESC so we can stop scanning once we have `limit`
            // entries — files older than the cutoff can only yield entries
            // older than the ones already collected.
            val sortedFiles = files.sortedByDescending { it.date }
            val collected = ArrayList<DatedMemoEntry>(limit * 2)
            for (file in sortedFiles) {
                val perFile = parseEntries(file.content, file.date)
                // parseEntries already returns newest-time-first inside the day.
                for (entry in perFile) {
                    collected += DatedMemoEntry(
                        date = file.date,
                        time = entry.time,
                        body = entry.body,
                    )
                }
                // Short-circuit: once we have at least `limit` entries, further
                // files can only contribute strictly-older-date entries (the
                // input is sorted date-desc), so they can never bump the top-N.
                if (collected.size >= limit) break
            }
            return collected.take(limit)
        }

        /**
         * Regex used by [toggleTodoLine] to recognise a single markdown checkbox
         * line. Kept in the companion so tests can pin the exact pattern without
         * importing the UI-layer parser.
         */
        private val TOGGLE_LINE_REGEX = Regex("""^([ \t]*)- \[([ xX])] (.*)$""")
    }

    // --- checklist toggle --------------------------------------------------
    //
    // p3-polish: the edit screen renders each `- [ ] foo` line as a live
    // Checkbox. Tapping it flips the box in-place and persists the whole
    // file. Unlike [appendToday] this does NOT append — it surgically rewrites
    // one line, then pushes the full new content via the same SHA-tracked
    // PUT path. Same locking, same retry-on-failure via PushWorker.
    //
    // Contract:
    //  - If [path] has no cached Room row, returns NOT_FOUND with no side effect.
    //  - If [lineIndex] is out of bounds or doesn't match `- [([ xX])] ...`,
    //    returns UNKNOWN (the UI clicked a checkbox that no longer exists —
    //    shouldn't happen in practice, but we don't silently corrupt the file).
    //  - Otherwise: flips the bracket, upserts as dirty, tries sync PUT, and
    //    enqueues PushWorker on failure (content is safe in Room either way).
    //
    //  Concurrency guard (p3-polish #3): callers must pass the raw line text
    //  they rendered against. If Room content has drifted (e.g. PullWorker
    //  landed a new revision mid-tap) the indexed line no longer matches and
    //  we refuse the toggle with STALE_VIEW, letting the UI re-render cleanly
    //  instead of corrupting the freshly-pulled body.
    suspend fun toggleTodoLine(
        path: String,
        lineIndex: Int,
        expectedRawLine: String,
        newChecked: Boolean,
    ): MemoResult<Unit> {
        val config = settings.current()
        if (!config.isConfigured) {
            return MemoResult.Err(ErrorCode.NOT_CONFIGURED, "PAT/owner/repo missing")
        }
        val result = PathLocker.withLock(path) {
            val nowMs = System.currentTimeMillis()
            // Data-1 R12 fix: wrap the guard + read + line rewrite + upsert
            // in a single Room transaction. Without this, the upsert of a
            // surgically-mutated body can be interrupted right before it
            // commits, leaving the row dirty with pre-edit content and the
            // next push shipping the old state to GitHub. Network push
            // stays outside the txn (see `runInTx` contract).
            val prep: TogglePrep = runInTx {
                val existing = dao.get(path)
                    ?: return@runInTx TogglePrep.Fail(
                        MemoResult.Err(ErrorCode.NOT_FOUND, "note not in cache: $path")
                    )
                val lines = existing.content.split("\n").toMutableList()
                if (lineIndex !in lines.indices) {
                    return@runInTx TogglePrep.Fail(
                        MemoResult.Err(
                            ErrorCode.CONFLICT,
                            "line index out of range after refresh: $lineIndex",
                        )
                    )
                }
                val original = lines[lineIndex]
                if (original != expectedRawLine) {
                    return@runInTx TogglePrep.Fail(
                        MemoResult.Err(ErrorCode.CONFLICT, "line changed since render")
                    )
                }
                val match = TOGGLE_LINE_REGEX.matchEntire(original)
                    ?: return@runInTx TogglePrep.Fail(
                        MemoResult.Err(
                            ErrorCode.UNKNOWN,
                            "line $lineIndex is not a checklist line",
                        )
                    )
                val indent = match.groupValues[1]
                val text = match.groupValues[3]
                val mark = if (newChecked) "x" else " "
                lines[lineIndex] = "$indent- [$mark] $text"
                val merged = lines.joinToString("\n")
                dao.upsert(
                    existing.copy(
                        content = merged,
                        localUpdatedAt = nowMs,
                        dirty = true,
                    )
                )
                TogglePrep.Ok(merged, existing.githubSha)
            }
            if (prep is TogglePrep.Fail) return@withLock prep.err
            val (newContent, existingSha) = prep as TogglePrep.Ok

            val pushResult = pushFile(config, path, newContent, existingSha)
            when (pushResult) {
                is MemoResult.Ok -> {
                    dao.markClean(path, pushResult.value, nowMs)
                    MemoResult.Ok(Unit)
                }
                is MemoResult.Err -> {
                    SyncScheduler.enqueuePush(appContext)
                    when (pushResult.code) {
                        ErrorCode.NETWORK, ErrorCode.CONFLICT -> MemoResult.Ok(Unit)
                        else -> pushResult
                    }
                }
            }
        }
        // P8 widget 自推：只要 Room 被改过（Ok 或降级成 Ok 的分支），widget 要刷新。
        // 失败分支（NOT_FOUND / CONFLICT-stale / UNKNOWN）Room 没 upsert，跳过。
        if (result is MemoResult.Ok) {
            WidgetRefresher.refreshAll(appContext)
        }
        return result
    }

    // --- cross-day recent feed ---------------------------------------------
    //
    // Widget bug fix: [recentEntries] only looks at today's `YYYY-MM-DD.md`,
    // so if the user didn't write today the widget showed nothing, even if
    // yesterday's file had fresh content. [recentEntriesAcrossDays] walks
    // every cached day-file in Room, parses all `## HH:MM` entries, and
    // returns the newest [limit] entries by (date, time). The widget renders
    // each row with its date so cross-day rows are unambiguous.
    //
    // Why read via [NoteDao.observeAll] rather than adding a new @Query: the
    // DAO contract is frozen for this change — `observeAll().first()` gives
    // us a one-shot snapshot that is cheap for the handful of day-files a
    // user realistically has cached. The expensive bit (markdown parse) is
    // done in memory after the DAO call returns; the helper short-circuits
    // once it has collected [limit] entries, so total work is O(k) in the
    // number of day-files actually touched to fill the widget.

    /**
     * Return the most recent [limit] memo entries across all cached day-files,
     * newest first. Falls back gracefully when Room is empty (returns `[]`).
     *
     * Ordering:
     *  - primary: [DatedMemoEntry.date] descending
     *  - secondary: [DatedMemoEntry.time] descending
     *  - tertiary (stable): original parse order within the same (date, time)
     *    bucket — first entry in the file wins, so ties are deterministic.
     *
     * Respects [AppConfig.isConfigured]: returns [ErrorCode.NOT_CONFIGURED]
     * when PAT/owner/repo are missing so the widget can prompt the user.
     */
    suspend fun recentEntriesAcrossDays(limit: Int = 3): MemoResult<List<DatedMemoEntry>> {
        val config = settings.current()
        if (!config.isConfigured) {
            return MemoResult.Err(ErrorCode.NOT_CONFIGURED, "PAT/owner/repo missing")
        }
        if (limit <= 0) return MemoResult.Ok(emptyList())
        // Fixes #51 (P6.1.1): LIMIT pushdown. Each day-file carries multiple
        // `## HH:MM` entries, so SQL can't be limited to `:limit` entries —
        // but asking Room for `limit * 2 + 1` FILES almost always yields
        // enough entries to fill the widget on a single DB read instead
        // of dragging the whole table into memory. Falls back to all files
        // only if the pool doesn't produce enough entries (super-sparse case).
        val fileLimit = limit * 2 + 1
        val recent = dao.observeRecent(fileLimit).first()
        val merged = mergeRecentAcrossDays(recent, limit)
        if (merged.size >= limit || recent.size < fileLimit) {
            return MemoResult.Ok(merged)
        }
        // Rare: every file in the pool contributed very few entries; fall
        // through to the full table so the widget still has content.
        val all = dao.observeAll().first()
        return MemoResult.Ok(mergeRecentAcrossDays(all, limit))
    }
}
