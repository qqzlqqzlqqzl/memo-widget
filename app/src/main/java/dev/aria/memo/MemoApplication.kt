package dev.aria.memo

import android.app.Application
import dev.aria.memo.data.ServiceLocator
import dev.aria.memo.data.sync.SyncScheduler
import dev.aria.memo.notify.NotificationChannelSetup

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
    }
}
