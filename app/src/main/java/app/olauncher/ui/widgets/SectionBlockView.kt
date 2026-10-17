package app.olauncher.ui.widgets

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout

/**
 * A home section placed on a [WidgetGridLayout]: the title row on top, the app rows below. It is
 * as tall as its content and spans the whole grid width.
 *
 * Only the title row grabs the block: a long press there drags the section to another row (or opens
 * its menu when released in place), while the apps below keep their own tap, long press and swipe.
 */
open class SectionBlockView(context: Context) : LinearLayout(context) {

    val touch = GridItemTouch(this)

    /** The title row; touches below it belong to the apps. */
    var header: View? = null

    private var onHeaderRow = false

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        // Counts as an interactive area for the home gestures (no home menu on a long press here)
        isClickable = true
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            onHeaderRow = ev.y < (header?.bottom ?: 0)
            if (!onHeaderRow) touch.cancel()
        }
        if (!onHeaderRow) return false
        return touch.intercept(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) onHeaderRow = event.y < (header?.bottom ?: 0)
        if (!onHeaderRow) return false
        return touch.touch(event)
    }
}
