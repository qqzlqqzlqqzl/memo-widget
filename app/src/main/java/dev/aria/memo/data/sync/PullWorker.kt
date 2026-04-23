package dev.aria.memo.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.aria.memo.data.ErrorCode
import dev.aria.memo.data.MemoResult
import dev.aria.memo.data.ServiceLocator
import dev.aria.memo.data.SingleNoteRepository
import dev.aria.memo.data.ics.IcsCodec
import dev.aria.memo.data.local.NoteFileEntity
import dev.aria.memo.data.local.SingleNoteEntity
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
                            // Remote UID may differ from what we had indexed — if so,
                            // drop the stale row so the new row (keyed on remote UID)
                            // becomes the canonical one.
                            if (localByPath != null && localByPath.uid != decoded.uid) {
                                eventDao.hardDelete(localByPath.uid)
                            }
                            // S1 fix: reminder is a local-only preference (not in .ics).
                            // Preserve whatever the user set locally, otherwise the first
                            // remote edit to the event would silently wipe every device's
                            // reminder back to null.
                            val merged = decoded.copy(
                                reminderMinutesBefore = localByPath?.reminderMinutesBefore
                                    ?: decoded.reminderMinutesBefore,
                            )
                            eventDao.upsert(merged)
                            // Keep AlarmManager in sync with the merged row.
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
                ErrorCode.UNAUTHORIZED -> return Result.success()
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
                ErrorCode.UNAUTHORIZED -> return Result.success()
                else -> Unit
            }
        }

        return if (anyNetwork) Result.retry() else Result.success()
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
