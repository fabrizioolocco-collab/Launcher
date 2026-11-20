package app.olauncher.ui.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import app.olauncher.helper.dpToPx
import app.olauncher.ui.UiKit
import java.text.DateFormatSymbols
import java.util.Calendar

/**
 * A month of the recorder's calendar: the days in a grid, with a dot for each recording made that
 * day (three at most), today outlined and the chosen day filled. A tap on a day picks it.
 */
class RecorderMonthView(context: Context) : View(context) {

    /** Called with the day of the month that was tapped. */
    var onPick: ((Int) -> Unit)? = null

    private var days = 30
    /** Empty cells before the 1st. */
    private var lead = 0
    private var counts: Map<Int, Int> = emptyMap()
    private var selected = 0
    private var today = 0
    private var labels: List<String> = emptyList()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val header = 22.dpToPx()
    private val rowHeight = 42.dpToPx()

    private val rows get() = (lead + days + 6) / 7

    /** Shows [month] (0 = January) of [year]; [selected] and [today] are days of it, 0 when in another month. */
    fun show(year: Int, month: Int, counts: Map<Int, Int>, selected: Int, today: Int) {
        val calendar = Calendar.getInstance().apply {
            clear()
            set(year, month, 1)
        }
        days = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
        val first = calendar.firstDayOfWeek
        lead = (calendar.get(Calendar.DAY_OF_WEEK) - first + 7) % 7
        val names = DateFormatSymbols.getInstance().shortWeekdays
        labels = (0 until 7).map { names[(first - 1 + it) % 7 + 1].take(1).uppercase() }
        this.counts = counts
        this.selected = selected
        this.today = today
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), header + rows * rowHeight)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val primary = UiKit.primary(context)
        val secondary = UiKit.secondary(context)
        val cell = width / 7f
        paint.textAlign = Paint.Align.CENTER
        paint.style = Paint.Style.FILL
        paint.isFakeBoldText = false
        paint.textSize = 11.dpToPx().toFloat()
        paint.color = secondary
        labels.forEachIndexed { i, label -> canvas.drawText(label, cell * (i + 0.5f), 13.dpToPx().toFloat(), paint) }
        val radius = 12.dpToPx().toFloat()
        val inset = 3.dpToPx().toFloat()
        for (day in 1..days) {
            val index = lead + day - 1
            val left = cell * (index % 7)
            val top = header + rowHeight * (index / 7).toFloat()
            box.set(left + inset, top + 1.dpToPx(), left + cell - inset, top + rowHeight - 1.dpToPx())
            val chosen = day == selected
            if (chosen) {
                paint.style = Paint.Style.FILL
                paint.color = primary
                canvas.drawRoundRect(box, radius, radius, paint)
            } else if (day == today) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.dpToPx().toFloat()
                paint.color = secondary
                canvas.drawRoundRect(box, radius, radius, paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = if (chosen) UiKit.card(context) else primary
            paint.isFakeBoldText = chosen
            paint.textSize = 14.dpToPx().toFloat()
            canvas.drawText(day.toString(), left + cell / 2, top + 22.dpToPx(), paint)
            val dots = (counts[day] ?: 0).coerceAtMost(3)
            if (dots > 0) {
                paint.color = if (chosen) UiKit.card(context) else DOT
                val size = 2.dpToPx().toFloat()
                val gap = 6.dpToPx().toFloat()
                val start = left + cell / 2 - gap * (dots - 1) / 2
                for (d in 0 until dots) canvas.drawCircle(start + gap * d, top + 31.dpToPx(), size, paint)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP && event.y > header && width > 0) {
            val column = (event.x / (width / 7f)).toInt().coerceIn(0, 6)
            val row = ((event.y - header) / rowHeight).toInt()
            val day = row * 7 + column - lead + 1
            if (day in 1..days) onPick?.invoke(day)
        }
        return true
    }

    companion object {
        private const val DOT = 0xFFE0564F.toInt()
    }
}
