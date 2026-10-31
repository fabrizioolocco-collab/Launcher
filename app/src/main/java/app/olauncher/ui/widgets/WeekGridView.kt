package app.olauncher.ui.widgets

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.ScrollView
import androidx.core.graphics.ColorUtils
import app.olauncher.helper.CalendarEvents
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The calendar widget's week: seven columns, Monday to Sunday, with the timed events and todos as
 * colored blocks at their hours, the all-day ones (and the todos without a time) in a band on top,
 * and a red line for now. The text in a block shrinks as far as needed to fit whole; pinch to
 * stretch the hours (zoom) when it gets too small. A tap on a block opens it, elsewhere in a
 * column (or on its day) shows that day.
 */
@SuppressLint("ViewConstructor")
class WeekGridView(context: Context) : View(context) {

    /** Something in the week: [startMin] / [endMin] are minutes from the day's midnight (ignored when [allDay]). */
    class Item(
        val day: Int,
        val startMin: Int,
        val endMin: Int,
        val allDay: Boolean,
        val title: String,
        val color: Int,
        val dim: Boolean,
        val onTap: () -> Unit,
    )

    var weekStart = 0
    var items: List<Item> = emptyList()
    var zoom = 1f
    var textColor = Color.WHITE
    var typeface: Typeface = Typeface.DEFAULT
    /** Largest text size of the blocks, in sp (they shrink from here to fit). */
    var maxTextSp = 10f
    var onDayTap: (Int) -> Unit = {}
    /** Called at the end of a pinch with the new zoom (to keep it). */
    var onZoomed: (Float) -> Unit = {}

    private val density = resources.displayMetrics.density
    private val scaled = resources.displayMetrics.scaledDensity
    private fun dp(v: Float) = v * density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    // Layout, worked out in onMeasure
    private val gutter get() = dp(20f)
    private val headerH get() = dp(34f)
    private var bandH = 0f
    private var firstHour = 8
    private var lastHour = 20
    private val hourH get() = dp(BASE_HOUR_DP) * zoom
    private var colW = 0f
    private val gap get() = dp(2f)

    /** Drawn blocks with their place, for taps. */
    private val placed = mutableListOf<Pair<RectF, Item>>()
    /** All-day band: per item its rect and text layout. */
    private val bandLayouts = mutableListOf<Triple<RectF, Item, StaticLayout>>()
    /** Timed items: rect and the layout that fits. */
    private val blockLayouts = mutableListOf<Triple<RectF, Item, StaticLayout>>()

    private val now get() = System.currentTimeMillis()

    fun refresh() {
        requestLayout()
        invalidate()
    }

