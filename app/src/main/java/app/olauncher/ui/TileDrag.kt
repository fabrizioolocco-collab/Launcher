package app.olauncher.ui

import android.annotation.SuppressLint
import android.graphics.Rect
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import app.olauncher.helper.dpToPx
import kotlin.math.abs

/**
 * The touches of a tile that opens with a tap, shows its menu with a long press and, when the
 * finger moves after the long press, is dragged onto another tile to take its place.
 *
 * The rows the tiles sit in must not clip their children, or a dragged tile vanishes outside its row.
 */
class TileDrag(
    private val view: View,
    /** The tiles it can be dropped on (itself among them is fine). */
    private val targets: () -> List<View>,
    private val canDrag: () -> Boolean,
    private val onClick: () -> Unit,
    private val onMenu: () -> Unit,
    private val onDrop: (View) -> Unit,
) : View.OnTouchListener {

    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var armed = false
    private var dragging = false
    private var moved = false
    private var over: View? = null

    private val arm = Runnable {
        armed = true
        view.isPressed = false
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        view.parent?.requestDisallowInterceptTouchEvent(true)
        if (canDrag()) lift(true)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, ev: MotionEvent): Boolean {
        val dx = ev.rawX - downX
        val dy = ev.rawY - downY
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                armed = false
                dragging = false
                moved = false
                v.isPressed = true
                v.postDelayed(arm, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                val far = abs(dx) > slop || abs(dy) > slop
                if (!armed) {
                    if (far && !moved) {
                        // A scroll or a slip of the finger: neither a tap nor a long press
                        moved = true
                        v.removeCallbacks(arm)
                        v.isPressed = false
                    }
                } else if (canDrag() && (dragging || far)) {
                    dragging = true
                    v.translationX = dx
                    v.translationY = dy
                    mark(targetAt(ev.rawX, ev.rawY))
                }
            }
            MotionEvent.ACTION_UP -> {
                v.removeCallbacks(arm)
                v.isPressed = false
                val target = over
                mark(null)
                lift(false)
                when {
                    dragging -> target?.let(onDrop)
                    armed -> onMenu()
                    !moved -> onClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                v.removeCallbacks(arm)
                v.isPressed = false
                mark(null)
                lift(false)
            }
        }
        return true
    }

    private fun targetAt(x: Float, y: Float): View? {
        val pos = IntArray(2)
        return targets().firstOrNull {
            if (it === view || !it.isShown) return@firstOrNull false
            it.getLocationOnScreen(pos)
            Rect(pos[0], pos[1], pos[0] + it.width, pos[1] + it.height).contains(x.toInt(), y.toInt())
        }
    }

    private fun mark(target: View?) {
        if (target === over) return
        over?.alpha = 1f
        target?.alpha = 0.45f
        over = target
    }

    /** Raised above the other tiles (and its row above the other rows) while it can be dragged. */
    private fun lift(up: Boolean) {
        val z = if (up) 12.dpToPx().toFloat() else 0f
        view.translationZ = z
        (view.parent as? View)?.translationZ = z
        view.animate().cancel()
        if (up) view.animate().scaleX(1.07f).scaleY(1.07f).setDuration(100).start()
        else {
            view.scaleX = 1f
            view.scaleY = 1f
            view.animate().translationX(0f).translationY(0f).setDuration(120).start()
        }
    }
}
