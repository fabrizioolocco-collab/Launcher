package app.olauncher.ui.widgets

import androidx.core.app.ActivityCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
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
import app.olauncher.R
import app.olauncher.data.CalendarText
import app.olauncher.data.CalendarWidgetConfig
import app.olauncher.data.CalendarWidgetRepository
import app.olauncher.data.NO_DAY
import app.olauncher.data.NO_TIME
import app.olauncher.data.Todo
import app.olauncher.data.TodoDone
import app.olauncher.data.TodoStore
import app.olauncher.data.WeatherIcons
import app.olauncher.data.WeatherRepository
import app.olauncher.helper.CalendarEvents
import app.olauncher.helper.FontManager
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.MenuEntry
import app.olauncher.helper.showDropdown
import app.olauncher.helper.showToast
import app.olauncher.ui.todo.TODO_LATE_COLOR
import app.olauncher.ui.todo.TodoActivity
import app.olauncher.ui.todo.TodoCheckView
import app.olauncher.ui.todo.TodoEditActivity
import app.olauncher.ui.todo.TodoLabels
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
 * events or todos; tapping an event opens it in the calendar app. Long press moves it or opens its
 * menu (with the settings), like any widget on home.
 *
 * The todos of the ToDo app show on their due date (the late ones on today, in red) with a circle
 * to mark them done; where they go among the events and what happens once done is in the settings.
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
    /** The next hours: hour, icon, temperature; a tap opens the Weather app (on the hour tapped). */
    private val weatherRow = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        isClickable = true
        setOnClickListener { app.olauncher.ui.weather.WeatherActivity.open(activity) }
    }
    private val header = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val dateText = text(CalendarText.HEADER).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setOnClickListener { CalendarEvents.openDay(activity, firstDay()) }
    }
    private val weekText = text(CalendarText.WEEK).apply { maxLines = 1 }
    private val clockText = text(CalendarText.CLOCK).apply {
        maxLines = 1
        setOnClickListener { openClock() }
    }
    private val addButton = iconButton(R.drawable.ic_cal_add, R.string.ml_cal_add) { showAddMenu(it) }
    /** Cycles the todos shown: all, only those to do, none. */
    private val todoFilterButton = iconButton(R.drawable.ic_todo_show_all, R.string.ml_cal_todo_filter_title) {
        updateConfig { c -> c.todoFilter = (c.todoFilter + 1) % 3 }
        activity.showToast(activity.getString(FILTER_NAMES[config.todoFilter]))
    }
    private val prevButton = iconButton(R.drawable.ic_cal_prev, R.string.ml_cal_prev) { moveDays(-1) }
    private val nextButton = iconButton(R.drawable.ic_cal_next, R.string.ml_cal_next) { moveDays(1) }
    private val buttons = listOf(todoFilterButton, addButton, prevButton, nextButton)
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
        header.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitDate() }
        header.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        buttons.forEach { header.addView(it) }
        content.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(weatherRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 8.dpToPx(); bottomMargin = 2.dpToPx()
        })
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
        val others = need(clockText) + need(weekText) + buttons.sumOf { need(it) }
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

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) onTouched?.invoke()
        trackDoubleTap(ev)
        return super.dispatchTouchEvent(ev)
    }

    private var tapDownX = 0f
    private var tapDownY = 0f
    private var lastTapUp = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    /** A double tap anywhere on the widget brings it back to today (seen before the children get the taps). */
    private fun trackDoubleTap(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tapDownX = ev.rawX
                tapDownY = ev.rawY
            }

            MotionEvent.ACTION_UP -> {
                val moved = abs(ev.rawX - tapDownX) > touchSlop || abs(ev.rawY - tapDownY) > touchSlop
                if (moved || swiping || touch.longPressed) {
                    lastTapUp = 0L
                    return
                }
                val near = abs(ev.rawX - lastTapX) < touchSlop * 4 && abs(ev.rawY - lastTapY) < touchSlop * 4
                if (lastTapUp != 0L && ev.eventTime - lastTapUp <= android.view.ViewConfiguration.getDoubleTapTimeout() && near) {
                    lastTapUp = 0L
                    if (offset != 0) {
                        offset = 0
                        reload()
                    }
                    return
                }
                lastTapUp = ev.eventTime
                lastTapX = ev.rawX
                lastTapY = ev.rawY
            }

            MotionEvent.ACTION_CANCEL -> lastTapUp = 0L
        }
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

    /** A touch on home at [rawX], [rawY]: outside the widget it brings the widget back to today. */
    private fun touchedAt(rawX: Int, rawY: Int) {
        if (offset == 0 || !isShown) return
        val loc = IntArray(2).also { getLocationOnScreen(it) }
        if (rawX in loc[0] until loc[0] + width && rawY in loc[1] until loc[1] + height) return
        offset = 0
        reload()
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

    private val todoListener: () -> Unit = { if (isAttachedToWindow) showEvents() }

    // ---------------------------------------------------------------- weather

    private var weatherJob: Job? = null
    private var hadLocation = false

    private fun refreshWeather(force: Boolean = false) {
        if (!config.showWeather) return
        hadLocation = WeatherRepository.hasPermission(activity)
        showWeather()
        if (!hadLocation || weatherJob?.isActive == true) return
        if (!force && !WeatherRepository.isStale(activity)) return
        val ctx = activity.applicationContext
        weatherJob = scope.launch {
            WeatherRepository.refresh(ctx, force)
            if (isAttachedToWindow) showWeather()
        }
    }

    private fun showWeather() {
        weatherRow.removeAllViews()
        weatherRow.isVisible = config.showWeather
        if (!config.showWeather) return
        if (!WeatherRepository.hasPermission(activity) && WeatherRepository.cached(activity) == null) {
            weatherRow.addView(text(CalendarText.STATUS).apply {
                setText(R.string.ml_w_no_location)
                alpha = 0.7f
                setPadding(0, 6.dpToPx(), 0, 6.dpToPx())
                setOnClickListener {
                    ActivityCompat.requestPermissions(activity, arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_LOCATION)
                }
            })
            return
        }
        // Today: the next hours; another day: that day from 8 to 20 every two hours (if the forecast gets there)
        val forecast = WeatherRepository.cached(activity)
        val today = CalendarEvents.julianDay(System.currentTimeMillis())
        val day = firstDay()
        val hours = if (day == today) forecast?.upcoming()?.take(WEATHER_HOURS).orEmpty()
        else forecast?.hours?.filter {
            CalendarEvents.julianDay(it.time) == day && ((it.time - CalendarEvents.dayStart(day)) / 3_600_000L).toInt().let { h -> h in 8..20 && h % 2 == 0 }
        }.orEmpty()
        val now = day == today
        hours.forEachIndexed { i, h ->
            val current = now && i == 0
            weatherRow.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, 4.dpToPx(), 0, 4.dpToPx())
                // A tap on an hour opens the Weather app right on it
                setOnClickListener { app.olauncher.ui.weather.WeatherActivity.open(activity, h.time) }
                addView(text(CalendarText.STATUS).apply {
                    text = if (current) activity.getString(R.string.ml_w_now) else WeatherIcons.hourLabel(h.time)
                    textSize = 11f
                    alpha = if (current) 0.9f else 0.55f
                    typeface = Typeface.DEFAULT
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(ImageView(activity).apply { setImageResource(WeatherIcons.icon(h.code, h.isDay)) },
                    LinearLayout.LayoutParams(22.dpToPx(), 22.dpToPx()).apply { topMargin = 4.dpToPx(); bottomMargin = 4.dpToPx() })
                addView(text(CalendarText.TIME, if (current) Typeface.BOLD else Typeface.NORMAL).apply {
                    text = activity.getString(R.string.ml_w_degrees, Math.round(h.temp))
                    textSize = 13f
                    gravity = Gravity.CENTER
                    if (!current) alpha = 0.85f
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        if (hours.isEmpty()) weatherRow.isVisible = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached += this
        TodoStore.addListener(todoListener)
        observe()
        reload()
        refreshWeather()
        scheduleTick()
    }

    override fun onDetachedFromWindow() {
        attached -= this
        TodoStore.removeListener(todoListener)
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
            refreshWeather()
        }
    }

    /** The permission prompt is a dialog over home: it doesn't hide the window, only takes the focus. */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && CalendarEvents.hasPermission(activity) != hadPermission) {
            observe()
            reload()
        }
        if (hasWindowFocus && config.showWeather && WeatherRepository.hasPermission(activity) != hadLocation) refreshWeather(force = true)
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
        if (isAttachedToWindow) showWeather()
        hadPermission = CalendarEvents.hasPermission(activity)
        if (!hadPermission) {
            // The todos show anyway, under the request
            loadJob?.cancel()
            events = emptyList()
            loadedDay = first
            showEvents()
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
        buttons.forEach {
            it.layoutParams = LinearLayout.LayoutParams(iconSize + 16.dpToPx(), iconSize + 12.dpToPx())
            it.setColorFilter(tint, PorterDuff.Mode.SRC_IN)
        }
        todoFilterButton.setImageResource(
            when (config.todoFilter) {
                CalendarWidgetConfig.TODO_HIDE_DONE -> R.drawable.ic_todo_hide_done
                CalendarWidgetConfig.TODO_HIDE_ALL -> R.drawable.ic_todo_hide_all
                else -> R.drawable.ic_todo_show_all
            }
        )
        todoFilterButton.contentDescription = activity.getString(FILTER_NAMES[config.todoFilter])
        prevButton.isVisible = config.showArrows
        nextButton.isVisible = config.showArrows
        styleText(clockText, CalendarText.CLOCK)
        clockText.isVisible = config.showClock
        styleText(weekText, CalendarText.WEEK)
        if (config.text(CalendarText.WEEK).color == 0) weekText.alpha = 0.6f else weekText.alpha = 1f
        weekText.isVisible = config.showWeek
        background = when {
            // A card like the menus' (the opacity setting fades its ground, the border stays)
            config.card -> GradientDrawable().apply {
                cornerRadius = 20.dpToPx().toFloat()
                val alpha = if (config.background > 0) config.background * 255 / 100 else 255
                setColor(ColorUtils.setAlphaComponent(activity.getColorFromAttr(R.attr.cardColor), alpha))
                setStroke(1.dpToPx(), activity.getColorFromAttr(R.attr.cardStrokeColor))
            }
            config.background > 0 -> GradientDrawable().apply {
                cornerRadius = 16.dpToPx().toFloat()
                setColor(ColorUtils.setAlphaComponent(activity.getColorFromAttr(R.attr.primaryInverseColor), config.background * 255 / 100))
            }
            else -> null
        }
        if (config.card) setPadding(14.dpToPx(), 12.dpToPx(), 14.dpToPx(), 10.dpToPx()) else setPadding(12.dpToPx(), 8.dpToPx(), 12.dpToPx(), 8.dpToPx())
        if (isAttachedToWindow) showWeather()
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
        weekText.text = activity.getString(R.string.ml_cal_week, isoWeek(start))
        fitDate()
    }

    private fun isoWeek(millis: Long): Int = java.util.Calendar.getInstance().apply {
        firstDayOfWeek = java.util.Calendar.MONDAY
        minimalDaysInFirstWeek = 4
        timeInMillis = millis
    }.get(java.util.Calendar.WEEK_OF_YEAR)

    private fun permissionRow() = text(CalendarText.TITLE).apply {
        setText(R.string.ml_cal_permission)
        alpha = 0.7f
        setPadding(0, 8.dpToPx(), 0, 8.dpToPx())
        setOnClickListener {
            ActivityCompat.requestPermissions(activity, arrayOf(android.Manifest.permission.READ_CALENDAR), REQUEST_CALENDAR)
        }
    }

    /** One line of a day: an event, a todo to do or a todo done, with where it goes in the day. */
    private sealed class Item(val group: Int, val minute: Int)
    private class EventItem(val event: CalendarEvents.Event, group: Int, minute: Int) : Item(group, minute)
    private class TodoItem(val todo: Todo, val lateDays: Int, group: Int, minute: Int) : Item(group, minute)
    private class DoneItem(val done: TodoDone, group: Int, minute: Int) : Item(group, minute)

    /** Completions just made with "disappear at once": shown with an undo for a few seconds. */
    private val undoable = mutableSetOf<Long>()

    private fun todoVisible(category: Long) = config.todoCategories.isEmpty() || category in config.todoCategories

    private fun showEvents() {
        updateHeader()
        list.removeAllViews()
        if (!CalendarEvents.hasPermission(activity)) list.addView(permissionRow())
        val now = System.currentTimeMillis()
        val today = CalendarEvents.julianDay(now)
        val nowMinute = ((now - CalendarEvents.dayStart(today)) / 60_000L).toInt()
        val first = loadedDay
        val todos = if (config.showTodos) TodoStore.todos(activity).filter { !it.done && it.due != NO_DAY && todoVisible(it.category) } else emptyList()
        val history = if (config.showTodos) TodoStore.history(activity).filter {
            todoVisible(it.category) && (!hidesDone() || it.at in undoable)
        } else emptyList()
        for (day in first until first + config.days) {
            var dayEvents = events.filter { day in it.startDay..it.endDay }
            if (config.pastMode == CalendarWidgetConfig.PAST_HIDE) dayEvents = dayEvents.filter { !isPast(it, now) }
            // A todo not done on its day moves on to today, late
            val dayTodos = todos.filter { (it.due == day && day >= today) || (day == today && it.due < today) }
            val dayDone = history.filter { it.day == day }
            list.addView(text(CalendarText.DAY).apply {
                text = dayLabel(day)
                setPadding(0, if (list.childCount == 0) 0 else 10.dpToPx(), 0, 4.dpToPx())
            })
            val eventItems = dayEvents.map { e ->
                if (e.allDay || e.startDay < day) EventItem(e, 0, 0)
                else EventItem(e, 3, ((e.begin - CalendarEvents.dayStart(day)) / 60_000L).toInt())
            }
            val todoItems = dayTodos.map { t ->
                val late = if (t.due < today) today - t.due else if (t.due == today && t.time != NO_TIME && t.time < nowMinute) 0 else -1
                when {
                    t.due < today -> TodoItem(t, late, 1, t.time)
                    t.time == NO_TIME -> TodoItem(t, late, 2, 0)
                    else -> TodoItem(t, late, 3, t.time)
                }
            } + dayDone.map { d -> if (d.time == NO_TIME) DoneItem(d, 2, 0) else DoneItem(d, 3, d.time) }
            val order = compareBy<Item>({ it.group }, { it.minute })
            val items = when (config.todoPlace) {
                CalendarWidgetConfig.TODO_MIXED -> (eventItems + todoItems).sortedWith(order)
                CalendarWidgetConfig.TODO_ABOVE -> todoItems.sortedWith(order) + eventItems
                else -> eventItems + todoItems.sortedWith(order)
            }
            if (items.isEmpty()) list.addView(text(CalendarText.TITLE).apply {
                setText(R.string.ml_cal_no_events)
                alpha = 0.5f
                setPadding(12.dpToPx(), 2.dpToPx(), 0, 2.dpToPx())
            })
            items.forEach { item ->
                list.addView(
                    when (item) {
                        is EventItem -> eventRow(item.event, day, now)
                        is TodoItem -> todoRow(item.todo, item.lateDays, now)
                        is DoneItem -> doneRow(item.done)
                    }
                )
            }
        }
    }

    // ---------------------------------------------------------------- todos

    /** Drop-down under the +: new event, new todo, the ToDo app. */
    private fun showAddMenu(anchor: View) {
        val day = firstDay()
        anchor.showDropdown(listOf(
            MenuEntry(activity.getString(R.string.ml_cal_add_event)) { CalendarEvents.addEvent(activity, day) },
            MenuEntry(activity.getString(R.string.ml_cal_add_todo)) {
                TodoEditActivity.create(activity, maxOf(day, CalendarEvents.julianDay(System.currentTimeMillis())))
            },
            MenuEntry(activity.getString(R.string.ml_cal_open_todo)) { TodoActivity.open(activity) },
        ))
    }

    /** Done todos go away: by setting, or with the header button on "only those to do". */
    private fun hidesDone() = config.todoDoneMode == CalendarWidgetConfig.TODO_DONE_HIDE ||
            config.todoFilter == CalendarWidgetConfig.TODO_HIDE_DONE

    private fun todoColor(category: Long) =
        TodoStore.category(activity, category)?.color?.or(0xFF000000.toInt()) ?: ColorUtils.setAlphaComponent(primary, 0x80)

    /** The bar of the category, the circle to tick, the time, the title and how late it is. */
    private fun todoFrame(color: Int, checked: Boolean, onCheck: () -> Unit): Pair<LinearLayout, LinearLayout> {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 3.dpToPx(), 0, 3.dpToPx())
            isClickable = true
            isFocusable = true
        }
        row.addView(View(activity).apply {
            background = GradientDrawable().apply {
                cornerRadius = 2.dpToPx().toFloat()
                setColor(color)
            }
        }, LinearLayout.LayoutParams(4.dpToPx(), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = 10.dpToPx() })
        val line = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val size = (config.text(CalendarText.TITLE).sizeSp * 1.15f).toInt().dpToPx()
        line.addView(TodoCheckView(activity).apply {
            this.color = color
            this.checked = checked
            isClickable = true
            contentDescription = activity.getString(if (checked) R.string.ml_todo_undo else R.string.ml_todo_done)
            setOnClickListener {
                this.checked = !checked
                postDelayed({ onCheck() }, 200)
            }
        }, LinearLayout.LayoutParams(size, size).apply { marginEnd = 10.dpToPx() })
        row.addView(line, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row to line
    }

    private fun todoRow(todo: Todo, lateDays: Int, now: Long): View {
        val (row, line) = todoFrame(todoColor(todo.category), false) { complete(todo) }
        row.setOnClickListener { TodoEditActivity.edit(activity, todo.id) }
        if (todo.time != NO_TIME) line.addView(text(CalendarText.TIME).apply {
            text = TodoLabels.time(activity, todo.time)
            maxLines = 1
            if (config.text(CalendarText.TIME).color == 0) alpha = 0.7f
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = 12.dpToPx() })
        line.addView(text(CalendarText.TITLE).apply {
            text = todo.title
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val status = when {
            lateDays >= 0 -> TodoLabels.late(activity, lateDays)
            config.showStatus && todo.time != NO_TIME && todo.due == CalendarEvents.julianDay(now) ->
                activity.getString(R.string.ml_cal_in, duration(CalendarEvents.dayStart(todo.due) + todo.time * 60_000L - now))
            else -> null
        }
        status?.let {
            line.addView(text(CalendarText.STATUS).apply {
                text = it
                maxLines = 1
                if (lateDays >= 0) setTextColor(TODO_LATE_COLOR)
                else if (config.text(CalendarText.STATUS).color == 0) alpha = 0.7f
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = 8.dpToPx() })
        }
        return row
    }

    private fun doneRow(done: TodoDone): View {
        val (row, line) = todoFrame(todoColor(done.category), true) { undo(done) }
        row.setOnClickListener {
            if (TodoStore.get(activity, done.todoId) != null) TodoEditActivity.edit(activity, done.todoId) else TodoActivity.open(activity)
        }
        val hideMode = hidesDone()
        if (!hideMode && done.time != NO_TIME) line.addView(text(CalendarText.TIME).apply {
            text = TodoLabels.time(activity, done.time)
            maxLines = 1
            alpha = 0.5f
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = 12.dpToPx() })
        line.addView(text(CalendarText.TITLE).apply {
            text = done.title
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            alpha = 0.5f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (hideMode) line.addView(text(CalendarText.STATUS, Typeface.BOLD).apply {
            setText(R.string.ml_todo_undo)
            maxLines = 1
            setPadding(8.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
            isClickable = true
            setOnClickListener { undo(done) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = 8.dpToPx() })
        return row
    }

    private fun complete(todo: Todo) {
        val record = TodoStore.complete(activity, todo.id) ?: return
        if (hidesDone()) {
            undoable += record.at
            showEvents()
            handler.postDelayed({
                if (undoable.remove(record.at) && isAttachedToWindow) showEvents()
            }, UNDO_MS)
        }
    }

    private fun undo(done: TodoDone) {
        undoable.remove(done.at)
        TodoStore.undo(activity, done)
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

    private fun eventRow(event: CalendarEvents.Event, day: Int, now: Long): View {
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
            text = timeLabel(event, day)
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

    /** The times as seen on [day]: an event across midnight shows where it starts and where it ends. */
    private fun timeLabel(event: CalendarEvents.Event, day: Int): String {
        if (event.allDay) return activity.getString(R.string.ml_cal_all_day)
        val format = android.text.format.DateFormat.getTimeFormat(activity)
        val begin = format.format(Date(event.begin))
        val end = format.format(Date(event.end))
        return when {
            event.startDay == event.endDay -> "$begin - $end"
            day <= event.startDay -> activity.getString(R.string.ml_cal_from, begin)
            day >= event.endDay -> activity.getString(R.string.ml_cal_until, end)
            else -> activity.getString(R.string.ml_cal_all_day)
        }
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

    private fun iconButton(icon: Int, description: Int, onClick: (View) -> Unit) = ImageView(activity).apply {
        setImageResource(icon)
        contentDescription = activity.getString(description)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(8.dpToPx(), 6.dpToPx(), 8.dpToPx(), 6.dpToPx())
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick(it) }
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

        /** Any touch on a calendar widget (the detox home bar starts again). */
        var onTouched: (() -> Unit)? = null
        const val REQUEST_CALENDAR = 7110
        const val REQUEST_LOCATION = 7112
        private const val WEATHER_HOURS = 7
        private const val UNDO_MS = 4_000L
        private val FILTER_NAMES = listOf(R.string.ml_cal_todo_show_all, R.string.ml_cal_todo_hide_done, R.string.ml_cal_todo_hide_all)

        private val attached = mutableSetOf<CalendarWidgetView>()

        /** Every touch on home (from the activity): the widgets showing another day go back to today, unless touched themselves. */
        fun onHomeTouch(ev: MotionEvent) {
            if (ev.actionMasked != MotionEvent.ACTION_DOWN) return
            attached.toList().forEach { it.touchedAt(ev.rawX.toInt(), ev.rawY.toInt()) }
        }
    }
}
