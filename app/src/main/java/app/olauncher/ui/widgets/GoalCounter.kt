package app.olauncher.ui.widgets

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import app.olauncher.R
import app.olauncher.helper.MenuEntry
import app.olauncher.helper.baseDialog
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.hideKeyboard
import app.olauncher.helper.showConfirmDialog
import app.olauncher.helper.showMenuDialog
import app.olauncher.helper.showTextInputDialog
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * A goal counter of the calendar widget: the time passed since a moment of the past ([start]) as a
 * share of a goal duration ([goalMinutes]), shown with [decimals] digits after the comma.
 */
data class GoalCounter(
    val start: Long = 0L,
    val goalMinutes: Long = DEFAULT_GOAL_MINUTES,
    val decimals: Int = 2,
    val name: String = "",
    val hidden: Boolean = false,
    /** Invisible until its place is tapped, gone again at the next tap. */
    val privacy: Boolean = false,
) {
    val isSet get() = start > 0 && goalMinutes > 0
    val goalMs get() = goalMinutes * 60_000L

    fun elapsedMs(now: Long = System.currentTimeMillis()) = (now - start).coerceAtLeast(0)

    fun percent(now: Long = System.currentTimeMillis()): Double =
        if (!isSet) 0.0 else elapsedMs(now) * 100.0 / goalMs

    fun percentText(now: Long = System.currentTimeMillis()): String =
        String.format(Locale.getDefault(), "%.${decimals}f%%", percent(now))

    /** How often the last digit shown changes, kept between a tenth of a second and a minute. */
    fun tickMs(): Long {
        if (!isSet) return 60_000L
        val step = goalMs / Math.pow(10.0, decimals + 2.0)
        return step.toLong().coerceIn(100L, 60_000L)
    }

    companion object {
        const val DEFAULT_GOAL_MINUTES = 100L * 24 * 60
        const val MAX_DECIMALS = 8
    }
}

/** The goal counters, one per calendar widget (by its id). */
object GoalCounterStore {
    private fun prefs(context: Context) = context.getSharedPreferences("app.minimalauncher.goalcounter", Context.MODE_PRIVATE)

    fun load(context: Context, widgetId: Int): GoalCounter {
        val p = prefs(context)
        val k = "w$widgetId"
        return GoalCounter(
            start = p.getLong("${k}_start", 0L),
            goalMinutes = p.getLong("${k}_goal", GoalCounter.DEFAULT_GOAL_MINUTES),
            decimals = p.getInt("${k}_decimals", 2),
            name = p.getString("${k}_name", "").orEmpty(),
            hidden = p.getBoolean("${k}_hidden", false),
            privacy = p.getBoolean("${k}_privacy", false),
        )
    }

    fun save(context: Context, widgetId: Int, c: GoalCounter) {
        val k = "w$widgetId"
        prefs(context).edit {
            putLong("${k}_start", c.start)
            putLong("${k}_goal", c.goalMinutes)
            putInt("${k}_decimals", c.decimals)
            putString("${k}_name", c.name)
            putBoolean("${k}_hidden", c.hidden)
            putBoolean("${k}_privacy", c.privacy)
        }
    }
}

/**
 * The small percentage in the calendar widget's header, refreshed as often as its last digit
 * changes while it is on screen. Before a goal is set it shows an hourglass. A tap opens
 * [GoalCounterDialog]; in privacy mode it is invisible and a tap shows / hides it instead (the
 * dialog is then in the widget's + menu).
 */
class GoalCounterView(private val activity: Activity, private val widgetId: Int) :
    androidx.appcompat.widget.AppCompatTextView(activity) {

    var counter: GoalCounter = GoalCounterStore.load(activity, widgetId)
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            show()
            handler.postDelayed(this, counter.tickMs())
        }
    }

    init {
        maxLines = 1
        gravity = Gravity.CENTER
        setPadding(6.dpToPx(), 4.dpToPx(), 6.dpToPx(), 4.dpToPx())
        isClickable = true
        isFocusable = true
        setOnClickListener {
            if (counter.privacy && counter.isSet) {
                revealed = !revealed
                applyPrivacy()
            } else GoalCounterDialog.show(activity, widgetId) { reload() }
        }
        // Tabular digits: the width doesn't jump while the number runs
        fontFeatureSettings = "tnum"
        show()
    }

    /** Shown for now in privacy mode (until the next tap; the setting itself stays). */
    private var revealed = false

    fun reload() {
        counter = GoalCounterStore.load(activity, widgetId)
        isVisible = !counter.hidden
        applyPrivacy()
        restart()
    }

    /** Invisible but still there to be tapped. */
    private fun applyPrivacy() {
        alpha = if (counter.privacy && counter.isSet && !revealed) 0f else 1f
    }

    private fun show() {
        text = if (counter.isSet) counter.percentText() else "⌛"
        contentDescription = (counter.name.ifBlank { activity.getString(R.string.ml_goal_title) }) + " " + text
    }

    private fun restart() {
        handler.removeCallbacks(tick)
        show()
        if (isAttachedToWindow && windowVisibility == VISIBLE && !counter.hidden && counter.isSet)
            handler.postDelayed(tick, counter.tickMs())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        reload()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        restart()
    }
}

