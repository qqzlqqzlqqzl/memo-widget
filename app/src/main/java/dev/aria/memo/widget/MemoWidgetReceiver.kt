package dev.aria.memo.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

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
}
