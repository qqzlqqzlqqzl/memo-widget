package dev.aria.memo.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import dev.aria.memo.data.ErrorCode
import dev.aria.memo.data.MemoEntry
import dev.aria.memo.data.MemoRepository
import dev.aria.memo.data.MemoResult
import dev.aria.memo.data.ServiceLocator
import dev.aria.memo.data.local.EventEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.ZoneId

/**
 * Glance widget that renders today's events + memos in a scrollable list.
 *
 * Width: `resizable` so the launcher can flex it between 4x2 and 4x4. Rows
 * tap into [dev.aria.memo.MainActivity] (landing on the Notes tab); the
 * header "+" opens [dev.aria.memo.EditActivity] for a quick memo.
 */
class TodayWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        ServiceLocator.init(context)
        val repo = ServiceLocator.repository
        val eventRepo = ServiceLocator.eventRepo
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val memoResult = repo.recentEntries(limit = 6)
        val isConfigured: Boolean
        val memos: List<MemoEntry>
        when (memoResult) {
            is MemoResult.Ok -> { isConfigured = true; memos = memoResult.value }
            is MemoResult.Err -> {
                isConfigured = memoResult.code != ErrorCode.NOT_CONFIGURED
                memos = emptyList()
            }
        }

        val events: List<EventEntity> = if (isConfigured) {
            withTimeoutOrNull(2_000) {
                eventRepo.observeBetween(dayStart, dayEnd).first()
            } ?: emptyList()
        } else {
            emptyList()
        }

        provideContent {
            TodayWidgetContent(
                isConfigured = isConfigured,
                date = today,
                events = events,
                memos = memos,
            )
        }
    }
}
