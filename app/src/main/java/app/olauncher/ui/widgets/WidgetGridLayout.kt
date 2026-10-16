package app.olauncher.ui.widgets

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import app.olauncher.R
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Grid of square cells holding widgets. Each child declares its cell rectangle through [CellParams].
 * The grid is as tall as its lowest widget (one extra empty row while editing, so widgets can be
 * moved or stretched downwards).
 *
 * It also draws the drop preview while a widget is dragged and the resize frame with its four
 * handles, and handles the handle drags itself.
 */
class WidgetGridLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    class CellParams(var col: Int, var row: Int, var colSpan: Int, var rowSpan: Int) :
        ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)

    interface ResizeListener {
        /** May the widget occupy these cells? */
        fun canPlace(view: View, col: Int, row: Int, colSpan: Int, rowSpan: Int): Boolean
        fun onResizeDragging(active: Boolean)
        fun onResized(view: View)
        fun onTapOutsideFrame()
    }

    /** Home gestures for a grid outside the home scroll view (bottom area). */
    interface HomeGestures {
        fun onEmptyLongPress(col: Int, row: Int)
        fun onSwipeUp()
        fun onSwipeDown()
    }

    var homeGestures: HomeGestures? = null

    /** Minimum height in rows even when empty (bottom area keeps a reachable strip). */
    var minRows = 0
        set(value) {
            field = value
            requestLayout()
        }

    private var downOnChild = false
    private var flingHandled = false
    private val gestureDetector = android.view.GestureDetector(context, object : android.view.GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onLongPress(e: MotionEvent) {
            if (downOnChild || resizeTarget != null) return
            val cell = cellSize()
            val col = ((e.x - paddingLeft) / cell).toInt().coerceIn(0, columns - 1)
            val row = ((e.y - paddingTop) / cell).toInt().coerceAtLeast(0)
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            homeGestures?.onEmptyLongPress(col, row)
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val start = e1 ?: return false
            val dy = e2.rawY - start.rawY
            val dx = e2.rawX - start.rawX
            if (abs(dy) > abs(dx) && abs(dy) > 100 && abs(velocityY) > 100) {
                flingHandled = true
                if (dy < 0) homeGestures?.onSwipeUp() else homeGestures?.onSwipeDown()
            }
            return false
        }
    })

    private fun childAt(x: Float, y: Float): View? {
        for (i in childCount - 1 downTo 0) {
            val c = getChildAt(i)
            if (x >= c.left && x < c.right && y >= c.top && y < c.bottom) return c
        }
        return null
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (homeGestures == null || resizeTarget != null || editing) return super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            flingHandled = false
            downOnChild = childAt(ev.x, ev.y) != null
        }
        gestureDetector.onTouchEvent(ev)
        if (flingHandled && ev.actionMasked == MotionEvent.ACTION_UP) {
            // A swipe never launches the icon under the finger
            val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
            return true
        }
        return super.dispatchTouchEvent(ev) || true
    }

    var columns = 6
        set(value) {
            field = value.coerceIn(2, 8)
            requestLayout()
        }

    var editing = false
        set(value) {
            field = value
            requestLayout()
        }

    var resizeListener: ResizeListener? = null

    /** Widget currently showing the resize frame, if any. */
    var resizeTarget: View? = null
        set(value) {
            field = value
            editing = value != null
            invalidate()
        }

    /** Drop preview in cells, or null. [dropValid] colors it. */
    private var dropPreview: Rect? = null
    private var dropValid = true

    private val gap = 4.dpToPx()
    private val handleRadius = 9.dpToPx().toFloat()
    private val handleTouch = 28.dpToPx()
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
    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    init {
        setWillNotDraw(false)
        clipChildren = false
    }

    fun cellSize(): Int {
        val w = if (width > 0) width else measuredWidth
        return ((w - paddingLeft - paddingRight) / columns).coerceAtLeast(1)
    }

    private fun usedRows(): Int {
        var rows = 0
        for (i in 0 until childCount) {
            val p = getChildAt(i).layoutParams as CellParams
            rows = maxOf(rows, p.row + p.rowSpan)
        }
        return rows
    }

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams = CellParams(0, 0, 1, 1)
    override fun generateDefaultLayoutParams(): LayoutParams = CellParams(0, 0, 1, 1)
    override fun checkLayoutParams(p: LayoutParams?) = p is CellParams

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val cell = ((width - paddingLeft - paddingRight) / columns).coerceAtLeast(1)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val p = child.layoutParams as CellParams
            child.measure(
                MeasureSpec.makeMeasureSpec(p.colSpan * cell - 2 * gap, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(p.rowSpan * cell - 2 * gap, MeasureSpec.EXACTLY)
            )
        }
        val rows = maxOf(usedRows() + if (editing) 1 else 0, minRows)
        setMeasuredDimension(width, rows * cell + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cell = cellSize()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val p = child.layoutParams as CellParams
            val left = paddingLeft + p.col * cell + gap
            val top = paddingTop + p.row * cell + gap
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
        }
    }

    // ---------------------------------------------------------------- drag preview (move)

    /** Cell where a child would land if dropped after being dragged by (dx, dy) pixels. */
    fun dropCellFor(child: View, dx: Float, dy: Float): Pair<Int, Int> {
        val cell = cellSize()
        val p = child.layoutParams as CellParams
        val col = ((p.col * cell + dx) / cell).roundToInt().coerceIn(0, columns - p.colSpan)
        val row = ((p.row * cell + dy) / cell).roundToInt().coerceAtLeast(0)
        return col to row
    }

    fun showDropPreview(col: Int, row: Int, colSpan: Int, rowSpan: Int, valid: Boolean) {
        dropPreview = Rect(col, row, col + colSpan, row + rowSpan)
        dropValid = valid
        invalidate()
    }

    fun hideDropPreview() {
        dropPreview = null
        invalidate()
    }

    private fun cellsToPx(r: Rect): RectF {
        val cell = cellSize()
        return RectF(
            (paddingLeft + r.left * cell + gap).toFloat(),
            (paddingTop + r.top * cell + gap).toFloat(),
            (paddingLeft + r.right * cell - gap).toFloat(),
            (paddingTop + r.bottom * cell - gap).toFloat(),
        )
    }

    override fun dispatchDraw(canvas: Canvas) {
        dropPreview?.let {
            previewPaint.color = primary
            previewPaint.alpha = if (dropValid) 50 else 15
            canvas.drawRoundRect(cellsToPx(it), 16f, 16f, previewPaint)
        }
        super.dispatchDraw(canvas)
        resizeTarget?.let { target ->
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
    private var startParams = CellParams(0, 0, 1, 1)

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        val target = resizeTarget ?: return false
        if (ev.actionMasked != MotionEvent.ACTION_DOWN) return activeHandle >= 0
        val f = frameRect(target)
        activeHandle = handles(f).indexOfFirst { (x, y) -> abs(ev.x - x) < handleTouch && abs(ev.y - y) < handleTouch }
        if (activeHandle >= 0) {
            val p = target.layoutParams as CellParams
            startParams = CellParams(p.col, p.row, p.colSpan, p.rowSpan)
            parent?.requestDisallowInterceptTouchEvent(true)
            resizeListener?.onResizeDragging(true)
            return true
        }
        if (!f.contains(ev.x, ev.y)) {
            // Tap elsewhere in the grid ends resizing
            resizeListener?.onTapOutsideFrame()
            return true
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val target = resizeTarget ?: return false
        if (activeHandle < 0) return event.actionMasked == MotionEvent.ACTION_DOWN
        val cell = cellSize()
        val p = target.layoutParams as CellParams
        val s = startParams
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val x = ((event.x - paddingLeft) / cell).roundToInt()
                val y = ((event.y - paddingTop) / cell).roundToInt()
                var col = s.col
                var row = s.row
                var colSpan = s.colSpan
                var rowSpan = s.rowSpan
                when (activeHandle) {
                    0 -> { // left
                        val left = x.coerceIn(0, s.col + s.colSpan - 1)
                        colSpan = s.col + s.colSpan - left; col = left
                    }

                    1 -> { // top
                        val top = y.coerceIn(0, s.row + s.rowSpan - 1)
                        rowSpan = s.row + s.rowSpan - top; row = top
                    }

                    2 -> colSpan = (x.coerceIn(s.col + 1, columns)) - s.col // right
                    3 -> rowSpan = (y.coerceAtLeast(s.row + 1)) - s.row // bottom
                }
                val changed = col != p.col || row != p.row || colSpan != p.colSpan || rowSpan != p.rowSpan
                if (changed && resizeListener?.canPlace(target, col, row, colSpan, rowSpan) != false) {
                    p.col = col; p.row = row; p.colSpan = colSpan; p.rowSpan = rowSpan
                    requestLayout()
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
