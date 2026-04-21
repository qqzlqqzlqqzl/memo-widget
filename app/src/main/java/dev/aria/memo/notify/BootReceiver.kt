package dev.aria.memo.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.aria.memo.data.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Android clears all [android.app.AlarmManager] registrations on reboot.
 * We listen for `BOOT_COMPLETED` and re-arm every reminder from Room.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // App is not directBootAware, so the system will not deliver
        // LOCKED_BOOT_COMPLETED — only handle the regular BOOT_COMPLETED action.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try {
                ServiceLocator.init(appContext)
                AlarmScheduler.rescheduleAll(appContext)
            } finally {
                pending.finish()
            }
        }
    }
}
