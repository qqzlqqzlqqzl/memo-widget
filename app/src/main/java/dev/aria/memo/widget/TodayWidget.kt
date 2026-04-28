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
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * Glance widget that renders today's events + memos in a scrollable list.
 *
 * Width: `resizable` so the launcher can flex it between 4x2 and 4x4. Rows
 * tap into [dev.aria.memo.MainActivity] (landing on the Notes tab); the
 * header "+" opens [dev.aria.memo.EditActivity] for a quick memo.
 *
 * ## 跨日（Fix-X3 / Review-X #4）
 *
 * `provideGlance` 内部用 `LocalDate.now(clock)` 决定"今天"。Widget 进程**不会**
 * 因为系统跨日（`ACTION_DATE_CHANGED`）自动重渲染 —— AppWidget 默认只听
 * `APPWIDGET_UPDATE`。Fix-X3 用配套的 [DateChangedReceiver] 监听
 * `DATE_CHANGED / TIME_SET / TIMEZONE_CHANGED` 三个广播，在跨日 / 时区切换的
 * 一瞬间调用 [dev.aria.memo.data.widget.WidgetRefresher.refreshAll]，把
 * MemoWidget + TodayWidget 的"今天"基线重新刷一次。
 *
 * `clock` 参数（默认 [Clock.systemDefaultZone]）是注入点 —— 单元测试可以传
 * `Clock.fixed(Instant.parse("2026-04-26T23:59:00Z"), ZoneId.of("Asia/Shanghai"))`
 * 锁定一个跨日边缘的时间点，断言 `LocalDate.now(clock)` 的输出。
 */
class TodayWidget(private val clock: Clock = Clock.systemDefaultZone()) : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        ServiceLocator.init(context)
        val repo = ServiceLocator.repository
        val eventRepo = ServiceLocator.eventRepo
        // Fix-X3: 用注入的 clock 决定"今天"，便于单元测试用 Clock.fixed(...) 锁定
        // 跨日边缘时间点。生产路径 clock = Clock.systemDefaultZone() 行为不变。
        val today = LocalDate.now(clock)
        val zone = clock.zone
        val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        // P8：把 memos 从 6 提到 20。LazyColumn 自己会滚，小 widget 仍能看前几条。
        //
        // Perf-fix C3（对 TodayWidget 的镜像处理）：`repo.recentEntries` 内部要先
        // settings.current()（虽然 C1 之后已 flowOn IO）+ 查 Room + parseEntries。
        // 冷路径叠加给 widget ANR 留的余量非常薄，这里也套一层 3s 保护；timeout
        // 发生时降级为"已配置 + 空列表"，下一轮 widget tick 再试。
        //
        // Fixes #331 (Agent 6 W-4): the previous query only read today's
        // legacy day-file (`config.filePathFor(today)`) and ignored
        // single-note rows that fall on today's date entirely. A user
        // who only writes single-notes saw an empty TodayWidget even
        // when they'd just saved a note. Merge both sources, filtered to
        // today's date, and sort newest-first.
        val memoResult = withTimeoutOrNull(3_000) { repo.recentEntries(limit = 20) }
        val isConfigured: Boolean
        val legacyMemos: List<MemoEntry>
        when (memoResult) {
            is MemoResult.Ok -> { isConfigured = true; legacyMemos = memoResult.value }
            is MemoResult.Err -> {
                isConfigured = memoResult.code != ErrorCode.NOT_CONFIGURED
                legacyMemos = emptyList()
            }
            null -> { isConfigured = true; legacyMemos = emptyList() }
        }
        val singleNoteMemos: List<MemoEntry> = if (isConfigured) {
            withTimeoutOrNull(2_000) {
                ServiceLocator.singleNoteRepo.observeAll().first()
                    .asSequence()
                    .filter { it.date == today }
                    .map { MemoEntry(date = it.date, time = it.time, body = it.body) }
                    .toList()
            } ?: emptyList()
        } else {
            emptyList()
        }
        val memos: List<MemoEntry> = (legacyMemos + singleNoteMemos)
            .sortedByDescending { it.time }
            .take(20)

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
