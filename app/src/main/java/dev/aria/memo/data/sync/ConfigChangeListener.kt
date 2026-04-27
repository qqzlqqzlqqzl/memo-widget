package dev.aria.memo.data.sync

import android.content.Context
import androidx.annotation.VisibleForTesting
import dev.aria.memo.data.AppConfig
import dev.aria.memo.data.SettingsStore
import kotlinx.coroutines.flow.Flow

/**
 * Fix-D6 (Review-W 7) — flip-on push trigger when isConfigured goes false to true.
 *
 * Background:
 *  - P6.1.1 #57 made writes land in Room as dirty=true rows when no PAT is set,
 *    instead of fake-syncing.
 *  - But until now, after the user finally fills in PAT/owner/repo, those dirty
 *    rows did not push immediately. They had to wait for the next 30-min Pull
 *    cycle, a manual pull-to-refresh, or a fresh save. UX feedback: "I configured
 *    it but my notes still don't sync until I write another one."
 *
 * Solution:
 *  - Listen on [SettingsStore.config]. The instant `isConfigured` flips false to
 *    true, enqueue one [SyncScheduler.enqueuePush]. WorkManager picks up every
 *    dirty note / event / single-note row and pushes it.
 *  - Reverse direction (true to false) is a no-op. PushWorker already short-
 *    circuits on `!isConfigured`, so triggering it would just burn one worker.
 *  - First emit when already configured at process start counts as a flip
 *    because `prevConfigured` defaults to false. That's intentional and
 *    harmless: redundant with the unconditional enqueuePush in
 *    MemoApplication.onCreate, but PushWorker.doWork() scans `pending()` and
 *    short-circuits to Result.success() when nothing is dirty — so the second
 *    pass is effectively a no-op regardless of which ExistingWorkPolicy
 *    SyncScheduler uses.
 *
 * Lifecycle:
 *  - Launched from [dev.aria.memo.MemoApplication.onCreate] in the IO scope.
 *    `collect` runs forever (DataStore flow is infinite); when the process
 *    dies the coroutine dies. No manual cancel needed.
 *  - Non-blocking: `start` is suspend, runs on IO; DataStore.data is cold
 *    so subscription itself triggers no IO until first read.
 *
 * Testability:
 *  - Primary constructor matches the review spec: `(SettingsStore, Context)`.
 *  - [createForTest] takes any `Flow<AppConfig>` plus a `(Context?) -> Unit`
 *    push trigger so unit tests can inject a `MutableStateFlow` and a counter
 *    lambda. Pure JVM, no WorkManager / Robolectric.
 */
class ConfigChangeListener private constructor(
    private val configFlow: Flow<AppConfig>,
    private val context: Context?,
    private val pushTrigger: (Context?) -> Unit,
) {

    /**
     * Production constructor — equivalent to the review spec
     * `class ConfigChangeListener(settings: SettingsStore, context: Context)`.
     * Routes [SettingsStore.config] through the listener and forwards the push
     * call to [SyncScheduler.enqueuePush].
     */
    constructor(settings: SettingsStore, context: Context) : this(
        configFlow = settings.config,
        context = context,
        pushTrigger = { ctx -> if (ctx != null) SyncScheduler.enqueuePush(ctx) },
    )

    /**
     * Suspends forever, collecting the config flow and firing the push trigger
     * each time `isConfigured` flips false to true. The caller decides which
     * scope hosts this; current production caller is `MemoApplication.onCreate`'s
     * IO scope (SupervisorJob, so any thrown exception stays local to this
     * coroutine).
     */
    suspend fun start() {
        var prevConfigured = false
        configFlow.collect { cfg ->
            if (!prevConfigured && cfg.isConfigured) {
                pushTrigger(context)
            }
            prevConfigured = cfg.isConfigured
        }
    }

    companion object {
        /**
         * Test-only entry point. Production code should use the public
         * `(SettingsStore, Context)` constructor.
         */
        @VisibleForTesting
        fun createForTest(
            configFlow: Flow<AppConfig>,
            pushTrigger: (Context?) -> Unit,
            context: Context? = null,
        ): ConfigChangeListener =
            ConfigChangeListener(
                configFlow = configFlow,
                context = context,
                pushTrigger = pushTrigger,
            )
    }
}
