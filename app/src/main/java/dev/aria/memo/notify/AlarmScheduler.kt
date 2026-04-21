package dev.aria.memo.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.getSystemService
import dev.aria.memo.data.ServiceLocator
import dev.aria.memo.data.ics.EventExpander
import dev.aria.memo.data.local.EventEntity
import java.time.ZoneId

/**
 * Schedules local `AlarmManager` alarms for upcoming event reminders.
 *
 * Non-recurring events get at most one alarm. Recurring events get the *next*
 * occurrence only; when it fires, [EventAlarmReceiver] re-enters here to
 * schedule the following one. This keeps AlarmManager's slot usage flat even
 * for events with thousands of future occurrences.
 */
object AlarmScheduler {

    /** Call after create/update; no-op when [EventEntity.reminderMinutesBefore] is null. */
    suspend fun scheduleForEvent(context: Context, event: EventEntity) {
        ServiceLocator.init(context.applicationContext) // defensive — BootReceiver / early alarm path
        cancelForUid(context, event.uid)
        val minutesBefore = event.reminderMinutesBefore ?: return
        if (event.tombstoned) return
        val now = System.currentTimeMillis()
        // Use a very large upper bound so long-horizon recurring events (MONTHLY/WEEKLY
        // whose next occurrence lies beyond a 1-year window) still get their first
        // alarm scheduled. EventExpander applies its own ABSOLUTE_HORIZON_DAYS cap,
        // and we only consume firstOrNull here, so the window size has no perf cost.
        // Half of Long.MAX_VALUE avoids any downstream overflow.
        val windowEnd = Long.MAX_VALUE / 2
        val nextStart = nextOccurrenceStartMs(event, now, windowEnd) ?: return
        val fireAt = nextStart - minutesBefore * 60_000L
        if (fireAt <= now) return // occurrence is in the past or too close
        setExactOrInexact(context, event, nextStart, fireAt)
    }

    suspend fun rescheduleForUid(context: Context, uid: String) {
        ServiceLocator.init(context.applicationContext)
        val dao = ServiceLocator.eventDao()
        val event = dao.get(uid) ?: return
        scheduleForEvent(context, event)
    }

    fun cancelForUid(context: Context, uid: String) {
        val manager = context.getSystemService<AlarmManager>() ?: return
        manager.cancel(pendingIntentFor(context, uid, null, 0L))
    }

    /** Re-schedule every upcoming reminder; called on BOOT_COMPLETED. */
    suspend fun rescheduleAll(context: Context) {
        ServiceLocator.init(context.applicationContext)
        val dao = ServiceLocator.eventDao()
        for (event in dao.snapshotAll()) {
            if (event.tombstoned) continue
            scheduleForEvent(context, event)
        }
    }

    // --- internals ---------------------------------------------------------

    private fun nextOccurrenceStartMs(event: EventEntity, from: Long, until: Long): Long? {
        val occ = EventExpander.expand(event, from, until, ZoneId.systemDefault())
        return occ.firstOrNull { it.startEpochMs >= from }?.startEpochMs
    }

    private fun setExactOrInexact(
        context: Context,
        event: EventEntity,
        occurrenceStartMs: Long,
        fireAtMs: Long,
    ) {
        val manager = context.getSystemService<AlarmManager>() ?: return
        val pi = pendingIntentFor(context, event.uid, event.summary, occurrenceStartMs)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        if (canExact) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAtMs, pi)
        } else {
            // Inexact window — fires within ~15 minutes of fireAtMs; still useful.
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAtMs, pi)
        }
    }

    private fun pendingIntentFor(
        context: Context,
        uid: String,
        summary: String?,
        occurrenceStartMs: Long,
    ): PendingIntent {
        val intent = Intent(context, EventAlarmReceiver::class.java).apply {
            putExtra(EventAlarmReceiver.EXTRA_UID, uid)
            if (summary != null) putExtra(EventAlarmReceiver.EXTRA_SUMMARY, summary)
            putExtra(EventAlarmReceiver.EXTRA_START_MS, occurrenceStartMs)
        }
        return PendingIntent.getBroadcast(
            context,
            uid.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
