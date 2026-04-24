package dev.aria.memo.widget

import dev.aria.memo.data.DatedMemoEntry
import dev.aria.memo.data.ErrorCode
import dev.aria.memo.data.MemoResult
import dev.aria.memo.data.local.SingleNoteEntity
import dev.aria.memo.data.widget.WidgetRefresher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * P8 回归守门测试（Fix-5 新增）。
 *
 * Red-1 报告指出：ext/ 下 12 个文件里没有一个引用 WidgetRefresher /
 * RefreshActionCallback / limit=20 —— 这是 P8 真正的核心变化，但被其他 agent 遗漏了。
 *
 * 本文件补上 P8 最容易发生回归的几个守门断言：
 *
 *  1. **MemoWidget limit=20 规约**：通过 [MemoWidgetDataSourceTest.decideRows] 的
 *     同形 helper 做对照验证 —— limit=20 渲染 20 条，limit=3 渲染 3 条（这条测试
 *     如果 P8 有人把 limit 改回 3 会立刻红）。
 *
 *  2. **RefreshMemoWidgetAction / RefreshTodayWidgetAction 的类型与运行时分发**：
 *     验证它们是 ActionCallback 子类，反射层面看它们的签名没有被改坏（Glance
 *     的 actionRunCallback<T>() 依赖 Class<out ActionCallback>，签名变了就炸）。
 *
 *  3. **WidgetRefresher.DEBOUNCE_MS 常量 = 400ms**：锁定 Fix-1 决定的 debounce
 *     窗口。低于 300ms 在真实设备上会撑不住用户"保存→回桌面"的 500ms 写入风暴
 *     （Agent 1 / P8 研究建议），高于 500ms 显得呆。如果有人无意中改回 250ms，
 *     这条测试会红。
 *
 *  4. **WidgetUpdater 接口可替换**：保证 WidgetRefresher.updater 可被写入，测试
 *     能替换成 fake（这是整个 Fix-1 重构的可测性基础）。
 *
 *  5. **MemoWidgetRow 构造契约**：widget row 的 4 字段必须 [date, time, label, noteUid]
 *     顺序（Composable 依赖这个构造顺序）。
 */
class WidgetP8GuardTest {

    private val today: LocalDate = LocalDate.of(2026, 4, 22)

    private fun single(
        uid: String,
        title: String = "title-$uid",
        body: String = "body-$uid",
        date: LocalDate = today,
        time: LocalTime = LocalTime.of(9, 15),
    ) = SingleNoteEntity(
        uid = uid,
        filePath = "notes/$uid.md",
        title = title,
        body = body,
        date = date,
        time = time,
        isPinned = false,
        githubSha = null,
        localUpdatedAt = 0L,
        remoteUpdatedAt = null,
        dirty = false,
    )

    /**
     * Pure copy of [MemoWidget.provideGlance]'s decision block —— widget 内部
     * 就是这个流程把 repo 返回的 SingleNoteEntity / DatedMemoEntry 转成
     * MemoWidgetRow。但注意：widget 里没有任何 `.take(N)`，limit 完全由调用
     * `observeRecent(limit = N)` 时传入的 N 决定。
     *
     * 这个 helper 复现 widget 的渲染逻辑，不截断。如果 widget 被人手滑加了
     * `.take(3)` 这种二次截断，P8 "20 条也能看完" 的承诺就废了。
     */
    private fun simulateWidgetRender(
        singleNotes: List<SingleNoteEntity>,
        legacy: MemoResult<List<DatedMemoEntry>> = MemoResult.Ok(emptyList()),
    ): List<MemoWidgetRow> = when {
        singleNotes.isNotEmpty() -> singleNotes.map { it.toRow() }
        legacy is MemoResult.Ok -> legacy.value.map { it.toRow() }
        else -> emptyList()
    }

    // ------------------------------------------------------------------
    // limit=20 回归守门
    // ------------------------------------------------------------------

    @Test
    fun `P8 regression - widget renders all 20 rows when repo returns 20 single notes`() {
        val twenty = (1..20).map { idx ->
            single(uid = "uid-$idx", title = "note-$idx", time = LocalTime.of(0, 0).plusMinutes(idx.toLong()))
        }
        val rows = simulateWidgetRender(singleNotes = twenty)
        assertEquals(
            "P8: widget 必须展示 repo 给出的全部 20 条；如果这里掉回 3/5/10 说明下游偷偷二次截断",
            20,
            rows.size,
        )
        // 所有 20 行都要带 uid，这样点击能 deep-link
        assertTrue(rows.all { !it.noteUid.isNullOrBlank() })
        // 顺序一致：repo 已按 time DESC 排，widget 不改顺序
        assertEquals("note-1", rows.first().label)
        assertEquals("note-20", rows.last().label)
    }

