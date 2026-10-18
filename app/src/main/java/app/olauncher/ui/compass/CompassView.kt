package app.olauncher.ui.compass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.View
import app.olauncher.helper.dpToPx
import app.olauncher.ui.UiKit
import kotlin.math.min

/**
 * The compass dial: ticks every 2°, numbers every 30° and the cardinal letters, turning so that
 * north points north, under a fixed red index at the top that marks where the phone points.
 */
class CompassView(context: Context) : View(context) {

    /** Where the phone points, in degrees clockwise from north. */
    var heading = 0f
        set(value) {
            field = value
            invalidate()
        }

    /** Letters for north, east, south and west. */
    var cardinals = listOf("N", "E", "S", "W")
        set(value) {
            field = value
            invalidate()
        }

    private val primary = UiKit.primary(context)
    private val secondary = UiKit.secondary(context)

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val index = Path()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val side = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) w else min(w, h)
        setMeasuredDimension(w, if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) h else side)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 22.dpToPx()
        if (r <= 0) return

        canvas.save()
        canvas.rotate(-heading, cx, cy)
        for (d in 0 until 360 step 2) {
            val major = d % 30 == 0
            val mid = d % 10 == 0
            val len = when {
                major -> r * 0.11f
                mid -> r * 0.075f
                else -> r * 0.04f
            }
            tickPaint.strokeWidth = (if (major) 3 else if (mid) 2 else 1).dpToPx().toFloat()
            tickPaint.color = when {
                d == 0 -> ACCENT
                mid -> primary
                else -> secondary
            }
            canvas.save()
            canvas.rotate(d.toFloat(), cx, cy)
            canvas.drawLine(cx, cy - r, cx, cy - r + len, tickPaint)
            if (major && d % 90 != 0) {
                textPaint.textSize = r * 0.085f
                textPaint.typeface = Typeface.DEFAULT
                textPaint.color = secondary
                drawCentered(canvas, d.toString(), cx, cy - r * 0.78f)
            }
            if (d % 90 == 0) {
                textPaint.textSize = r * 0.15f
                textPaint.typeface = Typeface.DEFAULT_BOLD
                textPaint.color = if (d == 0) ACCENT else primary
                drawCentered(canvas, cardinals[d / 90], cx, cy - r * 0.68f)
            }
            canvas.restore()
        }
        canvas.restore()

        // Fixed: crosshair, centre dot and the index at the top
        tickPaint.strokeWidth = 1.dpToPx().toFloat()
        tickPaint.color = secondary
        val cross = r * 0.18f
        canvas.drawLine(cx - cross, cy, cx + cross, cy, tickPaint)
        canvas.drawLine(cx, cy - cross, cx, cy + cross, tickPaint)
        fillPaint.color = primary
        canvas.drawCircle(cx, cy, 3.dpToPx().toFloat(), fillPaint)

        val w = 9.dpToPx().toFloat()
        val top = cy - r - 18.dpToPx()
        index.reset()
        index.moveTo(cx - w, top)
        index.lineTo(cx + w, top)
        index.lineTo(cx, top + 15.dpToPx())
        index.close()
        fillPaint.color = ACCENT
        canvas.drawPath(index, fillPaint)
    }

    private fun drawCentered(canvas: Canvas, text: String, x: Float, y: Float) {
        val fm = textPaint.fontMetrics
        canvas.drawText(text, x, y - (fm.ascent + fm.descent) / 2f, textPaint)
    }

    companion object {
        const val ACCENT = 0xFFE53935.toInt()
    }
}
