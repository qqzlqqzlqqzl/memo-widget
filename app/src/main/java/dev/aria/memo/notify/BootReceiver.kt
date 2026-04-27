package dev.aria.memo.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.aria.memo.data.ServiceLocator
import dev.aria.memo.data.widget.WidgetRefresher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Re-arms every [android.app.AlarmManager] reminder + repaints widgets when
 * the system delivers [Intent.ACTION_BOOT_COMPLETED],
 * [Intent.ACTION_MY_PACKAGE_REPLACED], [Intent.ACTION_TIMEZONE_CHANGED], or
 * `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` (API 33+).
 *
 * Fix-WP (Review-Q): used to handle BOOT_COMPLETED only, so the other three
 * events silently dropped every scheduled alarm until the user next opened
 * the app (APK upgrades wipe alarms; timezone changes leave them firing at
 * the wrong wall-clock time; exact-alarm permission toggles flip the
 * exact/inexact decision tree).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // App is not directBootAware, so LOCKED_BOOT_COMPLETED is never
        // delivered. Only the four documented actions are accepted.
        val action = intent.action ?: return
        if (action !in HANDLED_ACTIONS) return
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try {
                ServiceLocator.init(appContext)
                AlarmScheduler.rescheduleAll(appContext)
                // TIMEZONE_CHANGED also moves "今天" by up to 24h on the
                // widget headers. refreshAll is debounced + idempotent so
                // calling it from every handled action is cheap.
                WidgetRefresher.refreshAll(appContext)
            } catch (t: Throwable) {
                // Sec-1 / H1: previously any Throwable (e.g. a corrupt Keystore
                // throwing KeyStoreException inside ServiceLocator.init, or
                // Room failing to open on post-reboot storage) was swallowed by
                // the coroutine scope, leaving every scheduled alarm silently
                // un-armed. Log at warn level so the next adb logcat pull
                // surfaces the failure — we can't Toast or retry from a boot
                // BroadcastReceiver, but a breadcrumb is strictly better than
                // silent data loss.
                Log.w(TAG, "failed to restore alarms for action=$action", t)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        private const val TAG = "BootReceiver"

        /**
         * String constant for the API 33+ exact-alarm permission action.
         * Hard-coded so we can keep `minSdk = 26` without a SDK_INT guard —
         * the broadcast simply never arrives on pre-33 devices.
         */
        private const val ACTION_EXACT_ALARM_PERMISSION_STATE_CHANGED =
            "android.intent.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"

        private val HANDLED_ACTIONS: Set<String> = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            ACTION_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
    }
}
