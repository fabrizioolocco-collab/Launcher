package app.olauncher.ui

import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * Selection by sliding the finger over the items of a list or grid, as in the galleries: after the
 * long press that selects an item, keep the finger down and slide; or, with a selection already
 * going, slide sideways from an item. Every item between the first and the one under the finger
 * takes the first one's new state (selected or not); going back restores the ones left behind.
 * Near the top or bottom edge the list scrolls by itself.
 */
class DragSelect(
    private val list: RecyclerView,
    /** A selection is going (sideways slides start a drag then). */
    private val selecting: () -> Boolean,
    private val isSelected: (Int) -> Boolean,
    private val setSelected: (Int, Boolean) -> Unit,
    /** After a change: redraw and update the counters. */
    private val onChanged: () -> Unit,
) : RecyclerView.OnItemTouchListener {

    private val slop = ViewConfiguration.get(list.context).scaledTouchSlop
    private var active = false
    private var anchor = -1
    private var last = -1
    private var target = true
    /** The state of the items before this drag, to restore those it no longer covers. */
    private val before = HashMap<Int, Boolean>()
    private var downX = 0f
    private var downY = 0f
    private var downPos = -1
    private var decided = false
    private var lastX = 0f
    private var lastY = 0f
    private var scrollSpeed = 0

    private val autoScroll = object : Runnable {
        override fun run() {
            if (!active || scrollSpeed == 0) return
            list.scrollBy(0, scrollSpeed)
            update(lastX, lastY)
            list.postOnAnimation(this)
        }
    }

    init {
        list.addOnItemTouchListener(this)
    }

    /** From the long press on [position] (already toggled by it): the slide goes on from there. */
    fun start(position: Int) {
        if (position < 0) return
        active = true
        anchor = position
        last = position
        target = isSelected(position)
        before.clear()
        before[position] = !target
    }

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = false
                decided = false
                downX = e.x
                downY = e.y
                downPos = positionAt(e.x, e.y)
            }

            MotionEvent.ACTION_MOVE -> if (!active && !decided) {
                val dx = abs(e.x - downX)
                val dy = abs(e.y - downY)
                if (dx > slop || dy > slop) {
                    decided = true
                    // With a selection going, a sideways slide from an item selects instead of scrolling
                    if (selecting() && downPos >= 0 && dx > dy) {
                        setSelected(downPos, !isSelected(downPos))
                        start(downPos)
                        onChanged()
                    }
                }
            }
        }
        return active
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        if (!active) return
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                lastX = e.x
                lastY = e.y
                update(e.x, e.y)
                val edge = list.height / 8
                val speed = when {
                    e.y < edge -> -((edge - e.y) / edge * MAX_SCROLL).toInt().coerceAtLeast(4)
                    e.y > list.height - edge -> ((e.y - (list.height - edge)) / edge * MAX_SCROLL).toInt().coerceAtLeast(4)
                    else -> 0
                }
                val was = scrollSpeed
                scrollSpeed = speed
                if (was == 0 && speed != 0) list.postOnAnimation(autoScroll)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = false
                scrollSpeed = 0
                before.clear()
            }
        }
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}

    private fun positionAt(x: Float, y: Float): Int {
        val child = list.findChildViewUnder(x, y) ?: return -1
        return list.getChildAdapterPosition(child)
    }

    /** Items from the anchor to the one under the finger take the target state; the others go back. */
    private fun update(x: Float, y: Float) {
        val pos = positionAt(x, y)
        if (pos < 0 || pos == last) return
        last = pos
        val lo = minOf(anchor, pos)
        val hi = maxOf(anchor, pos)
        var changed = false
        // Items no longer covered: back as they were
        for ((p, state) in before.toList()) if (p !in lo..hi) {
            if (isSelected(p) != state) {
                setSelected(p, state)
                changed = true
            }
            before.remove(p)
        }
        for (p in lo..hi) {
            if (p !in before) before[p] = isSelected(p)
            if (isSelected(p) != target) {
                setSelected(p, target)
                changed = true
            }
        }
        if (changed) onChanged()
    }

    companion object {
        private const val MAX_SCROLL = 40
    }
}
