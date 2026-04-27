package dev.aria.memo.data.widget

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.glance.appwidget.updateAll
import dev.aria.memo.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * 数据写路径 → widget UI 的"自推刷新"通道（P8，Fix-1 加固版）。
 *
 * 设计目标：
 *  - 任何一条笔记 CRUD / 同步 Worker 完成后，都调用 [refreshAll]，由 Glance
 *    把两个 widget (`MemoWidget` + `TodayWidget`) 重新渲染一次，**完全不再依赖**
 *    `updatePeriodMillis` 的 30 分钟系统 tick。
 *  - 写路径是业务主线，绝不能被 widget 刷新失败带走。两个 `updateAll`
 *    各自用 [runCatching] 包一层，吞掉 `GlanceId not found`、Widget 未添加到桌面
 *    等常见 recoverable 异常；异常场景当作 no-op 处理。
 *  - 单元测试场景（没有真实 Context / Glance 环境）也能直接调用而不崩 —
 *    把默认的 [WidgetUpdater] 替换成 fake 即可覆盖 "updateAll 被调了几次"、
 *    "updateAll 抛了异常是否传播" 等断言。
 *
 * 并发模型（Fix-1 重构）：
 *  - 【C1 修复】debounce 不再用 `pendingJob?.cancel() + launch { delay }` 这种非原子
 *    read-modify-write。多个线程（PushWorker + PullWorker + UI 保存）并发进入时，
 *    cancel / write 两步之间的窗口会让 debounce "漏合并"，极端情况下 in-flight
 *    的 Glance session 被半途 cancel，产出 partial-state widget。
 *  - 改用 [MutableSharedFlow] + [kotlinx.coroutines.flow.debounce] 的 Flow 管道：
 *    每次 `refreshAll` 只调 `tryEmit(Unit)`，线程安全；`debounce(DEBOUNCE_MS)` 在
 *    Flow 层面做时间窗合并，不需要我们自己管 Job 生命周期。
 *  - buffer = 16, `DROP_OLDEST`：突发写入风暴下不会阻塞调用方；超过 16 条
 *    pending emit 时旧的被丢弃（反正 debounce 只看"最后一次"，丢旧的正合语义）。
 *  - scope 使用 [SupervisorJob] —— 一次 updateAll 抛异常不会让整条 pipeline 挂。
 *
 * 测试：
 *  - `refreshAllNow` 是 suspend 版，用于测试 / 极少数需要严格时序的调用点。
 *    它**不经过 Flow 管道、不 debounce**，调用即刻执行 updater。
 *  - 测试需要用虚拟时钟驱动 debounce 时，用 [overrideScopeForTest] 把 scope
 *    换成绑定 testScheduler 的 TestScope，collector pipeline 会被重启到新
 *    scope 上，`advanceTimeBy(DEBOUNCE_MS + ε)` 能精确触发一次合并刷新。
 */
object WidgetRefresher {

    /**
     * Debounce 等待窗口。选 400ms 的理由（Agent 1 / P8 研究建议 + Red-3 review）：
     *  - 低于 300ms 用户能感知延迟（打开编辑器 → 回桌面典型 ~500ms），窗口不够
     *    吸收 "保存 → 导航回 → 另一个 observer 又写一次" 这种真实写入风暴。
     *  - 高于 500ms 显得"呆"，用户体感 widget 没及时跟上。
     *  - 400ms 是安全区。Push + Pull Worker 并发完成、用户快速 CRUD 都能被
     *    这个窗口合并到一次真正的 Glance `updateAll`。
     *  - 之前的 250ms 在 cancel-based 实现里就不够稳，切到 Flow-debounce 后
     *    语义是 "最后一次 emit 起算再等 400ms"，和 sliding window 一致。
     */
    internal const val DEBOUNCE_MS: Long = 400L

