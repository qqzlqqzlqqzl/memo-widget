package dev.aria.memo.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object SyncScheduler {

    private const val PUSH_UNIQUE = "memo.push"
    private const val PULL_UNIQUE = "memo.pull.periodic"
    private const val PULL_NOW_UNIQUE = "memo.pull.now"

    /**
     * Fix-WP (Review-Q): chained saves used to be enqueued with
     * [ExistingWorkPolicy.APPEND_OR_REPLACE], serialising every retry-then-save
     * burst onto a single linear chain. A single failing push (transient
     * NETWORK in WorkManager's exponential backoff) blocked all subsequent
     * saves until the head finished its 6-hour retry curve.
     *
     * KEEP collapses redundant enqueues into a no-op because [PushWorker]
     * already re-scans every `dirty = true` row on entry.
     *
     * `internal` so [SyncSchedulerPolicyTest] can assert the policy without
     * needing a real WorkManager instance on the test classpath.
     */
    internal val PUSH_POLICY: ExistingWorkPolicy = ExistingWorkPolicy.KEEP

    /**
     * Same KEEP rationale as [PUSH_POLICY] applied to one-shot pulls:
     *   - REPLACE would cancel an in-flight PullWorker mid-HTTP (e.g. the
     *     "Settings → 立即同步" button tapped while the 30-min periodic
     *     pull is already running) and leave Room in an undefined state
     *     for the partial commit window.
     *   - APPEND_OR_REPLACE serialises taps onto a chain so a transient
     *     NETWORK error blocks every subsequent manual sync until backoff
     *     elapses — the same Fix-WP failure mode as the push side.
     *
     * `internal` so [SyncSchedulerPolicyTest] can assert the value without
     * spinning up a real WorkManager.
     */
    internal val PULL_NOW_POLICY: ExistingWorkPolicy = ExistingWorkPolicy.KEEP

    private val networkConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Enqueue a one-shot push. Chained saves collapse into a single run. */
    fun enqueuePush(context: Context) {
        val request = OneTimeWorkRequestBuilder<PushWorker>()
            .setConstraints(networkConstraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(PUSH_UNIQUE, PUSH_POLICY, request)
    }

    /**
     * Force a push retry with fresh credentials. Unlike [enqueuePush] this
     * uses [ExistingWorkPolicy.REPLACE] so any worker already running against
     * stale credentials (e.g. an expired PAT that 401'd) is cancelled and a
     * new attempt starts immediately.
     *
     * Fixes #113 (Bug-1 H10): after the user updates an expired PAT in
     * Settings, dirty rows would otherwise wait until the next note edit (or
     * an external trigger) before being pushed. Calling this from the
     * SettingsStore update path closes that gap — the new credentials hit
     * the network within seconds.
     */
    fun enqueuePushAfterCredentialChange(context: Context) {
        val request = OneTimeWorkRequestBuilder<PushWorker>()
            .setConstraints(networkConstraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(PUSH_UNIQUE, ExistingWorkPolicy.REPLACE, request)
    }

    /** One-shot pull triggered on app open / pull-to-refresh / "立即同步". */
    fun enqueuePullNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<PullWorker>()
            .setConstraints(networkConstraints)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(PULL_NOW_UNIQUE, PULL_NOW_POLICY, request)
    }

    /** Periodic background pull — call from Application.onCreate. */
    fun schedulePeriodicPull(context: Context) {
        val request = PeriodicWorkRequestBuilder<PullWorker>(30, TimeUnit.MINUTES)
            .setConstraints(networkConstraints)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PULL_UNIQUE, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
