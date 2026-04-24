package dev.aria.memo.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll

/**
 * P8 —— Widget 手动刷新的 Glance Action Callback。
 *
 * 为什么需要独立的 ActionCallback：
 *  - Glance 的 `onClick` 只能接受 `Action`（例如 `actionStartActivity(...)` 或
 *    `actionRunCallback<T>()`），**不能**直接调用 `suspend fun`。Widget 要自己
 *    触发一次重绘只能走 `MemoWidget().updateAll(context)`，而 `updateAll` 是
 *    `suspend`。因此必须封装成 [ActionCallback]：Glance 会在一个后台协程里
 *    调 [onAction]，我们在里面 `updateAll` 就合法了。
 *
 *  - 这里我们只针对 [MemoWidget]（"最近 20 条笔记"）。[TodayWidget] 有独立的
 *    [RefreshTodayWidgetAction]（见下）。拆成两个 class 是因为 Glance 的
 *    `RunCallbackAction` 要求具名 `Class<out ActionCallback>`，而语义上
 *    "刷新 Memo" 和 "刷新 Today" 是两个并列的用户意图，分开更清晰。
 *
 *  - 不传 [ActionParameters]：刷新没有副作用依赖，参数为空是合法的，
 *    Glance 的 `actionRunCallback<T>()` 零参重载直接传空 bundle。
 *
 * 【Fix-1 / C2 修复】`updateAll` 抛异常（冷启动 GlanceId 缺失 /
 * IllegalStateException("widget being updated")）时，Glance 内部 worker
 * 的 catch 会吞掉，用户按了 🔄 按钮却"什么也没发生"。这里显式用 [runCatching]
 * 包一层，保证和写路径 [dev.aria.memo.data.widget.WidgetRefresher] 的
 * "异常吞掉 + no-op" 策略一致。
 *
 * 为什么**不**走 WidgetRefresher 的 debounce：
 *  - 手动点 🔄 是用户"就是想让它现在刷新"，debounce 反而会显得失灵（用户点了
 *    没反应，以为按钮坏了）。
 *  - Glance ActionCallback 仅在用户交互时触发，不会高频；debounce 对它没意义。
 *  - 独立 onAction 协程由 Glance 内部 pool 调度，和 WidgetRefresher scope
 *    互不干扰。
 */
class RefreshMemoWidgetAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        // 用 applicationContext 避免捕获短命的 receiver context。
        // updateAll 会遍历所有已 pin 的 MemoWidget 实例重新 provideGlance。
        // runCatching：和 WidgetRefresher 写路径一致的"可恢复失败"语义 ——
        // 任何 Glance 内部异常（session lock 超时 / GlanceId 丢失 / RemoteViews
        // 二进制过大）都被吞掉，不让整个 Glance worker 报错。
        runCatching { MemoWidget().updateAll(context.applicationContext) }
    }
}

/**
 * 同理，刷新 [TodayWidget]。和 [RefreshMemoWidgetAction] 平级，两个 widget 的
 * 🔄 按钮各走各的 callback，互不影响（比如 Today 的数据源还包含 events，
 * 它的刷新逻辑将来有可能增加范围，所以先预留独立类型）。
 *
 * 【Fix-1 / C2 修复】同样套一层 [runCatching]。
 */
class RefreshTodayWidgetAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        runCatching { TodayWidget().updateAll(context.applicationContext) }
    }
}
