package dev.aria.memo.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.aria.memo.data.ServiceLocator
import dev.aria.memo.data.widget.WidgetRefresher

/**
 * Listens to [Intent.ACTION_DATE_CHANGED] / [Intent.ACTION_TIME_CHANGED] /
 * [Intent.ACTION_TIMEZONE_CHANGED] and triggers [WidgetRefresher.refreshAll]
 * so that [MemoWidget] + [TodayWidget] reflect the new "today" base line.
 *
 * ## Why this exists (Fix-X3 / Review-X #4)
 *
 * [TodayWidget.provideGlance] computes `LocalDate.now(clock)` to decide the
 * day's events / memos. AppWidget 进程默认**不订阅** `ACTION_DATE_CHANGED` ——
 * 它只在 `APPWIDGET_UPDATE` 周期性 tick / 数据写路径主动调 [WidgetRefresher]
 * 时重绘。结果是：晚上 23:59 看到的 widget 渲染的是"昨天"；过了 00:00 系统跨日
 * 后，**直到下一个 hook 触发刷新（保存笔记 / Pull Worker / 30 分钟 system tick）**
 * widget 才会切到"今天"。隔夜场景里这个延迟可能长达 30 分钟，BDD 跨日用例
 * (Fix-W2) 因此判定 widget 行为不正确。
 *
 * 解决方案：注册一个 BroadcastReceiver，在 system 发出"日期/时间/时区"变化广播
 * 的瞬间主动调 [WidgetRefresher.refreshAll]，让两个 widget 立即重新执行
 * `provideGlance` 拉取新的 `LocalDate.now()`。
 *
 * ## Manifest 注册
 *
 * 这种"系统时间相关"广播是 implicit broadcast，必须在 AndroidManifest 里通过
 * `<receiver>` + `<intent-filter>` 显式声明（Android 26+ 不允许 runtime register
 * 这一类）。`exported=false` 是因为发送方是系统本身，第三方 app 无法伪造这
 * 三个 action 的发送方。
 *
 * ```xml
 * <receiver android:name=".widget.DateChangedReceiver" android:exported="false">
 *     <intent-filter>
 *         <action android:name="android.intent.action.DATE_CHANGED"/>
 *         <action android:name="android.intent.action.TIME_SET"/>
 *         <action android:name="android.intent.action.TIMEZONE_CHANGED"/>
 *     </intent-filter>
 * </receiver>
 * ```
 *
 * ## 三个 action 的语义
 *  - `ACTION_DATE_CHANGED`：日历日变化（00:00 跨日、用户手改日期）。**主用例**。
 *  - `ACTION_TIME_CHANGED`：用户手动改了系统时间（"现在是明天 09:00"）。
 *    侧用例 —— 改时间也可能跨日。
 *  - `ACTION_TIMEZONE_CHANGED`：用户切换时区（飞机落地切到本地时区）。同样
 *    可能让 `LocalDate.now()` 的输出立刻跳到另一天。
 *
 * 三个一起监听才能覆盖"用户体感的今天变了"的所有场景。
 */
class DateChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                // ServiceLocator.init 是幂等的；广播进程冷启动时（receiver 单独
                // 起进程）需要它来初始化 Glance updater 依赖的单例。
                ServiceLocator.init(context)
                // refreshAll 走 debounce 管道，多个广播紧挨着到达（少见但可能：
                // 用户手动改时间会同时触发 TIME_SET + DATE_CHANGED）会合并成一次
                // 真正的 widget 刷新。
                WidgetRefresher.refreshAll(context)
            }
        }
    }
}
