package app.olauncher.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Prefs
import app.olauncher.helper.AppNotifications
import app.olauncher.helper.DetoxManager
import app.olauncher.helper.IconPackManager
import app.olauncher.helper.NotificationRepository
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil

/**
 * The page that takes the place of home while a detox session runs: the time left with a progress
 * bar on top, then the allowed apps sharing the rest of the page, all in sight however many they
 * are (rows, taller the fewer they are; more columns when they don't fit), each with the count of
 * its notifications; "End" opens the session menu. Optionally (Settings) a second bar empties in
 * a few seconds and the phone is locked if no app was opened meanwhile; when that happens several
 * times in a row the phone is locked out for a few minutes and the page offers only the phone and
 * the wallet.
 */
class DetoxPage(
    private val page: FrameLayout,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val onLaunch: (AppModel.App) -> Unit,
    private val onEnd: () -> Unit,
    /** Locks the phone: no app was opened in time. */
    private val onLock: () -> Unit,
) {
    private val context: Context = page.context
    private val primary = context.getColorFromAttr(R.attr.primaryColor)
    private val secondary = context.getColorFromAttr(R.attr.secondaryTextColor)
    private val accent = context.getColorFromAttr(R.attr.accentColor)

    private var apps: List<AppModel.App> = emptyList()
    private var notifications: Map<String, AppNotifications> = emptyMap()

    private val now = TextView(context, null, 0, R.style.TextDefault).apply {
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(primary)
    }
    private val lockFill = View(context).apply {
        pivotX = 0f
        background = GradientDrawable().apply { cornerRadius = 3.dpToPx().toFloat() }
    }
    private val lockBar = FrameLayout(context).apply {
        isVisible = false
        addView(lockFill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
    private var lockAnimator: android.animation.ValueAnimator? = null
    /** Missed lock bars out of those that lock the phone out, e.g. "2/3". */
    private val missed = smallText().apply {
        isVisible = false
        gravity = Gravity.END
    }
    private val remaining = TextView(context, null, 0, R.style.TextDefault).apply {
        textSize = 64f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(primary)
        includeFontPadding = false
    }
    private val remainingLabel = TextView(context, null, 0, R.style.TextDefault).apply {
        textSize = 16f
        setTextColor(secondary)
    }
    private val fill = View(context).apply {
        background = GradientDrawable().apply {
            cornerRadius = 4.dpToPx().toFloat()
            setColor(accent)
        }
    }
    private val empty = View(context)
    private val started = smallText()
    private val ends = smallText().apply { gravity = Gravity.END }
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val listBox = FrameLayout(context)
    private var shown: List<AppModel.App> = emptyList()
    private val lockoutNote = smallText().apply {
        textSize = 15f
        setTextColor(primary)
        isVisible = false
    }
    private val endButton = endButton()
    private var lockedOut = false

    init {
        page.setBackgroundColor(context.getColorFromAttr(R.attr.primaryInverseColor))
        // Takes every touch: home's gestures stay behind it
        page.isClickable = true
        page.removeAllViews()
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dpToPx(), 56.dpToPx(), 20.dpToPx(), 24.dpToPx())
        }
        column.addView(now)
        column.addView(header(), matchWrap(top = 12))
        column.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(remaining)
            addView(remainingLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = 10.dpToPx()
                bottomMargin = 8.dpToPx()
            })
        }, matchWrap(top = 12))
        column.addView(lockBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 6.dpToPx()).apply { topMargin = 16.dpToPx() })
        column.addView(missed, matchWrap(top = 6))
        column.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = 4.dpToPx().toFloat()
                setColor(context.getColorFromAttr(R.attr.tileColor))
            }
            addView(fill, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(empty, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0f))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 8.dpToPx()).apply { topMargin = 16.dpToPx() })
        column.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(started, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ends, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }, matchWrap(top = 10))

        column.addView(lockoutNote, matchWrap(top = 20))
        listBox.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // The apps share the room left: laid out again once its size is known or changes
        listBox.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (bottom - top != oldBottom - oldTop || right - left != oldRight - oldLeft) listBox.post { layoutApps() }
        }
        column.addView(listBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = 24.dpToPx()
            bottomMargin = 16.dpToPx()
        })
        column.addView(endButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 44.dpToPx()).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        page.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun smallText() = TextView(context, null, 0, R.style.TextSmall).apply {
        textSize = 14f
        setTextColor(secondary)
    }

    private fun matchWrap(top: Int) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = top.dpToPx() }

    private fun header() = TextView(context, null, 0, R.style.TextSmall).apply {
        text = context.getString(R.string.ml_detox).uppercase()
        textSize = 14f
        letterSpacing = 0.08f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(secondary)
        gravity = Gravity.CENTER_VERTICAL
        val size = 18.dpToPx()
        val icon = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_m_timer)?.mutate()?.apply {
            setTint(secondary)
            setBounds(0, 0, size, size)
        }
        compoundDrawablePadding = 8.dpToPx()
        setCompoundDrawablesRelative(icon, null, null, null)
    }

    private fun endButton() = TextView(context, null, 0, R.style.TextDefault).apply {
        text = context.getString(R.string.ml_detox_end_short)
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(primary)
        gravity = Gravity.CENTER
        setPadding(24.dpToPx(), 0, 24.dpToPx(), 0)
        background = GradientDrawable().apply {
            cornerRadius = 22.dpToPx().toFloat()
            setStroke(1.dpToPx(), context.getColorFromAttr(R.attr.cardStrokeColor))
        }
        foreground = UiKit.ripple(context)
        isClickable = true
        setOnClickListener {
            stopLockCountdown()
            onEnd()
        }
    }

    // ---------------------------------------------------------------- state

    /** Shown while a session runs; [onShown] when it has just appeared. */
    fun update(onShown: () -> Unit = {}) {
        val active = DetoxManager.isActive(context)
        val wasVisible = page.isVisible
        page.isVisible = active
        if (!active) {
            stopLockCountdown()
            return
        }
        val lock = DetoxManager.isLockedOut(context)
        if (!wasVisible || lock != lockedOut) {
            lockedOut = lock
            lockoutNote.isVisible = lock
            endButton.isVisible = !lock
            renderApps()
        }
        if (!wasVisible) {
            onShown()
            startLockCountdown()
        }
        updateTime()
    }

    fun setApps(list: List<AppModel.App>) {
        apps = list
        if (page.isVisible) renderApps()
    }

    fun setNotifications(map: Map<String, AppNotifications>) {
        if (map == notifications) return
        notifications = map
        if (page.isVisible) renderApps()
    }

    /** The allowed apps or the whitelist changed. */
    fun refreshApps() {
        if (page.isVisible) renderApps()
    }

    // ---------------------------------------------------------------- lock countdown

    /**
     * Each time the page comes up (unlocked, back from an app): if set, the lock bar empties in the
     * chosen seconds and then the phone is locked. Opening an app, its notifications or the session
     * menu stops it.
     */
    fun startLockCountdown() {
        stopLockCountdown()
        if (!prefs.detoxLock || !page.isVisible) return
        val color = prefs.detoxLockColor.takeIf { it != 0 } ?: DEFAULT_LOCK_COLOR
        (lockFill.background as GradientDrawable).setColor(color)
        lockFill.scaleX = 1f
        lockBar.isVisible = true
        lockAnimator = android.animation.ValueAnimator.ofFloat(1f, 0f).apply {
            duration = prefs.detoxLockSeconds * 1000L
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { lockFill.scaleX = it.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (cancelled) return
                    lockAnimator = null
                    lockBar.isVisible = false
                    if (!page.isVisible) return
                    if (DetoxManager.lockBarMissed(context, prefs.detoxLockoutAfter, prefs.detoxLockoutMinutes, prefs.detoxLockoutReset)) update()
                    updateMissed()
                    onLock()
                }
            })
            start()
        }
    }

    fun stopLockCountdown() {
        lockAnimator?.cancel()
        lockAnimator = null
        lockBar.isVisible = false
    }

    private fun updateTime() {
        val left = DetoxManager.remainingMs(context)
        val start = DetoxManager.startTime(context)
        val end = DetoxManager.endTime(context)
        val minutes = ceil(left / 60_000.0).toInt()
        if (minutes >= 60) {
            remaining.text = "%d:%02d".format(minutes / 60, minutes % 60)
        } else {
            remaining.text = context.getString(R.string.ml_detox_minutes, minutes)
        }
        remainingLabel.text = context.getString(R.string.ml_detox_left)
        if (lockedOut) {
            remaining.text = DetoxManager.formatDuration(DetoxManager.lockoutLeftMs(context))
            remainingLabel.text = context.getString(R.string.ml_detox_lockout_title).lowercase()
        }
        val total = (end - start).coerceAtLeast(1)
        val share = (left.toFloat() / total).coerceIn(0f, 1f)
        (fill.layoutParams as LinearLayout.LayoutParams).weight = share
        (empty.layoutParams as LinearLayout.LayoutParams).weight = 1f - share
        fill.requestLayout()
        val time = DateFormat.getTimeFormat(context)
        val date = DateFormat.format(DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "EEEdMMM"), java.util.Date())
        now.text = "${time.format(java.util.Date())}  ·  $date"
        started.text = context.getString(R.string.ml_detox_started_at, time.format(java.util.Date(start)))
        ends.text = context.getString(R.string.ml_detox_ends_at, time.format(java.util.Date(end)))
        updateMissed()
        if (lockedOut) lockoutNote.text = context.getString(
            R.string.ml_detox_lockout_message, time.format(java.util.Date(DetoxManager.lockoutEnd(context)))
        )
    }

    /** The missed bars count, while the lockout is on and not running. */
    private fun updateMissed() {
        val after = prefs.detoxLockoutAfter
        missed.isVisible = prefs.detoxLock && after > 0 && !lockedOut
        if (missed.isVisible) missed.text = context.getString(
            R.string.ml_detox_missed_bars, DetoxManager.missedBars(context, prefs.detoxLockoutReset), after
        )
    }

    // ---------------------------------------------------------------- apps

    /**
     * The apps allowed in detox (the launcher's own apps and the home's folders included; one each,
     * the own profile first) plus the phone, always allowed, in alphabetical order. During a
     * lockout only the phone and the wallet.
     */
    private fun allowedApps(): List<AppModel.App> {
        val wanted = DetoxManager.whitelist(context)
        val dialer = defaultDialer()
        return apps.filter {
            if (lockedOut) DetoxManager.isLockoutApp(context, it.appPackage)
            else (DetoxManager.whitelistKey(it) in wanted || it.appPackage == dialer) && DetoxManager.isAllowed(context, it.appPackage)
        }
            .sortedBy { it.user != android.os.Process.myUserHandle() }
            .distinctBy { DetoxManager.whitelistKey(it) }
            .sortedBy { it.appLabel.lowercase() }
    }

    private fun defaultDialer(): String? = try {
        (context.getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager).defaultDialerPackage
    } catch (e: Exception) {
        null
    }

    private fun renderApps() {
        shown = allowedApps()
        layoutApps()
    }

    /**
     * Every app in sight, no scrolling: one column of rows (56 to 140 dp tall) while they fit,
     * else as few columns as needed for tiles of at least 56 dp, else the tiles shrink.
     */
    private fun layoutApps() {
        list.removeAllViews()
        if (shown.isEmpty()) {
            list.addView(TextView(context, null, 0, R.style.TextSmall).apply {
                text = context.getString(R.string.ml_detox_empty_whitelist_hint)
                textSize = 15f
                setTextColor(secondary)
            })
            return
        }
        val height = listBox.height
        if (height == 0) return
        val gap = 10.dpToPx()
        val n = shown.size
        fun tileHeight(columns: Int): Int {
            val lines = (n + columns - 1) / columns
            return (height - gap * (lines - 1)) / lines
        }
        var columns = 1
        while (columns < MAX_COLUMNS && tileHeight(columns) < 56.dpToPx()) columns++
        val each = tileHeight(columns).coerceAtMost(140.dpToPx())
        shown.chunked(columns).forEachIndexed { i, line ->
            val lineView = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            line.forEachIndexed { j, app ->
                val tile = if (columns == 1) row(app) else tile(app, each, columns)
                lineView.addView(tile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (j > 0) marginStart = gap
                })
            }
            // Keep the columns of a short last line
            repeat(columns - line.size) {
                lineView.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = gap })
            }
            list.addView(lineView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, each).apply {
                if (i > 0) topMargin = gap
            })
        }
    }

    private fun notificationCount(app: AppModel.App) =
        notifications[NotificationRepository.key(app.appPackage, app.user.toString())]?.count ?: 0

    private fun loadIcon(app: AppModel.App, into: ImageView, sizePx: Int) {
        val iconPack = prefs.iconPack
        val appContext = context.applicationContext
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                IconPackManager.getIcon(appContext, app.appPackage, app.activityClassName, app.user, iconPack, sizePx)
            } ?: return@launch
            into.setImageDrawable(IconPackManager.toDrawable(context, bitmap, sizePx))
        }
    }

    private fun iconBox(app: AppModel.App, iconSize: Int) = FrameLayout(context).apply {
        background = GradientDrawable().apply {
            cornerRadius = 12.dpToPx().toFloat()
            setColor(context.getColorFromAttr(R.attr.tileColor))
        }
        val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        addView(icon, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
        loadIcon(app, icon, iconSize)
    }

    private fun badge(count: Int, sizeDp: Int) = TextView(context, null, 0, R.style.TextSmall).apply {
        text = if (count > 99) "99+" else count.toString()
        textSize = if (sizeDp >= 28) 14f else 11f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(android.graphics.Color.WHITE)
        gravity = Gravity.CENTER
        minWidth = sizeDp.dpToPx()
        setPadding((sizeDp / 4).dpToPx(), 0, (sizeDp / 4).dpToPx(), 0)
        includeFontPadding = false
        background = GradientDrawable().apply {
            cornerRadius = (sizeDp / 2).dpToPx().toFloat()
            setColor(NotificationBadge.NOTIFICATION_COLOR)
        }
    }

    /** Tap: the app, or first its notifications when it has some. */
    private fun View.opens(app: AppModel.App) {
        isClickable = true
        contentDescription = app.appLabel
        background = UiKit.cardBackground(context, 20)
        foreground = UiKit.ripple(context)
        setOnClickListener {
            stopLockCountdown()
            DetoxManager.lockBarUsed(context)
            if (notificationCount(app) > 0) NotificationBadge.showNotifications(context, app) { onLaunch(app) }
            else onLaunch(app)
        }
    }

    /** Several columns: the icon (badge on its corner) with the name below while there is room. */
    private fun tile(app: AppModel.App, heightPx: Int, columns: Int): View {
        val count = notificationCount(app)
        val withName = heightPx >= 76.dpToPx()
        val boxSize = (heightPx - (if (withName) 34 else 12).dpToPx()).coerceIn(24.dpToPx(), 48.dpToPx())
        val box = FrameLayout(context).apply {
            addView(iconBox(app, boxSize * 3 / 5), FrameLayout.LayoutParams(boxSize, boxSize, Gravity.CENTER))
            if (count > 0) addView(badge(count, 18), FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 18.dpToPx(), Gravity.TOP or Gravity.END))
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(6.dpToPx(), 0, 6.dpToPx(), 0)
            opens(app)
            addView(box, LinearLayout.LayoutParams(boxSize + 12.dpToPx(), boxSize + 6.dpToPx()))
            if (withName) addView(TextView(context, null, 0, R.style.TextSmall).apply {
                text = app.appLabel
                textSize = if (columns == 2) 15f else 13f
                setTextColor(primary)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 4.dpToPx() })
        }
    }

    /** One column: icon, name and notification count in a row. */
    private fun row(app: AppModel.App): View {
        val count = notificationCount(app)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20.dpToPx(), 0, 16.dpToPx(), 0)
            opens(app)
            addView(iconBox(app, 24.dpToPx()), LinearLayout.LayoutParams(40.dpToPx(), 40.dpToPx()))
            addView(TextView(context, null, 0, R.style.TextDefault).apply {
                text = app.appLabel
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(primary)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 16.dpToPx() })
            if (count > 0) addView(badge(count, 28), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 28.dpToPx()).apply { marginStart = 12.dpToPx() })
        }
    }

    private companion object {
        const val MAX_COLUMNS = 4
        const val DEFAULT_LOCK_COLOR = 0xFFFF9800.toInt()
    }
}
