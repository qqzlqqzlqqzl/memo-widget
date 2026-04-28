package dev.aria.memo.notify

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import dev.aria.memo.MainActivity
import dev.aria.memo.data.widget.WidgetRefresher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Fires when an event's reminder alarm lands. Posts a local notification and
 * (for recurring events) reschedules the next occurrence via [AlarmScheduler].
 *
 * Uses `goAsync` so the reschedule work (which touches Room) gets ~10 s even
 * after `onReceive` returns.
 */
class EventAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val uid = intent.getStringExtra(EXTRA_UID) ?: return
        val summary = intent.getStringExtra(EXTRA_SUMMARY) ?: return
        val startEpochMs = intent.getLongExtra(EXTRA_START_MS, 0L)
        postNotification(context, uid, summary, startEpochMs)

        val pending = goAsync()
        val appContext = context.applicationContext
        // Fixes #320 (Perf-1 M6): same rationale as BootReceiver — Room
        // reads + AlarmManager IPC are IO-bound, not CPU-bound. Using
        // Dispatchers.IO matches the work; Default produced spurious
        // context switches with no upside.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                dev.aria.memo.data.ServiceLocator.init(appContext) // defensive
                AlarmScheduler.rescheduleForUid(appContext, uid)
                // Fix-WP (Review-Q): for recurring events the next-occurrence
                // anchor moved when this alarm fired, and the today / memo
                // widgets render that anchor. refreshAllNow is suspending +
                // inline so it commits inside this 10 s goAsync frame —
                // debounced refreshAll would risk being reaped by Doze.
                WidgetRefresher.refreshAllNow(appContext)
            } finally {
                pending.finish()
            }
        }
    }

    private fun postNotification(context: Context, uid: String, summary: String, startEpochMs: Long) {
        val manager = context.getSystemService<NotificationManager>() ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            return // user denied permission; skip silently
        }

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            context,
            uid.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // S2 fix: hide title on lock screen; public version shows a generic label.
        val publicVersion = NotificationCompat.Builder(context, NotificationChannelSetup.CHANNEL_ID)
            .setContentTitle("日程提醒")
            .setContentText(formatTime(startEpochMs))
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        val notification = NotificationCompat.Builder(context, NotificationChannelSetup.CHANNEL_ID)
            .setContentTitle(summary)
            .setContentText(formatTime(startEpochMs))
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()

        manager.notify(uid.hashCode(), notification)
    }

    private fun formatTime(startEpochMs: Long): String {
        val zdt = java.time.Instant.ofEpochMilli(startEpochMs).atZone(java.time.ZoneId.systemDefault())
        val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        return "${zdt.format(fmt)} 开始"
    }

    companion object {
        const val EXTRA_UID = "event_uid"
        const val EXTRA_SUMMARY = "event_summary"
        const val EXTRA_START_MS = "event_start_ms"
    }
}
