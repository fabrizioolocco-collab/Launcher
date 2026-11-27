package app.olauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.util.TypedValue
import app.olauncher.R
import app.olauncher.data.Prefs
import app.olauncher.helper.IconPackManager
import app.olauncher.helper.Skin
import app.olauncher.ui.widgets.HomeWidgetsController
import kotlinx.coroutines.launch
import app.olauncher.helper.dpToPx
import app.olauncher.helper.expandNotificationDrawer
import app.olauncher.helper.getColorFromAttr
import kotlin.math.abs

/**
 * The simple view of home: at rest only a clock with a few data under it, the dock as big keys and
 * a key "All apps", red when one of the other apps has notifications. That key opens a page with
 * the apps of the home grid (those with notifications first, in red) and the search bar; the page
 * goes by itself after [Prefs.essentialSeconds] without a touch, or with Back.
 *
 * A layer above home does the work: home's own grid and widgets stay hidden under it, and it lets
 * through only the touches on the dock (at rest) or on the search row (on the page).
 */
class EssentialHome(
    private val context: Context,
    private val root: FrameLayout,
    /** Above it, the layer sits; under it, everything it covers. */
    private val homeColumn: View,
    private val dock: View,
    private val searchRow: View,
    /** Hidden while the simple view is on: the header, the grid, the free widgets. */
    private val hidden: () -> List<View>,
    private val prefs: Prefs,
    private val isSearching: () -> Boolean,
    private val scope: kotlinx.coroutines.CoroutineScope,
    /** The apps of the page (the dock's are left out by the keys given). */
    private val items: (Set<String>) -> List<HomeWidgetsController.PageItem>,
    /** [OFF], [REST] or [PAGE]: the dock as keys or gone, the search row gone or back. */
    private val onState: (Int) -> Unit,
    /** A long press on an empty spot: home's menu (add an app, the settings). */
    private val onLongPress: () -> Unit,
) {
    private val face = EssentialFace(context, prefs, scope)
    private val minute = object : Runnable {
        override fun run() {
            if (active() && !full) face.refresh()
            handler.postDelayed(this, 60_000L)
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    /** The page of apps is open. */
    private var full = false
    private var deadline = 0L
    private var dockKeys: Set<String> = emptySet()
    private val skinned = Skin.isOn(context)
    private val ink = if (skinned) Skin.palette(context).text else context.getColorFromAttr(R.attr.primaryColor)
    private val red = NotificationBadge.NOTIFICATION_COLOR

    private val allButton = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setAllCaps(true)
        letterSpacing = 0.12f
        Skin.bodyFace(context)?.let { typeface = it }
        foreground = UiKit.ripple(context)
        setOnClickListener { showPage() }
    }

    private val pageList = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(16.dpToPx(), 8.dpToPx(), 16.dpToPx(), 12.dpToPx())
    }
    private val page = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        isFillViewport = true
        visibility = View.GONE
        pageList.setOnLongClickListener {
            onLongPress()
            true
        }
        addView(pageList, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private val layer = object : FrameLayout(context) {
        private var downX = 0f
        private var downY = 0f
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private val longPress = Runnable {
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            onLongPress()
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (full && ev.actionMasked == MotionEvent.ACTION_DOWN) restartTime()
            return super.dispatchTouchEvent(ev)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (!active()) return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // The search row always, the dock at rest: theirs
                    if (inView(searchRow, ev.rawX, ev.rawY) || (!full && inView(dock, ev.rawX, ev.rawY))) return false
                    downX = ev.x
                    downY = ev.y
                    handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE ->
                    if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) handler.removeCallbacks(longPress)
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (!full && dy > slop * 4 && abs(dy) > abs(dx) && !prefs.swipeDownOff) expandNotificationDrawer(context)
                }
                MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longPress)
            }
            return true
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            place()
        }
    }

    private var hadFocus = true

    /** While home is on screen: steps aside for the search, counts the page's time. */
    private val watch = object : Runnable {
        override fun run() {
            if (!active()) return
            val searching = isSearching()
            val focus = root.hasWindowFocus()
            if (searching) {
                if (layer.visibility == View.VISIBLE) layer.visibility = View.INVISIBLE
            } else if (layer.visibility != View.VISIBLE) {
                // The search closed and put home's views back: this view's own again
                onState(if (full) PAGE else REST)
                layer.visibility = View.VISIBLE
                place()
            }
            if (full) {
                // Searching or a menu open: the time waits; a menu closed may have changed the apps
                if (searching || !focus) restartTime()
                if (focus && !hadFocus) renderPage()
                if (SystemClock.elapsedRealtime() >= deadline) hidePage(animate = true)
            } else if (focus && !hadFocus) renderButton()
            hadFocus = focus
            handler.postDelayed(this, 150)
        }
    }

    init {
        layer.addView(face.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        face.onTap = { if (calendarOpen) closeCalendar(animate = true) else openCalendar() }
        layer.addView(allButton, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, BUTTON_DP.dpToPx()).apply {
            marginStart = 16.dpToPx()
            marginEnd = 16.dpToPx()
        })
        layer.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dock.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() }
        searchRow.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() }
        root.addView(layer, root.indexOfChild(homeColumn) + 1,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        apply()
    }

    private fun active() = prefs.essentialHome

    /** Settings changed or home came back: at rest, or out of the way when switched off. */
    fun apply() {
        full = false
        page.visibility = View.GONE
        closeCalendar(animate = false)
        if (active()) {
            layer.visibility = View.VISIBLE
            face.view.animate().cancel()
            face.view.visibility = View.VISIBLE
            face.view.alpha = 1f
            setHidden(true)
            onState(REST)
            renderButton()
        } else {
            layer.visibility = View.GONE
            setHidden(false)
            onState(OFF)
        }
    }

    /** Back from an app (at rest while home is out of sight, so the page doesn't flash on return). */
    fun onHomeStopped() {
        apply()
        face.stop()
        handler.removeCallbacks(minute)
        handler.removeCallbacks(watch)
    }

    fun onHomeResumed() {
        apply()
        handler.removeCallbacks(minute)
        handler.removeCallbacks(watch)
        if (active()) {
            face.start()
            handler.postDelayed(minute, 60_000L)
            hadFocus = true
            handler.post(watch)
        }
    }

    /** Back closes the page of apps; false when there was none to close. */
    fun onBack(): Boolean {
        if (!full) return false
        hidePage(animate = true)
        return true
    }

    fun release() {
        handler.removeCallbacks(watch)
        handler.removeCallbacks(minute)
        face.stop()
        setHidden(false)
        onState(OFF)
        calendar?.let { layer.removeView(it) }
        calendar = null
        root.removeView(layer)
    }

    // ---------------------------------------------------------------- the calendar under the face

    private var calendar: app.olauncher.ui.widgets.CalendarWidgetView? = null
    private var calendarOpen = false

    /** The calendar widget of home, with its settings (or a calendar of its own when home has none). */
    private fun calendarWidgetId(): Int =
        app.olauncher.data.HomeWidgetsRepository(context).load()
            .firstOrNull { it.builtin == app.olauncher.ui.widgets.CalendarWidgetView.KIND }?.appWidgetId ?: OWN_CALENDAR_ID

    private fun openCalendar() {
        val activity = context as? android.app.Activity ?: return
        val id = calendarWidgetId()
        val view = calendar?.takeIf { it.widgetId == id } ?: run {
            calendar?.let { layer.removeView(it) }
            app.olauncher.ui.widgets.CalendarWidgetView(activity, id, scope).also {
                layer.addView(it, layer.indexOfChild(face.view) + 1, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0).apply {
                    marginStart = 16.dpToPx()
                    marginEnd = 16.dpToPx()
                })
                calendar = it
            }
        }
        calendarOpen = true
        view.visibility = View.VISIBLE
        view.alpha = 0f
        place()
        view.animate().alpha(1f).setDuration(200).start()
    }

    private fun closeCalendar(animate: Boolean) {
        if (!calendarOpen) return
        calendarOpen = false
        val view = calendar ?: return
        view.animate().cancel()
        if (animate) view.animate().alpha(0f).setDuration(150).withEndAction {
            if (!calendarOpen) view.visibility = View.GONE
        }.start()
        else view.visibility = View.GONE
        place()
    }

    /** Notifications or the dock's apps changed. */
    fun setNotifications(dockAppKeys: Set<String>) {
        dockKeys = dockAppKeys
        if (!active()) return
        renderButton()
        if (full) renderPage()
    }

    private fun showPage() {
        full = true
        closeCalendar(animate = false)
        face.view.visibility = View.INVISIBLE
        allButton.visibility = View.GONE
        onState(PAGE)
        renderPage()
        page.scrollTo(0, 0)
        page.alpha = 0f
        page.visibility = View.VISIBLE
        page.animate().alpha(1f).setDuration(180).start()
        restartTime()
        place()
    }

    private fun hidePage(animate: Boolean) {
        full = false
        page.animate().cancel()
        page.visibility = View.GONE
        onState(REST)
        face.view.animate().cancel()
        face.view.visibility = View.VISIBLE
        face.view.alpha = if (animate) 0f else 1f
        if (animate) face.view.animate().alpha(1f).setDuration(200).start()
        renderButton()
    }

    private fun restartTime() {
        deadline = SystemClock.elapsedRealtime() + prefs.essentialSeconds * 1000L
    }

    private fun setHidden(hide: Boolean) {
        hidden().forEach { view ->
            view.animate().cancel()
            view.alpha = if (hide) 0f else 1f
        }
    }

    private fun inView(view: View, x: Float, y: Float): Boolean {
        if (view.visibility != View.VISIBLE || view.height == 0) return false
        val pos = IntArray(2).also { view.getLocationOnScreen(it) }
        return Rect(pos[0], pos[1], pos[0] + view.width, pos[1] + view.height).contains(x.toInt(), y.toInt())
    }

    private fun panel(notified: Boolean, cut: Int) = if (skinned) Skin.panel(
        context, if (notified) TILE_RED else context.getColorFromAttr(R.attr.tileColor), if (notified) red else null, cut, 16,
    ) else GradientDrawable().apply {
        cornerRadius = 16.dpToPx().toFloat()
        setColor(if (notified) TILE_RED else context.getColorFromAttr(R.attr.tileColor))
        if (notified) setStroke(1.dpToPx(), red)
    }

    // ---------------------------------------------------------------- the key "All apps"

    private fun renderButton() {
        if (full) return
        val count = try { items(dockKeys).sumOf { it.count } } catch (e: Exception) { 0 }
        allButton.text = context.getString(R.string.ml_essential_all) + if (count > 0) "   $count" else ""
        allButton.setTextColor(if (count > 0) red else ink)
        allButton.background = panel(count > 0, 10)
        allButton.visibility = View.VISIBLE
        place()
    }

    // ---------------------------------------------------------------- the page of apps

    private fun label(text: String, color: Int) = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setAllCaps(true)
        letterSpacing = 0.14f
        setTextColor(color)
        Skin.bodyFace(context)?.let { typeface = it }
        setPadding(2.dpToPx(), 14.dpToPx(), 0, 8.dpToPx())
    }

    private fun renderPage() {
        pageList.removeAllViews()
        pageList.addView(TextView(context).apply {
            text = "‹  " + context.getString(R.string.ml_essential_back)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setAllCaps(true)
            letterSpacing = 0.08f
            setTextColor(ink)
            alpha = 0.7f
            Skin.bodyFace(context)?.let { typeface = it }
            setPadding(2.dpToPx(), 10.dpToPx(), 24.dpToPx(), 6.dpToPx())
            setOnClickListener { hidePage(animate = true) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val all = try { items(dockKeys) } catch (e: Exception) { emptyList() }
        val notified = all.filter { it.count > 0 }
        if (notified.isNotEmpty()) {
            pageList.addView(label(context.getString(R.string.ml_essential_notified), red))
            addGrid(notified, NOTIFIED_COLUMNS, 54) { wideTile(it) }
        }
        val others = all.filter { it.count == 0 }
        if (others.isNotEmpty()) {
            pageList.addView(label(context.getString(R.string.ml_essential_all), (ink and 0x00FFFFFF) or 0x99000000.toInt()))
            addGrid(others, COLUMNS, 82) { tile(it) }
        }
    }

    private fun addGrid(list: List<HomeWidgetsController.PageItem>, columns: Int, heightDp: Int, make: (HomeWidgetsController.PageItem) -> View) {
        val gap = 7.dpToPx()
        list.chunked(columns).forEachIndexed { r, line ->
            val row = LinearLayout(context)
            for (c in 0 until columns) {
                val lp = LinearLayout.LayoutParams(0, heightDp.dpToPx(), 1f).apply {
                    marginStart = if (c == 0) 0 else gap / 2
                    marginEnd = if (c == columns - 1) 0 else gap / 2
                }
                row.addView(line.getOrNull(c)?.let(make) ?: View(context), lp)
            }
            pageList.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (r > 0) topMargin = gap
            })
        }
    }

    private fun icon(item: HomeWidgetsController.PageItem, sizeDp: Int) = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        if (skinned && Skin.palette(context).iconTint != 0)
            colorFilter = android.graphics.PorterDuffColorFilter(Skin.palette(context).text, android.graphics.PorterDuff.Mode.MULTIPLY)
        val size = sizeDp.dpToPx()
        scope.launch {
            val bitmap = try { item.icon(size) } catch (e: Exception) { null } ?: return@launch
            setImageDrawable(IconPackManager.toDrawable(context, bitmap, size))
        }
    }

    private fun name(item: HomeWidgetsController.PageItem, sp: Float) = TextView(context).apply {
        text = item.label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setAllCaps(true)
        letterSpacing = 0.06f
        setTextColor(ink)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        Skin.bodyFace(context)?.let { typeface = it }
    }

    private fun LinearLayout.opens(item: HomeWidgetsController.PageItem) {
        foreground = UiKit.ripple(context)
        contentDescription = item.label
        setOnClickListener { item.open() }
        // The icon's own menu (remove it, its icon…), home's for an app that isn't on home
        setOnLongClickListener {
            item.menu?.invoke() ?: onLongPress()
            true
        }
    }

    /** An app without notifications: its icon over its name. */
    private fun tile(item: HomeWidgetsController.PageItem) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = panel(false, 9)
        setPadding(4.dpToPx(), 0, 4.dpToPx(), 0)
        addView(icon(item, 28), LinearLayout.LayoutParams(28.dpToPx(), 28.dpToPx()))
        addView(name(item, 11f).apply { gravity = Gravity.CENTER; alpha = 0.85f },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 7.dpToPx() })
        opens(item)
    }

    /** An app with notifications: red, its icon, its name and how many. */
    private fun wideTile(item: HomeWidgetsController.PageItem) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = panel(true, 9)
        setPadding(10.dpToPx(), 0, 10.dpToPx(), 0)
        addView(icon(item, 24), LinearLayout.LayoutParams(24.dpToPx(), 24.dpToPx()))
        addView(name(item, 13f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 9.dpToPx() })
        addView(TextView(context).apply {
            text = if (item.count > 99) "99+" else item.count.toString()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(red)
            setTypeface(Skin.bodyFace(context) ?: typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = 6.dpToPx() })
        opens(item)
    }

    // ---------------------------------------------------------------- places

    /** The clock at the top, the key just above the dock, the calendar between them, the page down to the search row. */
    private fun place() {
        if (layer.height == 0) return
        val faceTop = layer.height * 0.04f
        face.view.translationY = faceTop
        val layerTop = IntArray(2).also { layer.getLocationInWindow(it) }[1]
        fun topOf(view: View, fallback: Int) =
            if (view.visibility == View.VISIBLE && view.height > 0) IntArray(2).also { view.getLocationInWindow(it) }[1] - layerTop else fallback
        if (full) {
            val height = topOf(searchRow, layer.height - 80.dpToPx()).coerceAtLeast(0)
            val lp = page.layoutParams
            if (lp.height != height) {
                lp.height = height
                page.post { page.layoutParams = lp }
            }
            return
        }
        val buttonTop = (topOf(dock, layer.height - 96.dpToPx()) - BUTTON_DP.dpToPx() - GAP_DP.dpToPx()).toFloat().coerceAtLeast(0f)
        allButton.translationY = buttonTop
        val cal = calendar ?: return
        if (!calendarOpen) return
        val top = faceTop + face.view.height + 14.dpToPx()
        val height = (buttonTop - GAP_DP.dpToPx() - top).toInt().coerceAtLeast(MIN_CALENDAR_DP.dpToPx())
        cal.translationY = top
        val lp = cal.layoutParams
        if (lp.height != height) {
            lp.height = height
            // Not in the middle of this layout pass
            cal.post { cal.layoutParams = lp }
        }
    }

    companion object {
        const val OFF = 0
        const val REST = 1
        const val PAGE = 2
        private const val BUTTON_DP = 46
        /** Between the calendar, the key All apps and the dock's keys: the same as between the keys. */
        private const val GAP_DP = 8
        private const val COLUMNS = 4
        private const val NOTIFIED_COLUMNS = 2
        private const val TILE_RED = 0xFF3A1715.toInt()
        private const val MIN_CALENDAR_DP = 160
        /** Settings of the calendar shown here when home has no calendar widget. */
        private const val OWN_CALENDAR_ID = -7001
    }
}
