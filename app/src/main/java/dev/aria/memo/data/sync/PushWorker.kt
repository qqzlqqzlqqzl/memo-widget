package dev.aria.memo.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.aria.memo.data.ErrorCode
import dev.aria.memo.data.GhPutRequest
import dev.aria.memo.data.MemoResult
import dev.aria.memo.data.ServiceLocator
import java.util.Base64

/**
 * Background push of dirty note files to GitHub.
 *
 * WorkManager may run this Worker before Application.onCreate has fired
 * (e.g., after device reboot with queued work), so the first line calls the
 * idempotent [ServiceLocator.init] to guarantee the repository is built.
 */
class PushWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        ServiceLocator.init(applicationContext)
        val settings = ServiceLocator.settingsStore
        val api = ServiceLocator.api()
        val dao = ServiceLocator.noteDao()
        val config = settings.current()
        if (!config.isConfigured) return Result.failure()

        val pending = dao.pending()
        if (pending.isEmpty()) return Result.success()

        var retry = false
        for (row in pending) {
            val encoded = Base64.getEncoder().encodeToString(row.content.toByteArray(Charsets.UTF_8))
            val request = GhPutRequest(
                message = "memo: ${row.path}",
                content = encoded,
                branch = config.branch,
                sha = row.githubSha,
            )
            when (val res = api.putFile(config, row.path, request)) {
                is MemoResult.Ok -> {
                    val nowMs = System.currentTimeMillis()
                    dao.markClean(row.path, res.value.content.sha, nowMs)
                }
                is MemoResult.Err -> when (res.code) {
                    ErrorCode.NETWORK, ErrorCode.CONFLICT -> retry = true
                    ErrorCode.UNAUTHORIZED -> {
                        // Bad PAT: stop retrying this batch, user must fix config.
                        return Result.failure()
                    }
                    else -> { /* skip this file — next save will re-queue */ }
                }
            }
        }
        return if (retry) Result.retry() else Result.success()
    }
}
