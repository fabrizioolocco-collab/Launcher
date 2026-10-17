package app.olauncher.ui.widgets

import android.content.Context
import android.graphics.Bitmap
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
import app.olauncher.helper.getColorFromAttr
import app.olauncher.ui.HomeSectionsController

/**
 * An app icon placed on a [WidgetGridLayout] (bottom area): icon centered in its cell, optional
 * label; red icon while the app has notifications. Tap launches (or lists the notifications), long
 * press drags or opens the menu.
 */
class IconCellView(context: Context, onTap: () -> Unit) : FrameLayout(context) {

    /** With notifications a tap lists them ([onNotifiedTap]) instead of launching. */
    val touch = GridItemTouch(this) { if (notified) onNotifiedTap?.invoke() ?: onTap() else onTap() }

    private val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val label = TextView(context, null, 0, R.style.TextSmall).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
    }
    private val iconFrame = FrameLayout(context)
    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
    }

    init {
        iconFrame.addView(icon)
        column.addView(iconFrame)
        column.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(column, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        label.setTextColor(context.getColorFromAttr(R.attr.primaryColor))
        isClickable = true
        isLongClickable = true
    }

    /** Icon edge for a cell of [cellPx], leaving room for the label when shown. */
    fun iconSizeFor(cellPx: Int, showLabel: Boolean): Int =
        (cellPx * if (showLabel) 0.62f else 0.76f).toInt()

    /** [tile]: draw the icon on a rounded square ([iconSize] is then the tile's edge). */
    fun bind(text: String, showLabel: Boolean, iconSize: Int, tile: Boolean = false) {
        label.text = text
        label.isVisible = showLabel
        contentDescription = text
        iconFrame.layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
        tileOn = tile
        tileSize = iconSize
        updateFrame()
        val inner = if (tile) (iconSize * IconTiles.ICON_FRACTION).toInt() else iconSize
        icon.layoutParams = LayoutParams(inner, inner, Gravity.CENTER)
    }

    /** The icon bitmap edge to load for a cell, given [bind]'s parameters. */
    fun bitmapSizeFor(iconSize: Int, tile: Boolean): Int = if (tile) (iconSize * IconTiles.ICON_FRACTION).toInt() else iconSize

    fun setIcon(bitmap: Bitmap?) = icon.setImageBitmap(bitmap)

    /** Tap while the app has notifications (instead of launching it). */
    var onNotifiedTap: (() -> Unit)? = null

    private var notified = false
    private var tileOn = false
    private var tileSize = 0

    /** Behind the icon: red when the app has notifications, else the tile (if on). */
    private fun updateFrame() {
        iconFrame.background = when {
            notified && tileSize > 0 -> app.olauncher.ui.NotificationBadge.background(tileSize)
            tileOn -> IconTiles.drawable(context, tileSize)
            else -> null
        }
    }

    fun setNotified(value: Boolean) {
        notified = value
        updateFrame()
        icon.colorFilter = if (value) app.olauncher.ui.NotificationBadge.redFilter else null
        label.setTextColor(if (value) HomeSectionsController.NOTIFICATION_COLOR else context.getColorFromAttr(R.attr.primaryColor))
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = touch.intercept(ev)

    override fun onTouchEvent(event: MotionEvent): Boolean = touch.touch(event)
}