/** The goal counter opened: its live state on top, then what can be changed. */
object GoalCounterDialog {

    fun show(activity: Activity, widgetId: Int, onChanged: () -> Unit) {
        val c = GoalCounterStore.load(activity, widgetId)
        // Applied to the saved counter: the switch below may have changed it meanwhile
        fun change(edit: (GoalCounter) -> GoalCounter) {
            GoalCounterStore.save(activity, widgetId, edit(GoalCounterStore.load(activity, widgetId)))
            onChanged()
            show(activity, widgetId, onChanged)
        }
        if (!c.isSet) {
            // First time: it starts now, for the default goal; everything can be changed below
            change { it.copy(start = System.currentTimeMillis(), hidden = false) }
            return
        }
        val title = c.name.ifBlank { activity.getString(R.string.ml_goal_title) }
        activity.showMenuDialog(
            title,
            listOf(
                MenuEntry(activity.getString(R.string.ml_goal_name), value = c.name.ifBlank { "—" }) {
                    activity.showTextInputDialog(
                        title = activity.getString(R.string.ml_goal_name),
                        initial = c.name,
                        hint = activity.getString(R.string.ml_goal_title),
                        action = activity.getString(R.string.ml_save),
                        allowBlank = true,
                    ) { name -> change { it.copy(name = name) } }
                },
                MenuEntry(activity.getString(R.string.ml_goal_start), value = formatDate(c.start)) {
                    pickStart(activity, c.start) { start -> change { it.copy(start = start) } }
                },
                MenuEntry(activity.getString(R.string.ml_goal_duration), value = formatDuration(activity, c.goalMs)) {
                    askDuration(activity, c.goalMinutes) { goal -> change { it.copy(goalMinutes = goal) } }
                },
                MenuEntry(activity.getString(R.string.ml_goal_decimals), value = c.decimals.toString()) {
                    activity.showMenuDialog(
                        activity.getString(R.string.ml_goal_decimals),
                        (0..GoalCounter.MAX_DECIMALS).map { n ->
                            MenuEntry(String.format(Locale.getDefault(), "%.${n}f%%", 12.3456789012), value = n.toString()) {
                                change { it.copy(decimals = n) }
                            }
                        }
                    )
                },
                MenuEntry(activity.getString(R.string.ml_goal_restart)) {
                    activity.showConfirmDialog(
                        title = activity.getString(R.string.ml_goal_restart),
                        message = activity.getString(R.string.ml_goal_restart_confirm),
                        action = activity.getString(R.string.ml_goal_restart),
                    ) { change { it.copy(start = System.currentTimeMillis()) } }
                },
                MenuEntry(activity.getString(R.string.ml_goal_privacy), checked = c.privacy, onToggle = { on ->
                    GoalCounterStore.save(activity, widgetId, GoalCounterStore.load(activity, widgetId).copy(privacy = on))
                    onChanged()
                    true
                }),
                MenuEntry(activity.getString(R.string.ml_goal_show), checked = !c.hidden, onToggle = { on ->
                    GoalCounterStore.save(activity, widgetId, GoalCounterStore.load(activity, widgetId).copy(hidden = !on))
                    onChanged()
                    true
                }),
            ),
            header = { liveHeader(activity, c) },
        )
    }

