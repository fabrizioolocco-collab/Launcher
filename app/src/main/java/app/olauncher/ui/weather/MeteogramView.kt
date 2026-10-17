package app.olauncher.ui.weather

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import app.olauncher.R
import app.olauncher.data.WeatherHour
import app.olauncher.data.WeatherIcons
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The hourly meteogram, drawn on a canvas one column per hour. In [header] mode it draws the day
 * names, the weather icons and the hours; otherwise the charts (temperature, rain, wind, ...).
 * It sits in a HorizontalScrollView: [scrollPos] is that scroll, so the section titles and the
 * day names stay at the left edge of what's visible.
 */
class MeteogramView(context: Context, private val hours: List<WeatherHour>, private val header: Boolean) : View(context) {

    var scrollPos = 0
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val colW = COL_DP.dpToPx()
    private val primary = context.getColorFromAttr(R.attr.primaryColor)
    private val secondary = context.getColorFromAttr(R.attr.secondaryTextColor)
    private val lineColor = context.getColorFromAttr(R.attr.cardLineColor)
    private val band = 0x0FFFFFFF
    private val nowColor = ColorUtils.setAlphaComponent(primary, 0x66)

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val icons = HashMap<Int, Drawable?>()

    /** Index of the hours where a new day starts (0 included). */
    private val dayStarts: List<Int> = run {
        val out = ArrayList<Int>()
        val cal = Calendar.getInstance()
        var last = -1
        hours.forEachIndexed { i, h ->
            cal.timeInMillis = h.time
            val key = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
            if (key != last) {
                out.add(i)
                last = key
            }
        }
        out
    }

    private class Section(
        val icon: Int,
        val title: String,
        val unit: String,
        val color: Int,
        val values: FloatArray,
        val label: (Int) -> String,
        val fixedMin: Float? = null,
        val minRange: Float = 0f,
        val fixedMax: Float? = null,
        val labelEvery: Int = 1,
        val strongFill: Boolean = false,
        val arrows: Boolean = false,
    )

    private val sections: List<Section> = if (header) emptyList() else buildSections()

    private fun buildSections(): List<Section> {
        val loc = Locale.getDefault()
        val amber = 0xFFC9A227.toInt()
        val blue = 0xFF4FA3F7.toInt()
        val grey = 0xFF9AA4AD.toInt()
        val teal = 0xFF2BB3A0.toInt()
        val purple = 0xFF9F8CF0.toInt()
        fun arr(f: (WeatherHour) -> Float) = FloatArray(hours.size) { f(hours[it]) }
        val temp = arr { it.temp }
        val feels = arr { it.feels }
        val precip = arr { it.precip }
        val wind = arr { it.wind }
        val pressure = arr { it.pressure }
        val cloud = arr { it.cloud.toFloat() }
        val humidity = arr { it.humidity.toFloat() }
        val dew = arr { it.dewPoint }
        val uv = arr { it.uv }
        val all = listOf(
            Section(R.drawable.ic_m_thermo, context.getString(R.string.ml_w_temp), "°C", amber, temp, { "${temp[it].roundToInt()}°" }, minRange = 4f),
            Section(R.drawable.ic_m_feels, context.getString(R.string.ml_w_feels), "°C", amber, feels, { "${feels[it].roundToInt()}°" }, minRange = 4f),
            Section(R.drawable.ic_m_rain_line, context.getString(R.string.ml_w_precip), "mm", blue, precip, { String.format(loc, "%.1f", precip[it]) },
                fixedMin = 0f, minRange = 2f),
            Section(R.drawable.ic_m_wind, context.getString(R.string.ml_w_wind), "m/s", grey, wind, { String.format(loc, "%.1f", wind[it]) },
                fixedMin = 0f, minRange = 4f, arrows = true),
            Section(R.drawable.ic_m_gauge, context.getString(R.string.ml_w_pressure), "hPa", teal, pressure, { pressure[it].roundToInt().toString() },
                minRange = 6f, labelEvery = 2),
            Section(R.drawable.ic_m_cloud_line, context.getString(R.string.ml_w_cloud), "%", grey, cloud, { cloud[it].roundToInt().toString() },
                fixedMin = 0f, fixedMax = 100f, strongFill = true),
            Section(R.drawable.ic_m_drop, context.getString(R.string.ml_w_humidity), "%", blue, humidity, { humidity[it].roundToInt().toString() },
                fixedMin = 0f, fixedMax = 100f),
            Section(R.drawable.ic_m_dew, context.getString(R.string.ml_w_dew), "°C", blue, dew, { "${dew[it].roundToInt()}°" }, minRange = 4f),
            Section(R.drawable.ic_m_uv, context.getString(R.string.ml_w_uv), "/11", purple, uv, { uv[it].roundToInt().toString() },
                fixedMin = 0f, minRange = 8f),
        )
        // In the order chosen in the editor, the hidden ones left out
        val byKey = WeatherSections.ALL.zip(all).toMap()
        return WeatherSections.shown(context).mapNotNull { byKey[it] }
    }

    private val titleH = TITLE_DP.dpToPx()
    private val chartH = CHART_DP.dpToPx()
    private val arrowsH = ARROWS_DP.dpToPx()

