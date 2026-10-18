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
import android.widget.ScrollView
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
 * bar on top, then the allowed apps as rows sharing the rest of the page (taller the fewer they
 * are), each with the count of its notifications; "End" opens the session menu.
 */
class DetoxPage(
    private val page: FrameLayout,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val onLaunch: (AppModel.App) -> Unit,
    private val onEnd: () -> Unit,
) {
    private val context: Context = page.context
    private val primary = context.getColorFromAttr(R.attr.primaryColor)
    private val secondary = context.getColorFromAttr(R.attr.secondaryTextColor)
    private val accent = context.getColorFromAttr(R.attr.accentColor)

    private var apps: List<AppModel.App> = emptyList()
    private var notifications: Map<String, AppNotifications> = emptyMap()

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
    private var rows: List<View> = emptyList()

    init {
        page.setBackgroundColor(context.getColorFromAttr(R.attr.primaryInverseColor))
        // Takes every touch: home's gestures stay behind it
        page.isClickable = true
        page.removeAllViews()
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dpToPx(), 56.dpToPx(), 20.dpToPx(), 24.dpToPx())
        }
        column.addView(header())
        column.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(remaining)
            addView(remainingLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = 10.dpToPx()
                bottomMargin = 8.dpToPx()
            })
        }, matchWrap(top = 12))
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

        listBox.addView(ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // The rows share the room left: their height is known once the box is laid out
        listBox.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) listBox.post { sizeRows() }
        }
        column.addView(listBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = 24.dpToPx()
            bottomMargin = 16.dpToPx()
        })
        column.addView(endButton(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 44.dpToPx()).apply {
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
        setOnClickListener { onEnd() }
    }

    // ---------------------------------------------------------------- state

    /** Shown while a session runs; [onShown] when it has just appeared. */
    fun update(onShown: () -> Unit = {}) {
        val active = DetoxManager.isActive(context)
        val wasVisible = page.isVisible
        page.isVisible = active
        if (!active) return
        if (!wasVisible) {
            renderApps()
            onShown()
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
        val total = (end - start).coerceAtLeast(1)
        val share = (left.toFloat() / total).coerceIn(0f, 1f)
        (fill.layoutParams as LinearLayout.LayoutParams).weight = share
        (empty.layoutParams as LinearLayout.LayoutParams).weight = 1f - share
        fill.requestLayout()
        val time = DateFormat.getTimeFormat(context)
        started.text = context.getString(R.string.ml_detox_started_at, time.format(java.util.Date(start)))
        ends.text = context.getString(R.string.ml_detox_ends_at, time.format(java.util.Date(end)))
    }

    // ---------------------------------------------------------------- apps

    /**
     * The whitelisted apps (one per package, the own profile first) plus the phone, always allowed,
     * in alphabetical order.
     */
    private fun allowedApps(): List<AppModel.App> {
        val wanted = DetoxManager.whitelist(context) + listOfNotNull(defaultDialer())
        return apps.filter { it.appPackage in wanted && DetoxManager.isAllowed(context, it.appPackage) }
            .sortedBy { it.user != android.os.Process.myUserHandle() }
            .distinctBy { it.appPackage }
            .sortedBy { it.appLabel.lowercase() }
    }

    private fun defaultDialer(): String? = try {
        (context.getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager).defaultDialerPackage
    } catch (e: Exception) {
        null
    }

    private fun renderApps() {
        list.removeAllViews()
        val shown = allowedApps()
        if (shown.isEmpty()) {
            list.addView(TextView(context, null, 0, R.style.TextSmall).apply {
                text = context.getString(R.string.ml_detox_empty_whitelist_hint)
                textSize = 15f
                setTextColor(secondary)
            })
            rows = emptyList()
            return
        }
        rows = shown.map { app -> row(app).also { list.addView(it) } }
        sizeRows()
    }

    /** Rows share the box's height, between 56 and 140 dp each; past that the list scrolls. */
    private fun sizeRows() {
        if (rows.isEmpty() || listBox.height == 0) return
        val gap = 10.dpToPx()
        val each = ((listBox.height - gap * (rows.size - 1)) / rows.size).coerceIn(56.dpToPx(), 140.dpToPx())
        rows.forEachIndexed { i, row ->
            row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, each).apply {
                topMargin = if (i == 0) 0 else gap
            }
        }
        list.requestLayout()
    }

    private fun row(app: AppModel.App): View {
        val count = notifications[NotificationRepository.key(app.appPackage, app.user.toString())]?.count ?: 0
        val iconSize = 24.dpToPx()
        val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val iconBox = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = 12.dpToPx().toFloat()
                setColor(context.getColorFromAttr(R.attr.tileColor))
            }
            addView(icon, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
        }
        val iconPack = prefs.iconPack
        val appContext = context.applicationContext
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                IconPackManager.getIcon(appContext, app.appPackage, app.activityClassName, app.user, iconPack, iconSize)
            } ?: return@launch
            icon.setImageDrawable(IconPackManager.toDrawable(context, bitmap, iconSize))
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20.dpToPx(), 0, 16.dpToPx(), 0)
            background = UiKit.cardBackground(context, 20)
            foreground = UiKit.ripple(context)
            isClickable = true
            contentDescription = app.appLabel
            addView(iconBox, LinearLayout.LayoutParams(40.dpToPx(), 40.dpToPx()))
            addView(TextView(context, null, 0, R.style.TextDefault).apply {
                text = app.appLabel
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(primary)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 16.dpToPx() })
            if (count > 0) addView(TextView(context, null, 0, R.style.TextSmall).apply {
                text = if (count > 99) "99+" else count.toString()
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(android.graphics.Color.WHITE)
                gravity = Gravity.CENTER
                minWidth = 28.dpToPx()
                setPadding(8.dpToPx(), 0, 8.dpToPx(), 0)
                background = GradientDrawable().apply {
                    cornerRadius = 14.dpToPx().toFloat()
                    setColor(NotificationBadge.NOTIFICATION_COLOR)
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 28.dpToPx()).apply { marginStart = 12.dpToPx() })
            setOnClickListener {
                if (count > 0) NotificationBadge.showNotifications(context, app) { onLaunch(app) }
                else onLaunch(app)
            }
        }
    }
}
