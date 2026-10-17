package app.olauncher.ui.todo

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.olauncher.R
import app.olauncher.data.ALERT_ALARM
import app.olauncher.data.ALERT_NOTIFY
import app.olauncher.data.NO_DAY
import app.olauncher.data.NO_TIME
import app.olauncher.data.Todo
import app.olauncher.data.TodoCategory
import app.olauncher.data.TodoDone
import app.olauncher.data.TodoStore
import app.olauncher.helper.CalendarEvents
import app.olauncher.helper.MenuEntry
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.showColorPickerDialog
import app.olauncher.helper.showConfirmDialog
import app.olauncher.helper.showMenuDialog
import app.olauncher.helper.showTextInputDialog

/**
 * The launcher's ToDo app: the todos by date (late, today, tomorrow, later, without a date, done),
 * filtered by category. Tap the circle to mark one done, the row to edit it, ＋ for a new one;
 * ☰ manages the categories. Listed as "ToDo" in the app list.
 */
class TodoActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var chips: LinearLayout
    private var primary = 0
    /** Category shown (0 = all). */
    private var filter = 0L
    private var showDone = false
    private val prefs by lazy { getSharedPreferences("app.minimalauncher.todo", Context.MODE_PRIVATE) }
    private val listener: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        primary = getColorFromAttr(R.attr.primaryColor)
        filter = prefs.getLong("filter", 0L)
        showDone = prefs.getBoolean("show_done", false)
        setContentView(buildLayout())
    }

    override fun onStart() {
        super.onStart()
        TodoStore.addListener(listener)
        refresh()
    }

    override fun onStop() {
        TodoStore.removeListener(listener)
        super.onStop()
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dpToPx(), 0, 20.dpToPx(), 0)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top + 16.dpToPx(), v.paddingRight, bars.bottom + 8.dpToPx())
            insets
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(headerButton("←", 26f, R.string.ml_cal_back) { finish() }.apply {
            setPadding(0, 4.dpToPx(), 16.dpToPx(), 4.dpToPx())
        })
        header.addView(TextView(this, null, 0, R.style.TextLarge).apply {
            setText(R.string.ml_todo_app)
            setTypeface(typeface, Typeface.BOLD)
            textSize = 22f
            maxLines = 1
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(headerButton("☰", 22f, R.string.ml_todo_menu) { showMenu() })
        header.addView(headerButton("＋", 24f, R.string.ml_todo_new) { newTodo() })
        root.addView(header)

        chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(chips)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 12.dpToPx(); bottomMargin = 4.dpToPx()
        })

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 24.dpToPx())
        }
        root.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(list)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun headerButton(label: String, size: Float, description: Int, onClick: () -> Unit) =
        TextView(this, null, 0, R.style.TextLarge).apply {
            text = label
            textSize = size
            contentDescription = getString(description)
            setPadding(16.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
            isClickable = true
            setOnClickListener { onClick() }
        }

    /** A new todo is for today, in the category shown. */
    private fun newTodo() = TodoEditActivity.create(this, CalendarEvents.julianDay(System.currentTimeMillis()), filter)

    // ---------------------------------------------------------------- content

    private fun refresh() {
        val categories = TodoStore.categories(this)
        if (filter != 0L && categories.none { it.id == filter }) filter = 0L
        showChips(categories)
        list.removeAllViews()
        val today = CalendarEvents.julianDay(System.currentTimeMillis())
        val todos = TodoStore.todos(this).filter { !it.done && (filter == 0L || it.category == filter) }
        val order = compareBy<Todo>({ it.due }, { if (it.time == NO_TIME) -1 else it.time }, { it.created })

        val late = todos.filter { it.isOverdue(today) }.sortedWith(order)
        val dueToday = todos.filter { it.due == today }.sortedWith(order)
        val tomorrow = todos.filter { it.due == today + 1 }.sortedWith(order)
        val later = todos.filter { it.due != NO_DAY && it.due > today + 1 }.sortedWith(order)
        val undated = todos.filter { it.due == NO_DAY }.sortedBy { it.created }

        section(R.string.ml_todo_section_late, late, today, showDate = true)
        section(R.string.ml_cal_today, dueToday, today, showDate = false)
        section(R.string.ml_cal_tomorrow, tomorrow, today, showDate = false)
        section(R.string.ml_todo_section_later, later, today, showDate = true)
        section(R.string.ml_todo_no_date, undated, today, showDate = false)

        val done = TodoStore.history(this).filter { filter == 0L || it.category == filter }.sortedByDescending { it.at }
        if (list.childCount == 0) list.addView(TextView(this, null, 0, R.style.TextSmall).apply {
            setText(R.string.ml_todo_empty)
            alpha = 0.6f
            setPadding(0, 24.dpToPx(), 0, 8.dpToPx())
        })
        if (done.isNotEmpty()) {
            list.addView(sectionTitle(getString(R.string.ml_todo_section_done, done.size) + if (showDone) "  ▾" else "  ▸").apply {
                isClickable = true
                setOnClickListener {
                    showDone = !showDone
                    prefs.edit { putBoolean("show_done", showDone) }
                    refresh()
                }
            })
            if (showDone) done.take(100).forEach { list.addView(doneRow(it)) }
        }
    }

    private fun showChips(categories: List<TodoCategory>) {
        chips.removeAllViews()
        chips.addView(chip(getString(R.string.ml_todo_all), 0, filter == 0L) { setFilter(0L) })
        categories.forEach { c -> chips.addView(chip(c.name, c.color, filter == c.id) { setFilter(c.id) }) }
    }

    private fun setFilter(id: Long) {
        filter = id
        prefs.edit { putLong("filter", id) }
        refresh()
    }

    private fun chip(label: String, color: Int, selected: Boolean, onClick: () -> Unit) = TextView(this, null, 0, R.style.TextSmall).apply {
        text = if (color != 0) "●  $label" else label
        if (color != 0) text = android.text.SpannableString(text).apply {
            setSpan(android.text.style.ForegroundColorSpan(color or 0xFF000000.toInt()), 0, 1, 0)
        }
        setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
        setPadding(14.dpToPx(), 7.dpToPx(), 14.dpToPx(), 7.dpToPx())
        background = GradientDrawable().apply {
            cornerRadius = 20.dpToPx().toFloat()
            if (selected) setColor(ColorUtils.setAlphaComponent(primary, 0x30))
            setStroke(1.dpToPx(), ColorUtils.setAlphaComponent(primary, if (selected) 0xA0 else 0x40))
        }
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = 8.dpToPx() }
    }

    private fun sectionTitle(label: String) = TextView(this, null, 0, R.style.TextSmall).apply {
        text = label
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 18.dpToPx(), 0, 6.dpToPx())
    }

    private fun section(title: Int, todos: List<Todo>, today: Int, showDate: Boolean) {
        if (todos.isEmpty()) return
        list.addView(sectionTitle(getString(title)).apply {
            if (title == R.string.ml_todo_section_late) setTextColor(TODO_LATE_COLOR)
        })
        todos.forEach { list.addView(todoRow(it, today, showDate)) }
    }

    private fun categoryColor(id: Long) =
        TodoStore.category(this, id)?.color?.or(0xFF000000.toInt()) ?: ColorUtils.setAlphaComponent(primary, 0x60)

    private fun todoRow(todo: Todo, today: Int, showDate: Boolean): View {
        val color = categoryColor(todo.category)
        val row = rowFrame(color)
        val check = TodoCheckView(this).apply {
            this.color = color
            isClickable = true
            contentDescription = getString(R.string.ml_todo_done)
            setOnClickListener {
                checked = true
                postDelayed({ TodoStore.complete(this@TodoActivity, todo.id) }, 250)
            }
        }
        row.addView(check, LinearLayout.LayoutParams(26.dpToPx(), 26.dpToPx()).apply { marginEnd = 12.dpToPx() })
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(this, null, 0, R.style.TextMedium).apply {
            text = todo.title
            textSize = 18f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        val details = mutableListOf<String>()
        if (todo.isOverdue(today)) details += TodoLabels.day(this, todo.due) + " · " + TodoLabels.late(this, today - todo.due)
        else if (showDate) details += TodoLabels.day(this, todo.due)
        if (todo.time != NO_TIME) details += TodoLabels.time(this, todo.time)
        if (todo.recurring) details += "↻ " + TodoLabels.repeat(this, todo)
        if (todo.alert == ALERT_NOTIFY) details += "🔔"
        if (todo.alert == ALERT_ALARM) details += "⏰"
        TodoStore.category(this, todo.category)?.let { if (filter == 0L) details += it.name }
        if (details.isNotEmpty()) column.addView(TextView(this, null, 0, R.style.TextSmall).apply {
            text = details.joinToString("  ·  ")
            textSize = 13f
            alpha = 0.7f
            if (todo.isOverdue(today)) {
                setTextColor(TODO_LATE_COLOR)
                alpha = 1f
            }
            maxLines = 2
        })
        row.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.setOnClickListener { TodoEditActivity.edit(this, todo.id) }
        row.setOnLongClickListener {
            showTodoMenu(todo)
            true
        }
        return row
    }

    private fun doneRow(done: TodoDone): View {
        val color = categoryColor(done.category)
        val row = rowFrame(color)
        row.alpha = 0.55f
        row.addView(TodoCheckView(this).apply {
            this.color = color
            checked = true
            isClickable = true
            contentDescription = getString(R.string.ml_todo_undo)
            setOnClickListener { TodoStore.undo(this@TodoActivity, done) }
        }, LinearLayout.LayoutParams(26.dpToPx(), 26.dpToPx()).apply { marginEnd = 12.dpToPx() })
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(this, null, 0, R.style.TextMedium).apply {
            text = done.title
            textSize = 18f
            paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        column.addView(TextView(this, null, 0, R.style.TextSmall).apply {
            text = getString(R.string.ml_todo_done_on, TodoLabels.day(this@TodoActivity, done.day))
            textSize = 13f
            alpha = 0.7f
        })
        row.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.setOnClickListener {
            showMenuDialog(done.title, listOf(
                MenuEntry(getString(R.string.ml_todo_undo)) { TodoStore.undo(this, done) },
                MenuEntry(getString(R.string.ml_todo_forget)) { TodoStore.forgetDone(this, done) },
            ))
        }
        return row
    }

    /** A row with the category's color bar on the left. */
    private fun rowFrame(color: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 8.dpToPx(), 0, 8.dpToPx())
        isClickable = true
        isFocusable = true
        addView(View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = 2.dpToPx().toFloat()
                setColor(color)
            }
        }, LinearLayout.LayoutParams(4.dpToPx(), 36.dpToPx()).apply { marginEnd = 12.dpToPx() })
    }

    private fun showTodoMenu(todo: Todo) {
        showMenuDialog(todo.title, listOf(
            MenuEntry(getString(R.string.ml_todo_done)) { TodoStore.complete(this, todo.id) },
            MenuEntry(getString(R.string.ml_todo_edit)) { TodoEditActivity.edit(this, todo.id) },
            MenuEntry(getString(R.string.ml_todo_duplicate)) {
                val copy = Todo.fromJson(todo.toJson().put("id", TodoStore.newId(this)).put("cr", System.currentTimeMillis()))
                TodoStore.save(this, copy)
            },
            MenuEntry(getString(R.string.ml_todo_delete)) {
                showConfirmDialog(getString(R.string.ml_todo_delete), getString(R.string.ml_todo_delete_confirm, todo.title), getString(R.string.ml_todo_delete)) {
                    TodoStore.delete(this, todo.id)
                }
            },
        ))
    }

    // ---------------------------------------------------------------- menu and categories

    private fun showMenu() {
        showMenuDialog(getString(R.string.ml_todo_app), listOf(
            MenuEntry(getString(R.string.ml_todo_new)) { newTodo() },
            MenuEntry(getString(R.string.ml_todo_categories)) { showCategories(this) },
            MenuEntry(getString(R.string.ml_todo_show_done), checked = showDone, onToggle = { v ->
                showDone = v
                prefs.edit { putBoolean("show_done", v) }
                refresh()
                true
            }),
        ))
    }

    companion object {
        fun open(context: Context) {
            context.startActivity(Intent(context, TodoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        /** The categories: tap one to rename, recolor, move or delete it; the last entry adds one. */
        fun showCategories(context: Context, then: (() -> Unit)? = null) {
            val categories = TodoStore.categories(context)
            val back = { showCategories(context, then) }
            context.showMenuDialog(
                context.getString(R.string.ml_todo_categories),
                categories.map { c ->
                    MenuEntry("●  ${c.name}") { showCategory(context, c, back) }
                } + MenuEntry(context.getString(R.string.ml_todo_category_new)) { newCategory(context) { back() } }
            ).setOnDismissListener { then?.invoke() }
        }

        fun newCategory(context: Context, onCreated: (TodoCategory) -> Unit) {
            context.showTextInputDialog(context.getString(R.string.ml_todo_category_new), "", context.getString(R.string.ml_todo_category_name), context.getString(R.string.ml_save)) { name ->
                val palette = listOf(0xFF42A5F5, 0xFFEF6C00, 0xFF66BB6A, 0xFFAB47BC, 0xFFEC407A, 0xFF26A69A, 0xFFFFCA28, 0xFF8D6E63)
                val color = palette[TodoStore.categories(context).size % palette.size].toInt()
                val category = TodoCategory(TodoStore.newId(context), name, color)
                TodoStore.saveCategory(context, category)
                onCreated(category)
            }
        }

        private fun showCategory(context: Context, c: TodoCategory, back: () -> Unit) {
            context.showMenuDialog(c.name, listOf(
                MenuEntry(context.getString(R.string.ml_todo_category_rename)) {
                    context.showTextInputDialog(context.getString(R.string.ml_todo_category_rename), c.name, context.getString(R.string.ml_todo_category_name), context.getString(R.string.ml_save)) { name ->
                        c.name = name
                        TodoStore.saveCategory(context, c)
                        back()
                    }
                },
                MenuEntry(context.getString(R.string.ml_todo_category_color)) {
                    context.showColorPickerDialog(context.getString(R.string.ml_todo_category_color), c.color, context.getString(R.string.ml_default)) { color ->
                        c.color = if (color == 0) 0xFF42A5F5.toInt() else color
                        TodoStore.saveCategory(context, c)
                        back()
                    }
                },
                MenuEntry(context.getString(R.string.ml_todo_move_up)) {
                    TodoStore.moveCategory(context, c.id, -1)
                    back()
                },
                MenuEntry(context.getString(R.string.ml_todo_move_down)) {
                    TodoStore.moveCategory(context, c.id, 1)
                    back()
                },
                MenuEntry(context.getString(R.string.ml_todo_category_delete)) {
                    context.showConfirmDialog(
                        context.getString(R.string.ml_todo_category_delete),
                        context.getString(R.string.ml_todo_category_delete_confirm, c.name),
                        context.getString(R.string.ml_todo_delete)
                    ) {
                        TodoStore.deleteCategory(context, c.id)
                        back()
                    }
                },
            ))
        }
    }
}