    private fun sectionHeight(s: Section) = titleH + chartH + if (s.arrows) arrowsH else 0

    private val totalHeight: Int = if (header) HEADER_DP.dpToPx() else sections.sumOf { sectionHeight(it) } + 8.dpToPx()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(max(1, hours.size * colW), totalHeight)
    }

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private fun icon(res: Int): Drawable? = icons.getOrPut(res) { ContextCompat.getDrawable(context, res)?.mutate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (hours.isEmpty()) return
        val h = height.toFloat()
        // Alternate columns
        fillPaint.shader = null
        fillPaint.color = band
        for (i in hours.indices step 2) {
            canvas.drawRect((i * colW).toFloat(), 0f, ((i + 1) * colW).toFloat(), h, fillPaint)
        }
        // Day boundaries
        strokePaint.shader = null
        strokePaint.color = lineColor
        strokePaint.strokeWidth = 1.dpToPx().toFloat()
        dayStarts.forEach { i ->
            if (i > 0) canvas.drawLine((i * colW).toFloat(), 0f, (i * colW).toFloat(), h, strokePaint)
        }
        if (header) drawHeader(canvas) else drawBody(canvas)
        // Now
        val now = System.currentTimeMillis()
        val nowX = colW / 2f + (now - hours[0].time) / 3_600_000f * colW
        if (nowX >= 0 && nowX <= width) {
            strokePaint.color = nowColor
            strokePaint.strokeWidth = 1.dpToPx().toFloat()
            canvas.drawLine(nowX, 0f, nowX, h, strokePaint)
        }
    }

    // ---------------------------------------------------------------- header

    private fun drawHeader(canvas: Canvas) {
        val pad = 8.dpToPx().toFloat()
        val dayFmt = SimpleDateFormat("EEE d", Locale.getDefault())
        // Row 1: day names, kept visible while their day is on screen
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = sp(13f)
        textPaint.isFakeBoldText = true
        textPaint.color = primary
        val row1 = 18.dpToPx().toFloat()
        dayStarts.forEachIndexed { n, start ->
            val end = if (n + 1 < dayStarts.size) dayStarts[n + 1] else hours.size
            val label = if (n == 0) context.getString(R.string.ml_cal_today)
            else dayFmt.format(hours[start].time).replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            val w = textPaint.measureText(label)
            val startX = start * colW + pad
            val endX = end * colW - pad - w
            val x = if (endX < startX) startX else min(max(startX, scrollPos + pad), endX)
            canvas.drawText(label, x, row1, textPaint)
        }
        textPaint.isFakeBoldText = false
        // Row 2: icons
        val iconSize = 32.dpToPx()
        val iconTop = 26.dpToPx()
        hours.forEachIndexed { i, hour ->
            val d = icon(WeatherIcons.icon(hour.code, hour.isDay)) ?: return@forEachIndexed
            val left = i * colW + (colW - iconSize) / 2
            d.setBounds(left, iconTop, left + iconSize, iconTop + iconSize)
            d.draw(canvas)
        }
        // Row 3: hours
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = sp(13f)
        textPaint.color = secondary
        val row3 = (iconTop + iconSize + 18.dpToPx()).toFloat()
        hours.forEachIndexed { i, hour ->
            canvas.drawText(WeatherIcons.hourLabel(hour.time), i * colW + colW / 2f, row3, textPaint)
        }
        // Bottom line
        strokePaint.color = lineColor
        strokePaint.strokeWidth = 1.dpToPx().toFloat()
        canvas.drawLine(0f, height - 0.5f, width.toFloat(), height - 0.5f, strokePaint)
    }

    // ---------------------------------------------------------------- body

    private fun drawBody(canvas: Canvas) {
        var top = 0f
        sections.forEachIndexed { n, s ->
            if (n > 0) {
                strokePaint.shader = null
                strokePaint.color = lineColor
                strokePaint.strokeWidth = 1.dpToPx().toFloat()
                canvas.drawLine(0f, top, width.toFloat(), top, strokePaint)
            }
            drawTitle(canvas, s, top)
            drawChart(canvas, s, top + titleH, top + titleH + chartH)
            if (s.arrows) drawArrows(canvas, top + titleH + chartH)
            top += sectionHeight(s)
        }
    }

    private fun drawTitle(canvas: Canvas, s: Section, top: Float) {
        val x0 = scrollPos + 16.dpToPx().toFloat()
        val iconSize = 16.dpToPx()
        val cy = top + titleH / 2f + 2.dpToPx()
        icon(s.icon)?.let { d ->
            d.setTint(s.color)
            val t = (cy - iconSize / 2f).toInt()
            d.setBounds(x0.toInt(), t, x0.toInt() + iconSize, t + iconSize)
            d.draw(canvas)
        }
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = sp(14f)
        textPaint.color = primary
        textPaint.isFakeBoldText = true
        val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        val tx = x0 + iconSize + 8.dpToPx()
        canvas.drawText(s.title, tx, baseline, textPaint)
        val tw = textPaint.measureText(s.title)
        textPaint.isFakeBoldText = false
        textPaint.textSize = sp(12.5f)
        textPaint.color = secondary
        canvas.drawText(s.unit, tx + tw + 6.dpToPx(), baseline, textPaint)
    }

    private fun drawChart(canvas: Canvas, s: Section, top: Float, bottom: Float) {
        val v = s.values
        if (v.isEmpty()) return
        var lo = s.fixedMin ?: v.min()
        var hi = s.fixedMax ?: v.max()
        if (s.fixedMin == null && s.fixedMax == null) {
            val range = hi - lo
            val pad = max(range * 0.15f, (s.minRange - range) / 2f)
            lo -= pad
            hi += pad
        } else if (s.fixedMax == null) {
            hi = max(hi * 1.15f, lo + s.minRange)
        }
        if (hi - lo < 0.0001f) {
            lo -= 1f
            hi += 1f
        }
        val plotTop = top + 24.dpToPx()
        val plotBottom = bottom - 6.dpToPx()
        fun y(value: Float) = plotBottom - (value - lo) / (hi - lo) * (plotBottom - plotTop)
        fun x(i: Int) = i * colW + colW / 2f

        // Line, extended flat to both edges
        path.reset()
        path.moveTo(0f, y(v[0]))
        for (i in v.indices) path.lineTo(x(i), y(v[i]))
        path.lineTo(width.toFloat(), y(v[v.size - 1]))
        // Fill under it
        val fill = Path(path)
        fill.lineTo(width.toFloat(), bottom)
        fill.lineTo(0f, bottom)
        fill.close()
        fillPaint.shader = LinearGradient(0f, plotTop, 0f, bottom,
            ColorUtils.setAlphaComponent(s.color, if (s.strongFill) 0x88 else 0x55),
            ColorUtils.setAlphaComponent(s.color, if (s.strongFill) 0x22 else 0x00), Shader.TileMode.CLAMP)
        canvas.drawPath(fill, fillPaint)
        fillPaint.shader = null
        strokePaint.shader = null
        strokePaint.color = s.color
        strokePaint.strokeWidth = 2.dpToPx().toFloat()
        canvas.drawPath(path, strokePaint)

        // Points and values
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = sp(12f)
        textPaint.color = primary
        fillPaint.color = s.color
        val r = 2.5f * resources.displayMetrics.density
        for (i in v.indices) {
            val px = x(i)
            val py = y(v[i])
            canvas.drawCircle(px, py, r, fillPaint)
            if (i % s.labelEvery == 0) canvas.drawText(s.label(i), px, py - 7.dpToPx(), textPaint)
        }
    }

    /** Arrows showing where the wind goes (it comes from windDir). */
    private fun drawArrows(canvas: Canvas, top: Float) {
        strokePaint.shader = null
        strokePaint.color = ColorUtils.setAlphaComponent(primary, 0xCC)
        strokePaint.strokeWidth = 1.5f * resources.displayMetrics.density
        val cy = top + arrowsH / 2f
        val len = 7.dpToPx().toFloat()
        val head = 4.dpToPx().toFloat()
        hours.forEachIndexed { i, hour ->
            val cx = i * colW + colW / 2f
            canvas.save()
            // 0° = arrow pointing up (north); rotate clockwise to where the wind goes
            canvas.rotate(((hour.windDir + 180) % 360).toFloat(), cx, cy)
            canvas.drawLine(cx, cy + len, cx, cy - len, strokePaint)
            canvas.drawLine(cx, cy - len, cx - head, cy - len + head, strokePaint)
            canvas.drawLine(cx, cy - len, cx + head, cy - len + head, strokePaint)
            canvas.restore()
        }
    }

    companion object {
        const val COL_DP = 64
        const val TITLE_DP = 34
        const val CHART_DP = 110
        const val ARROWS_DP = 26
        const val HEADER_DP = 94
    }
}

