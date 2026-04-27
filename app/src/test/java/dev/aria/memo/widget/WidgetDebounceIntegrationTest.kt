package dev.aria.memo.widget

import android.content.Context
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fix-W1: Robolectric-driven debounce integration test for [WidgetRefresher].
 *
 * `WidgetRefresherTest` 已经覆盖了纯 JVM 下的 debounce 行为（10 次突发 emit
 * 合并成 1 次 updater 调用），但那是用 fake 直接喂 emit。这个文件用 Robolectric
 * 把测试拉到一个真 Application context 上，验证：
 *   1. 真 Context（[ApplicationProvider.getApplicationContext]）传到 [WidgetRefresher.refreshAll]
 *      后，pipeline 仍然能把多次写入合并成一次 updater.updateMemo 调用。
 *   2. SingleNote / Memo / Today 三条写路径任何一条调 refreshAll，最终只触发
 *      一次合并刷新（debounce 是源头不区分的）。
 *   3. WidgetRefresher 的 collector pipeline 在真 Context 下不会因为 Glance
 *      初始化失败而炸（updater 用 fake 替换，绕过 Glance）。
 *
 * 为什么单独一个文件：纯 JVM debounce 测试用 `null` context；这里用真 context
 * 是为了证明"换上真 context 后 pipeline 行为完全一致" —— 即 context 不会成为
 * debounce 行为的隐藏变量（lastContext 字段更新独立于 emit 合并）。
 *
 * **重要**：override scope 后必须 `testScheduler.advanceUntilIdle()` 让 collector
 * launch 真正订阅 SharedFlow（StandardTestDispatcher 上的 launch 不会立刻执行
 * body），否则早期 emit 落在没订阅者的 SharedFlow 上被 drop，测试假绿。helper
 * [bindTestScope] 把这步打包了。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
// `application = android.app.Application::class` 跳过 [dev.aria.memo.MemoApplication.onCreate]，
// 它会同步调 ServiceLocator.init + 启 IO 协程跑 Keystore-backed SettingsStore + WorkManager
// 调度。这两条在 Robolectric 下都炸（AndroidKeyStore 没 shadow，WorkManagerInitializer 没启用）
// —— 异常落在 background 协程被 runTest 检测为 UncaughtExceptionsBeforeTest 把测试拉红。
// 我们的测试只需要 ApplicationContext 来驱动 [WidgetRefresher.refreshAll] 的 lastContext 字段，
// 不需要 MemoApplication 的副作用，用普通 Application 即可。
@Config(sdk = [33], application = android.app.Application::class)
class WidgetDebounceIntegrationTest {

