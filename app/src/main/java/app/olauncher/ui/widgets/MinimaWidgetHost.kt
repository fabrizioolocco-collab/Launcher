package app.olauncher.ui.widgets

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.RemoteViews
import kotlin.math.abs
import kotlin.math.hypot

/** App widget host whose views support Nova-style long press: drag to move, release for the menu. */
class MinimaWidgetHost(context: Context, hostId: Int) : AppWidgetHost(context, hostId) {
    override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
        MinimaWidgetHostView(context)
}

/**
 * Long press anywhere on the widget (even over its clickable children) takes over the gesture:
 * moving the finger then drags the widget, lifting it without moving opens the menu.
 */
class MinimaWidgetHostView(context: Context) : AppWidgetHostView(context) {

    interface Listener {
        fun onLongPress(view: MinimaWidgetHostView)
        fun onDrag(view: MinimaWidgetHostView, dx: Float, dy: Float)
        fun onDrop(view: MinimaWidgetHostView, dx: Float, dy: Float)
        /** Long press released without dragging. */
        fun onMenu(view: MinimaWidgetHostView)
    }

    var listener: Listener? = null

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

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private var downRawX = 0f
    private var downRawY = 0f
    private var longPressed = false
    private var dragging = false

    private val longPressRunnable = Runnable {
        if (parent != null && hasWindowFocus()) {
            longPressed = true
            dragging = false
            parent?.requestDisallowInterceptTouchEvent(true)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            listener?.onLongPress(this)
        }
    }

    private fun track(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX
                downRawY = ev.rawY
                removeCallbacks(longPressRunnable)
                postDelayed(longPressRunnable, longPressTimeout)
            }

            MotionEvent.ACTION_MOVE ->
                if (abs(ev.rawX - downRawX) > touchSlop || abs(ev.rawY - downRawY) > touchSlop)
                    removeCallbacks(longPressRunnable)

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPressRunnable)
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (longPressed) return true
        track(ev)
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!longPressed) {
            // Empty widget areas (no child handles the touch): track the long press here
            track(event)
            return true
        }
        val dx = event.rawX - downRawX
        val dy = event.rawY - downRawY
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && hypot(dx, dy) > touchSlop) dragging = true
                if (dragging) listener?.onDrag(this, dx, dy)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) listener?.onDrop(this, dx, dy)
                else if (event.actionMasked == MotionEvent.ACTION_UP) listener?.onMenu(this)
                else listener?.onDrop(this, 0f, 0f)
                longPressed = false
                dragging = false
            }
        }
        return true
    }

    override fun cancelLongPress() {
        super.cancelLongPress()
        removeCallbacks(longPressRunnable)
    }
}
