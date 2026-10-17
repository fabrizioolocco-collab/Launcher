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
import app.olauncher.helper.dpToPx
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

    /** As a card: the square the card fills (column and count), centered in the cell. */
    private val cardFrame = FrameLayout(context)

    /** The count of a card with notifications. */
    private val badge = TextView(context).apply {
        textSize = 10.5f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(0xFFFFFFFF.toInt())
        gravity = Gravity.CENTER
        minWidth = 18.dpToPx()
        setPadding(5.dpToPx(), 0, 5.dpToPx(), 0)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 9.dpToPx().toFloat()
            setColor(BADGE_COLOR)
        }
        isVisible = false
    }

    init {
        iconFrame.addView(icon)
        column.addView(iconFrame)
        column.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        cardFrame.addView(column, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        cardFrame.addView(badge, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 18.dpToPx(), Gravity.TOP or Gravity.END).apply {
            topMargin = 6.dpToPx(); marginEnd = 6.dpToPx()
        })
        addView(cardFrame, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        label.setTextColor(context.getColorFromAttr(R.attr.primaryColor))
        isClickable = true
        isLongClickable = true
    }

    /** Icon edge for a cell of [cellPx], leaving room for the label when shown. */
    fun iconSizeFor(cellPx: Int, showLabel: Boolean): Int =
        (cellPx * if (showLabel) 0.62f else 0.76f).toInt()

    /** Icon edge on a card in a cell of [cellPx] (the card is the cell's square, a little inset). */
    fun cardIconSizeFor(cellPx: Int, showLabel: Boolean): Int =
        ((cellPx - 2 * CARD_INSET_DP.dpToPx()) * if (showLabel) 0.4f else 0.5f).toInt()

    /**
     * [tile]: draw the icon on a rounded square ([iconSize] is then the tile's edge). [card]: the
     * whole cell's square is a card with the icon and the name inside, like the app drawer's.
     */
    fun bind(text: String, showLabel: Boolean, iconSize: Int, tile: Boolean = false, card: Boolean = false) {
        label.text = text
        label.isVisible = showLabel
        contentDescription = text
        iconFrame.layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
        cardOn = card
        tileOn = tile && !card
        tileSize = iconSize
        label.setPadding(if (card) 4.dpToPx() else 0, if (card) 4.dpToPx() else 0, if (card) 4.dpToPx() else 0, 0)
        label.alpha = if (card) 0.85f else 1f
        updateFrame()
        val inner = if (tileOn) (iconSize * IconTiles.ICON_FRACTION).toInt() else iconSize
        icon.layoutParams = LayoutParams(inner, inner, Gravity.CENTER)
        requestLayout()
    }

    /** The icon bitmap edge to load for a cell, given [bind]'s parameters. */
    fun bitmapSizeFor(iconSize: Int, tile: Boolean, card: Boolean = false): Int =
        if (tile && !card) (iconSize * IconTiles.ICON_FRACTION).toInt() else iconSize

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // A card is square: the largest one that fits the cell, a little inset
        val lp = cardFrame.layoutParams as LayoutParams
        if (cardOn) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = MeasureSpec.getSize(heightMeasureSpec)
            val side = ((if (h > 0) minOf(w, h) else w) - 2 * CARD_INSET_DP.dpToPx()).coerceAtLeast(0)
            if (lp.width != side || lp.gravity != Gravity.CENTER) {
                // Changed in place: measured right below
                lp.width = side; lp.height = side; lp.gravity = Gravity.CENTER
            }
            // The name always fits: the icon gives way on a small card
            val labelRoom = if (label.isVisible) label.lineHeight + label.paddingTop else 0
            val maxIcon = (side - labelRoom - 2 * CARD_PADDING_DP.dpToPx()).coerceAtLeast(12.dpToPx())
            val edge = minOf(tileSize, maxIcon)
            val frame = iconFrame.layoutParams
            if (frame.width != edge) {
                frame.width = edge; frame.height = edge
                icon.layoutParams.width = edge; icon.layoutParams.height = edge
            }
        } else if (lp.width != ViewGroup.LayoutParams.MATCH_PARENT) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT; lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            lp.gravity = LayoutParams.UNSPECIFIED_GRAVITY
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun setIcon(bitmap: Bitmap?) = icon.setImageBitmap(bitmap)

    /** Tap while the app has notifications (instead of launching it). */
    var onNotifiedTap: (() -> Unit)? = null

    private var notified = false
    private var count = 0
    private var tileOn = false
    private var cardOn = false
    private var tileSize = 0

    /** Behind the icon: red when the app has notifications, else the tile (if on); as a card the card itself. */
    private fun updateFrame() {
        if (cardOn) {
            iconFrame.background = null
            cardFrame.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 18.dpToPx().toFloat()
                setColor(if (notified) CARD_RED else context.getColorFromAttr(R.attr.cardColor))
                setStroke(1.dpToPx(), if (notified) CARD_RED_STROKE else context.getColorFromAttr(R.attr.cardStrokeColor))
            }
            badge.isVisible = notified && count > 0
            badge.text = if (count > 99) "99+" else count.toString()
            return
        }
        cardFrame.background = null
        badge.isVisible = false
        iconFrame.background = when {
            notified && tileSize > 0 -> app.olauncher.ui.NotificationBadge.background(tileSize)
            tileOn -> IconTiles.drawable(context, tileSize)
            else -> null
        }
    }

    /** [count] notifications (0 = none): a red card with the count, or a red icon. */
    fun setNotified(count: Int) {
        this.count = count
        notified = count > 0
        updateFrame()
        // On a card the card turns red and the icon stays as it is
        icon.colorFilter = if (notified && !cardOn) app.olauncher.ui.NotificationBadge.redFilter else null
        label.setTextColor(if (notified && !cardOn) HomeSectionsController.NOTIFICATION_COLOR else context.getColorFromAttr(R.attr.primaryColor))
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = touch.intercept(ev)

    override fun onTouchEvent(event: MotionEvent): Boolean = touch.touch(event)

    companion object {
        /** Space around a card inside its cell (the gap between two cards is twice this). */
        const val CARD_INSET_DP = 3

        /** Least room above and below the icon and name on a card. */
        private const val CARD_PADDING_DP = 5
        private const val CARD_RED = 0xFF3A1715.toInt()
        private const val CARD_RED_STROKE = 0xFFB3261E.toInt()
        private const val BADGE_COLOR = 0xFFC62828.toInt()
    }
}
