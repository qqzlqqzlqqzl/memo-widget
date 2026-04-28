package dev.aria.memo.data.sync

import android.content.Context
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.aria.memo.data.ErrorCode
import dev.aria.memo.data.MemoResult
import dev.aria.memo.data.ServiceLocator
import dev.aria.memo.data.SingleNoteRepository
import dev.aria.memo.data.ics.IcsCodec
import dev.aria.memo.data.local.AppDatabase
import dev.aria.memo.data.local.NoteFileEntity
import dev.aria.memo.data.local.SingleNoteEntity
import dev.aria.memo.data.widget.WidgetRefresher
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Periodic pull of recent notes, single notes, and all events from GitHub.
 *
 * Notes (legacy day-file): 14-day sliding window by `YYYY-MM-DD.md` filename.
 * Single notes (P5 obsidian-style): directory listing of `notes/` — diff by
 *   SHA, fetch changed ones, upsert Room keyed on filePath. Local pins are
 *   preserved across pull.
 * Events: directory listing of `events/` — diff by SHA, fetch changed ones,
 *   decode iCalendar, upsert Room. Events not in the remote listing but
 *   present in Room (non-dirty) are hard-deleted to reflect the GitHub state.
 *
 * Dirty rows in any table are skipped so local edits are never clobbered.
 */
class PullWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        ServiceLocator.init(applicationContext)
        val settings = ServiceLocator.settingsStore
        val api = ServiceLocator.api()
        val noteDao = ServiceLocator.noteDao()
        val eventDao = ServiceLocator.eventDao()
        val singleNoteDao = ServiceLocator.singleNoteDao()
        val config = settings.current()
        if (!config.isConfigured) return Result.success()

        // Review-Z #1 fix: repo 健康探针。用户填错 owner/repo 时,GitHub 整个 repo
        // 返 404,后续 listDir(notes/) / listDir(events/) / getFile(...) 全部 404。
        // 此前 NOT_FOUND -> Unit 让所有 404 静默,用户列表永远空白却没任何提示,
        // 除了卸载没有线索意识到是配置错误。
        //
        // 修法:在 worker 入口调一次根 listDir,根 404 → 强信号 repo 不存在 →
        // emit Error(NOT_FOUND) + return success (避免无意义重试)。子目录 404 仍 OK
        // (用户 repo 只是还没创建 notes/events/ 子目录)。
        when (val probe = api.listDir(config, "")) {
            is MemoResult.Err -> when (probe.code) {
                ErrorCode.NOT_FOUND -> {
                    SyncStatusBus.emit(
                        SyncStatus.Error(
                            ErrorCode.NOT_FOUND,
                            "GitHub 仓库不存在 — 请检查设置中的 owner/repo 配置",
                        ),
                    )
                    return Result.success()
                }
                ErrorCode.UNAUTHORIZED -> {
                    SyncStatusBus.emit(SyncStatus.Error(ErrorCode.UNAUTHORIZED, "GitHub 拒绝访问"))
                    return Result.success()
                }
                else -> Unit // network / other transient errors fall through to normal pull
            }
            is MemoResult.Ok -> Unit // repo exists, proceed
        }

        var anyNetwork = false

        // P6.1 第 6 项：全局 pull 预算，四段共享。旧逻辑三段各自 50/50/50 封顶，
        // 最坏情况合计 164 次 API call，登录 PAT 用户 5000/h 虽够但 secondary
        // rate-limit 仍可能被触发。150 上限保证单轮不爆表。
        val budget = PullBudget()

        // --- notes ---------------------------------------------------------
        // Fixes #5: when the local cache is empty, list the repo root and pull
        // every `YYYY-MM-DD.md` up front so freshly-installed devices / cleared
        // caches see the full history instead of only the 14-day window.
        val today = LocalDate.now()
        val cachedNoteCount = noteDao.count()
        if (cachedNoteCount == 0) {
            anyNetwork = anyNetwork or bootstrapAllNotes(api, noteDao, config, budget)
        }

        // Always also refresh the 14-day sliding window so recent edits from
        // other devices are picked up even when the cache isn't empty.
        for (offset in 0 until WINDOW_DAYS) {
            val date = today.minusDays(offset.toLong())
            val path = config.filePathFor(date)
            val local = noteDao.get(path)
            if (local?.dirty == true) continue
            if (!budget.consume()) { anyNetwork = true; break }
            when (val res = api.getFile(config, path)) {
                is MemoResult.Ok -> {
                    if (local != null && local.githubSha == res.value.sha) continue
                    val text = runCatching { res.value.decodedContent }.getOrNull() ?: continue
                    val nowMs = System.currentTimeMillis()
                    noteDao.upsert(
                        NoteFileEntity(
                            path = path,
                            date = date,
                            content = text,
                            githubSha = res.value.sha,
                            localUpdatedAt = local?.localUpdatedAt ?: nowMs,
                            remoteUpdatedAt = nowMs,
                            dirty = false,
                        )
                    )
                }
                is MemoResult.Err -> when (res.code) {
                    ErrorCode.NOT_FOUND -> Unit
                    ErrorCode.NETWORK -> anyNetwork = true
                    ErrorCode.UNAUTHORIZED -> {
                        SyncStatusBus.emit(SyncStatus.Error(ErrorCode.UNAUTHORIZED, "GitHub 拒绝访问"))
                        return Result.success()
                    }
                    else -> Unit
                }
            }
        }

        // --- events --------------------------------------------------------
        // listDir itself is an HTTP call — 1 budget unit.
        if (!budget.consume()) {
            anyNetwork = true
        } else when (val res = api.listDir(config, "events")) {
            is MemoResult.Ok -> {
                val remote = res.value.filter { it.type == "file" && it.name.endsWith(".ics") }
                val remotePaths = remote.map { it.path }.toSet()
                // Pull new / changed. Fixes #2: locate by filePath (unique index),
                // NOT by deriving uid from filename — a remote rename used to produce
                // a duplicate row.
                for (item in remote) {
                    val localByPath = eventDao.getByPath(item.path)
                    if (localByPath?.dirty == true) continue
                    if (localByPath != null && localByPath.githubSha == item.sha) continue
                    // Fixes #53 (P6.1): exhausted-check + consume merged to a
                    // single atomic-in-intent call so the check-then-act pair
                    // can't drift. Stop fetching; the deletion loop below still
                    // reconciles, and leftovers pick up next cycle.
                    if (!budget.consume()) { anyNetwork = true; break }
                    when (val fileRes = api.getFile(config, item.path)) {
                        is MemoResult.Ok -> {
                            val text = runCatching { fileRes.value.decodedContent }.getOrNull() ?: continue
                            val nowMs = System.currentTimeMillis()
                            val decoded = IcsCodec.decode(text, item.path, item.sha, nowMs) ?: continue
                            // S1 fix: reminder is a local-only preference (not in .ics).
                            // Preserve whatever the user set locally, otherwise the first
                            // remote edit to the event would silently wipe every device's
                            // reminder back to null.
                            val merged = decoded.copy(
                                reminderMinutesBefore = localByPath?.reminderMinutesBefore
                                    ?: decoded.reminderMinutesBefore,
                            )
                            // Data-1 R13 fix: the "delete stale UID → upsert new row"
                            // sequence used to be two independent DAO calls. A process
                            // kill between them left the stale row deleted and the new
                            // row never written → the event disappeared from Room until
                            // the next pull cycle. Wrapping both in a single Room
                            // transaction makes the swap atomic (either both persist
                            // or neither does, and the next cycle retries cleanly).
                            runInTxOrFallback {
                                // Remote UID may differ from what we had indexed — if so,
                                // drop the stale row so the new row (keyed on remote UID)
                                // becomes the canonical one.
                                if (localByPath != null && localByPath.uid != decoded.uid) {
                                    eventDao.hardDelete(localByPath.uid)
                                }
                                eventDao.upsert(merged)
                            }
                            // Keep AlarmManager in sync with the merged row. This stays
                            // OUTSIDE the transaction — AlarmManager is a process-local
                            // IPC call, not a SQLite write, and scheduling it inside
                            // the txn would block the SQLite writer until the system
                            // server replies.
                            dev.aria.memo.notify.AlarmScheduler.scheduleForEvent(
                                applicationContext, merged
                            )
                        }
                        is MemoResult.Err -> when (fileRes.code) {
                            ErrorCode.NETWORK -> anyNetwork = true
                            else -> Unit
                        }
                    }
                }
                // Drop local events that remote no longer has (unless dirty, in which case
                // user still has pending work). M2 fix: cancel the paired alarm too.
                for (local in eventDao.snapshotAll()) {
                    if (local.dirty || local.tombstoned) continue
                    if (local.filePath !in remotePaths) {
                        dev.aria.memo.notify.AlarmScheduler.cancelForUid(applicationContext, local.uid)
                        eventDao.hardDelete(local.uid)
                    }
                }
            }
            is MemoResult.Err -> when (res.code) {
                ErrorCode.NOT_FOUND -> Unit // no events/ dir yet
                ErrorCode.NETWORK -> anyNetwork = true
                ErrorCode.UNAUTHORIZED -> {
                    // Bug-1 M15 fix (#138): UNAUTHORIZED 在所有段都 emit banner,
                    // 之前 events/notes 段 silent return 让用户看不到 401。
                    SyncStatusBus.emit(SyncStatus.Error(ErrorCode.UNAUTHORIZED, "GitHub 拒绝访问"))
                    return Result.success()
                }
                else -> Unit
            }
        }

        // --- single notes (P5 obsidian-style) -----------------------------
        // List `notes/`, diff by SHA, fetch changed ones, upsert Room.
        // `filePath` is UNIQUE, so we locate existing rows by it just like
        // the events arm. Local-only fields (isPinned) are preserved across
        // pulls — mirrors the S1 fix we applied to event reminders.
        if (!budget.consume()) {
            anyNetwork = true
        } else when (val res = api.listDir(config, "notes")) {
            is MemoResult.Ok -> {
                val remote = res.value.filter { it.type == "file" && it.name.endsWith(".md") }
                val remotePaths = remote.map { it.path }.toSet()
                for (item in remote) {
                    val localByPath = singleNoteDao.getByPath(item.path)
                    if (localByPath?.dirty == true) continue
                    if (localByPath != null && localByPath.githubSha == item.sha) continue
                    // Fixes #53 (P6.1): merged exhausted+consume check.
                    if (!budget.consume()) { anyNetwork = true; break }
                    when (val fileRes = api.getFile(config, item.path)) {
                        is MemoResult.Ok -> {
                            val text = runCatching { fileRes.value.decodedContent }
                                .getOrNull() ?: continue
                            val nowMs = System.currentTimeMillis()
                            val parsedDate = parseDateFromSingleNoteFilename(item.name)
                                ?: localByPath?.date
                                ?: today
                            val parsedTime = parseTimeFromSingleNoteFilename(item.name)
                                ?: localByPath?.time
                                ?: LocalTime.MIDNIGHT
                            val title = SingleNoteRepository.extractTitle(text)
                            val entity = SingleNoteEntity(
                                uid = localByPath?.uid ?: UUID.randomUUID().toString(),
                                filePath = item.path,
                                title = title,
                                body = text,
                                date = parsedDate,
                                time = parsedTime,
                                // Preserve the user's local pin. Front matter in
                                // the body still exists — the UI can surface it
                                // if we choose to mirror the day-file approach.
                                isPinned = localByPath?.isPinned ?: false,
                                githubSha = item.sha,
                                localUpdatedAt = localByPath?.localUpdatedAt ?: nowMs,
                                remoteUpdatedAt = nowMs,
                                dirty = false,
                                tombstoned = false,
                            )
                            singleNoteDao.upsert(entity)
                        }
                        is MemoResult.Err -> when (fileRes.code) {
                            ErrorCode.NETWORK -> anyNetwork = true
                            else -> Unit
                        }
                    }
                }
                // Drop local rows that remote no longer has (unless dirty or
                // tombstoned — those have pending local work).
                for (local in singleNoteDao.snapshotAll()) {
                    if (local.dirty || local.tombstoned) continue
                    if (local.filePath !in remotePaths) {
                        singleNoteDao.hardDelete(local.uid)
                    }
                }
            }
            is MemoResult.Err -> when (res.code) {
                ErrorCode.NOT_FOUND -> Unit // no notes/ dir yet
                ErrorCode.NETWORK -> anyNetwork = true
                ErrorCode.UNAUTHORIZED -> {
                    // Bug-1 M15 fix (#138): 同样 banner emit。
                    SyncStatusBus.emit(SyncStatus.Error(ErrorCode.UNAUTHORIZED, "GitHub 拒绝访问"))
                    return Result.success()
                }
                else -> Unit
            }
        }

        // P8 widget 自推：不管 retry 还是 success，只要 Pull 这一轮没有直接被
        // NOT_CONFIGURED / UNAUTHORIZED 提前拒绝，就可能 upsert 了新行
        // （notes / events / single notes 三段都会 upsert）。即使这轮什么都没拉
        // （远端无变化），多刷一次也无害 —— WidgetRefresher 的 debounce 会把
        // 多次连发合并成单次 Glance updateAll。
        // Fix-WP (Review-Q): debounced refreshAll → blocking refreshAllNow.
        // Worker exit lands in Doze's flexible window where the 400 ms
        // debounce can be reaped before Glance updateAll runs. refreshAllNow
        // runs inline so the system can't tear us down mid-render.
        WidgetRefresher.refreshAllNow(applicationContext)
        return if (anyNetwork) Result.retry() else Result.success()
    }

    /**
     * Data-1 R13 helper: run [block] inside a Room transaction when the live
     * [AppDatabase] is reachable (production path). In unit-test fakes the
     * db is unreachable, so we just run [block] directly — tests already
     * operate without durability semantics.
     */
    private suspend fun runInTxOrFallback(block: suspend () -> Unit) {
        val db = AppDatabase.instance()
        if (db != null) db.withTransaction(block) else block()
    }

    /**
     * Parse the date component from a P5 single-note filename. Accepts
     * `YYYY-MM-DD-HHMM-<slug>.md` — the first 10 chars are the date. Returns
     * null when the prefix doesn't parse so the caller can fall back.
     */
    private fun parseDateFromSingleNoteFilename(fileName: String): LocalDate? {
        val m = SINGLE_NOTE_FILENAME.matchEntire(fileName) ?: return null
        return runCatching { LocalDate.parse(m.groupValues[1]) }.getOrNull()
    }

    /**
     * Parse the time component (`HHMM`) from a P5 single-note filename.
     * Returns null when the prefix doesn't parse.
     */
    private fun parseTimeFromSingleNoteFilename(fileName: String): LocalTime? {
        val m = SINGLE_NOTE_FILENAME.matchEntire(fileName) ?: return null
        val hhmm = m.groupValues[2]
        val hour = hhmm.substring(0, 2).toIntOrNull() ?: return null
        val minute = hhmm.substring(2, 4).toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return LocalTime.of(hour, minute)
    }

    /**
     * Walk the root directory once, fetching every file whose name matches
     * `YYYY-MM-DD.md`. Returns `true` if a network error deserves a retry.
     *
     * P6.1 第 6 项：shared [budget] across all pull segments replaces the
     * pre-P6.1 per-segment `MAX_BOOTSTRAP_PULLS_PER_CYCLE` constant.
     */
    private suspend fun bootstrapAllNotes(
        api: dev.aria.memo.data.GitHubApi,
        dao: dev.aria.memo.data.local.NoteDao,
        config: dev.aria.memo.data.AppConfig,
        budget: PullBudget,
    ): Boolean {
        var anyNetwork = false
        // The listDir itself costs 1 budget unit.
        if (!budget.consume()) return true
        val root = when (val res = api.listDir(config, "")) {
            is MemoResult.Ok -> res.value
            is MemoResult.Err -> {
                if (res.code == ErrorCode.NETWORK) anyNetwork = true
                return anyNetwork
            }
        }
        val noteFiles = root.filter { it.type == "file" && NOTE_FILENAME.matches(it.name) }
        for (item in noteFiles) {
            val date = runCatching { LocalDate.parse(item.name.removeSuffix(".md")) }.getOrNull() ?: continue
            // Fixes #53 (P6.1): merged exhausted+consume check.
            if (!budget.consume()) { anyNetwork = true; break }
            when (val res = api.getFile(config, item.path)) {
                is MemoResult.Ok -> {
                    val text = runCatching { res.value.decodedContent }.getOrNull() ?: continue
                    val nowMs = System.currentTimeMillis()
                    dao.upsert(
                        dev.aria.memo.data.local.NoteFileEntity(
                            path = item.path,
                            date = date,
                            content = text,
                            githubSha = res.value.sha,
                            localUpdatedAt = nowMs,
                            remoteUpdatedAt = nowMs,
                            dirty = false,
                        )
                    )
                }
                is MemoResult.Err -> when (res.code) {
                    ErrorCode.NETWORK -> anyNetwork = true
                    else -> Unit
                }
            }
        }
        return anyNetwork
    }

    private companion object {
        const val WINDOW_DAYS = 14
        val NOTE_FILENAME = Regex("""\d{4}-\d{2}-\d{2}\.md""")
        /**
         * P5 obsidian-style single note filename:
         *   `YYYY-MM-DD-HHMM-<slug>.md`
         * Captures the date and HHMM components so the pull worker can
         * reconstruct the authoring timestamp without opening the file body.
         */
        val SINGLE_NOTE_FILENAME = Regex("""(\d{4}-\d{2}-\d{2})-(\d{4})-.+\.md""")
    }
}
