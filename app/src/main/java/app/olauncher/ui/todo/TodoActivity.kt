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
import app.olauncher.ui.UiKit

/**
 * The launcher's ToDo app: the todos by date (late, today, tomorrow, later, without a date, done),
 * filtered by category. Tap the circle to mark one done, the row to edit it, the add icon for a
 * new one; the menu icon manages the categories. Listed as "ToDo" in the app list.
 */
class TodoActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var chips: LinearLayout
    private var primary = 0
    /** Category shown (0 = all). */
    private var filter = 0L
    private var showDone = false
    /** The list by category (each one can be folded) instead of by date. */
    private var byCategory = false
    private val folded = mutableSetOf<Long>()
    private val prefs by lazy { getSharedPreferences("app.minimalauncher.todo", Context.MODE_PRIVATE) }
    private val listener: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        primary = getColorFromAttr(R.attr.primaryColor)
        filter = prefs.getLong("filter", 0L)
        showDone = prefs.getBoolean("show_done", false)
        byCategory = prefs.getBoolean("by_category", false)
        prefs.getStringSet("folded", emptySet())?.mapNotNullTo(folded) { it.toLongOrNull() }
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
            setPadding(8.dpToPx(), 0, 8.dpToPx(), 0)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top + 4.dpToPx(), v.paddingRight, bars.bottom + 8.dpToPx())
            insets
        }
        val bar = UiKit.appBar(this, getString(R.string.ml_todo_app), onBack = { finish() }, actions = listOf(
            UiKit.iconButton(this, R.drawable.ic_m_sliders, getString(R.string.ml_todo_menu)) { showMenu() },
            UiKit.iconButton(this, R.drawable.ic_m_add, getString(R.string.ml_todo_new)) { newTodo() },
        ))
        root.addView(bar.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(12.dpToPx(), 0, 12.dpToPx(), 0)
            addView(chips)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 8.dpToPx(); bottomMargin = 4.dpToPx()
        })

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dpToPx(), 0, 12.dpToPx(), 24.dpToPx())
        }
        root.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(list)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
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

        if (byCategory) showByCategory(categories, todos, today) else showByDate(todos, today, order)

        val done = TodoStore.history(this).filter { filter == 0L || it.category == filter }.sortedByDescending { it.at }
        if (list.childCount == 0) list.addView(
            UiKit.emptyState(this, R.drawable.ic_m_todo, getString(R.string.ml_todo_empty)),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        if (done.isNotEmpty()) {
            list.addView(sectionTitle(getString(R.string.ml_todo_section_done, done.size), open = showDone).apply {
                isClickable = true
                setOnClickListener {
                    showDone = !showDone
                    prefs.edit { putBoolean("show_done", showDone) }
                    refresh()
                }
            })
            if (showDone) addGroup(done.take(100).map { doneRow(it) })
        }
    }

    private fun showByDate(todos: List<Todo>, today: Int, order: Comparator<Todo>) {
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
    }

    /**
     * One block per category (in the categories' order, then the todos without one): tap the
     * title to fold it, long press to rename / recolor / delete the category.
     */
    private fun showByCategory(categories: List<TodoCategory>, todos: List<Todo>, today: Int) {
        // Dated first (by date, the late ones on top), the undated at the end
        val order = compareBy<Todo>({ if (it.due == NO_DAY) Int.MAX_VALUE else it.due }, { if (it.time == NO_TIME) -1 else it.time }, { it.created })
        val groups: List<Pair<TodoCategory?, List<Todo>>> = categories.map { it to todos.filter { t -> t.category == it.id } } +
                (null to todos.filter { t -> categories.none { it.id == t.category } })
        groups.forEach { (category, items) ->
            if (items.isEmpty() && (category == null || filter != 0L && filter != category.id)) return@forEach
            val id = category?.id ?: 0L
            val open = id !in folded
            val name = category?.name ?: getString(R.string.ml_todo_no_category)
            list.addView(sectionTitle("$name  (${items.size})", open = open, dot = categoryColor(id)).apply {
                isClickable = true
                setOnClickListener {
                    if (!folded.add(id)) folded.remove(id)
                    prefs.edit { putStringSet("folded", folded.map { it.toString() }.toSet()) }
                    refresh()
                }
                if (category != null) setOnLongClickListener {
                    showCategory(this@TodoActivity, category) {}
                    true
                }
            })
            if (open) addGroup(items.sortedWith(order).map { todoRow(it, today, showDate = true) })
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
        text = if (color != 0) TextUtils.concat(TodoSpans.dot(color or 0xFF000000.toInt(), 8.dpToPx()), "  ", label) else label
        setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
        setPadding(14.dpToPx(), 7.dpToPx(), 14.dpToPx(), 7.dpToPx())
        background = GradientDrawable().apply {
            cornerRadius = 20.dpToPx().toFloat()
            if (selected) setColor(ColorUtils.setAlphaComponent(primary, 0x30))
            setStroke(1.dpToPx(), if (selected) ColorUtils.setAlphaComponent(primary, 0xA0) else UiKit.line(this@TodoActivity))
        }
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = 8.dpToPx() }
    }

    /**
     * A section's title: small grey capitals, with a colored dot before it ([dot] != 0) and, when
     * it folds ([open] != null), a chevron at the end.
     */
    private fun sectionTitle(label: String, open: Boolean? = null, dot: Int = 0, color: Int = UiKit.secondary(this)) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(6.dpToPx(), 18.dpToPx(), 6.dpToPx(), 8.dpToPx())
        if (open != null) background = UiKit.ripple(this@TodoActivity)
        if (dot != 0) addView(View(this@TodoActivity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(dot)
            }
        }, LinearLayout.LayoutParams(8.dpToPx(), 8.dpToPx()).apply { marginEnd = 8.dpToPx() })
        addView(TextView(this@TodoActivity).apply {
            text = label.uppercase()
            textSize = 12f
            letterSpacing = 0.08f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (open != null) addView(UiKit.icon(this@TodoActivity, if (open) R.drawable.ic_m_chevron_down else R.drawable.ic_m_chevron_right, color, 18))
    }

    private fun section(title: Int, todos: List<Todo>, today: Int, showDate: Boolean) {
        if (todos.isEmpty()) return
        list.addView(
            if (title == R.string.ml_todo_section_late) sectionTitle(getString(title), color = TODO_LATE_COLOR)
            else sectionTitle(getString(title))
        )
        addGroup(todos.map { todoRow(it, today, showDate) })
    }

    /** [rows] in a bordered group, with thin lines between them. */
    private fun addGroup(rows: List<View>) {
        if (rows.isEmpty()) return
        val group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiKit.border(this@TodoActivity, 16)
            clipToOutline = true
        }
        val line = UiKit.line(this)
        rows.forEachIndexed { i, row ->
            if (i > 0) group.addView(View(this).apply { setBackgroundColor(line) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1.dpToPx()))
            group.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        list.addView(group, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
        val detailsView = TextView(this, null, 0, R.style.TextSmall).apply {
            textSize = 13f
            alpha = 0.7f
            if (todo.isOverdue(today)) {
                setTextColor(TODO_LATE_COLOR)
                alpha = 1f
            }
            maxLines = 2
        }
        // Repetition and alerts as small line icons, in the color of the text
        val iconColor = detailsView.currentTextColor
        val iconSize = 14.dpToPx()
        val details = mutableListOf<CharSequence>()
        if (todo.isOverdue(today)) details += TodoLabels.day(this, todo.due) + " · " + TodoLabels.late(this, today - todo.due)
        else if (showDate) details += TodoLabels.day(this, todo.due)
        if (todo.time != NO_TIME) details += TodoLabels.time(this, todo.time)
        if (todo.recurring) details += TextUtils.concat(TodoSpans.icon(this, R.drawable.ic_m_repeat, iconColor, iconSize), " ", TodoLabels.repeat(this, todo))
        if (todo.alert == ALERT_NOTIFY) details += TodoSpans.icon(this, R.drawable.ic_m_bell, iconColor, iconSize)
        if (todo.alert == ALERT_ALARM) details += TodoSpans.icon(this, R.drawable.ic_m_alarm, iconColor, iconSize)
        if (todo.photo.isNotEmpty()) details += TodoSpans.icon(this, R.drawable.ic_m_image, iconColor, iconSize)
        TodoStore.category(this, todo.category)?.let { if (filter == 0L && !byCategory) details += it.name }
        if (details.isNotEmpty()) {
            val text = android.text.SpannableStringBuilder()
            details.forEachIndexed { i, d ->
                if (i > 0) text.append("  ·  ")
                text.append(d)
            }
            detailsView.text = text
            column.addView(detailsView)
        }
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
        setPadding(12.dpToPx(), 10.dpToPx(), 12.dpToPx(), 10.dpToPx())
        minimumHeight = 56.dpToPx()
        isClickable = true
        isFocusable = true
        foreground = UiKit.ripple(this@TodoActivity)
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
                // Its own copy of the photo, so deleting one doesn't take the other's
                copy.photo = TodoPhotos.duplicate(this, todo.photo)
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
            MenuEntry(getString(R.string.ml_todo_by_category), checked = byCategory, onToggle = { v ->
                byCategory = v
                prefs.edit { putBoolean("by_category", v) }
                refresh()
                true
            }),
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
                    MenuEntry(c.name, dot = c.color or 0xFF000000.toInt()) { showCategory(context, c, back) }
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

        fun showCategory(context: Context, c: TodoCategory, back: () -> Unit) {
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
