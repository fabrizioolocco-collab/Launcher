package app.olauncher.ui.widgets

import android.content.Context
import android.graphics.drawable.GradientDrawable
import app.olauncher.R
import app.olauncher.helper.getColorFromAttr

/** The rounded square drawn behind app icons (home grid and app list grid). */
object IconTiles {
    /** Fraction of the tile taken by the icon itself. */
    const val ICON_FRACTION = 0.6f

    fun drawable(context: Context, sizePx: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = sizePx * 0.27f
        setColor(context.getColorFromAttr(R.attr.tileColor))
        setSize(sizePx, sizePx)
    }
}
