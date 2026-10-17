package app.olauncher.ui.todo

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import app.olauncher.R
import app.olauncher.data.NO_TIME
import app.olauncher.data.TodoStore
import app.olauncher.helper.TodoAlarms
import app.olauncher.helper.dpToPx

/** The alarm of a todo, over the lock screen: done, snooze or stop. */
class TodoAlarmActivity : AppCompatActivity() {

    private var todoId = -1L

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
            setBackgroundColor(0xF0101010.toInt())
            setPadding(32.dpToPx(), 32.dpToPx(), 32.dpToPx(), 32.dpToPx())
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
        root.addView(button(getString(R.string.ml_todo_done), filled = true) {
            TodoStore.complete(this, todoId)
            close()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 56.dpToPx()).apply { topMargin = 48.dpToPx() })
        root.addView(button(getString(R.string.ml_todo_snooze, TodoAlarms.SNOOZE_MINUTES), filled = false) {
            TodoStore.snooze(this, todoId, TodoAlarms.SNOOZE_MINUTES)
            close()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 56.dpToPx()).apply { topMargin = 16.dpToPx() })
        root.addView(button(getString(R.string.ml_todo_stop), filled = false) { close() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 56.dpToPx()).apply { topMargin = 16.dpToPx() })
        setContentView(root)
    }

    private fun close() {
        TodoAlarms.dismiss(this, todoId)
        finish()
    }

    private fun button(label: String, filled: Boolean, onClick: () -> Unit) = TextView(this).apply {
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
    }
}
