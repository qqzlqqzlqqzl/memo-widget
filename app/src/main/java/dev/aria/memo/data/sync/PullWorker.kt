package dev.aria.memo.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.aria.memo.data.ErrorCode
import dev.aria.memo.data.MemoResult
import dev.aria.memo.data.ServiceLocator
import dev.aria.memo.data.local.NoteFileEntity
import java.time.LocalDate

/**
 * Periodic pull of recent note files from GitHub into Room.
 *
 * Walks a sliding window of the last [WINDOW_DAYS] days. For each day:
 *   - Skip if local row is dirty (unpushed changes take priority).
 *   - Skip if local SHA matches remote SHA (no-op, avoids touching Room).
 *   - Otherwise overwrite local with the remote snapshot.
 *
 * 14-day window is the P1 default; a full tree-walk backfill ships in P2.
 */
class PullWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        ServiceLocator.init(applicationContext)
        val settings = ServiceLocator.settingsStore
        val api = ServiceLocator.api()
        val dao = ServiceLocator.noteDao()
        val config = settings.current()
        if (!config.isConfigured) return Result.success()

        var anyNetwork = false
        val today = LocalDate.now()
        for (offset in 0 until WINDOW_DAYS) {
            val date = today.minusDays(offset.toLong())
            val path = config.filePathFor(date)
            val local = dao.get(path)
            if (local?.dirty == true) continue

            when (val res = api.getFile(config, path)) {
                is MemoResult.Ok -> {
                    if (local != null && local.githubSha == res.value.sha) continue
                    val text = runCatching { res.value.decodedContent }.getOrNull() ?: continue
                    val nowMs = System.currentTimeMillis()
                    dao.upsert(
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
                    ErrorCode.NOT_FOUND -> { /* no memo that day, fine */ }
                    ErrorCode.NETWORK -> anyNetwork = true
                    ErrorCode.UNAUTHORIZED -> return Result.success()
                    else -> { /* keep trying the rest of the window */ }
                }
            }
        }
        return if (anyNetwork) Result.retry() else Result.success()
    }

    private companion object {
        const val WINDOW_DAYS = 14
    }
}
