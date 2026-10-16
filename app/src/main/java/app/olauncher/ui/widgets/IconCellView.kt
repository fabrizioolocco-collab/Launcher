package app.olauncher.ui.widgets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import app.olauncher.R
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import app.olauncher.ui.HomeSectionsController

/**
 * An app icon placed on a [WidgetGridLayout] (bottom area): icon centered in its cell, optional
 * label, red badge with the notification count. Tap launches, long press drags or opens the menu.
 */
class IconCellView(context: Context, onTap: () -> Unit) : FrameLayout(context) {

    val touch = GridItemTouch(this, onTap)

    private val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val label = TextView(context, null, 0, R.style.TextSmall).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
    }
    private val badge = TextView(context).apply {
        setTextColor(0xFFFFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        minWidth = 18.dpToPx()
        minHeight = 18.dpToPx()
        setPadding(4.dpToPx(), 0, 4.dpToPx(), 0)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 9.dpToPx().toFloat()
            setColor(HomeSectionsController.NOTIFICATION_COLOR)
        }
        isVisible = false
    }
    private val iconFrame = FrameLayout(context)
    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
    }

    init {
        iconFrame.addView(icon)
        iconFrame.addView(badge, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
        column.addView(iconFrame)
        column.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(column, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        label.setTextColor(context.getColorFromAttr(R.attr.primaryColor))
        isClickable = true
        isLongClickable = true
    }

    /** Icon edge for a cell of [cellPx], leaving room for the label when shown. */
    fun iconSizeFor(cellPx: Int, showLabel: Boolean): Int =
        (cellPx * if (showLabel) 0.58f else 0.72f).toInt()

    fun bind(text: String, showLabel: Boolean, iconSize: Int) {
        label.text = text
        label.isVisible = showLabel
        contentDescription = text
        val badgeRoom = 6.dpToPx()
        iconFrame.layoutParams = LinearLayout.LayoutParams(iconSize + badgeRoom, iconSize + badgeRoom)
        icon.layoutParams = LayoutParams(iconSize, iconSize, Gravity.BOTTOM or Gravity.START)
    }

    fun setIcon(bitmap: Bitmap?) = icon.setImageBitmap(bitmap)

    fun setBadge(count: Int) {
        badge.isVisible = count > 0
        badge.text = if (count > 99) "99+" else count.toString()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = touch.intercept(ev)

    override fun onTouchEvent(event: MotionEvent): Boolean = touch.touch(event)
}
