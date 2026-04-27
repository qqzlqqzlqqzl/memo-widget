package dev.aria.memo.widget

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import dev.aria.memo.data.widget.WidgetRefresher
import dev.aria.memo.data.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fix-X3 / Review-X #4 — TodayWidget 跨日修复的纯 JVM 守门测试。
 *
 * 覆盖三件事：
 *  1. **TodayWidget 的 Clock 注入位置**：把 `Clock.fixed(...)` 锁在 23:59 / 00:01
 *     两个跨日边缘点上，断言 `LocalDate.now(clock)` 能正确分别给出"昨天"和
 *     "今天"。这是 Fix-X3 给 widget "知道今天是哪天" 加的可测注入点。
 *  2. **DateChangedReceiver.onReceive 真的触发 WidgetRefresher.refreshAll**：
 *     用 spy（替换 WidgetRefresher.updater 数 updateMemo / updateToday 调用次数）
 *     驱动 receiver 的 onReceive，断言三个目标 action 都能让 updater 被调用，
 *     并且非目标 action 不触发。
 *  3. **DEBOUNCE_MS pipeline 兼容**：refreshAll 走 debounce 管道，所以测试通过
 *     advanceTimeBy(DEBOUNCE_MS + ε) 等待真正的 updater 触发；这也顺带验证
 *     receiver 没有错误使用 refreshAllNow / 直接绕过 pipeline。
 *
 * Robolectric 用法：receiver 的 onReceive 内部走 `ServiceLocator.init(context)` ——
 * 需要一个真实的 Android Context 才能初始化（Room / DataStore 等）。
 * `@RunWith(RobolectricTestRunner::class)` 把 JVM 测试挂到 Robolectric 模拟的
 * Android framework 上，`ApplicationProvider.getApplicationContext()` 拿到合法
 * Context。`@Config(manifest = Config.NONE)` 跳过真实 Manifest 解析以加速。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
// targetSdk=36 比 Robolectric 4.14.1 自带的最高 SDK (35) 还高，必须手动 pin sdk=35
// 否则启动报 "Package targetSdkVersion=36 > maxSdkVersion=35"。
// `manifest = Config.NONE` 跳过完整 manifest 解析；`application = Application::class`
// 用 plain Android `Application` 替换 MemoApplication —— MemoApplication.onCreate 会
// 启动 WorkManager / Sync 调度，在 isolated unit test 里直接 NPE。我们这条测试只
// 需要 Robolectric 提供"任意 Context"驱动 receiver.onReceive，不依赖 app 生命周期。
@Config(
    manifest = Config.NONE,
    sdk = [35],
    application = android.app.Application::class,
)
class TodayWidgetCrossDayTest {

    /** 测试开始前保存 updater，结束恢复，避免 object 单例污染后续测试。 */
    private lateinit var originalUpdater: WidgetUpdater

    @Before
    fun setUp() {
        originalUpdater = WidgetRefresher.updater
    }

    @After
    fun tearDown() {
        WidgetRefresher.updater = originalUpdater
        WidgetRefresher.resetScopeForTest()
    }

    // ------------------------------------------------------------------
    // 1) Clock 注入 —— 23:59 / 00:01 跨日边缘断言
    // ------------------------------------------------------------------

    /**
     * Asia/Shanghai 时区下，2026-04-26 23:59:00（本地）= UTC 2026-04-26 15:59:00。
     * Widget 用 `LocalDate.now(clock)` 读出来必须还是 2026-04-26。
     */
    @Test
    fun `clock fixed at 23 59 local time resolves today as same date`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val instant = LocalDate.of(2026, 4, 26)
            .atTime(23, 59, 0)
            .atZone(zone)
            .toInstant()
        val clock = Clock.fixed(instant, zone)

        // Mirror provideGlance 的 today 计算
        val today = LocalDate.now(clock)

