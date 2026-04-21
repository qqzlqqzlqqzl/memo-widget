package dev.aria.memo.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.aria.memo.data.ErrorCode
import dev.aria.memo.data.MemoResult
import dev.aria.memo.data.ServiceLocator
import dev.aria.memo.data.ics.IcsCodec
import dev.aria.memo.data.local.NoteFileEntity
import java.time.LocalDate

/**
 * Periodic pull of recent notes and all events from GitHub.
 *
 * Notes: 14-day sliding window by `YYYY-MM-DD.md` filename.
 * Events: directory listing of `events/` — diff by SHA, fetch changed ones,
 *   decode iCalendar, upsert Room. Events not in the remote listing but
 *   present in Room (non-dirty) are hard-deleted to reflect the GitHub state.
 *
 * Dirty rows in either table are skipped so local edits are never clobbered.
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
        val config = settings.current()
        if (!config.isConfigured) return Result.success()

        var anyNetwork = false

        // --- notes ---------------------------------------------------------
        // Fixes #5: when the local cache is empty, list the repo root and pull
        // every `YYYY-MM-DD.md` up front so freshly-installed devices / cleared
        // caches see the full history instead of only the 14-day window.
        val today = LocalDate.now()
        val cachedNoteCount = noteDao.count()
        if (cachedNoteCount == 0) {
            anyNetwork = anyNetwork or bootstrapAllNotes(api, noteDao, config)
        }

        // Always also refresh the 14-day sliding window so recent edits from
        // other devices are picked up even when the cache isn't empty.
        for (offset in 0 until WINDOW_DAYS) {
            val date = today.minusDays(offset.toLong())
            val path = config.filePathFor(date)
            val local = noteDao.get(path)
            if (local?.dirty == true) continue
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
        when (val res = api.listDir(config, "events")) {
            is MemoResult.Ok -> {
                val remote = res.value.filter { it.type == "file" && it.name.endsWith(".ics") }
                val remotePaths = remote.map { it.path }.toSet()
                // Fixes #10: budget the per-cycle work. Anything left over gets
                // picked up on the next scheduled run (periodic 30 min) or the
                // next explicit refresh, instead of draining the user's rate limit.
                var pullsThisCycle = 0
                // Pull new / changed. Fixes #2: locate by filePath (unique index),
                // NOT by deriving uid from filename — a remote rename used to produce
                // a duplicate row.
                for (item in remote) {
                    if (pullsThisCycle >= MAX_PULLS_PER_CYCLE) {
                        // Stop fetching; the loop below still reconciles deletions,
                        // and the leftovers come in on the next cycle. Mark as
                        // "wants-retry" so WorkManager re-runs us sooner.
                        anyNetwork = true
                        break
                    }
                    val localByPath = eventDao.getByPath(item.path)
                    if (localByPath?.dirty == true) continue
                    if (localByPath != null && localByPath.githubSha == item.sha) continue
                    pullsThisCycle++
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
                            eventDao.upsert(decoded)
                        }
                        is MemoResult.Err -> when (fileRes.code) {
                            ErrorCode.NETWORK -> anyNetwork = true
                            else -> Unit
                        }
                    }
                }
                // Drop local events that remote no longer has (unless dirty, in which case user still has pending work).
                for (local in eventDao.snapshotAll()) {
                    if (local.dirty || local.tombstoned) continue
                    if (local.filePath !in remotePaths) eventDao.hardDelete(local.uid)
                }
            }
            is MemoResult.Err -> when (res.code) {
                ErrorCode.NOT_FOUND -> Unit // no events/ dir yet
                ErrorCode.NETWORK -> anyNetwork = true
                ErrorCode.UNAUTHORIZED -> return Result.success()
                else -> Unit
            }
        }

        return if (anyNetwork) Result.retry() else Result.success()
    }

    /**
     * Walk the root directory once, fetching every file whose name matches
     * `YYYY-MM-DD.md`. Returns `true` if a network error deserves a retry.
     */
    private suspend fun bootstrapAllNotes(
        api: dev.aria.memo.data.GitHubApi,
        dao: dev.aria.memo.data.local.NoteDao,
        config: dev.aria.memo.data.AppConfig,
    ): Boolean {
        var anyNetwork = false
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
        /** Fixes #10: cap per-cycle event GETs to stay under GitHub rate limits. */
        const val MAX_PULLS_PER_CYCLE = 50
        val NOTE_FILENAME = Regex("""\d{4}-\d{2}-\d{2}\.md""")
    }
}
