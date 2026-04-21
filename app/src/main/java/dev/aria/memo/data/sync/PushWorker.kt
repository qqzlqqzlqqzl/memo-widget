package dev.aria.memo.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.aria.memo.data.ErrorCode
import dev.aria.memo.data.GhDeleteRequest
import dev.aria.memo.data.GhPutRequest
import dev.aria.memo.data.MemoResult
import dev.aria.memo.data.ServiceLocator
import dev.aria.memo.data.ics.IcsCodec
import java.util.Base64

/**
 * Background push of dirty note files and events to GitHub.
 *
 * Notes: iterate [dao.pending], PUT each with the latest SHA, mark clean.
 * Events: iterate event DAO pending; if tombstoned → DELETE file (then hard
 * delete the row); else → PUT the re-encoded `.ics`.
 *
 * WorkManager may instantiate this Worker before Application.onCreate on a
 * cold boot; the idempotent [ServiceLocator.init] guards against that NPE.
 */
class PushWorker(
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
        if (!config.isConfigured) return Result.failure()

        var retry = false

        // --- notes ---------------------------------------------------------
        for (row in noteDao.pending()) {
            val encoded = Base64.getEncoder().encodeToString(row.content.toByteArray(Charsets.UTF_8))
            val request = GhPutRequest(
                message = "memo: ${row.path}",
                content = encoded,
                branch = config.branch,
                sha = row.githubSha,
            )
            when (val res = api.putFile(config, row.path, request)) {
                is MemoResult.Ok -> noteDao.markClean(row.path, res.value.content.sha, System.currentTimeMillis())
                is MemoResult.Err -> when (res.code) {
                    ErrorCode.NETWORK, ErrorCode.CONFLICT -> retry = true
                    ErrorCode.UNAUTHORIZED -> return Result.failure()
                    else -> { /* skip */ }
                }
            }
        }

        // --- events --------------------------------------------------------
        for (row in eventDao.pending()) {
            if (row.tombstoned) {
                val sha = row.githubSha
                if (sha == null) {
                    // never uploaded — just drop the row
                    eventDao.hardDelete(row.uid)
                    continue
                }
                val req = GhDeleteRequest(
                    message = "event delete: ${row.uid}",
                    sha = sha,
                    branch = config.branch,
                )
                when (val res = api.deleteFile(config, row.filePath, req)) {
                    is MemoResult.Ok -> eventDao.hardDelete(row.uid)
                    is MemoResult.Err -> when (res.code) {
                        ErrorCode.NOT_FOUND -> eventDao.hardDelete(row.uid) // already gone, fine
                        ErrorCode.NETWORK, ErrorCode.CONFLICT -> retry = true
                        ErrorCode.UNAUTHORIZED -> return Result.failure()
                        else -> { /* skip */ }
                    }
                }
            } else {
                val ics = IcsCodec.encode(row)
                val encoded = Base64.getEncoder().encodeToString(ics.toByteArray(Charsets.UTF_8))
                val req = GhPutRequest(
                    message = "event: ${row.summary.take(40)}",
                    content = encoded,
                    branch = config.branch,
                    sha = row.githubSha,
                )
                when (val res = api.putFile(config, row.filePath, req)) {
                    is MemoResult.Ok -> eventDao.markClean(row.uid, res.value.content.sha, System.currentTimeMillis())
                    is MemoResult.Err -> when (res.code) {
                        ErrorCode.NETWORK, ErrorCode.CONFLICT -> retry = true
                        ErrorCode.UNAUTHORIZED -> return Result.failure()
                        else -> { /* skip */ }
                    }
                }
            }
        }

        return if (retry) Result.retry() else Result.success()
    }
}
