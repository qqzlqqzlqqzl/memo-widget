package dev.aria.memo.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
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
            } catch (t: Throwable) {
                // Sec-1 / H1: previously any Throwable (e.g. a corrupt Keystore
                // throwing KeyStoreException inside ServiceLocator.init, or
                // Room failing to open on post-reboot storage) was swallowed by
                // the coroutine scope, leaving every scheduled alarm silently
                // un-armed. Log at warn level so the next adb logcat pull
                // surfaces the failure — we can't Toast or retry from a boot
                // BroadcastReceiver, but a breadcrumb is strictly better than
                // silent data loss.
                Log.w(TAG, "failed to restore alarms after boot", t)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        private const val TAG = "BootReceiver"
    }
}
