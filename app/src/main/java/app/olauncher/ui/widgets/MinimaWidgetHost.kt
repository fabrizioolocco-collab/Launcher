package app.olauncher.ui.widgets

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.view.MotionEvent
import android.widget.RemoteViews

/** App widget host whose views support Nova-style long press: drag to move, release for the menu. */
class MinimaWidgetHost(context: Context, hostId: Int) : AppWidgetHost(context, hostId) {
    override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
        MinimaWidgetHostView(context)
}

/** Widget view: Nova-style long press (drag to move, release for the menu), see [GridItemTouch]. */
class MinimaWidgetHostView(context: Context) : AppWidgetHostView(context) {

    val touch = GridItemTouch(this)

    /** True once the provider has pushed content: used to tell a configured widget from an abandoned one. */
    var receivedUpdate = false
        private set

    private var errorShown = false

    override fun updateAppWidget(remoteViews: RemoteViews?) {
        errorShown = false
        super.updateAppWidget(remoteViews)
        if (remoteViews != null) receivedUpdate = true
        if (errorShown && remoteViews != null) {
            // Android swallowed the inflation error: reproduce it once to record the cause
            try {
                remoteViews.apply(context, this)
                WidgetLog.log(context, "widget $appWidgetId: error view shown (no exception on retry)")
            } catch (e: Throwable) {
                val cause = generateSequence(e) { it.cause }.last()
                WidgetLog.log(context, "widget $appWidgetId inflate error: $e | cause: $cause")
            }
        }
    }

    override fun getErrorView(): android.view.View {
        errorShown = true
        return super.getErrorView()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = touch.intercept(ev)

    override fun onTouchEvent(event: MotionEvent): Boolean = touch.touch(event)

    override fun cancelLongPress() {
        super.cancelLongPress()
        touch.cancel()
    }
}