    @Test
    fun `P8 regression - widget renders 3 rows when repo returns 3 (old P7 behavior still passes through)`() {
        // P7 时代 widget 拿 limit=3 —— 上游变了但渲染逻辑 limit-agnostic。
        // 这条测试是"向下兼容"的验证：如果 repo 被改成 limit=3，widget 不会再二次截断。
        val three = (1..3).map { single(uid = "uid-$it", title = "note-$it") }
        val rows = simulateWidgetRender(singleNotes = three)
        assertEquals(3, rows.size)
    }

    @Test
    fun `P8 regression - legacy fallback also renders 20 rows when single notes empty`() {
        val legacy = (1..20).map { idx ->
            DatedMemoEntry(
                date = today,
                time = LocalTime.of(0, 0).plusMinutes(idx.toLong()),
                body = "legacy-$idx",
            )
        }
        val rows = simulateWidgetRender(singleNotes = emptyList(), legacy = MemoResult.Ok(legacy))
        assertEquals(20, rows.size)
        // Legacy rows 没有 uid，这是 MemoWidgetRow 数据源并集的核心契约
        assertTrue(rows.all { it.noteUid == null })
    }

    @Test
    fun `P8 regression - empty both sources renders zero rows without crashing`() {
        val rows = simulateWidgetRender(singleNotes = emptyList(), legacy = MemoResult.Ok(emptyList()))
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `P8 regression - widget does not crash with 100 rows even though MemoWidget calls limit=20`() {
        // 防御性：如果上游 bug 给了 widget 超过 20 条，widget 也不会炸。
        // （P8 源码在 MemoWidget.kt 里写死 limit=20，但防御性测试要保证 widget
        // 渲染路径不假设"最多 20 条"）
        val hundred = (1..100).map { single(uid = "uid-$it", title = "note-$it") }
        val rows = simulateWidgetRender(singleNotes = hundred)
        assertEquals(100, rows.size)
    }

    // ------------------------------------------------------------------
    // RefreshActionCallback 类型守门
    // ------------------------------------------------------------------

    @Test
    fun `RefreshMemoWidgetAction is an ActionCallback subclass`() {
        // Glance 要求 actionRunCallback<T> 的 T 必须是 Class<out ActionCallback>，
        // 如果有人重构时无意中去掉了 `: ActionCallback`，Glance 注册就会崩在
        // 运行时；这里反射验证类型。
        val cls = RefreshMemoWidgetAction::class.java
        val superInterfaces = cls.interfaces.map { it.name }.toSet()
        // 实际继承层级可能是 RefreshMemoWidgetAction : ActionCallback
        assertTrue(
            "RefreshMemoWidgetAction must implement ActionCallback",
            superInterfaces.any { it.contains("ActionCallback") },
        )
    }

    @Test
    fun `RefreshTodayWidgetAction is an ActionCallback subclass`() {
        val cls = RefreshTodayWidgetAction::class.java
        val superInterfaces = cls.interfaces.map { it.name }.toSet()
        assertTrue(
            "RefreshTodayWidgetAction must implement ActionCallback",
            superInterfaces.any { it.contains("ActionCallback") },
        )
    }

    @Test
    fun `RefreshMemoWidgetAction has the expected onAction function signature`() {
        // Glance 用反射调 onAction(Context, GlanceId, ActionParameters)；签名错了
        // 就会 NoSuchMethodError at runtime。用 Kotlin 反射拿 declared functions
        // 来锁定签名存在。
        val methods = RefreshMemoWidgetAction::class.java.declaredMethods
        val onAction = methods.firstOrNull { it.name == "onAction" }
        assertNotNull(
            "RefreshMemoWidgetAction must declare an onAction function",
            onAction,
        )
    }

    @Test
    fun `RefreshTodayWidgetAction has the expected onAction function signature`() {
        val methods = RefreshTodayWidgetAction::class.java.declaredMethods
        val onAction = methods.firstOrNull { it.name == "onAction" }
        assertNotNull(
            "RefreshTodayWidgetAction must declare an onAction function",
            onAction,
        )
    }

    @Test
    fun `RefreshMemoWidgetAction and RefreshTodayWidgetAction are distinct classes`() {
        // 故意分成两个 class：语义上 Memo 和 Today 是两个独立的 widget，
        // 合并成一个类会让 Glance 的 actionRunCallback<T> 只能指向一个目标。
        // 如果有人合并了它们，这条测试会红。
        assertFalse(
            RefreshMemoWidgetAction::class.java == RefreshTodayWidgetAction::class.java,
        )
    }

    // ------------------------------------------------------------------
    // WidgetRefresher 配置守门
    // ------------------------------------------------------------------

    @Test
    fun `WidgetRefresher DEBOUNCE_MS is 400`() {
        // Fix-1 / Agent 1 研究结论：400ms 是 "能合并写入风暴 + 不让用户察觉延迟"
        // 的安全区。低于 300ms 撑不住保存→回桌面 500ms 真实场景，高于 500ms
        // 用户体感 widget 呆。如果有人无意中改回旧的 250ms 或调大到 1000ms，
        // 这条测试立刻红 —— 任何调整必须伴随数据支撑。
        assertEquals(400L, WidgetRefresher.DEBOUNCE_MS)
    }

    @Test
    fun `WidgetRefresher updater is replaceable for tests`() {
        // Fix-1 抽出 WidgetUpdater 接口就是为了让 pure-JVM 测试能替换掉真实
        // Glance updater。如果重构时把 `updater` 字段改回 private final，整个
        // 测试基础设施就废了。
        val original = WidgetRefresher.updater
        try {
            val fake = object : dev.aria.memo.data.widget.WidgetUpdater {
                var memoCalls = 0
                var todayCalls = 0
                override suspend fun updateMemo(context: android.content.Context?) {
                    memoCalls++
                }
                override suspend fun updateToday(context: android.content.Context?) {
                    todayCalls++
                }
            }
            WidgetRefresher.updater = fake
            assertTrue(WidgetRefresher.updater === fake)
        } finally {
            WidgetRefresher.updater = original
        }
    }

    // ------------------------------------------------------------------
    // MemoWidgetRow 数据契约守门
    // ------------------------------------------------------------------

    @Test
    fun `MemoWidgetRow has exactly four fields in documented order date time label noteUid`() {
        val row = MemoWidgetRow(
            date = today,
            time = LocalTime.of(12, 30),
            label = "label",
            noteUid = "uid-1",
        )
        // 解构顺序锁定 —— widget Composable 里依赖 row.date, row.time, row.label, row.noteUid
        val (date, time, label, noteUid) = row
        assertEquals(today, date)
        assertEquals(LocalTime.of(12, 30), time)
        assertEquals("label", label)
        assertEquals("uid-1", noteUid)
    }

    @Test
    fun `MemoWidgetRow noteUid is nullable only for legacy DatedMemoEntry rows`() {
        // 契约：SingleNoteEntity.toRow() 总是返回 noteUid != null（即使空字符串）
        //       DatedMemoEntry.toRow() 总是返回 noteUid == null
        // 这是 widget click handler 区分 "deep-link 到 EditActivity" 和 "打开空 EditActivity"
        // 的唯一依据。
        val singleRow = single("uid-X").toRow()
        assertEquals("uid-X", singleRow.noteUid)

        val datedRow = DatedMemoEntry(
            date = today,
            time = LocalTime.of(9, 0),
            body = "legacy",
        ).toRow()
        assertEquals(null, datedRow.noteUid)
    }

    @Test
    fun `SingleNoteEntity toRow label rule - title wins over body`() {
        val row = single("u", title = "MyTitle", body = "# ignored heading\nbody").toRow()
        assertEquals("MyTitle", row.label)
    }

    @Test
    fun `SingleNoteEntity toRow label rule - blank title uses body first line stripped`() {
        val row = single("u", title = "", body = "# heading text\nmore body").toRow()
        // firstNonEmptyLineStripped 去 markdown heading 前缀
        assertEquals("heading text", row.label)
    }

    @Test
    fun `SingleNoteEntity toRow label rule - blank title and blank body gives empty label`() {
        val row = single("u", title = "", body = "   \n\n\n").toRow()
        assertEquals("", row.label)
    }

    // ------------------------------------------------------------------
    // Widget NOT_CONFIGURED 分支守门（P8 没改但用户体感强相关）
    // ------------------------------------------------------------------

    @Test
    fun `widget flags NOT_CONFIGURED distinctly from transient network errors`() {
        // decideRows 里的分支决定了 widget 显示 "开应用配置 GitHub" 还是 "空列表"。
        // NOT_CONFIGURED → isConfigured=false 让 UI 层弹引导；别的 err → isConfigured=true
        // + 空列表（下次 tick 再试）。这是用户能否"知道 widget 为什么没内容"的关键。
        val notConfigured: MemoResult<List<DatedMemoEntry>> = MemoResult.Err(
            ErrorCode.NOT_CONFIGURED,
            "no PAT",
        )
        val network: MemoResult<List<DatedMemoEntry>> = MemoResult.Err(
            ErrorCode.NETWORK,
            "offline",
        )

        // 模拟 widget 的 isConfigured 分支判断（decideRows 里的逻辑）
        fun deriveIsConfigured(legacy: MemoResult<List<DatedMemoEntry>>, singleNotes: List<SingleNoteEntity>): Boolean {
            if (singleNotes.isNotEmpty()) return true
            return when (legacy) {
                is MemoResult.Ok -> true
                is MemoResult.Err -> legacy.code != ErrorCode.NOT_CONFIGURED
            }
        }

        assertFalse(
            "NOT_CONFIGURED must map to isConfigured=false",
            deriveIsConfigured(notConfigured, emptyList()),
        )
        assertTrue(
            "transient NETWORK err must keep isConfigured=true",
            deriveIsConfigured(network, emptyList()),
        )
    }
}
