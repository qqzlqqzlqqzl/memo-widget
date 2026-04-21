package dev.aria.memo

import android.app.Application
import dev.aria.memo.data.ServiceLocator
import dev.aria.memo.data.sync.SyncScheduler

/**
 * Application entry point. Bootstraps the ServiceLocator, schedules the
 * periodic pull worker, and kicks a one-shot push to flush any dirty rows
 * left behind by a crashed save.
 */
class MemoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        SyncScheduler.schedulePeriodicPull(this)
        SyncScheduler.enqueuePush(this)
    }
}
