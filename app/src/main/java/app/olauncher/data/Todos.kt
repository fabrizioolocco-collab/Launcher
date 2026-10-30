package app.olauncher.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.olauncher.R
import app.olauncher.helper.CalendarEvents
import app.olauncher.helper.TodoAlarms
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/** A ToDo category: [color] marks its todos in the ToDo app and in the calendar widget. */
class TodoCategory(val id: Long, var name: String, var color: Int) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("n", name).put("c", color)

    companion object {
        fun fromJson(o: JSONObject) = TodoCategory(o.getLong("id"), o.optString("n"), o.optInt("c"))
    }
}

/**
 * A ToDo. Dates are Julian days ([CalendarEvents.julianDay], [NO_DAY] = no date), [time] minutes
 * from midnight ([NO_TIME] = none). A recurring todo keeps a single instance: completing it moves
 * [due] to the next occurrence, a one-off is marked done ([doneAt]).
 */
class Todo(val id: Long) {
    var title = ""
    var category = 0L
    var due = NO_DAY
    var time = NO_TIME
    var alert = ALERT_NONE
    var repeat = REPEAT_NONE
    /** Custom repetition: every [every] [unit]s. */
    var every = 1
    var unit = UNIT_WEEK
    /** The next occurrence counts from the day it's done instead of from the due date. */
    var fromCompletion = false
    var doneAt = 0L
    /** Alarm postponed to this time (0 = not snoozed). */
    var snooze = 0L
    /** The alert time already notified, so it rings once. */
    var notified = 0L
    var created = 0L
    /** Attached photo: a file name in [TodoStore.photoDir] ("" = none), a copy so it outlives the original. */
    var photo = ""

    val done get() = doneAt > 0
    val recurring get() = repeat != REPEAT_NONE

    /** When the alert goes off: the due time (9:00 without a time), or the snooze. 0 = never. */
    fun alertTime(): Long = when {
        alert == ALERT_NONE || done -> 0L
        snooze > 0 -> snooze
        due == NO_DAY -> 0L
        else -> CalendarEvents.dayStart(due) + (if (time >= 0) time else DEFAULT_ALERT_MINUTES) * 60_000L
    }

    fun isOverdue(today: Int) = !done && due != NO_DAY && due < today

    fun copy(): Todo = fromJson(toJson())

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("t", title).put("c", category).put("d", due).put("h", time)
        .put("a", alert).put("r", repeat).put("e", every).put("u", unit).put("fc", fromCompletion)
        .put("dn", doneAt).put("sz", snooze).put("nt", notified).put("cr", created).put("p", photo)

    companion object {
        fun fromJson(o: JSONObject) = Todo(o.getLong("id")).apply {
            title = o.optString("t")
            category = o.optLong("c")
            due = o.optInt("d", NO_DAY)
            time = o.optInt("h", NO_TIME)
            alert = o.optInt("a", ALERT_NONE)
            repeat = o.optInt("r", REPEAT_NONE)
            every = o.optInt("e", 1).coerceAtLeast(1)
            unit = o.optInt("u", UNIT_WEEK)
            fromCompletion = o.optBoolean("fc")
            doneAt = o.optLong("dn")
            snooze = o.optLong("sz")
            notified = o.optLong("nt")
            created = o.optLong("cr")
            photo = o.optString("p")
        }
    }
}

/**
 * One completion: kept to show it (crossed out) on the day it was done and to undo it.
 * [prevDue] is where a recurring todo was before moving on.
 */
class TodoDone(
    val todoId: Long,
    val title: String,
    val category: Long,
    val time: Int,
    val day: Int,
    val at: Long,
    val prevDue: Int,
    val recurring: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", todoId).put("t", title).put("c", category).put("h", time)
        .put("d", day).put("at", at).put("pd", prevDue).put("r", recurring)

    companion object {
        fun fromJson(o: JSONObject) = TodoDone(
            o.getLong("id"), o.optString("t"), o.optLong("c"), o.optInt("h", NO_TIME),
            o.optInt("d"), o.optLong("at"), o.optInt("pd", NO_DAY), o.optBoolean("r"),
        )
    }
}

