package dev.aria.memo

import android.app.Application
import dev.aria.memo.data.PreferencesStore
import dev.aria.memo.data.ServiceLocator
import dev.aria.memo.data.sync.SyncScheduler
import dev.aria.memo.notify.NotificationChannelSetup
import dev.aria.memo.notify.QuickAddNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry point. Bootstraps the ServiceLocator, schedules the
 * periodic pull worker, kicks a one-shot push to flush any dirty rows left
 * behind by a crashed save, and installs the notification channel used by
 * event reminders.
 */
class MemoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // ServiceLocator.init 必须保持同步：UI / widget / Worker 第一次读都依赖它
        // 构造完毕。Room 的 `.build()` 本身只是 lazy open，不会触发 IO；
        // HttpClient(CIO) 构造也足够快（约 1–3ms）。这里保持同步，重活在下面的
        // background scope 推开。
        ServiceLocator.init(this)
        NotificationChannelSetup.ensure(this)

        // Perf-fix C2: 原来这里同步调 SyncScheduler.schedulePeriodicPull /
        // enqueuePush。WorkManager.getInstance(...) 首次触发时会打开其内部
        // SQLite（磁盘 IO），加上 PeriodicWorkRequestBuilder 构造 + enqueue
        // 本身都是同步 binder 调用，叠在冷启动主线程上会明显拖长首帧可见时间。
        // 把这些都挪到 IO background scope —— WorkManager 内部自己是线程安全的，
        // 唯一的"代价"是：periodic pull 的首次调度 / 启动时一次性 push 的入队
        // 会延后几十到几百毫秒生效。对用户体感无影响（pull 本来就 30 分钟周期，
        // push 本来就异步，不保证立即发车）。
        val prefs = PreferencesStore(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            SyncScheduler.schedulePeriodicPull(this@MemoApplication)
            SyncScheduler.enqueuePush(this@MemoApplication)
            // Re-hydrate the ongoing "记一笔" notification after a process
            // restart. DataStore reads must happen off the main thread.
            if (prefs.currentQuickAddEnabled()) {
                QuickAddNotificationManager.show(this@MemoApplication)
            }
        }
    }
}
