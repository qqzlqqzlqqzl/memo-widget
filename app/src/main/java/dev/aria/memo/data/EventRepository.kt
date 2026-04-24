package dev.aria.memo.data

import android.content.Context
import dev.aria.memo.data.ics.IcsCodec
import dev.aria.memo.data.local.EventDao
import dev.aria.memo.data.local.EventEntity
import dev.aria.memo.data.sync.SyncScheduler
import dev.aria.memo.data.widget.WidgetRefresher
import dev.aria.memo.notify.AlarmScheduler
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Business logic for calendar events. Mirrors [MemoRepository]'s local-first
 * model: every CRUD touches Room immediately and marks the row dirty; the
 * synchronous push is best-effort, and any failure is re-queued to
 * [SyncScheduler.enqueuePush] for background retry.
 *
 * Events live on GitHub as `events/<uid>.ics` — one iCalendar file per event.
 * The `.ics` format keeps the data compatible with Google Calendar, Apple
 * Calendar, and khal for external viewing / subscription.
 */
class EventRepository(
    private val appContext: Context,
    private val settings: SettingsStore,
    private val api: GitHubApi,
    private val dao: EventDao,
) {

    fun observeAll(): Flow<List<EventEntity>> = dao.observeAll()

    fun observeBetween(startMs: Long, endMs: Long): Flow<List<EventEntity>> =
        dao.observeBetween(startMs, endMs)

    suspend fun get(uid: String): EventEntity? = dao.get(uid)

    suspend fun create(
        summary: String,
        startMs: Long,
        endMs: Long,
        allDay: Boolean = false,
        rrule: String? = null,
        reminderMinutesBefore: Int? = null,
    ): MemoResult<EventEntity> {
        val config = settings.current()
        if (!config.isConfigured) return MemoResult.Err(ErrorCode.NOT_CONFIGURED, "not configured")
        val uid = UUID.randomUUID().toString()
        val entity = EventEntity(
            uid = uid,
            summary = summary,
            startEpochMs = startMs,
            endEpochMs = endMs,
            allDay = allDay,
            filePath = "events/$uid.ics",
            githubSha = null,
            localUpdatedAt = System.currentTimeMillis(),
            remoteUpdatedAt = null,
            dirty = true,
            rrule = rrule,
            reminderMinutesBefore = reminderMinutesBefore,
        )
        dao.upsert(entity)
        SyncScheduler.enqueuePush(appContext)
        // M4 fix: alarm scheduling must not tear down a successful event write.
        runCatching { AlarmScheduler.scheduleForEvent(appContext, entity) }
        // P8 widget 自推：TodayWidget 显示今天的 events + memos，event CRUD 后
        // 必须让 widget 立刻反映新事件（见 TodayWidget.provideGlance）。
        WidgetRefresher.refreshAll(appContext)
        return MemoResult.Ok(entity)
    }

    suspend fun update(
        uid: String,
        summary: String,
        startMs: Long,
        endMs: Long,
        rrule: String? = null,
        reminderMinutesBefore: Int? = null,
    ): MemoResult<EventEntity> {
        val existing = dao.get(uid) ?: return MemoResult.Err(ErrorCode.NOT_FOUND, "event not found")
        val updated = existing.copy(
            summary = summary,
            startEpochMs = startMs,
            endEpochMs = endMs,
            rrule = rrule,
            reminderMinutesBefore = reminderMinutesBefore,
            localUpdatedAt = System.currentTimeMillis(),
            dirty = true,
        )
        dao.upsert(updated)
        SyncScheduler.enqueuePush(appContext)
        runCatching { AlarmScheduler.scheduleForEvent(appContext, updated) }
        // P8 widget 自推。
        WidgetRefresher.refreshAll(appContext)
        return MemoResult.Ok(updated)
    }

    suspend fun delete(uid: String): MemoResult<Unit> {
        dao.tombstone(uid, System.currentTimeMillis())
        SyncScheduler.enqueuePush(appContext)
        runCatching { AlarmScheduler.cancelForUid(appContext, uid) }
        // P8 widget 自推：event 删除后 TodayWidget 也要刷新。
        WidgetRefresher.refreshAll(appContext)
        return MemoResult.Ok(Unit)
    }

    /** Export a single event as iCalendar text — primarily for tests and inspection. */
    fun encode(entity: EventEntity): String = IcsCodec.encode(entity)
}