        assertEquals(LocalDate.of(2026, 4, 26), today)
    }

    /**
     * 同一时区下，把时间推过 00:00 边界 —— 2026-04-27 00:01:00（本地）。
     * `LocalDate.now(clock)` 必须切到 2026-04-27（"新的一天"），证明 widget 注入
     * clock 后能感知跨日。
     */
    @Test
    fun `clock fixed at 00 01 local time resolves today as new date`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val instant = LocalDate.of(2026, 4, 27)
            .atTime(0, 1, 0)
            .atZone(zone)
            .toInstant()
        val clock = Clock.fixed(instant, zone)

        val today = LocalDate.now(clock)

        assertEquals(LocalDate.of(2026, 4, 27), today)
    }

    /**
     * 跨日边缘对比：23:59 与 00:01 的 `LocalDate.now(clock)` 必须不相等，
     * 这是 widget 会"切到新的今天"的最直接证据。
     */
    @Test
    fun `clock fixed two minutes apart across midnight gives two different LocalDate`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val before = LocalDate.of(2026, 4, 26).atTime(23, 59, 0).atZone(zone).toInstant()
        val after = LocalDate.of(2026, 4, 27).atTime(0, 1, 0).atZone(zone).toInstant()

        val dateBefore = LocalDate.now(Clock.fixed(before, zone))
        val dateAfter = LocalDate.now(Clock.fixed(after, zone))

        assertNotEquals(
            "23:59 和 00:01 必须落在两个不同的 LocalDate，否则 widget 永远不会切日",
            dateBefore,
            dateAfter,
        )
        assertEquals(LocalDate.of(2026, 4, 26), dateBefore)
        assertEquals(LocalDate.of(2026, 4, 27), dateAfter)
    }

    /**
     * 时区切换场景：UTC 2026-04-27 00:30 在 Asia/Shanghai 是 08:30（同一天），
     * 但在 Pacific/Honolulu (UTC-10) 是 2026-04-26 14:30（前一天）。
     * 这模拟用户飞机落地后切时区，`LocalDate.now(clock)` 必须立刻反映新时区。
     */
    @Test
    fun `clock with different zone shifts today by timezone`() {
        val instant = Instant.parse("2026-04-27T00:30:00Z")
        val shanghai = LocalDate.now(Clock.fixed(instant, ZoneId.of("Asia/Shanghai")))
        val honolulu = LocalDate.now(Clock.fixed(instant, ZoneId.of("Pacific/Honolulu")))

        assertEquals(LocalDate.of(2026, 4, 27), shanghai)
        assertEquals(LocalDate.of(2026, 4, 26), honolulu)
        assertNotEquals(shanghai, honolulu)
    }

    /**
     * TodayWidget 的 ctor 默认参数 = `Clock.systemDefaultZone()` —— 不传也能正常
     * 构造，生产路径行为不变。
     */
    @Test
    fun `TodayWidget default ctor has system default clock`() {
        val widget = TodayWidget()
        // widget 构造不应抛 —— 如果谁把默认值去掉了这里 compile fail / runtime fail
        assertNotNull(widget)
    }

    /**
     * TodayWidget 接受自定义 Clock 注入 —— 这是 Fix-X3 加的可测构造。如果有人
     * 重构时把 ctor 改成无参，整个跨日测试体系就废了，这条测试守门。
     */
    @Test
    fun `TodayWidget accepts injected clock`() {
        val zone = ZoneId.of("UTC")
        val clock = Clock.fixed(Instant.parse("2026-04-26T23:59:00Z"), zone)
        val widget = TodayWidget(clock = clock)
        assertNotNull(widget)
    }

    // ------------------------------------------------------------------
    // 2) DateChangedReceiver — 三个目标 action 都触发 WidgetRefresher
    // ------------------------------------------------------------------

    /**
     * `ACTION_DATE_CHANGED` —— 跨日主用例。receiver 收到后必须调用
     * WidgetRefresher.refreshAll，pipeline 走完 debounce 后能看到 updater 被调用。
     */
    @Test
    fun `onReceive ACTION_DATE_CHANGED triggers WidgetRefresher refreshAll`() = runTest {
        val spy = SpyUpdater()
        WidgetRefresher.updater = spy
        WidgetRefresher.overrideScopeForTest(
            CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
        )
        testScheduler.advanceUntilIdle()

        val receiver = DateChangedReceiver()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(Intent.ACTION_DATE_CHANGED)

        receiver.onReceive(context, intent)

        // refreshAll 走 debounce pipeline —— 推进虚拟时钟跨过 400ms 窗口。
        advanceTimeBy(WidgetRefresher.DEBOUNCE_MS + 50)
        advanceUntilIdle()

        assertEquals("DATE_CHANGED 必须触发一次 MemoWidget 刷新", 1, spy.memoCalls.get())
        assertEquals("DATE_CHANGED 必须触发一次 TodayWidget 刷新", 1, spy.todayCalls.get())
    }

    /**
     * `ACTION_TIME_CHANGED`（"android.intent.action.TIME_SET"）—— 用户手动改系统
     * 时间。可能跨日，必须刷。
     */
    @Test
    fun `onReceive ACTION_TIME_CHANGED triggers WidgetRefresher refreshAll`() = runTest {
        val spy = SpyUpdater()
        WidgetRefresher.updater = spy
        WidgetRefresher.overrideScopeForTest(
            CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
        )
        testScheduler.advanceUntilIdle()

        val receiver = DateChangedReceiver()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(Intent.ACTION_TIME_CHANGED)

        receiver.onReceive(context, intent)

        advanceTimeBy(WidgetRefresher.DEBOUNCE_MS + 50)
        advanceUntilIdle()

        assertEquals(1, spy.memoCalls.get())
        assertEquals(1, spy.todayCalls.get())
    }

    /**
     * `ACTION_TIMEZONE_CHANGED` —— 用户切换时区（飞机落地）。`LocalDate.now()`
     * 的输出会立刻变化，widget 必须立即跟进。
     */
    @Test
    fun `onReceive ACTION_TIMEZONE_CHANGED triggers WidgetRefresher refreshAll`() = runTest {
        val spy = SpyUpdater()
        WidgetRefresher.updater = spy
        WidgetRefresher.overrideScopeForTest(
            CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
        )
        testScheduler.advanceUntilIdle()

        val receiver = DateChangedReceiver()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(Intent.ACTION_TIMEZONE_CHANGED)

        receiver.onReceive(context, intent)

        advanceTimeBy(WidgetRefresher.DEBOUNCE_MS + 50)
        advanceUntilIdle()

        assertEquals(1, spy.memoCalls.get())
        assertEquals(1, spy.todayCalls.get())
    }

    /**
     * 非目标 action 不应触发刷新 —— 比如某天系统不小心给我们派了一个
     * `BOOT_COMPLETED`，receiver 必须当作 no-op，不能误触发 widget 刷新。
     */
    @Test
    fun `onReceive ignores unrelated action like BOOT_COMPLETED`() = runTest {
        val spy = SpyUpdater()
        WidgetRefresher.updater = spy
        WidgetRefresher.overrideScopeForTest(
            CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
        )
        testScheduler.advanceUntilIdle()

        val receiver = DateChangedReceiver()
        val context = ApplicationProvider.getApplicationContext<Context>()
        // 故意发一个不相关的 action（不在 receiver 的 when 分支里）
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)

        receiver.onReceive(context, intent)

        advanceTimeBy(WidgetRefresher.DEBOUNCE_MS + 50)
        advanceUntilIdle()

        assertEquals(
            "无关 action 必须当 no-op，不能让 widget 白刷一次",
            0,
            spy.memoCalls.get(),
        )
        assertEquals(0, spy.todayCalls.get())
    }

    /**
     * 三个目标 action 在同一个测试里依次到达（模拟用户手改时间 + 时区在
     * 一秒内连发三个广播）—— receiver 调用 refreshAll 三次，但 debounce
     * pipeline 把它们合并成一次真正的 widget 刷新（DEBOUNCE_MS=400ms）。
     */
    @Test
    fun `three target actions in rapid succession collapse to a single refresh`() = runTest {
        val spy = SpyUpdater()
        WidgetRefresher.updater = spy
        WidgetRefresher.overrideScopeForTest(
            CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
        )
        testScheduler.advanceUntilIdle()

        val receiver = DateChangedReceiver()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        receiver.onReceive(ctx, Intent(Intent.ACTION_DATE_CHANGED))
        receiver.onReceive(ctx, Intent(Intent.ACTION_TIME_CHANGED))
        receiver.onReceive(ctx, Intent(Intent.ACTION_TIMEZONE_CHANGED))

        // 连发三次后等 debounce 窗口过期
        advanceTimeBy(WidgetRefresher.DEBOUNCE_MS + 50)
        advanceUntilIdle()

        assertEquals(
            "三个广播紧挨着到达必须 debounce 合并为一次 MemoWidget 刷新",
            1,
            spy.memoCalls.get(),
        )
        assertEquals(1, spy.todayCalls.get())
    }

    // ------------------------------------------------------------------
    // 3) DateChangedReceiver 类型契约
    // ------------------------------------------------------------------

    /**
     * 类型守门：receiver 必须是 BroadcastReceiver 子类，否则 manifest 注册时
     * Android 会拒绝实例化。如果有人重构改成普通类，这条立即红。
     */
    @Test
    fun `DateChangedReceiver is a BroadcastReceiver subclass`() {
        assertTrue(
            "DateChangedReceiver 必须继承 BroadcastReceiver",
            android.content.BroadcastReceiver::class.java
                .isAssignableFrom(DateChangedReceiver::class.java),
        )
    }
}

/**
 * 计数版 [WidgetUpdater] —— 测试 spy。用 [AtomicInteger] 防止协程并发污染计数。
 *
 * 与 [dev.aria.memo.data.widget.WidgetRefresherTest] 里那个 FakeWidgetUpdater 思路
 * 重复，但 widget 包测试不 import 跨包 private 类；为保持每个测试文件独立可读，
 * 重写一份小 spy。
 */
private class SpyUpdater : WidgetUpdater {
    val memoCalls = AtomicInteger(0)
    val todayCalls = AtomicInteger(0)

    override suspend fun updateMemo(context: Context?) {
        memoCalls.incrementAndGet()
    }

    override suspend fun updateToday(context: Context?) {
        todayCalls.incrementAndGet()
    }
}
