package app.olauncher.ui.todo

import android.Manifest
import android.app.DatePickerDialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import app.olauncher.R
import app.olauncher.data.ALERT_ALARM
import app.olauncher.data.ALERT_NONE
import app.olauncher.data.ALERT_NOTIFY
import app.olauncher.data.NO_DAY
import app.olauncher.data.NO_TIME
import app.olauncher.data.REPEAT_CUSTOM
import app.olauncher.data.REPEAT_DAILY
import app.olauncher.data.REPEAT_MONTHLY
import app.olauncher.data.REPEAT_NONE
import app.olauncher.data.REPEAT_WEEKDAYS
import app.olauncher.data.REPEAT_WEEKLY
import app.olauncher.data.REPEAT_YEARLY
import app.olauncher.data.Todo
import app.olauncher.data.TodoStore
import app.olauncher.data.UNIT_DAY
import app.olauncher.data.UNIT_MONTH
import app.olauncher.data.UNIT_WEEK
import app.olauncher.data.UNIT_YEAR
import app.olauncher.helper.CalendarEvents
import app.olauncher.helper.DESTRUCTIVE_COLOR
import app.olauncher.helper.MenuEntry
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.hideKeyboard
import app.olauncher.helper.showConfirmDialog
import app.olauncher.helper.showMenuDialog
import app.olauncher.helper.showToast
import app.olauncher.ui.UiKit
import java.util.Calendar

/**
 * New / edit todo: what, category, due date and optional time, alert (none, notification, alarm)
 * and repetition (every day / week / month / year / working day, or every N days / weeks / months
 * / years, counted from the due date or from the day it's done).
 */
class TodoEditActivity : AppCompatActivity() {

    private lateinit var todo: Todo
    private var isNew = true
    /** Custom repetition: the day the count starts from (only to work out the due date). */
    private var countFrom = NO_DAY
    private var primary = 0

