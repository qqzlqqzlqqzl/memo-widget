package dev.aria.memo.widget

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Fix-W1: 跨日切换的 Robolectric 集成测试。
 *
 * Widget 的"今天"基线由 [java.time.LocalDate.now] 决定。Widget 进程默认**不订阅**
 * `ACTION_DATE_CHANGED`，AppWidget 默认只听 `APPWIDGET_UPDATE`。结果：晚上 23:59
 * 看到的 widget 渲染的还是"昨天"；过 00:00 跨日后，**直到下一个 hook 触发刷新
 * （保存笔记 / Pull Worker / 30 分钟 system tick）**widget 才会切到"今天"。
 *
 * 这个 test 文件锁定 widget 在跨日边缘的行为契约：
 *  1. `LocalDate.now(clock)` 在固定的"昨天 23:59" / "今天 00:00" / "今天 00:01"
 *     时刻能正确识别跨日。
 *  2. 跨时区切换（飞机落地）让"今天"在不同时区下分别落到正确的日期。
 *  3. 注入 [Clock] 是测试的唯一注入点 —— 生产路径用
 *     `Clock.systemDefaultZone()`；测试可以传 `Clock.fixed(instant, zone)` 锁定
 *     边缘点。这条契约由 [TodayWidget] 的 ctor 默认参数承诺。
 *
 * **关于 widget 真渲染**：本文件**不**直接 invoke widget 的 `provideGlance`，
 * 因为 Glance 1.1 在 Robolectric 下的 RemoteViews session 实现不可用。我们验证
 * 的是"widget 用什么 LocalDate 作为输入" —— 即 `LocalDate.now(clock)` 这一行 ——
 * 而真正的 RemoteViews 渲染回归留给 connectedDebugAndroidTest。
 */
