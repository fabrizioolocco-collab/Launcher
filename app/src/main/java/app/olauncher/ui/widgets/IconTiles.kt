package app.olauncher.ui.widgets

import app.olauncher.helper.Skin
import android.content.Context
import android.graphics.drawable.GradientDrawable
import app.olauncher.R
import app.olauncher.helper.getColorFromAttr

/** The rounded square drawn behind app icons (home grid and app list grid). */
object IconTiles {
    private fun Int.dpToPxCompat(context: Context) = (this * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)

    /** Fraction of the tile taken by the icon itself. */
    const val ICON_FRACTION = 0.6f

    fun drawable(context: Context, sizePx: Int): android.graphics.drawable.Drawable {
        if (!Skin.isOn(context)) return rounded(context, sizePx)
        // A look: its own shape (two cut corners, or a rounded sheet of glass)
        val dp = sizePx / context.resources.displayMetrics.density
        return Skin.panel(
            context, context.getColorFromAttr(R.attr.tileColor), context.getColorFromAttr(R.attr.cardStrokeColor),
            (dp * 0.2f).toInt().coerceAtLeast(4), (dp * 0.27f).toInt(),
        )
    }

    private fun rounded(context: Context, sizePx: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = sizePx * 0.27f
        setColor(context.getColorFromAttr(R.attr.tileColor))
        // A thin border, like the cards
        setStroke(1.dpToPxCompat(context), context.getColorFromAttr(R.attr.cardStrokeColor))
        setSize(sizePx, sizePx)
    }
}
