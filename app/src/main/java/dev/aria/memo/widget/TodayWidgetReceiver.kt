package dev.aria.memo.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * Binds [TodayWidget] to the launcher. See xml/today_widget_info.xml.
 *
 * State cleanup on removal is handled by the Glance-provided
 * [GlanceAppWidgetReceiver.onDeleted] default — no override required.
 */
class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}
