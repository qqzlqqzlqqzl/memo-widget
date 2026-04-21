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
        val today = LocalDate.now()
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
                    ErrorCode.UNAUTHORIZED -> return Result.success()
                    else -> Unit
                }
            }
        }

        // --- events --------------------------------------------------------
        when (val res = api.listDir(config, "events")) {
            is MemoResult.Ok -> {
                val remote = res.value.filter { it.type == "file" && it.name.endsWith(".ics") }
                val remotePaths = remote.map { it.path }.toSet()
                // Pull new / changed
                for (item in remote) {
                    val localByPath = findEventByPath(eventDao, item.path)
                    if (localByPath?.dirty == true) continue
                    if (localByPath != null && localByPath.githubSha == item.sha) continue
                    when (val fileRes = api.getFile(config, item.path)) {
                        is MemoResult.Ok -> {
                            val text = runCatching { fileRes.value.decodedContent }.getOrNull() ?: continue
                            val nowMs = System.currentTimeMillis()
                            val decoded = IcsCodec.decode(text, item.path, item.sha, nowMs) ?: continue
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

    private suspend fun findEventByPath(
        dao: dev.aria.memo.data.local.EventDao,
        path: String,
    ): dev.aria.memo.data.local.EventEntity? {
        // uid is filename without `.ics` → derive uid from path for a cheap lookup.
        val uid = path.substringAfterLast('/').removeSuffix(".ics")
        return dao.get(uid)
    }

    private companion object {
        const val WINDOW_DAYS = 14
    }
}
