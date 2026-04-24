package dev.aria.memo.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService

object NotificationChannelSetup {
    const val CHANNEL_ID = "event_reminders"
    private const val CHANNEL_NAME = "日程提醒"
    private const val CHANNEL_DESC = "日程开始前的本地提醒"

    // Quick-add ongoing-notification channel. Kept IMPORTANCE_LOW so the user
    // never sees a heads-up / hears a sound for what's effectively a launcher.
    const val CHANNEL_ID_QUICK_ADD = "quick_add"
    private const val CHANNEL_NAME_QUICK_ADD = "快速记一笔入口"
    private const val CHANNEL_DESC_QUICK_ADD = "通知栏常驻的快速写入口"

    /** Idempotent — safe to call on every Application.onCreate. */
    fun ensure(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = CHANNEL_DESC
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    /** Idempotent — only (re)creates the quick-add channel when absent. */
    fun ensureQuickAddChannel(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        if (manager.getNotificationChannel(CHANNEL_ID_QUICK_ADD) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID_QUICK_ADD,
            CHANNEL_NAME_QUICK_ADD,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = CHANNEL_DESC_QUICK_ADD
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }
}