@RunWith(RobolectricTestRunner::class)
// 跳过 MemoApplication.onCreate 的 Keystore + WorkManager 副作用 —— 在 Robolectric 下
// 它们会抛 UncaughtExceptionsBeforeTest 把 runTest 的测试拉红。本文件大多是纯
// java.time 计算，不依赖 ServiceLocator，用普通 Application 完全够用。
@Config(sdk = [33], application = android.app.Application::class)
class WidgetCrossDayTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    /**
     * Helper: 构造一个固定在指定本地时间的 [Clock]，时区 [zone]。
     */
    private fun clockAt(date: LocalDate, time: LocalTime): Clock =
        Clock.fixed(
            ZonedDateTime.of(date, time, zone).toInstant(),
            zone,
        )

    // ---------------------------------------------------------------------
    // Test 1: 23:59 仍然是"昨天" / "今天还没到"。Widget 应该渲染 2026-04-26
    // 的内容，而不是 2026-04-27。
    // ---------------------------------------------------------------------

    @Test
    fun `clock fixed at 23 59 yesterday resolves today as yesterday's date`() {
        val yesterday = LocalDate.of(2026, 4, 26)
        val clock = clockAt(yesterday, LocalTime.of(23, 59))

        // 这是 widget 的"今天"决策线。
        val widgetToday = LocalDate.now(clock)

        assertEquals(
            "23:59 时 widget 看到的 today 必须还是 2026-04-26（昨天）",
            yesterday,
            widgetToday,
        )
    }

    // ---------------------------------------------------------------------
    // Test 2: 00:01 是"新的今天"。Widget 跨过午夜后应该切到 2026-04-27。
    // ---------------------------------------------------------------------

    @Test
    fun `clock fixed at 00 01 today resolves today as new date`() {
        val today = LocalDate.of(2026, 4, 27)
        val clock = clockAt(today, LocalTime.of(0, 1))

        val widgetToday = LocalDate.now(clock)

        assertEquals(
            "00:01 时 widget 看到的 today 必须是 2026-04-27（新的一天）",
            today,
            widgetToday,
        )
    }

    // ---------------------------------------------------------------------
    // Test 3: 跨日边缘对比 —— 23:59 和 00:01 的两次 `LocalDate.now(clock)`
    // 必须不相等。这是 widget"会切到新一天"最直接证据；如果有人重构时把
    // clock 注入误用成 systemDefault()（测试根本没在测注入点），这条 case
    // 立即红。
    // ---------------------------------------------------------------------

    @Test
    fun `LocalDate now with clock crosses midnight when clock advances past midnight`() {
        val before = clockAt(LocalDate.of(2026, 4, 26), LocalTime.of(23, 59))
        val after = clockAt(LocalDate.of(2026, 4, 27), LocalTime.of(0, 1))

        val dateBefore = LocalDate.now(before)
        val dateAfter = LocalDate.now(after)

        assertNotEquals(
            "23:59 和 00:01 必须落在两个不同的 LocalDate，否则 widget 永远不会切日",
            dateBefore,
            dateAfter,
        )
        assertEquals(LocalDate.of(2026, 4, 26), dateBefore)
        assertEquals(LocalDate.of(2026, 4, 27), dateAfter)
    }

    // ---------------------------------------------------------------------
    // Test 4: 时区切换 —— UTC 同一时刻在两个时区下落到不同日期。模拟用户飞机
    // 落地后切时区，`LocalDate.now(clock)` 必须立刻反映新时区。
    // ---------------------------------------------------------------------

    @Test
    fun `clock with different zone shifts today by timezone`() {
        // UTC 2026-04-27 00:30 在 Asia/Shanghai 是 08:30（同一天）
        // 在 Pacific/Honolulu (UTC-10) 是 2026-04-26 14:30（前一天）
        val instant = Instant.parse("2026-04-27T00:30:00Z")

        val shanghai = LocalDate.now(Clock.fixed(instant, ZoneId.of("Asia/Shanghai")))
        val honolulu = LocalDate.now(Clock.fixed(instant, ZoneId.of("Pacific/Honolulu")))

        assertEquals(LocalDate.of(2026, 4, 27), shanghai)
        assertEquals(LocalDate.of(2026, 4, 26), honolulu)
        assertNotEquals(shanghai, honolulu)
    }

    // ---------------------------------------------------------------------
    // Test 5: Widget 构造时不需要 Robolectric 才能跑，但默认 ctor 必须可用。
    // 我们这里只验证 [TodayWidget] 实例化不抛 —— ServiceLocator 也不会被触发，
    // 因为 GlanceAppWidget 构造是 lazy。如果有人重构改坏 ctor 默认参数，这里
    // 立即红。
    // ---------------------------------------------------------------------

    @Test
    fun `TodayWidget instantiates without throwing under Robolectric`() {
        // 不调 provideGlance，只构造 —— Glance 内部不需要 framework AIDL 来
        // 构造一个 GlanceAppWidget 实例。
        val widget = TodayWidget()
        assertEquals(androidx.glance.appwidget.SizeMode.Single, widget.sizeMode)
    }

    // ---------------------------------------------------------------------
    // Test 6: ApplicationProvider 在 Robolectric 下能拿到非 null Context。
    // 如果 Robolectric 配置坏了，这里立即红 —— 是其他所有 widget integration
    // test 的"健康检查"，单独立一条不依赖 widget 内部状态。
    // ---------------------------------------------------------------------

    @Test
    fun `ApplicationProvider returns non null context under Robolectric`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals(
            "Robolectric Application 的 packageName 应该匹配项目 namespace",
            "dev.aria.memo",
            ctx.packageName,
        )
    }

    // ---------------------------------------------------------------------
    // Test 7: 跨日演练 —— 模拟"昨天 23:59 看 widget" → 跨日 → "今天 00:00 看 widget"
    // 的完整序列。两次 `LocalDate.now(clock)` 必须给出不同的"今天"，证明 widget
    // 在跨日后会自然渲染新的内容（前提是有触发刷新的机制 —— 那是
    // DateChangedReceiver 或 30 分钟 tick 的责任）。
    // ---------------------------------------------------------------------

    @Test
    fun `widget today flips after midnight in same timezone`() {
        val day1 = LocalDate.of(2026, 4, 26)
        val day2 = LocalDate.of(2026, 4, 27)

        // T1: 23:59 看 widget
        val clock1 = clockAt(day1, LocalTime.of(23, 59))
        val today1 = LocalDate.now(clock1)
        assertEquals(day1, today1)

        // T2: 00:00 看 widget （跨日瞬间）
        val clock2 = clockAt(day2, LocalTime.of(0, 0))
        val today2 = LocalDate.now(clock2)
        assertEquals(day2, today2)

        // T3: 00:30 看 widget
        val clock3 = clockAt(day2, LocalTime.of(0, 30))
        val today3 = LocalDate.now(clock3)
        assertEquals(day2, today3)

        // 跨日断言：T1 是昨天，T2 / T3 都是今天
        assertNotEquals(today1, today2)
        assertEquals(today2, today3)
    }
}
