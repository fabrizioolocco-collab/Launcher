package app.olauncher.ui.widgets

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import app.olauncher.R
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import kotlin.math.abs

/**
 * Layer over the home where widgets sit at free pixel positions, independent of the app grid.
 * Each child uses [FrameLayout.LayoutParams] with its size and left/top margins as position.
 *
 * Draws the resize frame with four handles around [editTarget] and handles the handle drags
 * itself; the move drag is handled by the widget's own touch helper. Touches outside the widgets
 * fall through to the home underneath.
 */
class FreeWidgetLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    interface ResizeListener {
        fun onResizeDragging(active: Boolean)
        fun onResized(view: View)
        fun onTapOutsideFrame()
    }

    var resizeListener: ResizeListener? = null

    /** Widget currently showing the resize frame, if any. */
    var editTarget: View? = null
        set(value) {
            field = value
            invalidate()
        }

    private val handleRadius = 9.dpToPx().toFloat()
    private val handleTouch = 28.dpToPx()
    private val minSize = 48.dpToPx()
    private val primary = context.getColorFromAttr(R.attr.primaryColor)
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.dpToPx().toFloat()
        color = primary
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = primary
    }

    init {
        setWillNotDraw(false)
        clipChildren = false
    }

    /** Keeps (x, y) of a [w] x [h] widget inside the layer. */
    fun clampX(x: Int, w: Int): Int = x.coerceIn(0, (width - w).coerceAtLeast(0))
    fun clampY(y: Int, h: Int): Int = y.coerceIn(0, (height - h).coerceAtLeast(0))

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        editTarget?.let { target ->
            val f = frameRect(target)
            canvas.drawRoundRect(f, 12f, 12f, framePaint)
            handles(f).forEach { (x, y) -> canvas.drawCircle(x, y, handleRadius, handlePaint) }
        }
    }

    private fun frameRect(v: View) = RectF(v.left.toFloat(), v.top.toFloat(), v.right.toFloat(), v.bottom.toFloat())

    /** Left, top, right, bottom handle centers. */
    private fun handles(f: RectF) = listOf(
        f.left to f.centerY(),
        f.centerX() to f.top,
        f.right to f.centerY(),
        f.centerX() to f.bottom,
    )

    // ---------------------------------------------------------------- resize handles

    private var activeHandle = -1
    private var startLeft = 0
    private var startTop = 0
    private var startRight = 0
    private var startBottom = 0
    private var downX = 0f
    private var downY = 0f

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        val target = editTarget ?: return false
        if (ev.actionMasked != MotionEvent.ACTION_DOWN) return activeHandle >= 0
        val f = frameRect(target)
        activeHandle = handles(f).indexOfFirst { (x, y) -> abs(ev.x - x) < handleTouch && abs(ev.y - y) < handleTouch }
        if (activeHandle >= 0) {
            startLeft = target.left; startTop = target.top; startRight = target.right; startBottom = target.bottom
            downX = ev.x; downY = ev.y
            parent?.requestDisallowInterceptTouchEvent(true)
            resizeListener?.onResizeDragging(true)
            return true
        }
        if (!f.contains(ev.x, ev.y)) {
            // Tap elsewhere ends resizing (the touch is not passed on)
            resizeListener?.onTapOutsideFrame()
            return true
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val target = editTarget ?: return false
        if (activeHandle < 0) return event.actionMasked == MotionEvent.ACTION_DOWN
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.x - downX).toInt()
                val dy = (event.y - downY).toInt()
                var left = startLeft; var top = startTop; var right = startRight; var bottom = startBottom
                // The battery widget is meant to be tiny
                val min = if (target is BatteryWidgetView || target is DetoxRingView) BatteryWidgetView.MIN_SIZE_DP.dpToPx() else minSize
                when (activeHandle) {
                    0 -> left = (startLeft + dx).coerceIn(0, startRight - min)
                    1 -> top = (startTop + dy).coerceIn(0, startBottom - min)
                    2 -> right = (startRight + dx).coerceIn(startLeft + min, width)
                    3 -> bottom = (startBottom + dy).coerceIn(startTop + min, height)
                }
                val p = target.layoutParams as LayoutParams
                if (p.leftMargin != left || p.topMargin != top || p.width != right - left || p.height != bottom - top) {
                    p.leftMargin = left; p.topMargin = top; p.width = right - left; p.height = bottom - top
                    target.layoutParams = p
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeHandle = -1
                resizeListener?.onResizeDragging(false)
                resizeListener?.onResized(target)
            }
        }
        return true
    }
}
