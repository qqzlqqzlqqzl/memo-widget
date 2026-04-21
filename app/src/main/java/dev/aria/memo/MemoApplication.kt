package dev.aria.memo

import android.app.Application
import dev.aria.memo.data.PreferencesStore
import dev.aria.memo.data.ServiceLocator
import dev.aria.memo.data.sync.SyncScheduler
import dev.aria.memo.notify.NotificationChannelSetup
import dev.aria.memo.notify.QuickAddNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry point. Bootstraps the ServiceLocator, schedules the
 * periodic pull worker, kicks a one-shot push to flush any dirty rows left
 * behind by a crashed save, and installs the notification channel used by
 * event reminders.
 */
class MemoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        NotificationChannelSetup.ensure(this)
        SyncScheduler.schedulePeriodicPull(this)
        SyncScheduler.enqueuePush(this)

        // Re-hydrate the ongoing "记一笔" notification after a process restart.
        // DataStore reads must happen off the main thread; using a supervisor
        // scope so a transient IO error can't crash app startup.
        val prefs = PreferencesStore(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            if (prefs.currentQuickAddEnabled()) {
                QuickAddNotificationManager.show(this@MemoApplication)
            }
        }
    }
}
