package app.olauncher.ui.todo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import app.olauncher.R
import app.olauncher.data.NO_DAY
import app.olauncher.data.REPEAT_CUSTOM
import app.olauncher.data.REPEAT_DAILY
import app.olauncher.data.REPEAT_MONTHLY
import app.olauncher.data.REPEAT_NONE
import app.olauncher.data.REPEAT_WEEKDAYS
import app.olauncher.data.REPEAT_WEEKLY
import app.olauncher.data.REPEAT_YEARLY
import app.olauncher.data.Todo
import app.olauncher.data.UNIT_DAY
import app.olauncher.data.UNIT_MONTH
import app.olauncher.data.UNIT_WEEK
import app.olauncher.helper.CalendarEvents
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Red for what's late. */
const val TODO_LATE_COLOR = 0xFFE53935.toInt()

/** Round check box: an empty ring, or filled with a check mark when [checked]. */
class TodoCheckView(context: Context) : View(context) {
    var color = 0
        set(value) {
            field = value
            invalidate()
        }
    var checked = false
        set(value) {
            field = value
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val stroke = size * 0.09f
        val r = size / 2f - stroke
        paint.color = color
        paint.strokeWidth = stroke
        if (checked) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy, r + stroke / 2, paint)
            paint.style = Paint.Style.STROKE
            paint.color = 0xFFFFFFFF.toInt()
            paint.strokeWidth = stroke * 1.2f
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            path.reset()
            path.moveTo(cx - r * 0.45f, cy + r * 0.02f)
            path.lineTo(cx - r * 0.1f, cy + r * 0.38f)
            path.lineTo(cx + r * 0.5f, cy - r * 0.35f)
            canvas.drawPath(path, paint)
        } else {
            paint.style = Paint.Style.STROKE
            canvas.drawCircle(cx, cy, r, paint)
        }
    }
}

object TodoLabels {

    /** "Today", "Tomorrow", "Yesterday", else "Sat 17 Oct" (with the year when it isn't this one). */
    fun day(context: Context, day: Int): String {
        if (day == NO_DAY) return context.getString(R.string.ml_todo_no_date)
        val today = CalendarEvents.julianDay(System.currentTimeMillis())
        return when (day - today) {
            0 -> context.getString(R.string.ml_cal_today)
            1 -> context.getString(R.string.ml_cal_tomorrow)
            -1 -> context.getString(R.string.ml_cal_yesterday)
            else -> {
                val start = CalendarEvents.dayStart(day)
                val sameYear = SimpleDateFormat("yyyy", Locale.getDefault()).let { it.format(Date(start)) == it.format(Date()) }
                SimpleDateFormat(if (sameYear) "EEE d MMM" else "EEE d MMM yyyy", Locale.getDefault()).format(Date(start))
                    .replaceFirstChar { it.titlecase(Locale.getDefault()) }
            }
        }
    }

    /** "dd/MM/yyyy"-like short date of the phone's locale. */
    fun date(context: Context, day: Int): String =
        if (day == NO_DAY) "" else android.text.format.DateFormat.getDateFormat(context).format(Date(CalendarEvents.dayStart(day)))

    fun time(context: Context, minutes: Int): String {
        val c = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, minutes / 60)
            set(java.util.Calendar.MINUTE, minutes % 60)
        }
        return android.text.format.DateFormat.getTimeFormat(context).format(c.time)
    }

    fun unit(context: Context, unit: Int, count: Int): String = context.resources.getQuantityString(
        when (unit) {
            UNIT_DAY -> R.plurals.ml_todo_unit_day
            UNIT_WEEK -> R.plurals.ml_todo_unit_week
            UNIT_MONTH -> R.plurals.ml_todo_unit_month
            else -> R.plurals.ml_todo_unit_year
        }, count
    )

    fun repeat(context: Context, todo: Todo): String = when (todo.repeat) {
        REPEAT_NONE -> context.getString(R.string.ml_todo_repeat_never)
        REPEAT_DAILY -> context.getString(R.string.ml_todo_repeat_daily)
        REPEAT_WEEKLY -> context.getString(R.string.ml_todo_repeat_weekly)
        REPEAT_MONTHLY -> context.getString(R.string.ml_todo_repeat_monthly)
        REPEAT_YEARLY -> context.getString(R.string.ml_todo_repeat_yearly)
        REPEAT_WEEKDAYS -> context.getString(R.string.ml_todo_repeat_weekdays)
        REPEAT_CUSTOM -> context.getString(
            if (todo.fromCompletion) R.string.ml_todo_repeat_after_n else R.string.ml_todo_repeat_every_n,
            todo.every, unit(context, todo.unit, todo.every)
        )
        else -> ""
    }

    /** How late a todo due [days] ago is (0: today, its time has gone by). */
    fun late(context: Context, days: Int): String = when {
        days <= 0 -> context.getString(R.string.ml_todo_late)
        days == 1 -> context.getString(R.string.ml_todo_late_yesterday)
        else -> context.getString(R.string.ml_todo_late_days, days)
    }
}