    private lateinit var titleInput: EditText
    private lateinit var categoryValue: TextView
    private lateinit var dateValue: TextView
    private lateinit var dateClear: View
    private lateinit var timeValue: TextView
    private lateinit var timeClear: View
    private lateinit var alertValue: TextView
    private lateinit var repeatValue: TextView
    private lateinit var customBox: LinearLayout
    private lateinit var countValue: TextView
    private lateinit var countClear: View
    private lateinit var modeBox: LinearLayout
    private lateinit var byDue: TextView
    private lateinit var byDone: TextView
    private lateinit var modeHint: TextView
    private var segmentFont: Typeface? = null
    private lateinit var intervalBox: LinearLayout
    private lateinit var everyLabel: TextView
    private lateinit var everyPicker: NumberPicker
    private lateinit var unitPicker: NumberPicker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        primary = getColorFromAttr(R.attr.primaryColor)
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        val existing = if (id >= 0) TodoStore.get(this, id) else null
        isNew = existing == null
        todo = existing?.copy() ?: Todo(-1L).apply {
            due = intent.getIntExtra(EXTRA_DAY, CalendarEvents.julianDay(System.currentTimeMillis()))
            category = intent.getLongExtra(EXTRA_CATEGORY, 0L).takeIf { c -> TodoStore.category(this@TodoEditActivity, c) != null } ?: 0L
        }
        savedInstanceState?.getString(STATE_TODO)?.let { todo = Todo.fromJson(org.json.JSONObject(it)) }
        countFrom = savedInstanceState?.getInt(STATE_COUNT, NO_DAY) ?: NO_DAY
        setContentView(buildLayout())
        titleInput.setText(todo.title)
        render()
        if (isNew) titleInput.requestFocus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        todo.title = titleInput.text.toString()
        outState.putString(STATE_TODO, todo.toJson().toString())
        outState.putInt(STATE_COUNT, countFrom)
        super.onSaveInstanceState(outState)
    }

    // ---------------------------------------------------------------- layout

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dpToPx(), 0, 20.dpToPx(), 0)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(v.paddingLeft, bars.top + 16.dpToPx(), v.paddingRight, bars.bottom + 8.dpToPx())
            insets
        }
        root.addView(TextView(this, null, 0, R.style.TextLarge).apply {
            setText(if (isNew) R.string.ml_todo_new else R.string.ml_todo_edit)
            setTypeface(typeface, Typeface.BOLD)
            textSize = 22f
            setPadding(0, 4.dpToPx(), 0, 8.dpToPx())
        })

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 24.dpToPx())
        }

        form.addView(label(R.string.ml_todo_what))
        titleInput = EditText(this).apply {
            setSingleLine(false)
            maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            textSize = 18f
            setTextColor(primary)
            setHintTextColor(ColorUtils.setAlphaComponent(primary, 0x80))
            setHint(R.string.ml_todo_what_hint)
            background = boxBackground()
            setPadding(14.dpToPx(), 12.dpToPx(), 14.dpToPx(), 12.dpToPx())
        }
        form.addView(titleInput, matchWrap())

        form.addView(label(R.string.ml_todo_category))
        val category = field(R.string.ml_todo_none, R.drawable.ic_m_chevron_down) { pickCategory() }
        categoryValue = category.second
        form.addView(category.first, matchWrap())
        form.addView(hint(R.string.ml_todo_category_hint))

        // Date and time side by side
        val dateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val dateColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        dateColumn.addView(label(R.string.ml_todo_due))
        val date = field(R.string.ml_todo_no_date, R.drawable.ic_m_close) { pickDate(todo.due) { setDue(it) } }
        dateValue = date.second
        dateClear = date.third
        dateClear.setOnClickListener { setDue(NO_DAY) }
        dateColumn.addView(date.first, matchWrap())
        dateRow.addView(dateColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 10.dpToPx() })
        val timeColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        timeColumn.addView(label(R.string.ml_todo_time))
        val time = field(R.string.ml_todo_no_time, R.drawable.ic_m_close) { pickTime() }
        timeValue = time.second
        timeClear = time.third
        timeClear.setOnClickListener {
            todo.time = NO_TIME
            render()
        }
        timeColumn.addView(time.first, matchWrap())
        dateRow.addView(timeColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        form.addView(dateRow, matchWrap())

        form.addView(label(R.string.ml_todo_alert))
        val alert = field(R.string.ml_todo_alert_none, R.drawable.ic_m_chevron_down) { pickAlert() }
        alertValue = alert.second
        form.addView(alert.first, matchWrap())

        form.addView(label(R.string.ml_todo_repeat))
        val repeat = field(R.string.ml_todo_repeat_never, R.drawable.ic_m_chevron_down) { pickRepeat() }
        repeatValue = repeat.second
        form.addView(repeat.first, matchWrap())

        // Custom repetition: where the count starts
        customBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        customBox.addView(label(R.string.ml_todo_count_from))
        val count = field(R.string.ml_todo_count_from_empty, R.drawable.ic_m_close) {
            pickDate(if (countFrom != NO_DAY) countFrom else todo.due.takeIf { it != NO_DAY }?.let { TodoStore.stepBack(todo, it) } ?: today()) {
                countFrom = it
                todo.due = TodoStore.step(todo, it)
                render()
            }
        }
        countValue = count.second
        countClear = count.third
        countClear.setOnClickListener {
            countFrom = NO_DAY
            render()
        }
        customBox.addView(count.first, matchWrap())
        customBox.addView(hint(R.string.ml_todo_count_from_hint))
        form.addView(customBox, matchWrap())

        // The interval (custom), under the due date / completion choice
        intervalBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        // From the due date or from the completion, for every repetition
        modeBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val segments = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = boxBackground()
        }
        byDue = segment(R.string.ml_todo_by_due) { setFromCompletion(false) }
        segmentFont = byDue.typeface
        byDone = segment(R.string.ml_todo_by_done) { setFromCompletion(true) }
        segments.addView(byDue, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        segments.addView(byDone, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        modeBox.addView(segments, matchWrap().apply { topMargin = 18.dpToPx() })
        modeHint = TextView(this, null, 0, R.style.TextSmall).apply {
            textSize = 14f
            setPadding(2.dpToPx(), 8.dpToPx(), 0, 0)
        }
        modeBox.addView(modeHint)

        everyLabel = TextView(this, null, 0, R.style.TextMedium).apply { textSize = 18f }
        intervalBox.addView(everyLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = 16.dpToPx() })
        everyPicker = NumberPicker(this).apply {
            minValue = 1
            maxValue = 99
            wrapSelectorWheel = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setOnValueChangedListener { _, _, v ->
                todo.every = v
                intervalChanged()
            }
        }
        intervalBox.addView(everyPicker, LinearLayout.LayoutParams(72.dpToPx(), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = 16.dpToPx() })
        unitPicker = NumberPicker(this).apply {
            minValue = 0
            maxValue = UNITS.size - 1
            wrapSelectorWheel = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setOnValueChangedListener { _, _, v ->
                todo.unit = UNITS[v]
                intervalChanged()
            }
        }
        intervalBox.addView(unitPicker, LinearLayout.LayoutParams(120.dpToPx(), ViewGroup.LayoutParams.WRAP_CONTENT))
        form.addView(modeBox, matchWrap())
        form.addView(intervalBox, matchWrap().apply { topMargin = 12.dpToPx() })

        root.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(form)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Delete, cancel, save
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 10.dpToPx(), 0, 0)
        }
        if (!isNew) bar.addView(UiKit.iconButton(this, R.drawable.ic_m_delete, getString(R.string.ml_todo_delete), DESTRUCTIVE_COLOR) { confirmDelete() },
            LinearLayout.LayoutParams(48.dpToPx(), 48.dpToPx()).apply { marginEnd = 10.dpToPx() })
        bar.addView(button(R.string.ml_todo_cancel, filled = false) { finish() }, LinearLayout.LayoutParams(0, 48.dpToPx(), 1f).apply { marginEnd = 12.dpToPx() })
        bar.addView(button(R.string.ml_save, filled = true) { save() }, LinearLayout.LayoutParams(0, 48.dpToPx(), 1f))
        root.addView(bar, matchWrap())
        return root
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun boxBackground() = UiKit.border(this, 12, ColorUtils.setAlphaComponent(primary, 0x0A))

    private fun label(text: Int) = TextView(this, null, 0, R.style.TextSmall).apply {
        setText(text)
        textSize = 14f
        alpha = 0.7f
        setPadding(4.dpToPx(), 16.dpToPx(), 0, 6.dpToPx())
    }

    private fun hint(text: Int) = TextView(this, null, 0, R.style.TextSmall).apply {
        setText(text)
        textSize = 13f
        alpha = 0.6f
        setPadding(4.dpToPx(), 6.dpToPx(), 0, 0)
    }

    /** A box showing a value, tapped to change it, with an icon at the end (a chevron, or a cross that clears). */
    private fun field(empty: Int, icon: Int, onClick: () -> Unit): Triple<LinearLayout, TextView, View> {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = boxBackground()
            setPadding(14.dpToPx(), 0, 4.dpToPx(), 0)
            minimumHeight = 52.dpToPx()
            isClickable = true
            setOnClickListener { onClick() }
        }
        val value = TextView(this, null, 0, R.style.TextMedium).apply {
            setText(empty)
            textSize = 18f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        box.addView(value, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val clears = icon != R.drawable.ic_m_chevron_down
        val end = android.widget.ImageView(this).apply {
            setImageResource(icon)
            imageTintList = android.content.res.ColorStateList.valueOf(UiKit.secondary(this@TodoEditActivity))
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            val p = 12.dpToPx()
            setPadding(p, p, p, p)
            isClickable = clears
            if (clears) {
                background = UiKit.roundRipple(this@TodoEditActivity)
                contentDescription = getString(R.string.close)
            }
        }
        box.addView(end, LinearLayout.LayoutParams(44.dpToPx(), 44.dpToPx()))
        return Triple(box, value, end)
    }

    private fun segment(text: Int, onClick: () -> Unit) = TextView(this, null, 0, R.style.TextSmall).apply {
        setText(text)
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(8.dpToPx(), 12.dpToPx(), 8.dpToPx(), 12.dpToPx())
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun button(text: Int, filled: Boolean, onClick: () -> Unit) = TextView(this, null, 0, R.style.TextMedium).apply {
        setText(text)
        textSize = 17f
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        background = GradientDrawable().apply {
            cornerRadius = 14.dpToPx().toFloat()
            if (filled) setColor(primary) else setStroke(1.dpToPx(), getColorFromAttr(R.attr.cardStrokeColor))
        }
        if (filled) setTextColor(getColorFromAttr(R.attr.primaryInverseColor))
        isClickable = true
        setOnClickListener { onClick() }
    }

    // ---------------------------------------------------------------- state

    private fun today() = CalendarEvents.julianDay(System.currentTimeMillis())

    private fun render() {
        categoryValue.text = TodoStore.category(this, todo.category)?.let { c ->
            android.text.TextUtils.concat(TodoSpans.dot(c.color or 0xFF000000.toInt(), 10.dpToPx()), "  ", c.name)
        } ?: getString(R.string.ml_todo_none)
        dateValue.text = if (todo.due == NO_DAY) getString(R.string.ml_todo_no_date) else TodoLabels.date(this, todo.due)
        dateValue.alpha = if (todo.due == NO_DAY) 0.5f else 1f
        dateClear.isVisible = todo.due != NO_DAY
        timeValue.text = if (todo.time == NO_TIME) getString(R.string.ml_todo_no_time) else TodoLabels.time(this, todo.time)
        timeValue.alpha = if (todo.time == NO_TIME) 0.5f else 1f
        timeClear.isVisible = todo.time != NO_TIME
        alertValue.setText(ALERT_NAMES[todo.alert.coerceIn(0, 2)])
        repeatValue.text = when (todo.repeat) {
            REPEAT_CUSTOM -> getString(R.string.ml_todo_repeat_custom)
            else -> TodoLabels.repeat(this, todo)
        }
        val custom = todo.repeat == REPEAT_CUSTOM
        customBox.isVisible = custom
        intervalBox.isVisible = custom
        modeBox.isVisible = todo.repeat != REPEAT_NONE
        countValue.text = if (countFrom == NO_DAY) getString(R.string.ml_todo_count_from_empty) else TodoLabels.date(this, countFrom)
        countValue.alpha = if (countFrom == NO_DAY) 0.5f else 1f
        countClear.isVisible = countFrom != NO_DAY
        val selectedBg = GradientDrawable().apply {
            cornerRadius = 12.dpToPx().toFloat()
            setColor(ColorUtils.setAlphaComponent(primary, 0x38))
        }
        byDue.background = if (!todo.fromCompletion) selectedBg else null
        byDone.background = if (todo.fromCompletion) selectedBg else null
        byDue.typeface = Typeface.create(segmentFont, if (!todo.fromCompletion) Typeface.BOLD else Typeface.NORMAL)
        byDone.typeface = Typeface.create(segmentFont, if (todo.fromCompletion) Typeface.BOLD else Typeface.NORMAL)
        modeHint.setText(if (todo.fromCompletion) R.string.ml_todo_by_done_hint else R.string.ml_todo_by_due_hint)
        everyLabel.setText(if (todo.fromCompletion) R.string.ml_todo_after else R.string.ml_todo_every)
        everyPicker.value = todo.every.coerceIn(1, 99)
        unitPicker.displayedValues = null
        unitPicker.displayedValues = UNITS.map { TodoLabels.unit(this, it, todo.every) }.toTypedArray()
        unitPicker.value = UNITS.indexOf(todo.unit).coerceAtLeast(0)
    }

    private fun setDue(day: Int) {
        todo.due = day
        countFrom = NO_DAY
        render()
    }

    private fun setFromCompletion(value: Boolean) {
        todo.fromCompletion = value
        render()
    }

    /** With a start of the count, the due date follows the interval. */
    private fun intervalChanged() {
        if (countFrom != NO_DAY) todo.due = TodoStore.step(todo, countFrom)
        render()
    }

    // ---------------------------------------------------------------- pickers

    private fun pickerTheme() =
        if ((resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
            android.R.style.Theme_DeviceDefault_Dialog_Alert
        else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert

    private fun pickDate(initial: Int, onPick: (Int) -> Unit) {
        titleInput.hideKeyboard()
        val c = Calendar.getInstance()
        if (initial != NO_DAY) c.timeInMillis = CalendarEvents.dayStart(initial) + 12 * 3_600_000L
        DatePickerDialog(this, pickerTheme(), { _, y, m, d ->
            val picked = Calendar.getInstance().apply {
                clear()
                set(y, m, d, 12, 0, 0)
            }
            onPick(CalendarEvents.julianDay(picked.timeInMillis))
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).apply {
            datePicker.firstDayOfWeek = Calendar.MONDAY
        }.show()
    }

    private fun pickTime() {
        titleInput.hideKeyboard()
        val initial = if (todo.time != NO_TIME) todo.time else Calendar.getInstance().let { (it.get(Calendar.HOUR_OF_DAY) + 1) % 24 * 60 }
        TimePickerDialog(this, pickerTheme(), { _, h, m ->
            todo.time = h * 60 + m
            if (todo.due == NO_DAY) todo.due = today()
            render()
        }, initial / 60, initial % 60, android.text.format.DateFormat.is24HourFormat(this)).show()
    }

    private fun pickCategory() {
        titleInput.hideKeyboard()
        val categories = TodoStore.categories(this)
        showMenuDialog(getString(R.string.ml_todo_category),
            listOf(MenuEntry(getString(R.string.ml_todo_none), icon = mark(todo.category == 0L)) {
                todo.category = 0L
                render()
            }) + categories.map { c ->
                MenuEntry(c.name, icon = mark(todo.category == c.id), dot = c.color or 0xFF000000.toInt()) {
                    todo.category = c.id
                    render()
                }
            } + MenuEntry(getString(R.string.ml_todo_category_new)) {
                TodoActivity.newCategory(this) {
                    todo.category = it.id
                    render()
                }
            } + MenuEntry(getString(R.string.ml_todo_categories_manage)) {
                TodoActivity.showCategories(this) { render() }
            })
    }

    /** The check icon of the current choice in a picker (0: none). */
    private fun mark(selected: Boolean) = if (selected) R.drawable.ic_m_check else 0

    private fun pickAlert() {
        titleInput.hideKeyboard()
        showMenuDialog(getString(R.string.ml_todo_alert), listOf(ALERT_NONE, ALERT_NOTIFY, ALERT_ALARM).map { a ->
            MenuEntry(getString(ALERT_NAMES[a]), icon = mark(todo.alert == a)) {
                todo.alert = a
                if (a != ALERT_NONE && todo.due == NO_DAY) todo.due = today()
                render()
                if (a != ALERT_NONE) askAlertPermissions(a)
            }
        })
    }

    private fun pickRepeat() {
        titleInput.hideKeyboard()
        val options = listOf(REPEAT_NONE, REPEAT_DAILY, REPEAT_WEEKLY, REPEAT_MONTHLY, REPEAT_YEARLY, REPEAT_WEEKDAYS, REPEAT_CUSTOM)
        showMenuDialog(getString(R.string.ml_todo_repeat), options.map { r ->
            val name = if (r == REPEAT_CUSTOM) getString(R.string.ml_todo_repeat_custom)
            else TodoLabels.repeat(this, Todo(-1L).apply { repeat = r })
            MenuEntry(name, icon = mark(todo.repeat == r)) {
                todo.repeat = r
                if (r != REPEAT_NONE && todo.due == NO_DAY) todo.due = today()
                if (r != REPEAT_CUSTOM) countFrom = NO_DAY
                render()
            }
        })
    }

    /** Notifications, and for the alarm the full screen page (Android 14+ asks for it separately). */
    private fun askAlertPermissions(alert: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            return
        }
        if (alert == ALERT_ALARM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            !getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        ) {
            showConfirmDialog(getString(R.string.ml_todo_alert_alarm), getString(R.string.ml_todo_fullscreen_permission), getString(R.string.ml_todo_allow)) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, android.net.Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    // ---------------------------------------------------------------- save / delete

    private fun save() {
        val title = titleInput.text.toString().trim()
        if (title.isBlank()) {
            showToast(getString(R.string.ml_todo_need_title))
            return
        }
        if (todo.alert != ALERT_NONE && todo.due == NO_DAY) {
            showToast(getString(R.string.ml_todo_need_date))
            return
        }
        todo.title = title
        val result = if (isNew) Todo.fromJson(todo.toJson().put("id", TodoStore.newId(this)).put("cr", System.currentTimeMillis())) else todo
        result.snooze = 0L
        // An alert whose time is already past (e.g. a todo for this morning) doesn't ring now
        val now = System.currentTimeMillis()
        result.notified = result.alertTime().takeIf { it in 1..now } ?: 0L
        titleInput.hideKeyboard()
        TodoStore.save(this, result)
        finish()
    }

    private fun confirmDelete() {
        showConfirmDialog(getString(R.string.ml_todo_delete), getString(R.string.ml_todo_delete_confirm, todo.title), getString(R.string.ml_todo_delete)) {
            TodoStore.delete(this, todo.id)
            // After the dialog's fade, so it doesn't close on a window already gone
            window.decorView.postDelayed({ if (!isFinishing) finish() }, 250)
        }
    }

    companion object {
        private const val EXTRA_ID = "todo_id"
        private const val EXTRA_DAY = "todo_day"
        private const val EXTRA_CATEGORY = "todo_category"
        private const val STATE_TODO = "todo"
        private const val STATE_COUNT = "count_from"
        private const val REQUEST_NOTIFICATIONS = 7120
        private val UNITS = listOf(UNIT_DAY, UNIT_WEEK, UNIT_MONTH, UNIT_YEAR)
        private val ALERT_NAMES = listOf(R.string.ml_todo_alert_none, R.string.ml_todo_alert_notify, R.string.ml_todo_alert_alarm)

        /** A new todo due on [day], optionally in [category]. */
        fun create(context: Context, day: Int, category: Long = 0L) {
            context.startActivity(
                Intent(context, TodoEditActivity::class.java).putExtra(EXTRA_DAY, day).putExtra(EXTRA_CATEGORY, category)
            )
        }

        fun edit(context: Context, id: Long) {
            context.startActivity(Intent(context, TodoEditActivity::class.java).putExtra(EXTRA_ID, id))
        }
    }
}
