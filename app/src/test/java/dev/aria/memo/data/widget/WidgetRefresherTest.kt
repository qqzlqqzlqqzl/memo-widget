package dev.aria.memo.data.widget

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 纯 JVM 测试：验证 [WidgetRefresher] 的行为承诺（Fix-1 版本）。
 *
 * 覆盖的承诺：
 *  1. `refreshAllNow` 同时调两个 widget 的 `updateAll`（MemoWidget + TodayWidget）。
 *  2. 任何一次 `updateAll` 抛异常都**不传播**到调用方（runCatching 吞掉）。
 *  3. `refreshAll` 走 MutableSharedFlow + Flow.debounce 管道：连续 N 次 emit
 *     在 400ms 窗口内合并为 1 次 updater 触发（C1 核心修复点）。
 *  4. 窗口过期后再 emit 能再触发一次（debounce 不是永久合并）。
 *  5. 两轮刷新相隔足够时间，两次都会触发（验证 pipeline 可以复用）。
 *  6. `refreshAllNow` 独立于 pipeline，立即触发且不被 debounce 影响。
 *  7. 10 次突发 emit 仍然只产出 1 次合并刷新（buffer DROP_OLDEST 下不阻塞）。
 *
 * 实现细节：
 *  - 把 [WidgetRefresher.updater] 替换成 [FakeWidgetUpdater] 就不需要 Glance /
 *    Robolectric —— [WidgetUpdater] 这一层抽象就是为了让这些纯 JVM 断言能跑。
 *  - 用 [WidgetRefresher.overrideScopeForTest] 把 collector pipeline 挂到
 *    StandardTestDispatcher(testScheduler) 上，`advanceTimeBy(DEBOUNCE_MS + ε)`
 *    能精确驱动 Flow.debounce 的时间窗。测试完用 [WidgetRefresher.resetScopeForTest]
 *    恢复生产 scope —— 单例状态不会污染后续测试。
 *  - Context 全部传 null —— fake updater 不看 context。
 *  - **重要**：override 后 collector coroutine 是 `launch {}` 排队在 StandardTest-
 *    Dispatcher 上的，还没真正开始 collect SharedFlow。必须先 `runCurrent()`（或
 *    `advanceUntilIdle()`）让它订阅，然后再 emit —— 否则早期 emit 落在没订阅者
 *    的 SharedFlow 上被 drop。helper 方法 [bindTestScope] 把这个步骤打包了。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WidgetRefresherTest {

    /** 测试开始前保存原 updater，测试结束恢复 —— object 是单例，状态会串起来污染其他测试。 */
    private lateinit var originalUpdater: WidgetUpdater

    @Before
    fun setUp() {
        originalUpdater = WidgetRefresher.updater
    }

    @After
    fun tearDown() {
        WidgetRefresher.updater = originalUpdater
        // 恢复默认 scope，避免测试用的 TestScope 污染后续测试 / 被 runTest 清掉后
        // 留下死的 collector。
        WidgetRefresher.resetScopeForTest()
    }

    @Test
    fun `refreshAllNow invokes both widget updaters exactly once`() = runTest {
        val fake = FakeWidgetUpdater()
        WidgetRefresher.updater = fake

        WidgetRefresher.refreshAllNow(context = null)

        assertEquals("MemoWidget.updateAll must be called once", 1, fake.memoCalls.get())
        assertEquals("TodayWidget.updateAll must be called once", 1, fake.todayCalls.get())
    }

    @Test
    fun `refreshAllNow swallows exceptions from memo updater`() = runTest {
        // 如果第一个 updater 抛了，仍要继续调第二个，且调用方不看到异常。
        val fake = FakeWidgetUpdater(memoThrows = true)
        WidgetRefresher.updater = fake

        // 不加 try/catch — 如果 runCatching 失效这里会崩。
        WidgetRefresher.refreshAllNow(context = null)

        assertEquals("MemoWidget.updateAll was attempted", 1, fake.memoCalls.get())
        assertEquals(
            "TodayWidget.updateAll must still run even when MemoWidget threw",
            1,
            fake.todayCalls.get(),
        )
    }

    @Test
    fun `refreshAllNow swallows exceptions from today updater`() = runTest {
        val fake = FakeWidgetUpdater(todayThrows = true)
        WidgetRefresher.updater = fake

        WidgetRefresher.refreshAllNow(context = null)

        assertEquals(1, fake.memoCalls.get())
        assertEquals(1, fake.todayCalls.get())
    }

    @Test
    fun `refreshAllNow swallows exceptions from both updaters`() = runTest {
        val fake = FakeWidgetUpdater(memoThrows = true, todayThrows = true)
        WidgetRefresher.updater = fake

        // 不应该传播任何异常 —— 写路径绝对不能被 widget 拖下水。
        WidgetRefresher.refreshAllNow(context = null)

        assertEquals(1, fake.memoCalls.get())
        assertEquals(1, fake.todayCalls.get())
    }

    @Test
    fun `refreshAll debounces rapid 3 calls into a single update`() = runTest {
        val fake = FakeWidgetUpdater()
        WidgetRefresher.updater = fake
        bindTestScope()

        // 连发 3 次：模拟用户快速连点保存 / Push 和 Pull Worker 同一时刻完成。
        WidgetRefresher.refreshAll(context = null)
        WidgetRefresher.refreshAll(context = null)
        WidgetRefresher.refreshAll(context = null)

        // 刚调完还没到 debounce 窗口，不应该有任何 update 发出。
        advanceTimeBy(WidgetRefresher.DEBOUNCE_MS - 50)
        runCurrent()
        assertEquals("no update yet — still within debounce window", 0, fake.memoCalls.get())
        assertEquals(0, fake.todayCalls.get())

        // 推进虚拟时间跨过 debounce 窗口 + 一点余量。
        advanceTimeBy(100)
        advanceUntilIdle()

        assertEquals(
            "rapid-fire refreshAll must collapse to one real update",
            1,
            fake.memoCalls.get(),
        )
        assertEquals(1, fake.todayCalls.get())
    }

    /**
     * 【新增 #1】10 次突发 emit 在 100ms 内：buffer DROP_OLDEST 不阻塞，最终
     * 合并为一次 updateMemo + 一次 updateToday。
     */
    @Test
    fun `10 rapid emits in 100ms collapse to a single update`() = runTest {
        val fake = FakeWidgetUpdater()
        WidgetRefresher.updater = fake
        bindTestScope()

        // 10 次 emit，每次间隔 10ms，总时间 ~100ms — 远短于 DEBOUNCE_MS (400ms)。
        repeat(10) {
            WidgetRefresher.refreshAll(context = null)
            advanceTimeBy(10)
        }
        runCurrent()

        // 最后一次 emit 在 t=90ms，debounce 窗口 400ms 要到 t=490ms 才关闭。
        // 当前虚拟时间 ≈ 100ms，离窗口关闭还差 390ms，不应该触发。
        assertEquals(0, fake.memoCalls.get())
        assertEquals(0, fake.todayCalls.get())

        // 过完 debounce 窗口。
        advanceTimeBy(WidgetRefresher.DEBOUNCE_MS + 50)
        advanceUntilIdle()

        assertEquals(
            "10 storm emits must collapse to exactly 1 memo update",
            1,
            fake.memoCalls.get(),
        )
        assertEquals(
            "10 storm emits must collapse to exactly 1 today update",
            1,
            fake.todayCalls.get(),
        )
    }

    /**
     * 【新增 #2】emit A (t=0)，等到 debounce 窗口过后再 emit B：两次分别触发。
     * 验证 debounce 窗口真的"过期"了而不是一直累积。
     */
    @Test
    fun `two emits separated by debounce window trigger two updates`() = runTest {
        val fake = FakeWidgetUpdater()
        WidgetRefresher.updater = fake
        bindTestScope()

        // t=0：第一次 emit
        WidgetRefresher.refreshAll(context = null)

        // 过完第一个 debounce 窗口 —— 第一次刷新应该跑了。
        advanceTimeBy(WidgetRefresher.DEBOUNCE_MS + 50)
        advanceUntilIdle()
        assertEquals("first emit fired after debounce", 1, fake.memoCalls.get())
        assertEquals(1, fake.todayCalls.get())

        // t ≈ DEBOUNCE_MS + 50：第二次 emit（间隔 > DEBOUNCE_MS，和第一次不合并）
        WidgetRefresher.refreshAll(context = null)
        advanceTimeBy(WidgetRefresher.DEBOUNCE_MS + 50)
        advanceUntilIdle()

        assertEquals(
            "second emit after window expiry fires independently",
            2,
            fake.memoCalls.get(),
        )
        assertEquals(2, fake.todayCalls.get())
    }

    /**
     * 【新增 #3】refreshAllNow 不经过 debounce pipeline —— 不需要
     * advanceTimeBy 就立即触发 updater，且独立于 refreshAll 的 Flow 管道。
     */
    @Test
    fun `refreshAllNow bypasses debounce pipeline`() = runTest {
        val fake = FakeWidgetUpdater()
        WidgetRefresher.updater = fake
        bindTestScope()

        // 直接调 Now —— 不走 Flow，不等 debounce。
        WidgetRefresher.refreshAllNow(context = null)

        // 没有 advanceTimeBy，立即可见 updater 被调。
        assertEquals("Now is immediate, no debounce needed", 1, fake.memoCalls.get())
        assertEquals(1, fake.todayCalls.get())
    }

    /**
     * 【新增 #4】emit 1 次 → 等窗口过完 → 再 emit 1 次 → 等窗口过完：
     * 共 2 次触发 update，验证"debounce 过期后下一批 emit 仍能独立触发"，
     * 即 Flow pipeline 长期可用、不会"用一次就死"。
     */
    @Test
    fun `expired debounce cycle allows next batch to trigger update`() = runTest {
        val fake = FakeWidgetUpdater()
        WidgetRefresher.updater = fake
        bindTestScope()

        // 第一批：1 条 emit。
        WidgetRefresher.refreshAll(context = null)
        advanceTimeBy(WidgetRefresher.DEBOUNCE_MS + 50)
        advanceUntilIdle()
        assertEquals(1, fake.memoCalls.get())
        assertEquals(1, fake.todayCalls.get())

        // 第二批：再 1 条 emit（pipeline 继续工作）。
        WidgetRefresher.refreshAll(context = null)
        advanceTimeBy(WidgetRefresher.DEBOUNCE_MS + 50)
        advanceUntilIdle()

        assertEquals(
            "pipeline remains active across debounce cycles",
            2,
            fake.memoCalls.get(),
        )
        assertEquals(2, fake.todayCalls.get())
    }

    /**
     * 验证 refreshAllNow 不会干扰 pipeline 里已经 emit 的 pending 请求 ——
     * Now 独立跑一次，pipeline 该刷新还是会刷新（累加）。
     *
     * 这是有意的设计：见 WidgetRefresher.refreshAllNow 的 KDoc。
     */
    @Test
    fun `refreshAllNow does not cancel pending debounced refresh`() = runTest {
        val fake = FakeWidgetUpdater()
        WidgetRefresher.updater = fake
        bindTestScope()

        WidgetRefresher.refreshAll(context = null)      // emit 排队
        WidgetRefresher.refreshAllNow(context = null)   // 立即跑一次（独立）
        assertEquals("refreshAllNow triggered immediately", 1, fake.memoCalls.get())
        assertEquals(1, fake.todayCalls.get())

        // 过完 debounce 窗口 —— pipeline 里的 emit 应该也会跑一次（不被 Now 取消）。
        advanceTimeBy(WidgetRefresher.DEBOUNCE_MS + 50)
        advanceUntilIdle()
        assertEquals(
            "pending debounced refresh fires independently of Now",
            2,
            fake.memoCalls.get(),
        )
        assertEquals(2, fake.todayCalls.get())
    }

    /**
     * 绑定 [WidgetRefresher] 的 collector pipeline 到当前 runTest 的虚拟时钟
     * scheduler，并确保 collector 已经启动（订阅了 SharedFlow）。
     *
     * 为什么要 `advanceUntilIdle()`：`overrideScopeForTest` 内部是 `scope.launch {
     * refreshRequests.debounce(...).collect {...} }`。在 StandardTestDispatcher
     * 上 launch 的协程**不会立刻执行 body**，而是排队等调度。我们需要先让调度器
     * 跑一轮，collector 真正进入 `collect`，才算订阅了 SharedFlow。否则之后的
     * `refreshAll(...) → tryEmit` 没有订阅者，emit 被 drop（replay=0，
     * extraBufferCapacity 只服务 slow subscribers，不是 "no subscribers"）。
     */
    private fun TestScope.bindTestScope() {
        WidgetRefresher.overrideScopeForTest(
            CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
        )
        testScheduler.advanceUntilIdle()
    }
}

/**
 * 可配置是否抛异常的 [WidgetUpdater] 测试替身。计数器用 AtomicInteger 防止
 * 协程并发写同一 Int。
 */
private class FakeWidgetUpdater(
    private val memoThrows: Boolean = false,
    private val todayThrows: Boolean = false,
) : WidgetUpdater {
    val memoCalls = AtomicInteger(0)
    val todayCalls = AtomicInteger(0)

    override suspend fun updateMemo(context: Context?) {
        memoCalls.incrementAndGet()
        if (memoThrows) throw IllegalStateException("memo updater boom (test)")
    }

    override suspend fun updateToday(context: Context?) {
        todayCalls.incrementAndGet()
        if (todayThrows) throw IllegalStateException("today updater boom (test)")
    }
}
