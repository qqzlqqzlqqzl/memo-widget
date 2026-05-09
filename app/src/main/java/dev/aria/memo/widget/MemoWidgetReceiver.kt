package dev.aria.memo.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.state.deleteAppWidgetState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Broadcast receiver that hosts [MemoWidget] on the home screen.
 *
 * Manifest wiring (owned by Agent A):
 *  - <receiver android:name="dev.aria.memo.widget.MemoWidgetReceiver">
 *      <intent-filter>
 *        <action android:name="android.appwidget.action.APPWIDGET_UPDATE"/>
 *      </intent-filter>
 *      <meta-data android:name="android.appwidget.provider"
 *                 android:resource="@xml/memo_widget_info"/>
 *    </receiver>
 *
 * This class intentionally contains no logic — all rendering lives in
 * [MemoWidget.provideGlance] / [MemoWidgetContent] per Glance's architecture.
 */
class MemoWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MemoWidget()

    /**
     * Cleans up Glance state when the user removes a widget instance from the launcher.
     *
     * [MemoWidget] does not declare a custom stateDefinition, so it relies on
     * the Glance default (PreferencesGlanceStateDefinition). [deleteAppWidgetState]
     * is the Glance 1.1.x helper that targets exactly that default and resolves the
     * GlanceId from the raw appWidgetId internally — no manual manager look-up needed.
     *
     * [goAsync] extends the BroadcastReceiver deadline so the IO coroutine can
     * complete without the system killing the receiver process prematurely.
     */
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val mgr = GlanceAppWidgetManager(context)
                appWidgetIds.forEach { id ->
                    runCatching {
                        val glanceId = mgr.getGlanceIdBy(id)
                        deleteAppWidgetState(context, glanceId)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}