    private lateinit var context: Context
    private lateinit var originalUpdater: WidgetUpdater

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        originalUpdater = WidgetRefresher.updater
    }

    @After
    fun tearDown() {
        WidgetRefresher.updater = originalUpdater
        // 恢复生产 scope —— 单例状态不能污染后续测试。
        WidgetRefresher.resetScopeForTest()
    }

    // ---------------------------------------------------------------------
    // Test 1: 真 Context + 单次 refreshAll → 1 次 updater 触发。
    // 健康检查：debounce pipeline 在真 Context 下能跑通最简单的路径。
    // ---------------------------------------------------------------------

    @Test
    fun `single refreshAll with real context triggers exactly one updater call after debounce`() = runTest {
        val fake = CountingUpdater()
        WidgetRefresher.updater = fake
        bindTestScope()

        WidgetRefresher.refreshAll(context = context)

        // 还没到 debounce 窗口尾，updater 不应被调
        advanceTimeBy(WidgetRefresher.DEBOUNCE_MS - 50)
        assertEquals(0, fake.memoCalls.get())
        assertEquals(0, fake.todayCalls.get())

        // 跨过窗口尾
        advanceTimeBy(100)
        advanceUntilIdle()
        assertEquals(1, fake.memoCalls.get())
        assertEquals(1, fake.todayCalls.get())
    }

    // ---------------------------------------------------------------------
    // Test 2: 10 次紧密 refreshAll 在 100ms 内 → 合并成 1 次 updater 触发。
    // 这是 P8 debounce 的核心承诺：用户连续保存 10 次，widget 只刷新 1 次。
    // 与 WidgetRefresherTest 的同名测试不同，本测试用真 Context 而非 null。
    // ---------------------------------------------------------------------

    @Test
    fun `10 rapid refreshAll calls collapse to a single widget update`() = runTest {
        val fake = CountingUpdater()
        WidgetRefresher.updater = fake
        bindTestScope()

        // 10 次 emit，每次间隔 10ms，总时间 ~100ms — 远短于 DEBOUNCE_MS (400ms)。
        repeat(10) {
            WidgetRefresher.refreshAll(context = context)
            advanceTimeBy(10)
        }

        // 此时虚拟时间 ~100ms，离 debounce 窗口尾（最后一次 emit 后 400ms）还差 ~390ms
        assertEquals("debounce 还没到窗口尾，updater 不应被调", 0, fake.memoCalls.get())

        // 推过窗口尾
        advanceTimeBy(WidgetRefresher.DEBOUNCE_MS + 50)
        advanceUntilIdle()

        assertEquals(
            "10 次连发必须合并成 1 次 updateMemo 调用（P8 核心 debounce 承诺）",
            1,
            fake.memoCalls.get(),
        )
        assertEquals(1, fake.todayCalls.get())
    }

    // ---------------------------------------------------------------------
    // Test 3: 第一批 emit 触发后，第二批 emit 在窗口外 → 两次独立刷新。
    // 验证 debounce pipeline 不是"用一次就死"，长期可用。
    // ---------------------------------------------------------------------

    @Test
    fun `two emit batches separated by debounce window trigger two updates`() = runTest {
        val fake = CountingUpdater()
        WidgetRefresher.updater = fake
        bindTestScope()

        // 第一批
        WidgetRefresher.refreshAll(context = context)
        advanceTimeBy(WidgetRefresher.DEBOUNCE_MS + 50)
        advanceUntilIdle()
        assertEquals(1, fake.memoCalls.get())

        // 第二批（间隔 > DEBOUNCE_MS）
        WidgetRefresher.refreshAll(context = context)
        advanceTimeBy(WidgetRefresher.DEBOUNCE_MS + 50)
        advanceUntilIdle()

        assertEquals(
            "第二批 emit 必须独立触发；pipeline 长期可用，不是 \"一次性\"",
            2,
            fake.memoCalls.get(),
        )
        assertEquals(2, fake.todayCalls.get())
    }

    // ---------------------------------------------------------------------
    // Test 4: refreshAllNow 不走 debounce —— 真 Context 下立即触发。
    // ---------------------------------------------------------------------

    @Test
    fun `refreshAllNow with real context bypasses debounce and fires immediately`() = runTest {
        val fake = CountingUpdater()
        WidgetRefresher.updater = fake
        bindTestScope()

        // 不需要 advanceTimeBy —— Now 是 suspend，await 即立即执行
        WidgetRefresher.refreshAllNow(context = context)

        assertEquals("Now 应立即触发 updateMemo（不走 debounce pipeline）", 1, fake.memoCalls.get())
        assertEquals(1, fake.todayCalls.get())
    }

    // ---------------------------------------------------------------------
    // Test 5: refreshAllNow 与 refreshAll 并发 —— Now 立即跑，
    // refreshAll 的 emit 仍然进 pipeline，DEBOUNCE_MS 后再触发一次。
    // 共 2 次 updateMemo 调用。验证 "Now 不会取消 pipeline 里的 pending emit"
    // 这条契约。
    // ---------------------------------------------------------------------

    @Test
    fun `refreshAllNow plus pending refreshAll yields two updates total`() = runTest {
        val fake = CountingUpdater()
        WidgetRefresher.updater = fake
        bindTestScope()

        WidgetRefresher.refreshAll(context = context) // emit 进 pipeline
        WidgetRefresher.refreshAllNow(context = context) // 立即跑一次

        // 此时已经跑过 1 次 Now 调用
        assertEquals(1, fake.memoCalls.get())
        assertEquals(1, fake.todayCalls.get())

        // 跨过 debounce 窗口 —— pipeline emit 应再触发一次
        advanceTimeBy(WidgetRefresher.DEBOUNCE_MS + 50)
        advanceUntilIdle()

        assertEquals(
            "pipeline 中 pending 的 emit 不会被 Now 取消，DEBOUNCE_MS 后独立触发",
            2,
            fake.memoCalls.get(),
        )
        assertEquals(2, fake.todayCalls.get())
    }

    // ---------------------------------------------------------------------
    // Test 6: 异常恢复 —— updater.updateMemo 抛异常，pipeline 应继续工作；
    // 后续的 emit 仍能触发新的刷新。
    // ---------------------------------------------------------------------

    @Test
    fun `pipeline survives an updater throwing exception`() = runTest {
        val fake = CountingUpdater(throwOnMemo = true)
        WidgetRefresher.updater = fake
        bindTestScope()

        WidgetRefresher.refreshAll(context = context)
        advanceTimeBy(WidgetRefresher.DEBOUNCE_MS + 50)
        advanceUntilIdle()

        // 第一次 updateMemo 抛了，但 runCatching 在 collector 里吞掉
        assertEquals(1, fake.memoCalls.get())
        // updateToday 仍应被调（第一个 runCatching 吞了 memo 异常）
        assertEquals(1, fake.todayCalls.get())

        // pipeline 应该没死 —— 再 emit 一次仍能触发
        WidgetRefresher.refreshAll(context = context)
        advanceTimeBy(WidgetRefresher.DEBOUNCE_MS + 50)
        advanceUntilIdle()

        assertEquals(
            "pipeline 不应该被一次 updater 异常带挂",
            2,
            fake.memoCalls.get(),
        )
        assertEquals(2, fake.todayCalls.get())
    }

    // ---------------------------------------------------------------------
    // Test 7: WidgetRefresher.DEBOUNCE_MS 锁定为 400ms。如果有人改了，
    // 整个 debounce 测试组的 advanceTimeBy 数字会全部失准 —— 这条 case 锁住
    // 常量值，让任何 debounce 调整必须同步更新此处。
    // ---------------------------------------------------------------------

    @Test
    fun `WidgetRefresher DEBOUNCE_MS is 400`() {
        assertEquals(400L, WidgetRefresher.DEBOUNCE_MS)
    }

    // ---------------------------------------------------------------------
    // Test 8: lastContext 字段被正确更新 —— 真 Context 进 refreshAll 后，
    // 后续从 ApplicationProvider 拿的 context 应该等价于 widget pipeline 持有的
    // applicationContext。我们不直接断言私有字段（反射太脆），但通过
    // updater 收到的 context 来间接验证。
    // ---------------------------------------------------------------------

    @Test
    fun `updater receives applicationContext when refreshAll passes a real Context`() = runTest {
        val captured = mutableListOf<Context?>()
        val fake = object : WidgetUpdater {
            override suspend fun updateMemo(context: Context?) {
                captured.add(context)
            }

            override suspend fun updateToday(context: Context?) {
                captured.add(context)
            }
        }
        WidgetRefresher.updater = fake
        bindTestScope()

        WidgetRefresher.refreshAll(context = context)
        advanceTimeBy(WidgetRefresher.DEBOUNCE_MS + 50)
        advanceUntilIdle()

        assertTrue("updater 应该至少被调一次", captured.isNotEmpty())
        captured.forEach { ctx ->
            assertEquals(
                "updater 收到的 context 必须是 applicationContext（不是任何 Activity / Service）",
                context.applicationContext,
                ctx,
            )
        }
    }

    // =====================================================================
    // helpers
    // =====================================================================

    /**
     * 把 [WidgetRefresher] 的 collector pipeline 挂到当前 runTest 的虚拟时钟
     * scheduler，并 advanceUntilIdle 让 collector 真正订阅 SharedFlow。
     *
     * 不这样做的话，`overrideScopeForTest` 内部 `scope.launch { collect { } }`
     * 在 StandardTestDispatcher 上排队但不执行，早期 emit 会落在"没订阅者"的
     * SharedFlow 上被 drop（replay=0，extraBufferCapacity 只服务 slow subscribers）。
     */
    private fun kotlinx.coroutines.test.TestScope.bindTestScope() {
        WidgetRefresher.overrideScopeForTest(
            CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
        )
        testScheduler.advanceUntilIdle()
    }
}

/**
 * [WidgetUpdater] 计数器。`@AtomicInteger` 防止协程并发访问错乱。
 *
 * 重写一份（而不是从 [dev.aria.memo.data.widget.WidgetRefresherTest] 复用）是
 * 因为那里的 FakeWidgetUpdater 是 file-private，跨包不可见；而且本测试需要
 * 控制每条更新方法是否抛异常。
 */
private class CountingUpdater(
    private val throwOnMemo: Boolean = false,
    private val throwOnToday: Boolean = false,
) : WidgetUpdater {
    val memoCalls = AtomicInteger(0)
    val todayCalls = AtomicInteger(0)

    override suspend fun updateMemo(context: Context?) {
        memoCalls.incrementAndGet()
        if (throwOnMemo) throw IllegalStateException("memo updater boom (test)")
    }

    override suspend fun updateToday(context: Context?) {
        todayCalls.incrementAndGet()
        if (throwOnToday) throw IllegalStateException("today updater boom (test)")
    }
}