    // ---------------------------------------------------------------- layout

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        colW = ((w - gutter) / 7f).coerceAtLeast(1f)
        // Hours: 8-20, widened to whatever the week has
        val timed = items.filter { !it.allDay }
        firstHour = min(DEFAULT_FIRST, timed.minOfOrNull { floor(it.startMin / 60f).toInt() } ?: DEFAULT_FIRST).coerceIn(0, 23)
        lastHour = max(DEFAULT_LAST, timed.maxOfOrNull { ceil(it.endMin / 60f).toInt() } ?: DEFAULT_LAST).coerceIn(firstHour + 1, 24)
        layoutBand()
        layoutBlocks()
        val h = headerH + bandH + (lastHour - firstHour) * hourH + dp(6f)
        setMeasuredDimension(w, h.toInt())
    }

    private fun colLeft(i: Int) = gutter + i * colW

    private fun fitLayout(title: String, width: Int, maxHeight: Float, color: Int): StaticLayout {
        text.typeface = typeface
        text.color = contrast(color)
        var sp = maxTextSp
        var layout = build(title, width, sp)
        while (layout.height > maxHeight && sp > MIN_TEXT_SP) {
            sp = max(MIN_TEXT_SP, sp - 0.5f)
            layout = build(title, width, sp)
        }
        return layout
    }

    /** Each layout gets its own paint: the layout keeps it and draws with it later. */
    private fun build(title: String, width: Int, sp: Float): StaticLayout {
        val paint = TextPaint(text).apply { textSize = sp * scaled }
        return StaticLayout.Builder.obtain(title, 0, title.length, paint, width.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, 0.95f)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
    }

    /** The all-day items stacked in each column; the band is as tall as the fullest column. */
    private fun layoutBand() {
        bandLayouts.clear()
        val pad = dp(2f)
        var tallest = 0f
        for (i in 0 until 7) {
            var y = 0f
            items.filter { it.allDay && it.day == weekStart + i }.forEach { item ->
                val inner = (colW - gap - pad * 2).toInt()
                // Up to three lines in the band, smaller text before cutting
                val layout = fitLayout(item.title, inner, maxTextSp * scaled * 3.2f, item.color)
                val h = layout.height + pad * 2
                bandLayouts += Triple(RectF(colLeft(i), y, colLeft(i) + colW - gap, y + h), item, layout)
                y += h + gap
            }
            tallest = max(tallest, y)
        }
        bandH = if (tallest > 0) tallest + dp(4f) else 0f
    }

    /** Timed items as blocks; side by side where they overlap. */
    private fun layoutBlocks() {
        blockLayouts.clear()
        val pad = dp(2f)
        val minH = dp(14f)
        for (i in 0 until 7) {
            val day = items.filter { !it.allDay && it.day == weekStart + i }.sortedWith(compareBy({ it.startMin }, { -it.endMin }))
            // Clusters of overlapping items, each split in lanes
            var cluster = mutableListOf<Item>()
            var clusterEnd = -1
            fun flush() {
                if (cluster.isEmpty()) return
                val laneEnds = mutableListOf<Int>()
                val lanes = cluster.map { item ->
                    val shownEnd = max(item.endMin, item.startMin + (minH / hourH * 60).toInt())
                    var lane = laneEnds.indexOfFirst { it <= item.startMin }
                    if (lane < 0) {
                        laneEnds += shownEnd
                        lane = laneEnds.size - 1
                    } else laneEnds[lane] = shownEnd
                    item to lane
                }
                val laneW = (colW - gap) / laneEnds.size
                lanes.forEach { (item, lane) ->
                    val top = minuteY(item.startMin)
                    val bottom = max(minuteY(item.endMin), top + minH)
                    val left = colLeft(i) + lane * laneW
                    val r = RectF(left, top, left + laneW - (if (lane < laneEnds.size - 1) gap else 0f), bottom - dp(1f))
                    val layout = fitLayout(item.title, (r.width() - pad * 2).toInt(), r.height() - pad * 2, item.color)
                    blockLayouts += Triple(r, item, layout)
                }
                cluster = mutableListOf()
                clusterEnd = -1
            }
            day.forEach { item ->
                val shownEnd = max(item.endMin, item.startMin + (minH / hourH * 60).toInt())
                if (cluster.isNotEmpty() && item.startMin >= clusterEnd) flush()
                cluster += item
                clusterEnd = max(clusterEnd, shownEnd)
            }
            flush()
        }
    }

    private fun minuteY(minute: Int) = headerH + bandH + (minute.coerceIn(firstHour * 60, lastHour * 60) - firstHour * 60) / 60f * hourH

    /** Dark text on light colors, white on dark ones. */
    private fun contrast(color: Int) = if (ColorUtils.calculateLuminance(color or 0xFF000000.toInt()) > 0.45) 0xFF10141C.toInt() else Color.WHITE

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        placed.clear()
        val today = CalendarEvents.julianDay(now)
        val gridTop = headerH + bandH
        val gridBottom = gridTop + (lastHour - firstHour) * hourH

        // Column grounds, today's tinted
        for (i in 0 until 7) {
            fill.color = ColorUtils.setAlphaComponent(textColor, if (weekStart + i == today) 0x22 else 0x0C)
            rect.set(colLeft(i), gridTop, colLeft(i) + colW - gap, gridBottom)
            canvas.drawRoundRect(rect, dp(5f), dp(5f), fill)
        }
        // Hour lines and labels
        text.typeface = typeface
        text.textSize = 9f * scaled
        text.color = ColorUtils.setAlphaComponent(textColor, 0x8C)
        text.textAlign = Paint.Align.RIGHT
        fill.color = ColorUtils.setAlphaComponent(textColor, 0x16)
        val step = if (hourH < dp(16f)) 2 else 1
        for (h in firstHour..lastHour) {
            val y = gridTop + (h - firstHour) * hourH
            if (h in (firstHour + 1) until lastHour) canvas.drawRect(gutter, y, colLeft(7) - gap, y + max(1f, dp(0.5f)), fill)
            if ((h - firstHour) % step == 0 && h < lastHour) canvas.drawText(h.toString(), gutter - dp(4f), y + text.textSize * 0.8f, text)
        }
        text.textAlign = Paint.Align.LEFT

        // Day headers: initial and number, today's number in a circle
        val initial = SimpleDateFormat("EEEEE", Locale.getDefault())
        for (i in 0 until 7) {
            val day = weekStart + i
            val cx = colLeft(i) + (colW - gap) / 2
            text.textAlign = Paint.Align.CENTER
            text.textSize = 10f * scaled
            text.typeface = Typeface.create(typeface, Typeface.BOLD)
            text.color = ColorUtils.setAlphaComponent(textColor, 0xA0)
            canvas.drawText(initial.format(Date(CalendarEvents.dayStart(day) + 12 * 3_600_000L)).uppercase(Locale.getDefault()), cx, dp(10f), text)
            text.textSize = 14f * scaled
            val number = SimpleDateFormat("d", Locale.getDefault()).format(Date(CalendarEvents.dayStart(day) + 12 * 3_600_000L))
            if (day == today) {
                fill.color = textColor
                canvas.drawCircle(cx, dp(23f), dp(10f), fill)
                text.color = contrast(textColor)
            } else text.color = textColor
            canvas.drawText(number, cx, dp(23f) + text.textSize * 0.36f, text)
        }
        text.textAlign = Paint.Align.LEFT
        text.typeface = typeface

        // All-day band and blocks
        val pad = dp(2f)
        bandLayouts.forEach { (r0, item, layout) ->
            val r = RectF(r0).apply { offset(0f, headerH) }
            drawBlock(canvas, r, item, layout, pad)
        }
        blockLayouts.forEach { (r, item, layout) -> drawBlock(canvas, r, item, layout, pad) }

        // Now
        val todayIndex = today - weekStart
        if (todayIndex in 0..6) {
            val minute = ((now - CalendarEvents.dayStart(today)) / 60_000L).toInt()
            if (minute in firstHour * 60..lastHour * 60) {
                val y = minuteY(minute)
                fill.color = NOW_COLOR
                canvas.drawRect(colLeft(todayIndex) - dp(1f), y - dp(1f), colLeft(todayIndex) + colW - gap + dp(1f), y + dp(1f), fill)
                canvas.drawCircle(colLeft(todayIndex), y, dp(3f), fill)
            }
        }
    }

    private fun drawBlock(canvas: Canvas, r: RectF, item: Item, layout: StaticLayout, pad: Float) {
        placed += r to item
        val alpha = if (item.dim) 0x66 else 0xEE
        fill.color = ColorUtils.setAlphaComponent(item.color or 0xFF000000.toInt(), alpha)
        canvas.drawRoundRect(r, dp(4f), dp(4f), fill)
        canvas.save()
        canvas.clipRect(r.left + pad, r.top + pad, r.right - pad, r.bottom - pad)
        canvas.translate(r.left + pad, r.top + pad)
        layout.paint.color = contrast(item.color)
        layout.paint.alpha = if (item.dim) 0xB0 else 0xFF
        layout.draw(canvas)
        canvas.restore()
    }

    // ---------------------------------------------------------------- touch

    private var pinching = false

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            pinching = true
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        override fun onScale(d: ScaleGestureDetector): Boolean {
            // Mostly the vertical spread counts: the hours stretch
            val factor = if (d.previousSpanY > dp(20f)) d.currentSpanY / d.previousSpanY else d.scaleFactor
            val old = zoom
            zoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
            if (zoom != old) {
                // Keep the hour under the fingers in place
                val scroll = generateSequence(parent) { it.parent }.firstOrNull { it is ScrollView } as? ScrollView
                val focus = d.focusY - (headerH + bandH)
                val shift = focus * (zoom / old - 1f)
                refresh()
                if (scroll != null && shift != 0f) post { scroll.scrollBy(0, shift.toInt()) }
            }
            return true
        }

        override fun onScaleEnd(d: ScaleGestureDetector) {
            onZoomed(zoom)
        }
    })

    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (pinching) return false
            placed.lastOrNull { it.first.contains(e.x, e.y) }?.let {
                it.second.onTap()
                return true
            }
            val i = ((e.x - gutter) / colW).toInt()
            if (e.x >= gutter && i in 0..6) onDayTap(weekStart + i)
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) pinching = false
        scaler.onTouchEvent(event)
        if (event.pointerCount == 1 && !scaler.isInProgress) taps.onTouchEvent(event)
        return true
    }

    companion object {
        private const val BASE_HOUR_DP = 22f
        private const val DEFAULT_FIRST = 8
        private const val DEFAULT_LAST = 20
        private const val MIN_TEXT_SP = 5.5f
        const val MIN_ZOOM = 0.6f
        const val MAX_ZOOM = 5f
        private const val NOW_COLOR = 0xFFEF5350.toInt()
    }
}
