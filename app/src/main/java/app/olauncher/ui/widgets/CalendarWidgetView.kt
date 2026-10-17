package app.olauncher.ui.widgets

import android.app.Activity
import android.content.Context
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import app.olauncher.R
import app.olauncher.data.CalendarText
import app.olauncher.data.CalendarWidgetConfig
import app.olauncher.data.CalendarWidgetRepository
import app.olauncher.helper.CalendarEvents
import app.olauncher.helper.FontManager
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * The launcher's own calendar widget: the events of the selected day (or of a few days from it)
 * with a colored mark for their calendar, the time left / to the start and a progress bar for the
 * ones in progress. A side swipe (or the optional arrows) moves between days, the header adds
 * events; tapping an event opens it in the calendar app. Long press moves it or opens its menu
 * (with the settings), like any widget on home.
 */
class CalendarWidgetView(
    private val activity: Activity,
    val widgetId: Int,
    private val scope: CoroutineScope,
) : FrameLayout(activity), app.olauncher.ui.HomeScrollView.SideSwipeOwner {

    val touch = GridItemTouch(this)

    private val repo = CalendarWidgetRepository(activity)
    var config: CalendarWidgetConfig = repo.load(widgetId)
        private set

    /** Days from today of the first day shown. */
    private var offset = 0
    private var events: List<CalendarEvents.Event> = emptyList()
    private var loadedDay = Int.MIN_VALUE
    private var loadJob: Job? = null
    private val primary = activity.getColorFromAttr(R.attr.primaryColor)

    private val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val header = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val dateText = text(CalendarText.HEADER).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setOnClickListener { CalendarEvents.openDay(activity, firstDay()) }
    }
    private val todayButton = iconButton(R.drawable.ic_cal_today, R.string.ml_cal_today_button) {
        offset = 0
        reload()
    }
    private val weekText = text(CalendarText.WEEK).apply { maxLines = 1 }
    private val clockText = text(CalendarText.CLOCK).apply {
        maxLines = 1
        setOnClickListener { openClock() }
    }
    private val addButton = iconButton(R.drawable.ic_cal_add, R.string.ml_cal_add) { CalendarEvents.addEvent(activity, firstDay()) }
    private val prevButton = iconButton(R.drawable.ic_cal_prev, R.string.ml_cal_prev) { moveDays(-1) }
    private val nextButton = iconButton(R.drawable.ic_cal_next, R.string.ml_cal_next) { moveDays(1) }
    private val buttons = listOf(addButton, prevButton, nextButton)
    private val divider = View(activity)
    private val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(activity).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(16.dpToPx())
        addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (firstDay() != loadedDay) reload() else showEvents()
            scheduleTick()
        }
    }
    private var observing = false
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = reload()
    }

    init {
        header.addView(clockText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = 10.dpToPx()
        })
        header.addView(dateText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        header.addView(weekText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = 10.dpToPx()
        })
        header.addView(todayButton)
        todayButton.isVisible = false
        header.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitDate() }
        header.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        buttons.forEach { header.addView(it) }
        content.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(divider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1.dpToPx()).apply {
            topMargin = 4.dpToPx(); bottomMargin = 6.dpToPx()
        })
        content.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(content, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setPadding(12.dpToPx(), 8.dpToPx(), 12.dpToPx(), 8.dpToPx())
        isClickable = true
        isLongClickable = true
        applyConfig()
    }

    /** A long date shrinks (ellipsized) instead of pushing the week number and the buttons out. */
    private fun fitDate() {
        if (header.width == 0) return
        fun need(v: View): Int {
            if (!v.isVisible) return 0
            val lp = v.layoutParams as LinearLayout.LayoutParams
            val w = if (v is TextView) (v.paint.measureText(v.text.toString()) + v.paddingLeft + v.paddingRight).toInt() + 1
            else lp.width.coerceAtLeast(0)
            return w + lp.marginStart + lp.marginEnd
        }
        val others = need(clockText) + need(weekText) + need(todayButton) + buttons.sumOf { need(it) }
        val max = (header.width - header.paddingLeft - header.paddingRight - others).coerceAtLeast(0)
        if (dateText.maxWidth != max) header.post { dateText.maxWidth = max }
    }

    // ---------------------------------------------------------------- touch: long press moves the widget, side swipe changes day

    override val ownsSideSwipes get() = config.swipeDays

    private val touchSlop = android.view.ViewConfiguration.get(activity).scaledTouchSlop
    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var swiping = false

    /** True once the finger moved sideways enough to be a day swipe (not before a long press). */
    private fun trackSwipe(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeDownX = ev.rawX
                swipeDownY = ev.rawY
                swiping = false
            }

            MotionEvent.ACTION_MOVE -> if (!swiping && config.swipeDays && !touch.longPressed) {
                val dx = ev.rawX - swipeDownX
                val dy = ev.rawY - swipeDownY
                if (abs(dx) > touchSlop * 2 && abs(dx) > abs(dy) * 1.5f) {
                    swiping = true
                    touch.cancel()
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
        }
        return swiping
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (touch.intercept(ev)) return true
        return trackSwipe(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!touch.longPressed) trackSwipe(event)
        if (!swiping) return touch.touch(event)
        val dx = event.rawX - swipeDownX
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                content.translationX = dx
                content.alpha = 1f - (abs(dx) / width.coerceAtLeast(1)).coerceAtMost(1f) * 0.6f
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                swiping = false
                val commit = event.actionMasked == MotionEvent.ACTION_UP && abs(dx) > minOf(width / 5f, 80f * resources.displayMetrics.density)
                if (commit) slideTo(if (dx < 0) 1 else -1)
                else content.animate().translationX(0f).alpha(1f).setDuration(150).start()
            }
        }
        return true
    }

    /** Slides the days out on the side of the swipe and the new ones in from the other side. */
    private fun slideTo(direction: Int) {
        val w = width.toFloat()
        content.animate().translationX(-direction * w).alpha(0f).setDuration(120).withEndAction {
            moveDays(direction)
            content.translationX = direction * w * 0.4f
            content.animate().translationX(0f).alpha(1f).setDuration(180).start()
        }.start()
    }

    /** Forward ([direction] 1) or back (-1) by as many days as are shown. */
    private fun moveDays(direction: Int) {
        offset += direction * config.days
        reload()
    }

    /** The list scrolling takes the gesture: no long press then. */
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        if (disallowIntercept) touch.cancel()
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    override fun cancelLongPress() {
        super.cancelLongPress()
        touch.cancel()
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        observe()
        reload()
        scheduleTick()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tick)
        if (observing) {
            activity.contentResolver.unregisterContentObserver(observer)
            observing = false
        }
        loadJob?.cancel()
        super.onDetachedFromWindow()
    }

    /** Back on home (from the calendar app or the permission prompt): fresh data. */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE && isAttachedToWindow) {
            observe()
            reload()
        }
    }

    /** The permission prompt is a dialog over home: it doesn't hide the window, only takes the focus. */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && CalendarEvents.hasPermission(activity) != hadPermission) {
            observe()
            reload()
        }
    }

    private fun observe() {
        if (observing || !CalendarEvents.hasPermission(activity)) return
        observing = try {
            activity.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Next run at the start of the next minute. */
    private fun scheduleTick() {
        handler.removeCallbacks(tick)
        val now = System.currentTimeMillis()
        handler.postDelayed(tick, 60_000L - now % 60_000L + 200)
    }

    // ---------------------------------------------------------------- data

    private fun firstDay() = CalendarEvents.julianDay(System.currentTimeMillis()) + offset

    /** Settings changed (from the settings dialog): saves and redraws. */
    fun updateConfig(change: (CalendarWidgetConfig) -> Unit) {
        change(config)
        repo.save(widgetId, config)
        applyConfig()
        reload()
    }

    private var hadPermission = false

    fun reload() {
        val first = firstDay()
        updateHeader()
        hadPermission = CalendarEvents.hasPermission(activity)
        if (!hadPermission) {
            loadedDay = first
            showPermissionRequest()
            return
        }
        val last = first + config.days - 1
        val cfg = config
        val ctx = activity.applicationContext
        loadJob?.cancel()
        loadJob = scope.launch {
            val found = withContext(Dispatchers.IO) { CalendarEvents.events(ctx, first, last, cfg) }
            events = found
            loadedDay = first
            showEvents()
        }
    }

    // ---------------------------------------------------------------- drawing

    private fun applyConfig() {
        header.isVisible = config.showHeader
        divider.isVisible = config.showHeader
        divider.setBackgroundColor(ColorUtils.setAlphaComponent(primary, 0x50))
        styleText(dateText, CalendarText.HEADER)
        val iconSize = (config.text(CalendarText.HEADER).sizeSp * 1.35f).toInt().dpToPx()
        val tint = config.text(CalendarText.HEADER).color.takeIf { it != 0 } ?: primary
        (buttons + todayButton).forEach {
            it.layoutParams = LinearLayout.LayoutParams(iconSize + 16.dpToPx(), iconSize + 12.dpToPx())
            it.setColorFilter(tint, PorterDuff.Mode.SRC_IN)
        }
        prevButton.isVisible = config.showArrows
        nextButton.isVisible = config.showArrows
        styleText(clockText, CalendarText.CLOCK)
        clockText.isVisible = config.showClock
        styleText(weekText, CalendarText.WEEK)
        if (config.text(CalendarText.WEEK).color == 0) weekText.alpha = 0.6f else weekText.alpha = 1f
        weekText.isVisible = config.showWeek
        background = if (config.background > 0) GradientDrawable().apply {
            cornerRadius = 16.dpToPx().toFloat()
            setColor(ColorUtils.setAlphaComponent(activity.getColorFromAttr(R.attr.primaryInverseColor), config.background * 255 / 100))
        } else null
    }

    private fun updateHeader() {
        val first = firstDay()
        val start = CalendarEvents.dayStart(first)
        val sameYear = SimpleDateFormat("yyyy", Locale.getDefault()).let { it.format(Date(start)) == it.format(Date()) }
        val pattern = when {
            config.shortDate && sameYear -> "EEE d MMM"
            config.shortDate -> "EEE d MMM yyyy"
            sameYear -> "EEEE, d MMMM"
            else -> "EEEE, d MMMM yyyy"
        }
        // Some languages abbreviate with a dot ("sab." / "ott."): not in the short header
        dateText.text = SimpleDateFormat(pattern, Locale.getDefault()).format(Date(start)).uppercase(Locale.getDefault())
            .let { if (config.shortDate) it.replace(".", "") else it }
        clockText.text = android.text.format.DateFormat.getTimeFormat(activity).format(Date())
        todayButton.isVisible = offset != 0
        weekText.text = activity.getString(R.string.ml_cal_week, isoWeek(start))
        fitDate()
    }

    private fun isoWeek(millis: Long): Int = java.util.Calendar.getInstance().apply {
        firstDayOfWeek = java.util.Calendar.MONDAY
        minimalDaysInFirstWeek = 4
        timeInMillis = millis
    }.get(java.util.Calendar.WEEK_OF_YEAR)

    private fun showPermissionRequest() {
        list.removeAllViews()
        list.addView(text(CalendarText.TITLE).apply {
            setText(R.string.ml_cal_permission)
            alpha = 0.7f
            setPadding(0, 8.dpToPx(), 0, 8.dpToPx())
            setOnClickListener {
                ActivityCompat.requestPermissions(activity, arrayOf(android.Manifest.permission.READ_CALENDAR), REQUEST_CALENDAR)
            }
        })
    }

    private fun showEvents() {
        updateHeader()
        if (!CalendarEvents.hasPermission(activity)) return
        list.removeAllViews()
        val now = System.currentTimeMillis()
        val first = loadedDay
        for (day in first until first + config.days) {
            var dayEvents = events.filter { day in it.startDay..it.endDay }
            if (config.pastMode == CalendarWidgetConfig.PAST_HIDE) dayEvents = dayEvents.filter { !isPast(it, now) }
            list.addView(text(CalendarText.DAY).apply {
                text = dayLabel(day)
                setPadding(0, if (list.childCount == 0) 0 else 10.dpToPx(), 0, 4.dpToPx())
            })
            if (dayEvents.isEmpty()) list.addView(text(CalendarText.TITLE).apply {
                setText(R.string.ml_cal_no_events)
                alpha = 0.5f
                setPadding(12.dpToPx(), 2.dpToPx(), 0, 2.dpToPx())
            })
            dayEvents.forEach { list.addView(eventRow(it, now)) }
        }
    }

    private fun dayLabel(day: Int): String {
        val today = CalendarEvents.julianDay(System.currentTimeMillis())
        return when (val d = day - today) {
            0 -> activity.getString(R.string.ml_cal_today)
            1 -> activity.getString(R.string.ml_cal_tomorrow)
            -1 -> activity.getString(R.string.ml_cal_yesterday)
            else -> if (config.days > 1)
                SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(CalendarEvents.dayStart(day)))
                    .replaceFirstChar { it.titlecase(Locale.getDefault()) }
            else if (d > 0) activity.getString(R.string.ml_cal_in_days, d)
            else activity.getString(R.string.ml_cal_days_ago, -d)
        }
    }

    private fun eventRow(event: CalendarEvents.Event, now: Long): View {
        val ongoing = !event.allDay && now >= event.begin && now < event.end
        val past = isPast(event, now)
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 3.dpToPx(), 0, 3.dpToPx())
            isClickable = true
            isFocusable = true
            setOnClickListener { CalendarEvents.openEvent(activity, event) }
            if (past && config.pastMode == CalendarWidgetConfig.PAST_DIM) alpha = 0.4f
        }
        row.addView(View(activity).apply {
            background = GradientDrawable().apply {
                cornerRadius = 2.dpToPx().toFloat()
                setColor(event.color or 0xFF000000.toInt())
            }
        }, LinearLayout.LayoutParams(4.dpToPx(), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = 10.dpToPx() })

        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val line = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val extra = if (ongoing) Typeface.BOLD else Typeface.NORMAL
        line.addView(text(CalendarText.TIME, extra).apply {
            text = timeLabel(event)
            maxLines = 1
            if (config.text(CalendarText.TIME).color == 0) alpha = 0.7f
        })
        line.addView(text(CalendarText.TITLE, extra).apply {
            text = event.title.ifBlank { activity.getString(R.string.ml_cal_no_title) }
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 12.dpToPx() })
        statusLabel(event, now, ongoing, past)?.let { status ->
            line.addView(text(CalendarText.STATUS, extra).apply {
                text = status
                maxLines = 1
                if (config.text(CalendarText.STATUS).color == 0) alpha = 0.7f
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = 8.dpToPx() })
        }
        column.addView(line)
        if (ongoing && config.showProgress) {
            val fraction = (now - event.begin).toFloat() / (event.end - event.begin).coerceAtLeast(1)
            val fill = config.progressFill.takeIf { it != 0 } ?: ColorUtils.blendARGB(primary, 0xFF808080.toInt(), 0.6f)
            val track = config.progressTrack.takeIf { it != 0 } ?: primary
            column.addView(ProgressBarView(activity, fill, track, fraction), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 6.dpToPx()).apply {
                topMargin = 6.dpToPx(); bottomMargin = 4.dpToPx(); marginStart = 4.dpToPx()
            })
        }
        row.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    /** The clock app (alarms), else nothing. */
    private fun openClock() {
        try {
            activity.startActivity(android.content.Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** All-day events are over once their last day is; timed ones at their end. */
    private fun isPast(event: CalendarEvents.Event, now: Long) =
        if (event.allDay) event.endDay < CalendarEvents.julianDay(now) else event.end <= now

    private fun timeLabel(event: CalendarEvents.Event): String {
        if (event.allDay) return activity.getString(R.string.ml_cal_all_day)
        val format = android.text.format.DateFormat.getTimeFormat(activity)
        return "${format.format(Date(event.begin))} - ${format.format(Date(event.end))}"
    }

    private fun statusLabel(event: CalendarEvents.Event, now: Long, ongoing: Boolean, past: Boolean): String? {
        if (!config.showStatus || event.allDay || past) return null
        return if (ongoing) activity.getString(R.string.ml_cal_left, duration(event.end - now))
        else activity.getString(R.string.ml_cal_in, duration(event.begin - now))
    }

    /** "29 min", "1h 59m", "3 g". */
    private fun duration(ms: Long): String {
        val minutes = ((ms + 59_999) / 60_000).coerceAtLeast(1)
        return when {
            minutes < 60 -> activity.getString(R.string.ml_cal_minutes, minutes.toInt())
            minutes < 24 * 60 -> if (minutes % 60 == 0L) activity.getString(R.string.ml_cal_hours, (minutes / 60).toInt())
            else activity.getString(R.string.ml_cal_hours_minutes, (minutes / 60).toInt(), (minutes % 60).toInt())
            else -> activity.getString(R.string.ml_cal_days_short, ((minutes + 12 * 60) / (24 * 60)).toInt())
        }
    }

    private fun text(t: CalendarText, extraStyle: Int = Typeface.NORMAL): TextView =
        TextView(activity, null, 0, R.style.TextSmall).also { styleText(it, t, extraStyle) }

    private fun styleText(view: TextView, t: CalendarText, extraStyle: Int = Typeface.NORMAL) {
        val s = config.text(t)
        val base = FontManager.typeface(activity, s.font) ?: Typeface.DEFAULT
        view.typeface = Typeface.create(base, s.style or extraStyle)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, s.sizeSp.toFloat())
        view.setTextColor(if (s.color != 0) s.color else primary)
        view.includeFontPadding = false
    }

    private fun iconButton(icon: Int, description: Int, onClick: () -> Unit) = ImageView(activity).apply {
        setImageResource(icon)
        contentDescription = activity.getString(description)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(8.dpToPx(), 6.dpToPx(), 8.dpToPx(), 6.dpToPx())
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /** Rounded bar: the elapsed part in [fill] (dimmed by default), the part left in [track]. */
    private class ProgressBarView(context: Context, private val fill: Int, private val track: Int, private val fraction: Float) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            val r = height / 2f
            val split = width * fraction.coerceIn(0f, 1f)
            paint.color = track
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, r, r, paint)
            paint.color = fill
            canvas.save()
            canvas.clipRect(0f, 0f, split, height.toFloat())
            canvas.drawRoundRect(rect, r, r, paint)
            canvas.restore()
        }
    }

    companion object {
        const val KIND = "calendar"
        const val REQUEST_CALENDAR = 7110
    }
}
