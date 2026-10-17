package app.olauncher.ui.widgets

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import app.olauncher.R
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr

/**
 * The "news" row on the home grid: a reserved, movable block one cell tall where the apps with
 * notifications appear as red tiles on the grid columns (scrolling sideways, one column at a time,
 * when they are more than the columns). Invisible while empty; a dashed outline shows where it is
 * while something is being moved or resized, or on request.
 */
class NewsStripView(context: Context) : SectionBlockView(context) {

    /** Width of one grid column: scrolling always comes to rest with the tiles on the columns. */
    var snapStep = 0

    private val scroll = SnapScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        // Faded edges hint at more tiles beyond the visible columns
        isHorizontalFadingEdgeEnabled = true
        setFadingEdgeLength(24.dpToPx())
    }
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val label = TextView(context, null, 0, R.style.TextSmall).apply {
        setText(R.string.ml_news_zone)
        alpha = 0.5f
        gravity = Gravity.CENTER
        isVisible = false
    }
    private val outline = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 14.dpToPx().toFloat()
        setStroke(2.dpToPx(), context.getColorFromAttr(R.attr.primaryColor) and 0x80FFFFFF.toInt(), 10.dpToPx().toFloat(), 6.dpToPx().toFloat())
    }
    private val hideOutline = Runnable { setOutlined(false) }

    init {
        // The whole strip grabs the long press (the tiles inside don't drag by themselves)
        header = this
        scroll.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val stack = FrameLayout(context)
        stack.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        stack.addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(stack, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** Touches anywhere on the strip belong to it (so the long press moves it), header or not. */
    override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
        header = this
        return super.onInterceptTouchEvent(ev)
    }

    val isEmpty get() = row.childCount == 0

    /** Settles on the nearest column once the finger is up and the fling is over. */
    private inner class SnapScrollView(context: Context) : HorizontalScrollView(context) {
        private var touching = false
        private val snap = Runnable {
            val step = snapStep
            if (step <= 0 || touching) return@Runnable
            val target = ((scrollX + step / 2) / step) * step
            if (target != scrollX) smoothScrollTo(target, 0)
        }

        private fun scheduleSnap() {
            removeCallbacks(snap)
            postDelayed(snap, 80)
        }

        // dispatch, not onTouchEvent: the down usually lands on a tile, not on the scroll view
        override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    touching = true
                    removeCallbacks(snap)
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    touching = false
                    scheduleSnap()
                }
            }
            return super.dispatchTouchEvent(ev)
        }

        override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
            super.onScrollChanged(l, t, oldl, oldt)
            if (!touching) scheduleSnap()
        }
    }

    fun setOutlined(outlined: Boolean) {
        removeCallbacks(hideOutline)
        background = if (outlined) outline else null
        label.isVisible = outlined && isEmpty
    }

    /** Shows the outline for a few seconds, to find the strip while it's empty. */
    fun flashOutline() {
        setOutlined(true)
        postDelayed(hideOutline, 3000)
    }
}
