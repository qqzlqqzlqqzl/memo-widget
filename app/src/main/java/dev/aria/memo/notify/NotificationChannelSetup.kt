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
}
