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
            .enqueueUniqueWork(PUSH_UNIQUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** One-shot pull triggered on app open / pull-to-refresh. */
    fun enqueuePullNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<PullWorker>()
            .setConstraints(networkConstraints)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(PULL_NOW_UNIQUE, ExistingWorkPolicy.KEEP, request)
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
