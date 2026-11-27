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
import android.widget.TextView
import app.olauncher.R
import app.olauncher.data.Prefs
import app.olauncher.helper.AppNotifications
import app.olauncher.helper.dpToPx
import app.olauncher.helper.expandNotificationDrawer
import app.olauncher.helper.getColorFromAttr
import kotlin.math.abs

/**
 * Essential home: at rest home shows only the clock, the dock and a sign that other apps have
 * notifications (dots or a number); the grid, the widgets and the search bar are hidden. A tap
 * anywhere off the dock shows the whole home for [Prefs.essentialSeconds], then it hides again;
 * every touch on it starts the time again. Opening an app or coming back to home hides it at once.
 *
 * A transparent layer above home does the work: at rest it takes every touch except those on the
 * dock; while the whole home shows it only notices touches and lets them through.
 */
class EssentialHome(
    private val context: Context,
    private val root: FrameLayout,
    /** Above it, the layer sits; under it, everything it covers. */
    private val homeColumn: View,
    private val dock: View,
    /** Hidden at rest: the grid, the free widgets, the search row. */
    private val hidden: () -> List<View>,
    private val prefs: Prefs,
    private val isSearching: () -> Boolean,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var full = false
    private var deadline = 0L
    private var notifications: Map<String, AppNotifications> = emptyMap()
    private var dockKeys: Set<String> = emptySet()

    private val sign = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }
    private val signMark = LinearLayout(context).apply { gravity = Gravity.CENTER }
    private val signText = TextView(context, null, 0, R.style.TextSmall).apply {
        gravity = Gravity.CENTER
        alpha = 0.6f
    }

    private val layer = object : FrameLayout(context) {
        private var downX = 0f
        private var downY = 0f
        private var onDock = false
        private val slop = ViewConfiguration.get(context).scaledTouchSlop

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (!active()) return false
            if (full) {
                // Only noticed: the touch goes on to the home under it
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) restartTime()
                return false
            }
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    onDock = inDock(ev.rawX, ev.rawY)
                    if (onDock) return false
                    downX = ev.x
                    downY = ev.y
                }
                MotionEvent.ACTION_UP -> {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    when {
                        abs(dx) < slop && abs(dy) < slop -> showFull()
                        dy > slop * 4 && abs(dy) > abs(dx) && !prefs.swipeDownOff -> expandNotificationDrawer(context)
                    }
                }
            }
            return true
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            placeSign()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!full) return
            // Searching or a menu open on home: the time waits
            if (isSearching() || !root.hasWindowFocus()) restartTime()
            if (SystemClock.elapsedRealtime() >= deadline) hideFull(animate = true)
            else handler.postDelayed(this, 250)
        }
    }

    init {
        sign.addView(signMark)
        sign.addView(signText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 6.dpToPx()
        })
        layer.addView(sign, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dock.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeSign() }
        root.addView(layer, root.indexOfChild(homeColumn) + 1,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        apply()
    }

    private fun active() = prefs.essentialHome

    /** Settings changed or home came back: at rest, or out of the way when switched off. */
    fun apply() {
        handler.removeCallbacks(ticker)
        full = false
        if (active()) {
            layer.visibility = View.VISIBLE
            setHidden(true, animate = false)
            renderSign()
        } else {
            layer.visibility = View.GONE
            sign.visibility = View.GONE
            setHidden(false, animate = false)
        }
    }

    /** Back from an app (hidden while home is out of sight, so it doesn't flash on return). */
    fun onHomeStopped() = apply()
    fun onHomeResumed() = apply()

    fun release() {
        handler.removeCallbacks(ticker)
        setHidden(false, animate = false)
        root.removeView(layer)
    }

    fun setNotifications(map: Map<String, AppNotifications>, dockAppKeys: Set<String>) {
        notifications = map
        dockKeys = dockAppKeys
        if (active()) renderSign()
    }

    private fun showFull() {
        full = true
        sign.animate().alpha(0f).setDuration(200).start()
        setHidden(false, animate = true)
        restartTime()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun hideFull(animate: Boolean) {
        full = false
        handler.removeCallbacks(ticker)
        setHidden(true, animate)
        renderSign()
    }

    private fun restartTime() {
        deadline = SystemClock.elapsedRealtime() + prefs.essentialSeconds * 1000L
    }

    private fun setHidden(hide: Boolean, animate: Boolean) {
        val target = if (hide) 0f else 1f
        hidden().forEach { view ->
            view.animate().cancel()
            if (animate) view.animate().alpha(target).setDuration(250).start()
            else view.alpha = target
        }
    }

    private fun inDock(x: Float, y: Float): Boolean {
        if (dock.visibility != View.VISIBLE) return false
        val pos = IntArray(2).also { dock.getLocationOnScreen(it) }
        return Rect(pos[0], pos[1], pos[0] + dock.width, pos[1] + dock.height).contains(x.toInt(), y.toInt())
    }

    // ---------------------------------------------------------------- the sign

    private fun renderSign() {
        val others = notifications.filter { (key, n) -> key !in dockKeys && n.count > 0 }
        val apps = others.size
        val total = others.values.sumOf { it.count }
        signMark.removeAllViews()
        if (apps == 0 || full) {
            sign.visibility = View.GONE
            return
        }
        val color = context.getColorFromAttr(R.attr.primaryColor)
        if (prefs.essentialSign == Prefs.ESSENTIAL_SIGN_COUNT) {
            signMark.addView(TextView(context, null, 0, R.style.TextDefault).apply {
                text = total.toString()
                setTextColor(color)
                setPadding(14.dpToPx(), 2.dpToPx(), 14.dpToPx(), 2.dpToPx())
                background = GradientDrawable().apply {
                    cornerRadius = 100f
                    setStroke(1.dpToPx(), (color and 0x00FFFFFF) or 0x66000000)
                }
            })
            signText.text = context.resources.getQuantityString(R.plurals.ml_essential_count, total, total)
        } else {
            val size = 8.dpToPx()
            repeat(apps.coerceAtMost(MAX_DOTS)) {
                signMark.addView(View(context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(color)
                    }
                    alpha = 0.85f
                }, LinearLayout.LayoutParams(size, size).apply { marginStart = 3.dpToPx(); marginEnd = 3.dpToPx() })
            }
            signText.text = context.resources.getQuantityString(R.plurals.ml_essential_apps, apps, apps)
        }
        sign.visibility = View.VISIBLE
        sign.alpha = 1f
        placeSign()
    }

    /** Just above the dock (or the bottom of the screen without one). */
    private fun placeSign() {
        if (sign.visibility != View.VISIBLE || layer.height == 0) return
        val layerPos = IntArray(2).also { layer.getLocationInWindow(it) }
        val dockPos = IntArray(2).also { dock.getLocationInWindow(it) }
        val bottom = if (dock.visibility == View.VISIBLE && dock.height > 0) dockPos[1] - layerPos[1]
        else layer.height - 96.dpToPx()
        sign.measure(
            View.MeasureSpec.makeMeasureSpec(layer.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        sign.translationY = (bottom - sign.measuredHeight - 20.dpToPx()).toFloat().coerceAtLeast(0f)
    }

    companion object {
        private const val MAX_DOTS = 12
    }
}
