package app.olauncher.helper

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import androidx.core.content.res.ResourcesCompat
import app.olauncher.R
import app.olauncher.data.Prefs

/**
 * The "tactical" theme (setting in Appearance): olive and sand colours (the TacticalOverlay style),
 * a faint camouflage behind the home, panels and tiles with two cut corners, the clock in a stencil
 * face, military date and countdowns in the calendar widget. Off, nothing changes.
 */
object Tactical {

    const val SAND = 0xFFC8AE6E.toInt()
    const val KHAKI = 0xFFD9D2AE.toInt()
    const val KHAKI_DIM = 0xFF8E8A6C.toInt()
    const val PANEL = 0xFF1F2318.toInt()
    const val PANEL_STROKE = 0xFF343A28.toInt()
    const val GROUND = 0xFF121410.toInt()
    const val ALERT = 0xFFFF7A1A.toInt()

    fun isOn(context: Context) = Prefs(context).tacticalTheme

    fun stencil(context: Context): Typeface? = try {
        ResourcesCompat.getFont(context, R.font.ml_saira_stencil)
    } catch (e: Exception) {
        null
    }

    fun body(context: Context): Typeface? = try {
        ResourcesCompat.getFont(context, R.font.ml_saira_condensed)
    } catch (e: Exception) {
        null
    }

    /** A panel with the top-left and bottom-right corners cut by [cutDp]. */
    fun panel(fill: Int = PANEL, stroke: Int = PANEL_STROKE, cutDp: Int = 10): Drawable = NotchedDrawable(fill, stroke, cutDp.dpToPx().toFloat())

    /** The home ground: dark olive with a few faint blotches. */
    fun ground(): Drawable = CamoDrawable()

    private class NotchedDrawable(private val fill: Int, private val stroke: Int, private val cut: Float) : Drawable() {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = fill }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = stroke
            strokeWidth = 1.dpToPx().toFloat()
        }
        private val path = Path()

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            val half = strokePaint.strokeWidth / 2
            val l = bounds.left + half
            val t = bounds.top + half
            val r = bounds.right - half
            val b = bounds.bottom - half
            val c = cut.coerceAtMost(minOf(r - l, b - t) / 3)
            path.reset()
            path.moveTo(l + c, t)
            path.lineTo(r, t)
            path.lineTo(r, b - c)
            path.lineTo(r - c, b)
            path.lineTo(l, b)
            path.lineTo(l, t + c)
            path.close()
        }

        override fun draw(canvas: Canvas) {
            if (Color.alpha(fill) > 0) canvas.drawPath(path, fillPaint)
            if (Color.alpha(stroke) > 0) canvas.drawPath(path, strokePaint)
        }

        override fun getOutline(outline: android.graphics.Outline) {
            @Suppress("DEPRECATION")
            if (android.os.Build.VERSION.SDK_INT >= 30) outline.setPath(path) else outline.setConvexPath(path)
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha
            strokePaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            fillPaint.colorFilter = colorFilter
            strokePaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private object Color {
        fun alpha(c: Int) = c ushr 24
    }

    private class CamoDrawable : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        /** Blotches: centre (fractions of the size), radius (fraction of the width), colour. */
        private val blotches = listOf(
            floatArrayOf(0.2f, 0.25f, 0.55f), floatArrayOf(0.85f, 0.45f, 0.5f),
            floatArrayOf(0.3f, 0.7f, 0.5f), floatArrayOf(0.75f, 0.9f, 0.45f),
        )
        private val colors = intArrayOf(0xFF1D2116.toInt(), 0xFF1A1D13.toInt(), 0xFF1C2015.toInt(), 0xFF191C12.toInt())

        override fun draw(canvas: Canvas) {
            val b = bounds
            canvas.drawColor(GROUND)
            blotches.forEachIndexed { i, (cx, cy, r) ->
                val x = b.left + b.width() * cx
                val y = b.top + b.height() * cy
                val radius = b.width() * r
                paint.shader = RadialGradient(x, y, radius, colors[i], colors[i] and 0x00FFFFFF, Shader.TileMode.CLAMP)
                canvas.drawCircle(x, y, radius, paint)
            }
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.OPAQUE
    }
}