    /** Big percentage, bar, and the times: started, passed, left, end. Runs while shown. */
    private fun liveHeader(activity: Activity, c: GoalCounter): View {
        val primary = activity.getColorFromAttr(R.attr.primaryColor)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12.dpToPx(), 8.dpToPx(), 8.dpToPx())
        }
        val big = TextView(activity).apply {
            textSize = 34f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(primary)
            fontFeatureSettings = "tnum"
        }
        val bar = Bar(activity, primary, ColorUtils.setAlphaComponent(primary, 0x30))
        val details = TextView(activity).apply {
            textSize = 14f
            setTextColor(primary)
            alpha = 0.8f
            setLineSpacing(4.dpToPx().toFloat(), 1f)
        }
        box.addView(big)
        box.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 8.dpToPx()).apply {
            topMargin = 6.dpToPx(); bottomMargin = 10.dpToPx()
        })
        box.addView(details)
        val handler = Handler(Looper.getMainLooper())
        val update = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                big.text = c.percentText(now)
                bar.fraction = (c.percent(now) / 100.0).toFloat()
                val elapsed = c.elapsedMs(now)
                val left = c.goalMs - elapsed
                details.text = listOf(
                    activity.getString(R.string.ml_goal_started, formatDate(c.start)),
                    activity.getString(R.string.ml_goal_elapsed, formatDuration(activity, elapsed)),
                    if (left > 0) activity.getString(R.string.ml_goal_left, formatDuration(activity, left))
                    else activity.getString(R.string.ml_goal_reached, formatDuration(activity, -left)),
                    activity.getString(R.string.ml_goal_end, formatDate(c.start + c.goalMs)),
                ).joinToString("\n")
                if (box.isAttachedToWindow) handler.postDelayed(this, c.tickMs().coerceAtMost(1000L))
            }
        }
        box.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                handler.removeCallbacks(update)
                update.run()
            }

            override fun onViewDetachedFromWindow(v: View) = handler.removeCallbacks(update)
        })
        update.run()
        return box
    }

    private class Bar(context: Context, private val fill: Int, private val track: Int) : View(context) {
        var fraction = 0f
            set(value) {
                if (field != value) {
                    field = value
                    invalidate()
                }
            }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            val r = height / 2f
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            paint.color = track
            canvas.drawRoundRect(rect, r, r, paint)
            rect.right = width * fraction.coerceIn(0f, 1f)
            paint.color = fill
            if (rect.right > 0) canvas.drawRoundRect(rect, r, r, paint)
        }
    }

    private fun formatDate(ms: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

    /** "12 g 5 h 3 m" (days only when there are any). */
    fun formatDuration(context: Context, ms: Long): String {
        val totalMin = ms / 60_000L
        val d = totalMin / (24 * 60)
        val h = (totalMin / 60) % 24
        val m = totalMin % 60
        return if (d > 0) context.getString(R.string.ml_goal_dhm, d, h, m) else context.getString(R.string.ml_goal_hm, h, m)
    }

    private fun pickerTheme(context: Context) =
        if ((context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        ) android.R.style.Theme_DeviceDefault_Dialog_Alert
        else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert

    /** Day, then time; not in the future. */
    private fun pickStart(activity: Activity, initial: Long, onPick: (Long) -> Unit) {
        val c = Calendar.getInstance().apply { timeInMillis = initial }
        val theme = pickerTheme(activity)
        android.app.DatePickerDialog(activity, theme, { _, y, mo, d ->
            android.app.TimePickerDialog(activity, theme, { _, h, mi ->
                val picked = Calendar.getInstance().apply {
                    clear()
                    set(y, mo, d, h, mi, 0)
                }.timeInMillis
                onPick(picked.coerceAtMost(System.currentTimeMillis()))
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(activity)).show()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).apply {
            datePicker.firstDayOfWeek = Calendar.MONDAY
            datePicker.maxDate = System.currentTimeMillis()
        }.show()
    }

    /** Days, hours and minutes of the goal, side by side. */
    private fun askDuration(activity: Activity, minutes: Long, onSave: (Long) -> Unit) {
        val (dialog, binding) = activity.baseDialog(activity.getString(R.string.ml_goal_duration))
        val ctx = dialog.context
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun field(value: Long, label: Int): EditText {
            val column = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val input = EditText(ctx).apply {
                setText(value.toString())
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_NUMBER
                imeOptions = EditorInfo.IME_ACTION_NEXT
                gravity = Gravity.CENTER
                app.olauncher.ui.UiKit.styleInput(this)
                textSize = 20f
                setSelectAllOnFocus(true)
            }
            column.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            column.addView(TextView(ctx).apply {
                text = ctx.getString(label)
                textSize = 13f
                setTextColor(ctx.getColorFromAttr(R.attr.primaryColor))
                alpha = 0.7f
                gravity = Gravity.CENTER
            })
            row.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 8.dpToPx()
            })
            return input
        }
        val days = field(minutes / (24 * 60), R.string.ml_goal_days)
        val hours = field((minutes / 60) % 24, R.string.ml_goal_hours)
        val mins = field(minutes % 60, R.string.ml_goal_minutes)
        mins.imeOptions = EditorInfo.IME_ACTION_DONE
        fun submit() {
            fun v(e: EditText) = e.text.toString().trim().toLongOrNull()?.coerceAtLeast(0) ?: 0L
            val total = v(days) * 24 * 60 + v(hours) * 60 + v(mins)
            if (total <= 0) return
            mins.hideKeyboard()
            dialog.dismiss()
            onSave(total)
        }
        mins.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { submit(); true } else false }
        binding.contentContainer.addView(row, ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 16.dpToPx(); bottomMargin = 8.dpToPx()
        })
        binding.contentContainer.isVisible = true
        binding.tvAction.text = activity.getString(R.string.ml_save)
        binding.tvAction.isVisible = true
        binding.tvAction.setOnClickListener { submit() }
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.showRespectingStatusBar()
        days.requestFocus()
    }
}
