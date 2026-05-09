package dev.aria.memo.widget

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import dev.aria.memo.data.MemoEntry
import dev.aria.memo.data.MemoRepository
import dev.aria.memo.data.MemoResult
import dev.aria.memo.data.ServiceLocator
import dev.aria.memo.data.local.EventEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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
 *
 * ## Zone 暴露（B17 → B21 契约）
 *
 * `clock.zone` 通过 [PreferencesGlanceStateDefinition] 写入 widget 的 DataStore
 * state（key: [PREF_KEY_ZONE_ID]）。TodayWidgetContent.EventLine 的 zone 消费
 * 由 **B21** 完成：读 `currentState<Preferences>()[TodayWidget.PREF_KEY_ZONE_ID]`，
 * fallback `ZoneId.systemDefault()`。
 */
class TodayWidget(private val clock: Clock = Clock.systemDefaultZone()) : GlanceAppWidget() {

    // Use PreferencesGlanceStateDefinition so we can write zone into widget state
    // via updateAppWidgetState and B21 can read it with currentState<Preferences>().
    override val stateDefinition = PreferencesGlanceStateDefinition

    override val sizeMode: SizeMode = SizeMode.Single

    companion object {
        /**
         * GlanceState Preferences key that carries the clock's ZoneId string
         * (e.g. "Asia/Shanghai") into the widget's DataStore-backed state.
         *
         * **B21 contract**: in TodayWidgetContent.EventLine call
         *   `currentState<Preferences>()[TodayWidget.PREF_KEY_ZONE_ID]`
         *   and resolve with `ZoneId.of(...)`, falling back to
         *   `ZoneId.systemDefault()` when the key is absent.
         */
        val PREF_KEY_ZONE_ID = stringPreferencesKey("today_widget_zone_id")
    }

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

        // B17 (zone injection): write clock.zone into GlanceState so TodayWidgetContent
        // can read it via currentState<Preferences>()[PREF_KEY_ZONE_ID] (B21 consumes).
        // Done before provideContent so the state is available on the first render.
        updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { prefs ->
            prefs.toMutablePreferences().apply { set(PREF_KEY_ZONE_ID, zone.id) }
        }

        // Fixes #299 (Red-3 N3): short-circuit on isConfigured before
        // hitting recentEntries / observeAll / observeBetween. The old
        // path called recentEntries which itself looked up
        // settings.current() and got NOT_CONFIGURED back, then we'd
        // skip the rest — cheaper to read the gate once up front and
        // skip every Room read entirely.
        //
        // TODO(P9, mirrors B16/MemoWidget): promote IO entirely out of provideGlance —
        //  override update(), run settingsStore.current() + all Room reads there,
        //  write results into GlanceState, and have provideGlance only read currentState().
        val isConfigured = withTimeoutOrNull(1_000) {
            withContext(Dispatchers.IO) { ServiceLocator.settingsStore.current().isConfigured }
        } ?: false
        if (!isConfigured) {
            provideContent {
                TodayWidgetContent(
                    isConfigured = false,
                    date = today,
                    events = emptyList(),
                    memos = emptyList(),
                    zone = zone,
                )
            }
            return
        }

        // P8：把 memos 从 6 提到 20。LazyColumn 自己会滚，小 widget 仍能看前几条。
        //
        // Perf-fix C3（对 TodayWidget 的镜像处理）：`repo.recentEntries` 内部要先
        // settings.current()（虽然 C1 之后已 flowOn IO）+ 查 Room + parseEntries。
        // 冷路径叠加给 widget ANR 留的余量非常薄，这里也套一层 3s 保护；timeout
        // 发生时降级为空列表，下一轮 widget tick 再试。
        //
        // Fixes #331 (Agent 6 W-4): merge legacy day-file entries with
        // single-note rows that fall on today's date so single-note
        // writers don't see an empty TodayWidget.
        val memoResult = withTimeoutOrNull(3_000) { repo.recentEntries(limit = 20) }
        val legacyMemos: List<MemoEntry> = when (memoResult) {
            is MemoResult.Ok -> memoResult.value
            else -> emptyList()
        }
        val singleNoteMemos: List<MemoEntry> = withTimeoutOrNull(2_000) {
            ServiceLocator.singleNoteRepo.observeAll().first()
                .asSequence()
                .filter { it.date == today }
                .map { MemoEntry(date = it.date, time = it.time, body = it.body) }
                .toList()
        } ?: emptyList()
        val memos: List<MemoEntry> = (legacyMemos + singleNoteMemos)
            .sortedByDescending { it.time }
            .take(20)

        val events: List<EventEntity> = withTimeoutOrNull(2_000) {
            eventRepo.observeBetween(dayStart, dayEnd).first()
        } ?: emptyList()

        provideContent {
            TodayWidgetContent(
                isConfigured = isConfigured,
                date = today,
                events = events,
                memos = memos,
                zone = zone,
            )
        }
    }
}
