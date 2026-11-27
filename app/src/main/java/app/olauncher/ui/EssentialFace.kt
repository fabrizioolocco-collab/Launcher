package app.olauncher.ui

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.os.BatteryManager
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import app.olauncher.R
import app.olauncher.data.CalendarWidgetConfig
import app.olauncher.data.Prefs
import app.olauncher.data.WeatherIcons
import app.olauncher.data.WeatherRepository
import app.olauncher.helper.CalendarEvents
import app.olauncher.helper.Skin
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The top of essential home: a big clock, the date and a row of small tags (battery, next alarm,
 * weather), then the next appointment. Each piece can be switched off in Settings
 * ([Prefs.essentialData]). The clock and the date tick by themselves; the rest is read again when
 * home comes back, on battery changes and once a minute.
 */
class EssentialFace(private val context: Context, private val prefs: Prefs, private val scope: CoroutineScope) {

    private val text = context.getColorFromAttr(R.attr.primaryColor)
    private val soft = (text and 0x00FFFFFF) or 0xCC000000.toInt()
    private val alert = 0xFFE0473E.toInt()

    val view = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }

    private val clock = TextClock(context).apply {
        format12Hour = "h:mm"
        format24Hour = "H:mm"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 92f)
        setTextColor(text)
        includeFontPadding = false
        gravity = Gravity.CENTER
        typeface = Skin.clockFace(context) ?: android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
        letterSpacing = 0.02f
    }

    private val date = TextClock(context).apply {
        val pattern = DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "EEEEdMMMM")
        format12Hour = pattern
        format24Hour = pattern
        setAllCaps(true)
        letterSpacing = 0.18f
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(soft)
        gravity = Gravity.CENTER
        Skin.bodyFace(context)?.let { typeface = it }
    }

    private val tags = LinearLayout(context).apply { gravity = Gravity.CENTER }
    private val battery = tag(R.drawable.ic_m_battery)
    private val alarm = tag(R.drawable.ic_m_alarm)
    private val weather = tag(R.drawable.ic_w_sun)
    private val event = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(soft)
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        Skin.bodyFace(context)?.let { typeface = it }
        setPadding(24.dpToPx(), 0, 24.dpToPx(), 0)
    }

    private var batteryLevel = -1
    private var charging = false
    private var receiverOn = false
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            readBattery(intent)
            showBattery()
        }
    }

    init {
        view.addView(clock, wrap())
        view.addView(date, wrap(top = 8))
        listOf(battery, alarm, weather).forEach { tags.addView(it.first, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = 4.dpToPx(); marginEnd = 4.dpToPx()
        }) }
        view.addView(tags, wrap(top = 26))
        view.addView(event, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 16.dpToPx() })
    }

    private fun wrap(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = top.dpToPx()
    }

    /** A small tag: an icon and a value, on the look's panel. */
    private fun tag(icon: Int): Pair<LinearLayout, Pair<ImageView, TextView>> {
        val image = ImageView(context).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(soft)
        }
        val value = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(text)
            Skin.bodyFace(context)?.let { typeface = it }
        }
        val box = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Skin.panel(context, cutDp = 6, radiusDp = 12)
            setPadding(10.dpToPx(), 6.dpToPx(), 12.dpToPx(), 6.dpToPx())
            addView(image, LinearLayout.LayoutParams(15.dpToPx(), 15.dpToPx()).apply { marginEnd = 7.dpToPx() })
            addView(value)
        }
        return box to (image to value)
    }

    /** Home is on screen: read everything again and follow the battery. */
    fun start() {
        if (!receiverOn) {
            ContextCompat.registerReceiver(context, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
                ?.let { readBattery(it) }
            receiverOn = true
        }
        refresh()
        if (prefs.essentialShows(Prefs.ESSENTIAL_WEATHER) && WeatherRepository.hasPermission(context) &&
            WeatherRepository.shouldRetry(context)
        ) scope.launch {
            WeatherRepository.refresh(context)
            showWeather()
        }
    }

    fun stop() {
        if (receiverOn) {
            runCatching { context.unregisterReceiver(batteryReceiver) }
            receiverOn = false
        }
    }

    fun refresh() {
        date.visibility = if (prefs.essentialShows(Prefs.ESSENTIAL_DATE)) View.VISIBLE else View.GONE
        showBattery()
        showAlarm()
        showWeather()
        showEvent()
    }

    private fun updateTags() {
        tags.visibility = if ((0 until tags.childCount).any { tags.getChildAt(it).visibility == View.VISIBLE }) View.VISIBLE else View.GONE
    }

    private fun readBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        batteryLevel = if (level >= 0 && scale > 0) (level * 100f / scale).roundToInt() else -1
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun showBattery() {
        val (box, parts) = battery
        if (!prefs.essentialShows(Prefs.ESSENTIAL_BATTERY) || batteryLevel < 0) {
            box.visibility = View.GONE
        } else {
            box.visibility = View.VISIBLE
            val low = !charging && batteryLevel < 20
            parts.first.setImageResource(if (charging) R.drawable.ic_m_bolt else R.drawable.ic_m_battery)
            parts.first.imageTintList = ColorStateList.valueOf(if (low) alert else soft)
            parts.second.text = "$batteryLevel%"
            parts.second.setTextColor(if (low) alert else text)
        }
        updateTags()
    }

    private fun showAlarm() {
        val (box, parts) = alarm
        val next = if (prefs.essentialShows(Prefs.ESSENTIAL_ALARM))
            context.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.triggerTime else null
        if (next == null) {
            box.visibility = View.GONE
        } else {
            box.visibility = View.VISIBLE
            val time = DateFormat.getTimeFormat(context).format(next)
            // Further than a day away: the day too
            parts.second.text = if (next - System.currentTimeMillis() > 86_400_000L)
                DateFormat.format("EEE", next).toString() + " " + time else time
        }
        updateTags()
    }

    private fun showWeather() {
        val (box, parts) = weather
        val hour = if (prefs.essentialShows(Prefs.ESSENTIAL_WEATHER)) WeatherRepository.cached(context)
            ?.takeIf { System.currentTimeMillis() - it.fetched < 6 * 3_600_000L }?.upcoming()?.firstOrNull() else null
        if (hour == null) {
            box.visibility = View.GONE
        } else {
            box.visibility = View.VISIBLE
            parts.first.setImageResource(WeatherIcons.icon(hour.code, hour.isDay))
            parts.first.imageTintList = null
            parts.second.text = "${hour.temp.roundToInt()}° " + WeatherIcons.label(context, hour.code).lowercase()
        }
        updateTags()
    }

    private fun showEvent() {
        if (!prefs.essentialShows(Prefs.ESSENTIAL_EVENT) || !CalendarEvents.hasPermission(context)) {
            event.visibility = View.GONE
            return
        }
        scope.launch {
            val now = System.currentTimeMillis()
            val today = CalendarEvents.julianDay(now)
            val next = withContext(Dispatchers.IO) {
                CalendarEvents.events(context, today, today + 1, CalendarWidgetConfig())
                    .filter { !it.allDay && it.end > now }
                    .minByOrNull { it.begin }
            }
            if (next == null) {
                event.visibility = View.GONE
                return@launch
            }
            val time = DateFormat.getTimeFormat(context).format(next.begin)
            val tomorrow = CalendarEvents.julianDay(next.begin) != today
            val label = (if (tomorrow) context.getString(R.string.ml_essential_tomorrow) + " " else "") + time
            event.text = android.text.SpannableStringBuilder().apply {
                append(label, android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0)
                append("  ·  ")
                append(next.title.ifBlank { "—" })
            }
            event.setTextColor(soft)
            event.visibility = View.VISIBLE
        }
    }
}
