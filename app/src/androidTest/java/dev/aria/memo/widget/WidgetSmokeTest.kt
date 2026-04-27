package dev.aria.memo.widget

import android.appwidget.AppWidgetManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.aria.memo.data.widget.WidgetRefresher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented smoke tests — confirm the two Glance widgets are wired into the
 * runtime AppWidgetManager and that the production `WidgetRefresher.refreshAllNow`
 * pipeline can hit real Glance `updateAll` without crashing.
 *
 * 这套测试**只在真实 Android emulator / device 上跑**（CI 里的
 * android-instrumented job）。Kotlin/JVM unit test 不会跑这个目录。
 *
 * 不验证 widget 像素级渲染（屏幕尺寸/主题差异太多），只验：
 *  1. AndroidManifest 里的 receiver 注册是否被系统 AppWidgetManager 看到。
 *  2. 真 Context 下的 Glance updateAll 路径是否能跑通（即便桌面没添加 widget
 *     实例，updateAll 也应该 no-op 而不是抛异常）。
 */
@RunWith(AndroidJUnit4::class)
class WidgetSmokeTest {

    /**
     * Smoke 1：两个 widget receiver 必须被 AppWidgetManager 识别。
     *
     * `getInstalledProvidersForPackage(pkg, profile=null)` 返回当前 user 的所有
     * AppWidgetProviderInfo。manifest 里写了 MemoWidgetReceiver +
     * TodayWidgetReceiver 各自带 `<meta-data android:appwidget.provider>`，少一个
     * 这个测试就会红。
     */
    @Test
    fun memoWidget_canBePinnedToHomescreen() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val mgr = AppWidgetManager.getInstance(ctx)
        assertNotNull("AppWidgetManager should be available on a real device", mgr)

        val providers = mgr.getInstalledProvidersForPackage(ctx.packageName, null)
        assertTrue(
            "MemoWidgetReceiver must be registered in AndroidManifest.xml",
            providers.any { it.provider.className.endsWith("MemoWidgetReceiver") }
        )
        assertTrue(
            "TodayWidgetReceiver must be registered in AndroidManifest.xml",
            providers.any { it.provider.className.endsWith("TodayWidgetReceiver") }
        )
    }

    /**
     * Smoke 2：在真实 instrumented Context 上跑一次 [WidgetRefresher.refreshAllNow]。
     *
     *  - 单元测试里调这个会因为 Glance `updateAll` 需要真 AppWidgetManager 而 NPE，
     *    所以 P8 在 JVM test 里全用 fake updater 替身。这里我们要的就是**没替身**
     *    的真 Glance 路径不会崩。
     *  - 即便桌面上没添加任何 widget 实例，Glance 内部也会迭代 0 个 GlanceId 并
     *    平稳返回；refreshAllNow 用 runCatching 兜底，所以**只要这条测试不抛异常就算通过**。
     */
    @Test
    fun widgetRefresher_endToEnd() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            // refreshAllNow 内部已经 runCatching 吞掉所有 Glance 异常 —— 这里要的就是
            // "整个调用栈不会从这里冒出 unchecked exception 把 androidTest runner 弄崩"。
            WidgetRefresher.refreshAllNow(ctx)
        }
    }
}
