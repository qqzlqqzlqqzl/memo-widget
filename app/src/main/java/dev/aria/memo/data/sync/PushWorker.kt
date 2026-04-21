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
 * Fixes #6: every note PUT happens inside [PathLocker.withLock] so a foreground
 * `appendToday` cannot race this worker on the same SHA.
 * Fixes #11: posts [SyncStatus] to [SyncStatusBus] so UI can surface errors.
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
        if (!config.isConfigured) {
            SyncStatusBus.emit(SyncStatus.Error(ErrorCode.NOT_CONFIGURED, "还没配置 GitHub"))
            return Result.failure()
        }

        val pendingNotes = noteDao.pending()
        val pendingEvents = eventDao.pending()
        if (pendingNotes.isEmpty() && pendingEvents.isEmpty()) return Result.success()
        SyncStatusBus.emit(SyncStatus.Syncing)

        var retry = false
        var lastError: Pair<ErrorCode, String>? = null

        // --- notes ---------------------------------------------------------
        for (row in pendingNotes) {
            val outcome = PathLocker.withLock(row.path) {
                val encoded = Base64.getEncoder().encodeToString(row.content.toByteArray(Charsets.UTF_8))
                val request = GhPutRequest(
                    message = "memo: ${row.path}",
                    content = encoded,
                    branch = config.branch,
                    sha = row.githubSha,
                )
                api.putFile(config, row.path, request)
            }
            when (outcome) {
                is MemoResult.Ok ->
                    noteDao.markClean(row.path, outcome.value.content.sha, System.currentTimeMillis())
                is MemoResult.Err -> when (outcome.code) {
                    ErrorCode.NETWORK, ErrorCode.CONFLICT -> {
                        retry = true
                        lastError = outcome.code to outcome.message
                    }
                    ErrorCode.UNAUTHORIZED -> {
                        SyncStatusBus.emit(SyncStatus.Error(ErrorCode.UNAUTHORIZED, outcome.message))
                        return Result.failure()
                    }
                    else -> lastError = outcome.code to outcome.message
                }
            }
        }

        // --- events --------------------------------------------------------
        // Fixes #6 (events arm): lock on filePath so EventRepository's foreground
        // writes can't race us on the same .ics SHA.
        for (row in pendingEvents) {
            val outcome: MemoResult<Unit> = PathLocker.withLock(row.filePath) {
                if (row.tombstoned) {
                    val sha = row.githubSha
                    if (sha == null) {
                        eventDao.hardDelete(row.uid)
                        MemoResult.Ok(Unit)
                    } else {
                        val req = GhDeleteRequest(
                            message = "event delete: ${row.uid}",
                            sha = sha,
                            branch = config.branch,
                        )
                        when (val res = api.deleteFile(config, row.filePath, req)) {
                            is MemoResult.Ok -> {
                                eventDao.hardDelete(row.uid)
                                MemoResult.Ok(Unit)
                            }
                            is MemoResult.Err -> when (res.code) {
                                ErrorCode.NOT_FOUND -> {
                                    eventDao.hardDelete(row.uid)
                                    MemoResult.Ok(Unit)
                                }
                                else -> MemoResult.Err(res.code, res.message)
                            }
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
                        is MemoResult.Ok -> {
                            eventDao.markClean(row.uid, res.value.content.sha, System.currentTimeMillis())
                            MemoResult.Ok(Unit)
                        }
                        is MemoResult.Err -> MemoResult.Err(res.code, res.message)
                    }
                }
            }
            if (outcome is MemoResult.Err) {
                when (outcome.code) {
                    ErrorCode.NETWORK, ErrorCode.CONFLICT -> {
                        retry = true
                        lastError = outcome.code to outcome.message
                    }
                    ErrorCode.UNAUTHORIZED -> {
                        SyncStatusBus.emit(SyncStatus.Error(ErrorCode.UNAUTHORIZED, outcome.message))
                        return Result.failure()
                    }
                    else -> lastError = outcome.code to outcome.message
                }
            }
        }

        // State machine: non-retry terminal failures → Error; success → Ok;
        // pure retries → Idle (don't strand the UI on Syncing forever).
        when {
            retry -> SyncStatusBus.emit(SyncStatus.Idle)
            lastError != null ->
                SyncStatusBus.emit(SyncStatus.Error(lastError!!.first, lastError!!.second))
            else -> SyncStatusBus.emit(SyncStatus.Ok)
        }
        return if (retry) Result.retry() else Result.success()
    }
}
