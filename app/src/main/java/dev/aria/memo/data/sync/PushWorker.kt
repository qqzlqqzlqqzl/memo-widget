package dev.aria.memo.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.aria.memo.data.AppConfig
import dev.aria.memo.data.ErrorCode
import dev.aria.memo.data.GhDeleteRequest
import dev.aria.memo.data.GhPutRequest
import dev.aria.memo.data.GhPutResponse
import dev.aria.memo.data.GitHubApi
import dev.aria.memo.data.MemoResult
import dev.aria.memo.data.ServiceLocator
import dev.aria.memo.data.ics.IcsCodec
import dev.aria.memo.data.widget.WidgetRefresher
import java.util.Base64

/**
 * Background push of dirty note files and events to GitHub.
 *
 * Fixes #6: every note PUT happens inside [PathLocker.withLock] so a foreground
 * `appendToday` cannot race this worker on the same SHA.
 * Fixes #11: posts [SyncStatus] to [SyncStatusBus] so UI can surface errors.
 * Fixes #21: wraps the whole body in try/finally so the UI can never get
 * stranded on [SyncStatus.Syncing] if something unexpected aborts the worker.
 * Fixes #27: a 409/422 CONFLICT on a note PUT is transparently re-attempted
 * once after refreshing the remote SHA, because the common cause is a
 * parallel push from another device rather than a semantic conflict.
 *
 * Fix-D1 (Review-W #1): the single-note arm extends the same SHA-refresh
 * retry up to **two** times before giving up. Cross-device concurrent edits
 * to one note used to leak past the first 409 — the loser was then stuck in
 * WorkManager's exponential backoff for 6+ hours, looking like data loss.
 * Two refreshes cover the realistic burst window; a third hard-409 surfaces
 * as [SyncStatus.Error] with [ErrorCode.CONFLICT] so the UI can route the
 * user into conflict resolution. The retry algorithm is exposed as the
 * top-level [pushSingleNoteWithConflictRetry] helper so it can be unit-tested
 * end-to-end against a Ktor MockEngine without spinning up WorkManager /
 * Robolectric / Room.
 *
 * WorkManager may instantiate this Worker before Application.onCreate on a
 * cold boot; the idempotent [ServiceLocator.init] guards against that NPE.
 */
class PushWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        doWorkInner()
    } finally {
        // Fixes #21: if anything threw or an early return skipped the status
        // emit below, clear the Syncing spinner so the UI doesn't hang.
        if (SyncStatusBus.status.value is SyncStatus.Syncing) {
            SyncStatusBus.emit(SyncStatus.Idle)
        }
    }

    private suspend fun doWorkInner(): Result {
        ServiceLocator.init(applicationContext)
        val settings = ServiceLocator.settingsStore
        val api = ServiceLocator.api()
        val noteDao = ServiceLocator.noteDao()
        val eventDao = ServiceLocator.eventDao()
        val singleNoteDao = ServiceLocator.singleNoteDao()
        val config = settings.current()
        if (!config.isConfigured) {
            SyncStatusBus.emit(SyncStatus.Error(ErrorCode.NOT_CONFIGURED, "还没配置 GitHub"))
            return Result.failure()
        }

        val pendingNotes = noteDao.pending()
        val pendingEvents = eventDao.pending()
        val pendingSingleNotes = singleNoteDao.pending()
        if (pendingNotes.isEmpty() && pendingEvents.isEmpty() && pendingSingleNotes.isEmpty()) {
            return Result.success()
        }
        SyncStatusBus.emit(SyncStatus.Syncing)

        var retry = false
        var lastError: Pair<ErrorCode, String>? = null
        // Fixes #297 (Red-3 N2): track Room mutations so we only fire
        // WidgetRefresher when something actually went clean / got
        // hard-deleted. A push cycle that fails *before* any markClean
        // (e.g. first-attempt 401 on every row) should leave the widget
        // alone — its content didn't change.
        var roomChanged = false

        // --- notes ---------------------------------------------------------
        for (row in pendingNotes) {
            val outcome = PathLocker.withLock(row.path) {
                val encoded = Base64.getEncoder().encodeToString(row.content.toByteArray(Charsets.UTF_8))
                val firstAttempt = api.putFile(
                    config,
                    row.path,
                    GhPutRequest(
                        message = "memo: ${row.path}",
                        content = encoded,
                        branch = config.branch,
                        sha = row.githubSha,
                    ),
                )
                // Fixes #27: one SHA-refresh retry for note conflicts. The
                // normal cause is another device pushing between our last pull
                // and this PUT; grabbing the fresh sha and replaying exactly
                // once keeps convergence cheap without looping forever.
                if (firstAttempt is MemoResult.Err && firstAttempt.code == ErrorCode.CONFLICT) {
                    val refreshed = api.getFile(config, row.path)
                    if (refreshed is MemoResult.Ok) {
                        api.putFile(
                            config,
                            row.path,
                            GhPutRequest(
                                message = "memo: ${row.path}",
                                content = encoded,
                                branch = config.branch,
                                sha = refreshed.value.sha,
                            ),
                        )
                    } else {
                        firstAttempt
                    }
                } else {
                    firstAttempt
                }
            }
            when (outcome) {
                is MemoResult.Ok -> {
                    noteDao.markClean(row.path, outcome.value.content.sha, System.currentTimeMillis())
                    roomChanged = true
                }
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
                        roomChanged = true
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
                                roomChanged = true
                                MemoResult.Ok(Unit)
                            }
                            is MemoResult.Err -> when (res.code) {
                                ErrorCode.NOT_FOUND -> {
                                    eventDao.hardDelete(row.uid)
                                    roomChanged = true
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
                            roomChanged = true
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

        // --- single notes (P5 obsidian-style) -----------------------------
        // Same PathLocker + tombstone semantics as the events arm. Each row is
        // a standalone markdown file under `notes/`. Tombstones drive DELETE;
        // fresh rows (no githubSha) get a plain PUT; subsequent edits re-PUT
        // with the known SHA. Conflict/network errors trigger a retry.
        for (row in pendingSingleNotes) {
            val outcome: MemoResult<Unit> = PathLocker.withLock(row.filePath) {
                if (row.tombstoned) {
                    val sha = row.githubSha
                    if (sha == null) {
                        // Never made it to GitHub in the first place — just drop locally.
                        singleNoteDao.hardDelete(row.uid)
                        roomChanged = true
                        MemoResult.Ok(Unit)
                    } else {
                        val req = GhDeleteRequest(
                            message = "note delete: ${row.filePath}",
                            sha = sha,
                            branch = config.branch,
                        )
                        when (val res = api.deleteFile(config, row.filePath, req)) {
                            is MemoResult.Ok -> {
                                singleNoteDao.hardDelete(row.uid)
                                roomChanged = true
                                MemoResult.Ok(Unit)
                            }
                            is MemoResult.Err -> when (res.code) {
                                ErrorCode.NOT_FOUND -> {
                                    // Already gone remotely — converge.
                                    singleNoteDao.hardDelete(row.uid)
                                    roomChanged = true
                                    MemoResult.Ok(Unit)
                                }
                                else -> MemoResult.Err(res.code, res.message)
                            }
                        }
                    }
                } else {
                    // Fix-D1 (Review-W #1): delegate the PUT to a shared helper
                    // that transparently re-fetches the remote SHA and replays
                    // the request up to MAX_CONFLICT_RETRIES times on 409. This
                    // closes the cross-device data-loss window described in the
                    // class KDoc and is unit-tested directly via MockEngine in
                    // PushWorkerSingleNoteConflictTest.
                    val res = pushSingleNoteWithConflictRetry(
                        api = api,
                        config = config,
                        filePath = row.filePath,
                        body = row.body,
                        title = row.title,
                        currentSha = row.githubSha,
                    )
                    when (res) {
                        is MemoResult.Ok -> {
                            singleNoteDao.markClean(
                                row.uid,
                                res.value.content.sha,
                                System.currentTimeMillis(),
                            )
                            roomChanged = true
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

        // State machine (Fix-6 / C6): preserve `lastError` visibility even
        // when we're about to retry.
        //
        // Previously `retry -> emit(Idle)` clobbered every UNKNOWN/5xx the
        // events or single-notes branches recorded into `lastError` as soon
        // as *any* row hit CONFLICT/NETWORK on the notes branch — painting
        // the UI green (Idle) while parts of the push were silently stuck.
        // Now:
        //   - retry + lastError recorded → surface the error (user sees it);
        //   - retry + no lastError       → Idle (spinner off, no banner);
        //   - lastError without retry    → Error (unchanged);
        //   - clean run                  → Ok (unchanged).
        //
        // Note: we can't `no-op` the retry branch — the outer try/finally
        // only clears Syncing to Idle, so a bus left on Syncing would just
        // fall back to Idle anyway. Explicit Idle here keeps intent clear.
        when {
            retry -> {
                if (lastError != null) {
                    SyncStatusBus.emit(
                        SyncStatus.Error(lastError!!.first, lastError!!.second),
                    )
                } else {
                    SyncStatusBus.emit(SyncStatus.Idle)
                }
            }
            lastError != null ->
                SyncStatusBus.emit(SyncStatus.Error(lastError!!.first, lastError!!.second))
            else -> SyncStatusBus.emit(SyncStatus.Ok)
        }
        // P8 widget 自推 + #297 (Red-3 N2) 短路：only refresh when at
        // least one row went clean / hard-deleted this cycle. A push
        // wave that 401'd on every row before any markClean leaves the
        // widget alone — its dirty markers are still accurate.
        //
        // Fix-WP (Review-Q): refreshAll → refreshAllNow. Worker exit
        // lands in Doze's flexible window where the 400 ms debounce can
        // be reaped before Glance updateAll runs. refreshAllNow is
        // inline so the dirty→clean transition becomes visible on the
        // widget immediately.
        if (roomChanged) {
            WidgetRefresher.refreshAllNow(applicationContext)
        }
        return if (retry) Result.retry() else Result.success()
    }
}

/**
 * Fix-D1 (Review-W #1): maximum number of SHA-refresh retries the single-note
 * push will perform on consecutive 409s before giving up. With 2 retries the
 * worker burns at most 3 PUTs + 2 GETs per conflicting note — cheap enough
 * that we don't need exponential backoff inside the loop, and large enough to
 * converge against realistic cross-device concurrent edit bursts (where each
 * loser only needs 1-2 SHA refreshes to land its content).
 */
internal const val MAX_CONFLICT_RETRIES: Int = 2

/**
 * Push a single Obsidian-style note with transparent SHA-refresh retry on
 * HTTP 409 / 422. Mirrors the existing day-file [PushWorker.doWorkInner] note
 * arm but allows up to [maxConflictRetries] consecutive refreshes before
 * surfacing a CONFLICT error to the caller (Fix-D1 / Review-W #1).
 *
 * Algorithm:
 *  1. PUT with the caller's [currentSha] (the row's last-known remote blob).
 *  2. On HTTP 200 — return [MemoResult.Ok] with the GitHub response so the
 *     caller can persist the new SHA via `markClean`.
 *  3. On any non-CONFLICT error (NETWORK, UNAUTHORIZED, …) — propagate
 *     verbatim. We do not retry these; that's the outer worker's job.
 *  4. On CONFLICT — GET the file to discover the current remote SHA and
 *     replay the PUT with the refreshed SHA. Repeat up to
 *     [maxConflictRetries] times; if all retries also CONFLICT, surface
 *     [ErrorCode.CONFLICT] so the worker emits [SyncStatus.Error] and the UI
 *     routes the user into conflict resolution.
 *  5. If a refresh GET itself fails (e.g. NETWORK during the retry window)
 *     surface that error verbatim — refreshing is not retried recursively,
 *     because doing so would let a flapping connection silently burn the
 *     entire worker quota on one note.
 *
 * Pure-function: takes only the request payload + dependencies, returns the
 * response. No side effects on Room / SyncStatusBus, so the unit test in
 * [dev.aria.memo.data.sync.PushWorkerSingleNoteConflictTest] can stub the
 * entire chain with a Ktor MockEngine.
 */
internal suspend fun pushSingleNoteWithConflictRetry(
    api: GitHubApi,
    config: AppConfig,
    filePath: String,
    body: String,
    title: String,
    currentSha: String?,
    maxConflictRetries: Int = MAX_CONFLICT_RETRIES,
): MemoResult<GhPutResponse> {
    val encoded = Base64.getEncoder().encodeToString(body.toByteArray(Charsets.UTF_8))
    val message = "note: ${title.take(40).ifBlank { filePath }}"

    var attemptSha: String? = currentSha
    var conflictAttempts = 0
    while (true) {
        val req = GhPutRequest(
            message = message,
            content = encoded,
            branch = config.branch,
            sha = attemptSha,
        )
        when (val res = api.putFile(config, filePath, req)) {
            is MemoResult.Ok -> return res
            is MemoResult.Err -> {
                if (res.code != ErrorCode.CONFLICT ||
                    conflictAttempts >= maxConflictRetries) {
                    // Either a non-CONFLICT error (network/auth/etc.) or
                    // we've already burned every retry — propagate so the
                    // outer dispatcher emits the right SyncStatus.Error.
                    return MemoResult.Err(res.code, res.message)
                }
                // Refresh SHA and try again. If the refresh GET itself
                // fails (e.g. 404 because the row was deleted on the other
                // device, or NETWORK) surface the error verbatim so the
                // worker can react without looping forever.
                when (val refreshed = api.getFile(config, filePath)) {
                    is MemoResult.Ok -> {
                        attemptSha = refreshed.value.sha
                        conflictAttempts += 1
                    }
                    is MemoResult.Err -> {
                        return MemoResult.Err(refreshed.code, refreshed.message)
                    }
                }
            }
        }
    }
}
