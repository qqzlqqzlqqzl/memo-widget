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

        // P8：把 memos 从 6 提到 20。LazyColumn 自己会滚，小 widget 仍能看前几条。
        //
        // Perf-fix C3（对 TodayWidget 的镜像处理）：`repo.recentEntries` 内部要先
        // settings.current()（虽然 C1 之后已 flowOn IO）+ 查 Room + parseEntries。
        // 冷路径叠加给 widget ANR 留的余量非常薄，这里也套一层 3s 保护；timeout
        // 发生时降级为"已配置 + 空列表"，下一轮 widget tick 再试。
        val memoResult = withTimeoutOrNull(3_000) { repo.recentEntries(limit = 20) }
        val isConfigured: Boolean
        val memos: List<MemoEntry>
        when (memoResult) {
            is MemoResult.Ok -> { isConfigured = true; memos = memoResult.value }
            is MemoResult.Err -> {
                isConfigured = memoResult.code != ErrorCode.NOT_CONFIGURED
                memos = emptyList()
            }
            null -> { isConfigured = true; memos = emptyList() }
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
