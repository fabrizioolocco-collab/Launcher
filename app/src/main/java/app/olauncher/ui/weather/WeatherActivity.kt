package app.olauncher.ui.weather

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import app.olauncher.R
import app.olauncher.data.Forecast
import app.olauncher.data.WeatherDay
import app.olauncher.data.WeatherIcons
import app.olauncher.data.WeatherRepository
import app.olauncher.helper.dpToPx
import app.olauncher.ui.UiKit
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The weather: a meteogram of the coming hours (temperature, rain, wind, pressure, ...) scrolling
 * sideways under a pinned row of hours and icons, and a list of the next 7 days.
 * Data from Open-Meteo through [WeatherRepository].
 */
class WeatherActivity : AppCompatActivity() {

    private lateinit var bar: UiKit.AppBar
    private lateinit var content: FrameLayout
    private lateinit var segHours: TextView
    private lateinit var segDays: TextView
    private lateinit var refreshButton: ImageView

    private var forecast: Forecast? = null
    private var loading = false
    private var showDays = false

    /** Hour column to scroll to when the hours view is next shown. */
    private var pendingColumn = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        setContentView(buildLayout())
        forecast = WeatherRepository.cached(this)
        render()
        load(force = false)
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        refreshButton = UiKit.iconButton(this, R.drawable.ic_m_rotate, getString(R.string.ml_w_app)) { load(force = true) }
        val customize = UiKit.iconButton(this, R.drawable.ic_m_sliders, getString(R.string.ml_w_customize)) {
            WeatherSections.edit(this) { render() }
        }
        bar = UiKit.appBar(this, getString(R.string.ml_w_app), onBack = { finish() }, actions = listOf(customize, refreshButton))
        bar.view.setPadding(4.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
        root.addView(bar.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Hours | Days
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = UiKit.border(this@WeatherActivity, 18)
            val p = 3.dpToPx()
            setPadding(p, p, p, p)
        }
        segHours = segment(getString(R.string.ml_w_hours)) { setMode(days = false) }
        segDays = segment(getString(R.string.ml_w_days)) { setMode(days = true) }
        seg.addView(segHours, LinearLayout.LayoutParams(0, 36.dpToPx(), 1f))
        seg.addView(segDays, LinearLayout.LayoutParams(0, 36.dpToPx(), 1f))
        root.addView(seg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(16.dpToPx(), 4.dpToPx(), 16.dpToPx(), 10.dpToPx())
        })

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun segment(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(UiKit.primary(this@WeatherActivity))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun updateSegments() {
        fun style(view: TextView, selected: Boolean) {
            view.background = if (selected) GradientDrawable().apply {
                cornerRadius = 15.dpToPx().toFloat()
                setColor(ColorUtils.setAlphaComponent(UiKit.primary(this@WeatherActivity), 0x26))
            } else null
            view.setTypeface(Typeface.DEFAULT, if (selected) Typeface.BOLD else Typeface.NORMAL)
            view.setTextColor(if (selected) UiKit.primary(this@WeatherActivity) else UiKit.secondary(this@WeatherActivity))
        }
        style(segHours, !showDays)
        style(segDays, showDays)
    }

    private fun setMode(days: Boolean) {
        if (showDays == days) return
        showDays = days
        render()
    }

    // ---------------------------------------------------------------- data

    private fun load(force: Boolean) {
        if (loading) return
        loading = true
        refreshButton.alpha = 0.4f
        if (forecast == null) render()
        lifecycleScope.launch {
            val fresh = try {
                WeatherRepository.refresh(this@WeatherActivity, force)
            } catch (e: Exception) {
                null
            }
            loading = false
            refreshButton.alpha = 1f
            if (fresh != null) forecast = fresh
            render()
        }
    }

    private fun requestLocation() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_LOCATION)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) load(force = true)
    }

    // ---------------------------------------------------------------- render

    private fun render() {
        updateSegments()
        val f = forecast
        bar.title.text = f?.place?.takeIf { it.isNotBlank() } ?: getString(R.string.ml_w_app)
        UiKit.setSubtitle(bar, f?.let {
            getString(R.string.ml_w_updated, updatedLabel(it.fetched)) + " · " + getString(R.string.ml_w_source)
        })
        content.removeAllViews()
        val view: View = when {
            f != null && f.upcoming().isNotEmpty() -> if (showDays) daysView(f) else hoursView(f)
            !WeatherRepository.hasPermission(this) ->
                UiKit.emptyState(this, R.drawable.ic_m_crosshair, getString(R.string.ml_w_no_location), getString(R.string.ml_todo_allow)) { requestLocation() }
            loading -> TextView(this).apply {
                text = getString(R.string.ml_w_loading)
                textSize = 15f
                setTextColor(UiKit.secondary(this@WeatherActivity))
                gravity = Gravity.CENTER
            }
            else -> UiKit.emptyState(this, R.drawable.ic_m_rotate, getString(R.string.ml_w_error)).apply {
                // tap anywhere to retry
                isClickable = true
                setOnClickListener { load(force = true) }
            }
        }
        content.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun updatedLabel(time: Long): String {
        val same = sameDay(time, System.currentTimeMillis())
        return SimpleDateFormat(if (same) "HH:mm" else "d MMM HH:mm", Locale.getDefault()).format(Date(time))
    }

    private fun sameDay(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }

    // ---------------------------------------------------------------- hours

    private fun hoursView(f: Forecast): View {
        val hours = f.upcoming()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val headerView = MeteogramView(this, hours, header = true)
        val headerScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(headerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        box.addView(headerScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val bodyView = MeteogramView(this, hours, header = false)
        val bodyScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(bodyView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val vertical = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(bodyScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        box.addView(vertical, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Keep the two rows scrolled together
        var syncing = false
        headerScroll.setOnScrollChangeListener { _, x, _, _, _ ->
            headerView.scrollPos = x
            if (!syncing) {
                syncing = true
                bodyScroll.scrollTo(x, 0)
                bodyView.scrollPos = x
                syncing = false
            }
        }
        bodyScroll.setOnScrollChangeListener { _, x, _, _, _ ->
            bodyView.scrollPos = x
            if (!syncing) {
                syncing = true
                headerScroll.scrollTo(x, 0)
                headerView.scrollPos = x
                syncing = false
            }
        }

        val column = pendingColumn
        pendingColumn = -1
        if (column > 0) {
            bodyScroll.post {
                val x = column * MeteogramView.COL_DP.dpToPx()
                bodyScroll.scrollTo(x, 0)
                headerScroll.scrollTo(x, 0)
            }
        }
        return box
    }

    // ---------------------------------------------------------------- days

    private fun daysView(f: Forecast): View {
        val now = System.currentTimeMillis()
        val days = f.days.filter { it.time + DAY > now }.take(7)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dpToPx(), 4.dpToPx(), 16.dpToPx(), 24.dpToPx())
        }
        val group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiKit.border(this@WeatherActivity, 16)
            clipToOutline = true
        }
        val weekMin = days.minOfOrNull { it.min } ?: 0f
        val weekMax = days.maxOfOrNull { it.max } ?: 0f
        days.forEachIndexed { i, day ->
            if (i > 0) group.addView(View(this).apply { setBackgroundColor(UiKit.line(this@WeatherActivity)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1.dpToPx()))
            group.addView(dayRow(f, day, weekMin, weekMax), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        list.addView(group, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val today = days.firstOrNull { sameDay(it.time, now) } ?: days.firstOrNull()
        if (today != null && today.sunrise > 0 && today.sunset > 0) {
            val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            list.addView(TextView(this).apply {
                text = getString(R.string.ml_w_sunrise, fmt.format(Date(today.sunrise)), fmt.format(Date(today.sunset)))
                textSize = 13f
                setTextColor(UiKit.secondary(this@WeatherActivity))
                gravity = Gravity.CENTER
                setPadding(0, 16.dpToPx(), 0, 0)
            })
        }
        return ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun dayLabel(time: Long): String {
        val now = System.currentTimeMillis()
        return when {
            sameDay(time, now) -> getString(R.string.ml_cal_today)
            sameDay(time, now + DAY) -> getString(R.string.ml_cal_tomorrow)
            else -> SimpleDateFormat("EEE d", Locale.getDefault()).format(Date(time))
        }
    }

    private fun dayRow(f: Forecast, day: WeatherDay, weekMin: Float, weekMax: Float): View {
        val ctx = this
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dpToPx(), 12.dpToPx(), 14.dpToPx(), 12.dpToPx())
            background = UiKit.ripple(ctx)
            isClickable = true
            isFocusable = true
            setOnClickListener { openDay(f, day) }
        }
        row.addView(ImageView(ctx).apply {
            setImageResource(WeatherIcons.icon(day.code, true))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(32.dpToPx(), 32.dpToPx()).apply { marginEnd = 12.dpToPx() })

        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(ctx).apply {
            text = dayLabel(day.time)
            textSize = 15f
            setTextColor(UiKit.primary(ctx))
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        val sub = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sub.addView(TextView(ctx).apply {
            text = WeatherIcons.label(ctx, day.code)
            textSize = 12.5f
            setTextColor(UiKit.secondary(ctx))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (day.precip > 0f || day.precipChance > 0) {
            val parts = ArrayList<String>()
            if (day.precipChance > 0) parts.add("${day.precipChance}%")
            if (day.precip > 0f) parts.add(String.format(Locale.getDefault(), "%.1f mm", day.precip))
            sub.addView(TextView(ctx).apply {
                text = parts.joinToString(" · ")
                textSize = 12.5f
                setTextColor(WeatherIcons.RAIN)
                maxLines = 1
                setPadding(6.dpToPx(), 0, 0, 0)
            })
        }
        texts.addView(sub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        fun temp(value: Float, color: Int, gravity: Int) = TextView(ctx).apply {
            text = "${value.roundToInt()}°"
            textSize = 14f
            setTextColor(color)
            this.gravity = gravity
            maxLines = 1
        }
        row.addView(temp(day.min, UiKit.secondary(ctx), Gravity.END or Gravity.CENTER_VERTICAL),
            LinearLayout.LayoutParams(36.dpToPx(), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = 8.dpToPx() })
        row.addView(TempRangeBar(ctx, day.min, day.max, weekMin, weekMax),
            LinearLayout.LayoutParams(72.dpToPx(), 20.dpToPx()).apply {
                marginStart = 8.dpToPx()
                marginEnd = 8.dpToPx()
            })
        row.addView(temp(day.max, UiKit.primary(ctx), Gravity.START or Gravity.CENTER_VERTICAL),
            LinearLayout.LayoutParams(36.dpToPx(), ViewGroup.LayoutParams.WRAP_CONTENT))
        return row
    }

    /** Shows the hours from the first one of [day]. */
    private fun openDay(f: Forecast, day: WeatherDay) {
        val hours = f.upcoming()
        val index = hours.indexOfFirst { it.time >= day.time && it.time < day.time + DAY }
        pendingColumn = if (index < 0) 0 else index
        showDays = false
        render()
    }

    companion object {
        private const val REQUEST_LOCATION = 4711
        private const val DAY = 24 * 3_600_000L

        fun open(context: Context) {
            context.startActivity(Intent(context, WeatherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
