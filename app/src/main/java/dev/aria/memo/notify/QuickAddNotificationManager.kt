package dev.aria.memo.notify

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import dev.aria.memo.EditActivity

/**
 * Manages the ongoing "记一笔" status-bar shortcut (Google Keep / Obsidian-style).
 *
 * Posts a low-importance, silent, ongoing notification that the user can tap to
 * jump straight into [EditActivity]. Users can't swipe it away (ongoing), but
 * they can disable it via the settings toggle — which calls [hide].
 *
 * Idempotent: calling [show] multiple times just overwrites the same
 * [NOTIFICATION_ID], so re-entry from Application.onCreate after a process
 * restart is safe.
 */
object QuickAddNotificationManager {

    // Bug-1 M17 fix (#142): 1001 是常见 hash collision 区, event uid.hashCode
    // 极少正好等于但仍存在风险; 改用高位常量,event hashCode 默认落在 0 附近
    // 几乎绝不会撞。Int.MIN_VALUE+1001 还是合法 notification id。
    const val NOTIFICATION_ID = Int.MIN_VALUE + 1001

    /** Post (or refresh) the ongoing quick-add notification. */
    fun show(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return

        // Runtime permission is still needed on 13+ even for an IMPORTANCE_LOW
        // channel; skip silently when denied — SettingsScreen already surfaces a
        // card nudging the user to grant it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        NotificationChannelSetup.ensureQuickAddChannel(context)

        val tapIntent = Intent(context, EditActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, NotificationChannelSetup.CHANNEL_ID_QUICK_ADD)
            .setContentTitle("备忘")
            .setContentText("点我记一笔")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }

    /** Cancel the ongoing quick-add notification (if present). */
    fun hide(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        manager.cancel(NOTIFICATION_ID)
    }
}
