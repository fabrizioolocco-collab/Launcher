package app.olauncher.ui

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import app.olauncher.R
import app.olauncher.data.AppModel
import kotlin.math.abs

/**
 * Home scroll container that also recognises the launcher gestures.
 *
 * - Swipe up opens the drawer only if the list was already at the bottom when the finger went down,
 *   so a long home scrolls first and a further swipe opens the drawer.
 * - Swipe down opens the notification shade only if the list was already at the top.
 * - Long press / double tap count only on empty space, not on app rows or section headers.
 * - A fling cancels the tap on the row under the finger, so swiping never launches an app.
 * - A vertical swipe on an app with a swipe action (see [appSwipe]) runs it instead of scrolling;
 *   on other apps, or in a direction without an action, the list scrolls as usual.
 */
class HomeScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {

    interface GestureCallbacks {
        fun onSwipeUp()
        fun onSwipeDown()
        fun onSwipeLeft()
        fun onSwipeRight()
        fun onEmptyLongPress()
        fun onEmptyDoubleTap()
        fun onEmptyTap()
    }

    var callbacks: GestureCallbacks? = null

    interface AppSwipeHandler {
        fun hasAction(app: AppModel.App, up: Boolean): Boolean
        fun run(app: AppModel.App, up: Boolean)
    }

    var appSwipe: AppSwipeHandler? = null

    private var swipeApp: AppModel.App? = null
    private var swipeDecided = false
    private var swipeCapturing = false
    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    /** Turned off while resizing a widget, so the drag isn't taken as a scroll or a swipe. */
    var gesturesEnabled = true

    private var downOnInteractive = false
    private var atBottomOnDown = false
    private var atTopOnDown = false
    private var flingHandled = false
    private val tmpRect = Rect()

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (!downOnInteractive) callbacks?.onEmptyTap()
            return false
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (!downOnInteractive) callbacks?.onEmptyDoubleTap()
            return false
        }

        override fun onLongPress(e: MotionEvent) {
            if (!downOnInteractive) callbacks?.onEmptyLongPress()
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val start = e1 ?: return false
            val diffX = e2.rawX - start.rawX
            val diffY = e2.rawY - start.rawY
            if (abs(diffX) > abs(diffY)) {
                if (abs(diffX) > SWIPE_THRESHOLD && abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                    flingHandled = true
                    if (diffX > 0) callbacks?.onSwipeRight() else callbacks?.onSwipeLeft()
                }
            } else if (abs(diffY) > SWIPE_THRESHOLD && abs(velocityY) > SWIPE_VELOCITY_THRESHOLD) {
                if (diffY < 0 && atBottomOnDown) {
                    flingHandled = true
                    callbacks?.onSwipeUp()
                } else if (diffY > 0 && atTopOnDown) {
                    flingHandled = true
                    callbacks?.onSwipeDown()
                }
            }
            return false
        }
    })

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean =
        gesturesEnabled && super.onInterceptTouchEvent(ev)

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!gesturesEnabled) return super.dispatchTouchEvent(ev)
        if (handleAppSwipe(ev)) return true
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            flingHandled = false
            atBottomOnDown = !canScrollVertically(1)
            atTopOnDown = !canScrollVertically(-1)
            downOnInteractive = isOverInteractiveChild(this, ev.rawX.toInt(), ev.rawY.toInt())
        }
        detector.onTouchEvent(ev)
        if (flingHandled && ev.actionMasked == MotionEvent.ACTION_UP) {
            // Don't let the row under the finger treat the swipe as a click
            val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    /** App swipe actions; true while the gesture belongs to an app (no scrolling, no other gestures). */
    private fun handleAppSwipe(ev: MotionEvent): Boolean {
        val handler = appSwipe ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeApp = findSwipeApp(this, ev.rawX.toInt(), ev.rawY.toInt())
                swipeDecided = false
                swipeCapturing = false
                swipeDownX = ev.rawX
                swipeDownY = ev.rawY
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                val app = swipeApp
                if (app != null && !swipeDecided) {
                    val dx = ev.rawX - swipeDownX
                    val dy = ev.rawY - swipeDownY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        swipeDecided = true
                        if (abs(dy) > abs(dx) && handler.hasAction(app, dy < 0)) {
                            swipeCapturing = true
                            // The row under the finger must not take it as a press or long press
                            val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                            super.dispatchTouchEvent(cancel)
                            cancel.recycle()
                        }
                    }
                }
            }

            MotionEvent.ACTION_UP -> if (swipeCapturing) {
                swipeCapturing = false
                val dy = ev.rawY - swipeDownY
                swipeApp?.let { if (abs(dy) > SWIPE_THRESHOLD) handler.run(it, dy < 0) }
                return true
            }

            MotionEvent.ACTION_CANCEL -> if (swipeCapturing) {
                swipeCapturing = false
                return true
            }
        }
        return swipeCapturing
    }

    private fun findSwipeApp(group: ViewGroup, x: Int, y: Int): AppModel.App? {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            if (!child.getGlobalVisibleRect(tmpRect) || !tmpRect.contains(x, y)) continue
            (child.getTag(R.id.ml_swipe_app) as? AppModel.App)?.let { return it }
            if (child is ViewGroup) findSwipeApp(child, x, y)?.let { return it }
        }
        return null
    }

    private fun isOverInteractiveChild(group: ViewGroup, x: Int, y: Int): Boolean {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            if (!child.getGlobalVisibleRect(tmpRect) || !tmpRect.contains(x, y)) continue
            if (child.isClickable || child.isLongClickable || child is AppWidgetHostView) return true
            if (child is ViewGroup && isOverInteractiveChild(child, x, y)) return true
        }
        return false
    }

    companion object {
        private const val SWIPE_THRESHOLD = 100
        private const val SWIPE_VELOCITY_THRESHOLD = 100
    }
}