const val NO_DAY = 0
const val NO_TIME = -1
const val DEFAULT_ALERT_MINUTES = 9 * 60

const val ALERT_NONE = 0
const val ALERT_NOTIFY = 1
const val ALERT_ALARM = 2

const val REPEAT_NONE = 0
const val REPEAT_DAILY = 1
const val REPEAT_WEEKLY = 2
const val REPEAT_MONTHLY = 3
const val REPEAT_YEARLY = 4
const val REPEAT_WEEKDAYS = 5
const val REPEAT_CUSTOM = 6

const val UNIT_DAY = 0
const val UNIT_WEEK = 1
const val UNIT_MONTH = 2
const val UNIT_YEAR = 3

/**
 * The todos, their categories and the recent completions, in a JSON file in the app's storage.
 * Kept in memory once loaded; every change is saved at once, tells the listeners (the calendar
 * widgets, the ToDo app) and reschedules the alerts.
 */
object TodoStore {
    private const val FILE = "todos.json"
    private const val HISTORY_DAYS = 60
    /** A done todo's photo is deleted this long after it was completed, to save space. */
    private const val PHOTO_KEEP_MS = 7 * 24 * 3_600_000L

    private var loaded = false
    private val todos = mutableListOf<Todo>()
    private val categories = mutableListOf<TodoCategory>()
    private val history = mutableListOf<TodoDone>()
    private var nextId = 1L
    private val listeners = mutableSetOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    @Synchronized
    private fun ensure(context: Context) {
        if (loaded) return
        loaded = true
        val file = File(context.applicationContext.filesDir, FILE)
        if (!file.exists()) {
            // A few categories to start with
            listOf(R.string.ml_todo_cat_personal to 0xFF42A5F5.toInt(), R.string.ml_todo_cat_work to 0xFFEF6C00.toInt(), R.string.ml_todo_cat_home to 0xFF66BB6A.toInt())
                .forEach { (name, color) -> categories += TodoCategory(nextId++, context.getString(name), color) }
            return
        }
        try {
            val o = JSONObject(file.readText())
            nextId = o.optLong("next", 1L)
            o.optJSONArray("cats")?.let { a -> for (i in 0 until a.length()) categories += TodoCategory.fromJson(a.getJSONObject(i)) }
            o.optJSONArray("todos")?.let { a -> for (i in 0 until a.length()) todos += Todo.fromJson(a.getJSONObject(i)) }
            o.optJSONArray("done")?.let { a -> for (i in 0 until a.length()) history += TodoDone.fromJson(a.getJSONObject(i)) }
            // Saved with the next change; the file is already gone
            prunePhotos(context)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    private fun write(context: Context) {
        val today = CalendarEvents.julianDay(System.currentTimeMillis())
        history.removeAll { it.day < today - HISTORY_DAYS }
        prunePhotos(context)
        val o = JSONObject()
            .put("next", nextId)
            .put("cats", JSONArray().also { a -> categories.forEach { a.put(it.toJson()) } })
            .put("todos", JSONArray().also { a -> todos.forEach { a.put(it.toJson()) } })
            .put("done", JSONArray().also { a -> history.forEach { a.put(it.toJson()) } })
        try {
            val file = File(context.applicationContext.filesDir, FILE)
            val tmp = File(file.parentFile, "$FILE.tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(file)) file.writeText(o.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Saves, then (unless [quiet]) reschedules the alerts and refreshes whoever is listening. */
    private fun changed(context: Context, quiet: Boolean = false) {
        write(context)
        if (quiet) return
        TodoAlarms.schedule(context)
        main.post { listeners.toList().forEach { it() } }
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    @Synchronized
    fun newId(context: Context): Long {
        ensure(context)
        return nextId++
    }

    // ---------------------------------------------------------------- todos

    @Synchronized
    fun todos(context: Context): List<Todo> {
        ensure(context)
        return todos.toList()
    }

    @Synchronized
    fun get(context: Context, id: Long): Todo? {
        ensure(context)
        return todos.firstOrNull { it.id == id }
    }

    @Synchronized
    fun save(context: Context, todo: Todo) {
        ensure(context)
        val i = todos.indexOfFirst { it.id == todo.id }
        if (i >= 0) {
            // A replaced or removed photo goes with the old version
            if (todos[i].photo != todo.photo) deletePhoto(context, todos[i].photo)
            todos[i] = todo
        } else todos += todo
        changed(context)
    }

    @Synchronized
    fun delete(context: Context, id: Long) {
        ensure(context)
        todos.filter { it.id == id }.forEach { deletePhoto(context, it.photo) }
        todos.removeAll { it.id == id }
        history.removeAll { it.todoId == id }
        changed(context)
    }

    /** Marks the alerts as given without telling anyone (only the alarm scheduler changes them). */
    @Synchronized
    fun markNotified(context: Context, ids: Map<Long, Long>) {
        ensure(context)
        todos.forEach { t -> ids[t.id]?.let { t.notified = it } }
        changed(context, quiet = true)
    }

    /** Done on [now]: a one-off is closed, a recurring one moves to its next occurrence. */
    @Synchronized
    fun complete(context: Context, id: Long, now: Long = System.currentTimeMillis()): TodoDone? {
        ensure(context)
        val todo = todos.firstOrNull { it.id == id } ?: return null
        if (todo.done) return null
        val today = CalendarEvents.julianDay(now)
        val record = TodoDone(todo.id, todo.title, todo.category, todo.time, today, now, todo.due, todo.recurring)
        if (todo.recurring) {
            todo.due = nextDue(todo, today)
            todo.notified = 0L
        } else {
            todo.doneAt = now
        }
        todo.snooze = 0L
        // An alert that already went by for the new date (e.g. done late in the day) doesn't ring
        todo.alertTime().takeIf { it in 1..now }?.let { todo.notified = it }
        history += record
        changed(context)
        return record
    }

    /** Takes a completion back: the todo returns where it was. */
    @Synchronized
    fun undo(context: Context, record: TodoDone) {
        ensure(context)
        history.removeAll { it.todoId == record.todoId && it.at == record.at }
        todos.firstOrNull { it.id == record.todoId }?.let { todo ->
            if (record.recurring) todo.due = record.prevDue else todo.doneAt = 0L
            todo.alertTime().takeIf { it in 1..System.currentTimeMillis() }?.let { todo.notified = it }
        }
        changed(context)
    }

    /** Moves [id] to [at]: the due date and time change, the alert rings again then. */
    @Synchronized
    fun postpone(context: Context, id: Long, at: Long) {
        ensure(context)
        todos.firstOrNull { it.id == id }?.let {
            it.due = CalendarEvents.julianDay(at)
            it.time = ((at - CalendarEvents.dayStart(it.due)) / 60_000L).toInt().coerceIn(0, 24 * 60 - 1)
            it.snooze = 0L
            it.notified = 0L
            changed(context)
        }
    }

    @Synchronized
    fun history(context: Context): List<TodoDone> {
        ensure(context)
        return history.toList()
    }

    @Synchronized
    fun forgetDone(context: Context, record: TodoDone) {
        ensure(context)
        history.removeAll { it.todoId == record.todoId && it.at == record.at }
        // A one-off done todo goes with its record
        todos.filter { it.id == record.todoId && it.done }.forEach { deletePhoto(context, it.photo) }
        todos.removeAll { it.id == record.todoId && it.done }
        changed(context)
    }

    // ---------------------------------------------------------------- photos

    /** Where the todos' photos are kept: the app's own storage, untouched by the gallery. */
    fun photoDir(context: Context): File = File(context.applicationContext.filesDir, "todo_photos").apply { mkdirs() }

    /** The photo of [todo], if it has one and the file is there. */
    fun photoFile(context: Context, todo: Todo): File? =
        todo.photo.takeIf { it.isNotEmpty() }?.let { File(photoDir(context), it) }?.takeIf { it.isFile }

    /** Drops the photos of the todos done more than a week ago (a recurring todo is never done). */
    private fun prunePhotos(context: Context) {
        val limit = System.currentTimeMillis() - PHOTO_KEEP_MS
        todos.filter { it.done && it.doneAt < limit && it.photo.isNotEmpty() }.forEach {
            deletePhoto(context, it.photo)
            it.photo = ""
        }
    }

    fun deletePhoto(context: Context, name: String) {
        if (name.isNotEmpty() && !name.contains('/')) File(photoDir(context), name).delete()
    }

    // ---------------------------------------------------------------- categories

    @Synchronized
    fun categories(context: Context): List<TodoCategory> {
        ensure(context)
        return categories.toList()
    }

    @Synchronized
    fun category(context: Context, id: Long): TodoCategory? {
        ensure(context)
        return categories.firstOrNull { it.id == id }
    }

    @Synchronized
    fun saveCategory(context: Context, category: TodoCategory) {
        ensure(context)
        val i = categories.indexOfFirst { it.id == category.id }
        if (i >= 0) categories[i] = category else categories += category
        changed(context)
    }

    /** The todos of a deleted category stay, without a category. */
    @Synchronized
    fun deleteCategory(context: Context, id: Long) {
        ensure(context)
        categories.removeAll { it.id == id }
        todos.forEach { if (it.category == id) it.category = 0L }
        changed(context)
    }

    @Synchronized
    fun moveCategory(context: Context, id: Long, delta: Int) {
        ensure(context)
        val i = categories.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in categories.indices) return
        categories.add(j, categories.removeAt(i))
        changed(context)
    }

    // ---------------------------------------------------------------- repetition

    /**
     * The due date after [today]'s completion. From the due date, missed occurrences are skipped
     * (the next one is after today); from the completion it's simply today plus the interval.
     */
    fun nextDue(todo: Todo, today: Int): Int {
        if (todo.fromCompletion || todo.due == NO_DAY) return step(todo, today)
        var next = step(todo, todo.due)
        var guard = 0
        while (next <= today && guard++ < 5000) next = step(todo, next)
        return next
    }

    /** One interval after [day]. */
    fun step(todo: Todo, day: Int): Int = when (todo.repeat) {
        REPEAT_DAILY -> day + 1
        REPEAT_WEEKLY -> day + 7
        REPEAT_MONTHLY -> addMonths(day, 1)
        REPEAT_YEARLY -> addMonths(day, 12)
        REPEAT_WEEKDAYS -> {
            var d = day + 1
            while (isWeekend(d)) d++
            d
        }
        REPEAT_CUSTOM -> when (todo.unit) {
            UNIT_DAY -> day + todo.every
            UNIT_WEEK -> day + 7 * todo.every
            UNIT_MONTH -> addMonths(day, todo.every)
            else -> addMonths(day, 12 * todo.every)
        }
        else -> day
    }

    /** One interval before [day] (where the count starts when it isn't given). */
    fun stepBack(todo: Todo, day: Int): Int = when (todo.repeat) {
        REPEAT_DAILY -> day - 1
        REPEAT_WEEKLY -> day - 7
        REPEAT_MONTHLY -> addMonths(day, -1)
        REPEAT_YEARLY -> addMonths(day, -12)
        REPEAT_WEEKDAYS -> {
            var d = day - 1
            while (isWeekend(d)) d--
            d
        }
        REPEAT_CUSTOM -> when (todo.unit) {
            UNIT_DAY -> day - todo.every
            UNIT_WEEK -> day - 7 * todo.every
            UNIT_MONTH -> addMonths(day, -todo.every)
            else -> addMonths(day, -12 * todo.every)
        }
        else -> day
    }

    private fun addMonths(day: Int, months: Int): Int {
        val c = Calendar.getInstance().apply { timeInMillis = CalendarEvents.dayStart(day) + 12 * 3_600_000L }
        c.add(Calendar.MONTH, months)
        return CalendarEvents.julianDay(c.timeInMillis)
    }

    private fun isWeekend(day: Int): Boolean {
        val c = Calendar.getInstance().apply { timeInMillis = CalendarEvents.dayStart(day) + 12 * 3_600_000L }
        val dow = c.get(Calendar.DAY_OF_WEEK)
        return dow == Calendar.SATURDAY || dow == Calendar.SUNDAY
    }
}
