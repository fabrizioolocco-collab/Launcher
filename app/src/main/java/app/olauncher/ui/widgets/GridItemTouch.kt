package app.olauncher.ui.widgets

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot

/** Callbacks of an item (widget or icon) placed on a [WidgetGridLayout]. */
interface GridItemListener {
    fun onLongPress(view: View)
    /** Finger moved by (dx, dy) since the touch began; [rawY] is the finger's screen position. */
    fun onDrag(view: View, dx: Float, dy: Float, rawY: Float)
    fun onDrop(view: View, dx: Float, dy: Float)
    /** Long press released without dragging. */
    fun onMenu(view: View)
}

/**
 * Nova-style touch handling shared by widgets and icons: a long press anywhere on the item (even
 * over clickable children) takes over the gesture; moving then drags the item, lifting without
 * moving opens its menu. [onTap] (icons) is called for a plain tap.
 */
class GridItemTouch(private val view: View, private val onTap: (() -> Unit)? = null) {

    var listener: GridItemListener? = null

    private val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private var downRawX = 0f
    private var downRawY = 0f
    private var moved = false
    /** True from the long press until the finger is lifted: the gesture belongs to move / menu. */
    var longPressed = false
        private set
    private var dragging = false

    private val longPressRunnable = Runnable {
        if (view.parent != null && view.hasWindowFocus()) {
            longPressed = true
            dragging = false
            view.parent?.requestDisallowInterceptTouchEvent(true)
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            listener?.onLongPress(view)
        }
    }

    private fun track(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX
                downRawY = ev.rawY
                moved = false
                view.removeCallbacks(longPressRunnable)
                view.postDelayed(longPressRunnable, longPressTimeout)
            }

            MotionEvent.ACTION_MOVE ->
                if (abs(ev.rawX - downRawX) > touchSlop || abs(ev.rawY - downRawY) > touchSlop) {
                    moved = true
                    view.removeCallbacks(longPressRunnable)
                }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.removeCallbacks(longPressRunnable)
        }
    }

    /** From onInterceptTouchEvent: true once the long press took over. */
    fun intercept(ev: MotionEvent): Boolean {
        if (longPressed) return true
        track(ev)
        return false
    }

    /** From onTouchEvent. */
    fun touch(event: MotionEvent): Boolean {
        if (!longPressed) {
            track(event)
            if (event.actionMasked == MotionEvent.ACTION_UP && !moved) onTap?.invoke()
            return true
        }
        val dx = event.rawX - downRawX
        val dy = event.rawY - downRawY
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && hypot(dx, dy) > touchSlop) dragging = true
                if (dragging) listener?.onDrag(view, dx, dy, event.rawY)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) listener?.onDrop(view, dx, dy)
                else if (event.actionMasked == MotionEvent.ACTION_UP) listener?.onMenu(view)
                else listener?.onDrop(view, 0f, 0f)
                longPressed = false
                dragging = false
            }
        }
        return true
    }

    fun cancel() = view.removeCallbacks(longPressRunnable)
}
