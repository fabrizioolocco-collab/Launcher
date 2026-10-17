package app.olauncher.ui

import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.olauncher.R
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr

/**
 * The settings screen in the cards' style, applied over its layout: each group a card with a
 * thin border, its title in small grey capitals, the rows split by thin lines, the values on
 * the right in grey.
 */
object SettingsStyle {

    fun apply(scrollLayout: LinearLayout) {
        val ctx = scrollLayout.context
        val card = ctx.getColorFromAttr(R.attr.cardColor)
        val stroke = ctx.getColorFromAttr(R.attr.cardStrokeColor)
        val line = ctx.getColorFromAttr(R.attr.cardLineColor)
        val secondary = ctx.getColorFromAttr(R.attr.secondaryTextColor)
        for (i in 0 until scrollLayout.childCount) {
            val group = scrollLayout.getChildAt(i) as? LinearLayout ?: continue
            // Only the groups (the links at the bottom have no background)
            if (group.background == null) continue
            group.background = GradientDrawable().apply {
                cornerRadius = 20.dpToPx().toFloat()
                setColor(card)
                setStroke(1.dpToPx(), stroke)
            }
            group.setPadding(6.dpToPx(), 10.dpToPx(), 6.dpToPx(), 6.dpToPx())
            for (j in 0 until group.childCount) {
                val child = group.getChildAt(j)
                if (j == 0 && child is TextView) {
                    sectionTitle(child, secondary)
                    continue
                }
                if (j == 0) continue
                // A line above every row after the title (it follows the row when that is hidden)
                if (child.background == null) child.background = LayerDrawable(arrayOf(ColorDrawable(line), ColorDrawable(card))).apply {
                    setLayerInset(1, 0, 1.dpToPx(), 0, 0)
                }
                child.minimumHeight = 52.dpToPx()
                (child.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                    it.topMargin = 0
                    child.layoutParams = it
                }
                if (child is FrameLayout) row(child, secondary)
                else if (child is TextView) child.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            }
        }
    }

    private fun sectionTitle(title: TextView, color: Int) {
        title.textSize = 12f
        title.isAllCaps = true
        title.letterSpacing = 0.08f
        title.setTextColor(color)
        title.typeface = Typeface.create(title.typeface, Typeface.BOLD)
        (title.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.topMargin = 4.dpToPx()
            it.bottomMargin = 10.dpToPx()
            title.layoutParams = it
        }
    }

    /** Label on the left, value on the right, both centred in the row; the value grey and plain. */
    private fun row(row: FrameLayout, secondary: Int) {
        for (k in 0 until row.childCount) {
            val v = row.getChildAt(k)
            val lp = v.layoutParams as? FrameLayout.LayoutParams ?: continue
            val end = lp.gravity != FrameLayout.LayoutParams.UNSPECIFIED_GRAVITY &&
                    (lp.gravity and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK).let { it == Gravity.END || it == Gravity.RIGHT }
            lp.gravity = (if (end) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
            v.layoutParams = lp
            if (end && v is TextView) {
                v.setTextColor(secondary)
                v.typeface = Typeface.create(v.typeface, Typeface.NORMAL)
                v.textSize = 14f
            }
        }
    }
}
