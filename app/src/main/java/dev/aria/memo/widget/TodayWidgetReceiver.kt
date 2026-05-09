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

/** Binds [TodayWidget] to the launcher. See xml/today_widget_info.xml. */
class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val mgr = GlanceAppWidgetManager(context)
                appWidgetIds.forEach { id ->
                    runCatching {
                        val glanceId = mgr.getGlanceIdBy(id)
                        deleteAppWidgetState(context, glanceAppWidget.stateDefinition, glanceId)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
