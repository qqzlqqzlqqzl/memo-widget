package dev.aria.memo

import android.app.Application
import dev.aria.memo.data.ServiceLocator

/**
 * Application entry point. Bootstraps the ServiceLocator (simple DI container
 * defined in the data package) exactly once, before any Activity / Widget
 * Receiver touches the repository or SettingsStore.
 *
 * Manifest wiring: android:name=".MemoApplication" (set by Agent A).
 */
class MemoApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}