    /**
     * 刷新请求管道。每次 [refreshAll] 只做 `tryEmit(Unit)`，轻量且线程安全。
     *
     *  - `extraBufferCapacity = 16`：突发 10+ 次写入不会因为 suspend 阻塞调用方。
     *    debounce 只保留最后一次，所以 buffer 不需要很大。
     *  - `onBufferOverflow = DROP_OLDEST`：即使 buffer 满了也不阻塞；反正中间
     *    那些 emit 会被 debounce 吃掉，丢掉也无损最终刷新状态。
     *  - `replay = 0`（默认）：订阅方不需要看到历史 emit，只关心"有新请求来"。
     */
    private val refreshRequests = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * 可替换的刷新执行器。生产环境用 [GlanceWidgetUpdater] 调真实 Glance API；
     * 测试里替换成 fake 就能避开 Android / Glance 初始化。`@Volatile` 防止
     * 测试线程刚写入、生产线程读到旧引用。
     *
     * **测试 hook（Review-Y #9 / Fix-Y2）**：标 `@VisibleForTesting` 让 lint
     * 在生产代码里写入时报警。真实生产路径**只读**这个字段，写入只发生在
     * `app/src/test/` 下的 `*Test.kt` 的 `@Before` / `@After` 钩子里。Release
     * APK 仍然可被反射改写（无法在 field-level 加 BuildConfig.DEBUG 守护，
     * 否则测试会无法 setter），但配合 `@VisibleForTesting` 的 lint 报警 +
     * `@Suppress` 显式标注，已经把意外滥用的概率降到最低；运行时守护体现在
     * [overrideScopeForTest] / [resetScopeForTest] 上 —— 反射改 updater 仍
     * 改不动 `scope` 上的 collector pipeline。
     */
    @VisibleForTesting
    @Suppress("MemberVisibilityCanBePrivate")
    @Volatile internal var updater: WidgetUpdater = GlanceWidgetUpdater

    /**
     * 最后一次见到的 applicationContext。由 [refreshAll] / [refreshAllNow] 更新。
     *
     * 为什么不把 context 塞进 Flow 的 emit：Flow 元素带着 Context 引用会拖长
     * GC 生命周期；而且真正执行 `updateAll` 的时候用的就是"应用级单例 Context"，
     * 任何调用者传进来的都等价。单独拿一个 `@Volatile` 字段缓存最新的即可。
     */
    @Volatile private var lastContext: Context? = null

    /**
     * 当前正在运行 debounce+collect 的 Job。只由 [startCollector] 写，
     * [overrideScopeForTest] 调用前会把它 cancel 掉。
     */
    @Volatile private var collectorJob: Job? = null

    /**
     * 运行 pipeline 的 scope。生产环境默认 SupervisorJob + Default；
     * 测试用 [overrideScopeForTest] 替换成绑定虚拟时钟的 TestScope。
     *
     * 字段本身就是 `private`，外部既读不了也写不了；唯一的写入路径是
     * [overrideScopeForTest] / [resetScopeForTest]，那两个方法在 release
     * build 里会 throw（见运行时守护）。
     */
    @Volatile private var scope: CoroutineScope = defaultScope()

    init {
        // 启动默认 collector pipeline。即便进程启动后从来没人调 refreshAll，
        // 这条协程也只挂在 SharedFlow 上等 emit，基本零开销。
        startCollector()
    }

    private fun defaultScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 在给定 scope 上启动 refreshRequests → debounce → updateAll 管道。
     *
     * 在 [overrideScopeForTest] 中被重新调用以把 collector 挂到测试 scope。
     */
    @OptIn(FlowPreview::class)
    private fun startCollector() {
        collectorJob?.cancel()
        collectorJob = scope.launch {
            refreshRequests
                .debounce(DEBOUNCE_MS)
                .collect {
                    val ctx = lastContext
                    // ctx 为 null 时仍然走一次 updater（fake 允许 null；生产
                    // GlanceWidgetUpdater 见 null 会提前返回）。保持与 refreshAllNow
                    // 对称的容错语义。
                    runCatching { updater.updateMemo(ctx) }
                    runCatching { updater.updateToday(ctx) }
                }
        }
    }

    /**
     * Fire-and-forget 刷新两个 widget。线程安全；写路径调用后立刻 return，
     * 实际的 Glance `updateAll` 在 [scope] 里异步、debounce 后执行。
     *
     * 调用语义：
     *  - 连续多次调用在 [DEBOUNCE_MS] 窗口内合并为一次真正的 updater 触发。
     *  - 任何 Glance 异常都被 [runCatching] 吞掉，不会传播到写路径。
     *  - 单元测试想要严格断言"调了几次 updateAll"时请用 [refreshAllNow]。
     *
     * context 允许 null 是为了纯-JVM 单元测试里传 null 不崩 —— 生产调用点始终
     * 传 ServiceLocator.appContext 或 Worker.applicationContext，非 null。
     */
    fun refreshAll(context: Context?) {
        // 取 applicationContext 是为了避免 Activity / Service 被回收后
        // 残留的 Context 引用（Glance updateAll 会用它拿 AppWidgetManager）。
        lastContext = context?.applicationContext
        // tryEmit 在 SharedFlow 有足够 buffer 或 DROP_OLDEST 策略下永远不会阻塞。
        // 若 buffer 满了会 drop 最旧的 emit —— debounce 只关心最后一次，无损。
        refreshRequests.tryEmit(Unit)
    }

