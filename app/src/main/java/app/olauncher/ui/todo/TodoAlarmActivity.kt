package app.olauncher.ui.todo

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import app.olauncher.R
import app.olauncher.data.NO_TIME
import app.olauncher.data.Todo
import app.olauncher.data.TodoStore
import app.olauncher.helper.CalendarEvents
import app.olauncher.helper.TodoAlarms
import app.olauncher.helper.dpToPx
import app.olauncher.helper.showToast
import java.util.Calendar

/**
 * The alarm of a todo, over the lock screen: done, postpone or stop. Postponing moves the todo
 * (due date and time) by 5 / 15 / 30 minutes, 1 / 2 hours, to tomorrow at the same time, or to a
 * date and time picked by hand; while picking, the alarm stops ringing.
 */
class TodoAlarmActivity : AppCompatActivity() {

    private var todoId = -1L
    private lateinit var buttons: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        show(intent)
    }

    /** The page is already open (e.g. "Postpone" tapped in the notification): show what was asked. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        show(intent)
    }

    private fun show(intent: Intent) {
        todoId = intent.getLongExtra(TodoAlarms.EXTRA_ID, -1L)
        val todo = TodoStore.get(this, todoId)
        if (todo == null) {
            finish()
            return
        }
        val white = 0xFFFFFFFF.toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32.dpToPx(), 48.dpToPx(), 32.dpToPx(), 32.dpToPx())
        }
        root.addView(TextView(this).apply {
            text = "⏰"
            textSize = 56f
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = todo.title
            textSize = 30f
            setTextColor(white)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 24.dpToPx(), 0, 8.dpToPx())
        })
        val details = listOfNotNull(
            todo.time.takeIf { it != NO_TIME }?.let { TodoLabels.time(this, it) },
            TodoStore.category(this, todo.category)?.name,
        ).joinToString("  ·  ")
        if (details.isNotBlank()) root.addView(TextView(this).apply {
            text = details
            textSize = 18f
            setTextColor(ColorUtils.setAlphaComponent(white, 0xB0))
            gravity = Gravity.CENTER
        })
        buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 32.dpToPx()
        })
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(0xF0101010.toInt())
            addView(root)
        })
        if (intent.getBooleanExtra(EXTRA_POSTPONE, false)) showPostpone(todo) else showMain(todo)
    }

    private fun showMain(todo: Todo) {
        buttons.removeAllViews()
        addButton(getString(R.string.ml_todo_done), filled = true) {
            TodoStore.complete(this, todoId)
            close()
        }
        addButton(getString(R.string.ml_todo_postpone)) { showPostpone(todo) }
        addButton(getString(R.string.ml_todo_stop)) { close() }
    }

    private fun showPostpone(todo: Todo) {
        buttons.removeAllViews()
        listOf(5, 15, 30).forEach { m ->
            addButton(getString(R.string.ml_todo_postpone_minutes, m)) { postpone(System.currentTimeMillis() + m * 60_000L) }
        }
        listOf(1, 2).forEach { h ->
            addButton(resources.getQuantityString(R.plurals.ml_todo_postpone_hours, h, h)) { postpone(System.currentTimeMillis() + h * 3_600_000L) }
        }
        addButton(getString(R.string.ml_todo_postpone_tomorrow)) {
            val base = todo.alertTime().takeIf { it > 0 } ?: System.currentTimeMillis()
            postpone(Calendar.getInstance().apply {
                timeInMillis = base
                add(Calendar.DAY_OF_MONTH, 1)
            }.timeInMillis)
        }
        addButton(getString(R.string.ml_todo_postpone_custom)) { pickCustom(todo) }
        addButton(getString(R.string.ml_cal_back)) { showMain(todo) }
    }

    /** Date, then time; the sound stops first, as picking takes a while. Cancelled: back to the choices. */
    private fun pickCustom(todo: Todo) {
        TodoAlarms.dismiss(this, todoId)
        val start = Calendar.getInstance().apply {
            timeInMillis = todo.alertTime().takeIf { it > 0 } ?: System.currentTimeMillis()
            add(Calendar.DAY_OF_MONTH, 1)
        }
        val theme = android.R.style.Theme_DeviceDefault_Dialog_Alert
        DatePickerDialog(this, theme, { _, y, m, d ->
            TimePickerDialog(this, theme, { _, h, min ->
                postpone(Calendar.getInstance().apply {
                    clear()
                    set(y, m, d, h, min, 0)
                }.timeInMillis)
            }, start.get(Calendar.HOUR_OF_DAY), start.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(this)).show()
        }, start.get(Calendar.YEAR), start.get(Calendar.MONTH), start.get(Calendar.DAY_OF_MONTH)).apply {
            datePicker.firstDayOfWeek = Calendar.MONDAY
            datePicker.minDate = System.currentTimeMillis() - 1000
        }.show()
    }

    private fun postpone(at: Long) {
        TodoStore.postpone(this, todoId, at)
        val day = CalendarEvents.julianDay(at)
        val minutes = ((at - CalendarEvents.dayStart(day)) / 60_000L).toInt()
        showToast(getString(R.string.ml_todo_postponed, TodoLabels.day(this, day) + " " + TodoLabels.time(this, minutes)))
        close()
    }

    private fun close() {
        TodoAlarms.dismiss(this, todoId)
        finish()
    }

    private fun addButton(label: String, filled: Boolean = false, onClick: () -> Unit) {
        buttons.addView(TextView(this).apply {
            text = label
            textSize = 18f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (filled) 0xFF101010.toInt() else 0xFFFFFFFF.toInt())
            background = GradientDrawable().apply {
                cornerRadius = 28.dpToPx().toFloat()
                if (filled) setColor(0xFFFFFFFF.toInt()) else setStroke(2.dpToPx(), 0x80FFFFFF.toInt())
            }
            isClickable = true
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 52.dpToPx()).apply { topMargin = 12.dpToPx() })
    }

    companion object {
        const val EXTRA_POSTPONE = "postpone"
    }
}