/** The min–max bar of a day, placed on the range of the whole week. */
class TempRangeBar(context: Context, private val dayMin: Float, private val dayMax: Float, private val weekMin: Float, private val weekMax: Float) :
    View(context) {

    private val track = ColorUtils.setAlphaComponent(context.getColorFromAttr(R.attr.secondaryTextColor), 0x40)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = 6.dpToPx().toFloat()
        val top = (height - h) / 2f
        val w = width.toFloat()
        paint.shader = null
        paint.color = track
        rect.set(0f, top, w, top + h)
        canvas.drawRoundRect(rect, h / 2, h / 2, paint)
        val span = (weekMax - weekMin).takeIf { it > 0.01f } ?: 1f
        val l = ((dayMin - weekMin) / span).coerceIn(0f, 1f) * w
        val r = max(l + h, ((dayMax - weekMin) / span).coerceIn(0f, 1f) * w).coerceAtMost(w)
        val left = min(l, r - h).coerceAtLeast(0f)
        paint.shader = LinearGradient(0f, 0f, w, 0f, 0xFF4FA3F7.toInt(), 0xFFC9A227.toInt(), Shader.TileMode.CLAMP)
        rect.set(left, top, r, top + h)
        canvas.drawRoundRect(rect, h / 2, h / 2, paint)
        paint.shader = null
    }
}