    /**
     * 阻塞版刷新，用于测试 / 需要保证刷新完成后再继续的调用点。
     * **不 debounce**，不经过 Flow pipeline —— 立即执行两个 widget 的 updateAll。
     * 仍然用 runCatching 吞异常，保持 "写路径绝不被拖下水" 的语义。
     *
     * 注意：这个版本不会取消 pipeline 里已经 emit 但还在 debounce 窗口里的请求
     * —— 400ms 后那次合并刷新**仍然会再跑一次**。刻意保留这个行为：调用 Now
     * 说明 caller 现在要结果，但后续写路径的请求也可能需要最新数据，pipeline
     * 再刷一次是幂等的（Glance updateAll 对"无变化"会很快退出）。
     */
    suspend fun refreshAllNow(context: Context?) {
        val appCtx = context?.applicationContext
        lastContext = appCtx
        runCatching { updater.updateMemo(appCtx) }
        runCatching { updater.updateToday(appCtx) }
    }

    /**
     * **测试专用**：把 pipeline 重新挂到调用方提供的 scope 上。
     *
     * 用法（在测试 `@Before` / 测试用例里调用）：
     * ```
     * WidgetRefresher.overrideScopeForTest(
     *     CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
     * )
     * ```
     * 然后 `advanceTimeBy(DEBOUNCE_MS + 50)` 能精确触发合并刷新。
     *
     * 测试 `@After` 必须调用 [resetScopeForTest] 恢复生产 scope，否则单例
     * 状态会污染后续测试。
     *
     * **运行时守护（Review-Y #9 / Fix-Y2）**：release APK 里反射调到这个
     * 方法会立刻 throw [IllegalStateException]。debug build (`BuildConfig.DEBUG
     * == true`) 才能正常替换 scope。这堵住了"反射改 collector pipeline 让
     * widget 刷新挂在攻击者控制的 scope 上"这条 vector。
     */
    @VisibleForTesting
    internal fun overrideScopeForTest(newScope: CoroutineScope) {
        check(BuildConfig.DEBUG) {
            "WidgetRefresher.overrideScopeForTest() called in non-debug build"
        }
        collectorJob?.cancel()
        scope = newScope
        startCollector()
    }

    /**
     * **测试专用**：恢复成默认的 SupervisorJob + Dispatchers.Default scope，
     * 并重启 collector pipeline。测试 `@After` 里必调。
     *
     * **运行时守护（Review-Y #9 / Fix-Y2）**：和 [overrideScopeForTest] 同样的
     * BuildConfig.DEBUG 检查 —— release APK 反射触发会 throw [IllegalStateException]。
     */
    @VisibleForTesting
    internal fun resetScopeForTest() {
        check(BuildConfig.DEBUG) {
            "WidgetRefresher.resetScopeForTest() called in non-debug build"
        }
        collectorJob?.cancel()
        scope = defaultScope()
        startCollector()
    }
}

/**
 * "刷哪些 widget" 的抽象接口。抽出来的唯一目的是让 [WidgetRefresher] 能在
 * pure-JVM 单元测试里替换成 fake —— Glance `updateAll` 本身是 suspend + 需要
 * 真实 Android Context / AppWidgetManager，在 JVM test 里直接调会 NPE。
 *
 * 注意：生产代码只有一个实现 [GlanceWidgetUpdater]。这里没做成 DI 是因为
 * ServiceLocator 的风格就是 object + 手工注入，加一层 interface 够用了。
 *
 * [context] 允许为 null，专供单元测试里的 fake 实现（fake 不需要也用不到 Context）。
 * 生产路径 [GlanceWidgetUpdater] 会在 null 时提前返回 —— 这样才算 runCatching
 * 的"可恢复失败"语义的一部分。
 */
internal interface WidgetUpdater {
    /** 触发 `MemoWidget.updateAll(context)`，重绘最近笔记列表。 */
    suspend fun updateMemo(context: Context?)

    /** 触发 `TodayWidget.updateAll(context)`，重绘今天的 events + memos。 */
    suspend fun updateToday(context: Context?)
}

/**
 * 生产环境下的真实实现：直接调用 Glance 的 `updateAll`。每个 Widget 类都是
 * `GlanceAppWidget` 的子类，`updateAll(context)` 会让 Glance 遍历所有 GlanceId
 * 重新执行 `provideGlance`，从而拉最新的数据重绘。
 *
 * [context] 理论上生产路径永远非 null；null guard 只是兜底 —— 如果某个
 * 冷启动时序把 null 推到这里，我们 no-op 而不是 NPE。
 */
internal object GlanceWidgetUpdater : WidgetUpdater {
    override suspend fun updateMemo(context: Context?) {
        if (context == null) return
        dev.aria.memo.widget.MemoWidget().updateAll(context)
    }

    override suspend fun updateToday(context: Context?) {
        if (context == null) return
        dev.aria.memo.widget.TodayWidget().updateAll(context)
    }
}
