package app.olauncher.data

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** The texts of the calendar widget, each with its own font, size, style and color. */
enum class CalendarText(val key: String, val defaultSize: Int, val defaultStyle: Int) {
    CLOCK("ck", 18, Typeface.BOLD),
    HEADER("hd", 18, Typeface.BOLD),
    WEEK("wk", 14, Typeface.BOLD),
    DAY("dy", 14, Typeface.BOLD),
    TIME("tm", 15, Typeface.NORMAL),
    TITLE("tt", 17, Typeface.NORMAL),
    STATUS("st", 13, Typeface.ITALIC),
}

/** [font] is a FontManager id ("" = default), [color] 0 = the theme's text color. */
data class CalendarTextStyle(
    var font: String = "",
    var sizeSp: Int,
    var style: Int,
    var color: Int = 0,
)

/** Settings of one calendar widget on home. */
class CalendarWidgetConfig {
    /** Calendar ids to show; empty = every calendar visible in the calendar app. */
    var calendars: Set<Long> = emptySet()
    var pastMode = PAST_DIM
    /** Days listed from the selected one (1 = only that day). */
    var days = 1
    var showHeader = true
    /** Week number (ISO) next to the date. */
    var showWeek = true
    /** Current time before the date. */
    var showClock = true
    /** "SAB 17 OTT" instead of "SABATO, 17 OTTOBRE". */
    var shortDate = false
    /** Progress bar colors: the elapsed part and the rest (0 = default). */
    var progressFill = 0
    var progressTrack = 0
    /** Swipe left / right on the widget for the next / previous day. */
    var swipeDays = true
    /** Previous / next day buttons in the header. */
    var showArrows = false
    var showProgress = true
    var showStatus = true
    var showAllDay = true
    var showDeclined = false
    /** Background opacity in percent (0 = none). */
    var background = 0
    /** A card behind the widget: the cards' color with a thin border. */
    var card = true
    /** The next hours' weather under the header (opens the Weather app). */
    var showWeather = true
    /** The ToDos of the launcher's ToDo app in the widget: all, only those to do, none (button in the header). */
    var todoFilter = TODO_SHOW_ALL
    val showTodos get() = todoFilter != TODO_HIDE_ALL
    var todoPlace = TODO_MIXED
    var todoDoneMode = TODO_DONE_STRIKE
    /** ToDo categories to show; empty = all (and the todos without a category). */
    var todoCategories: Set<Long> = emptySet()
    val texts: MutableMap<CalendarText, CalendarTextStyle> =
        CalendarText.entries.associateWith { CalendarTextStyle(sizeSp = it.defaultSize, style = it.defaultStyle) }.toMutableMap()

    fun text(t: CalendarText): CalendarTextStyle = texts.getValue(t)

    fun toJson(): JSONObject = JSONObject()
        .put("cal", JSONArray(calendars.toList()))
        .put("past", pastMode)
        .put("days", days)
        .put("hdr", showHeader)
        .put("wk", showWeek)
        .put("clk", showClock)
        .put("sd", shortDate)
        .put("pf", progressFill)
        .put("pt", progressTrack)
        .put("swp", swipeDays)
        .put("arr", showArrows)
        .put("prg", showProgress)
        .put("sts", showStatus)
        .put("all", showAllDay)
        .put("dec", showDeclined)
        .put("bg", background)
        .put("card", card)
        .put("wx", showWeather)
        .put("tdf", todoFilter)
        .put("tdp", todoPlace)
        .put("tdd", todoDoneMode)
        .put("tdc", JSONArray(todoCategories.toList()))
        .put("txt", JSONObject().also { o ->
            texts.forEach { (t, s) ->
                o.put(t.key, JSONObject().put("f", s.font).put("s", s.sizeSp).put("y", s.style).put("c", s.color))
            }
        })

    companion object {
        const val PAST_SHOW = 0
        const val PAST_DIM = 1
        const val PAST_HIDE = 2

        const val TODO_SHOW_ALL = 0
        const val TODO_HIDE_DONE = 1
        const val TODO_HIDE_ALL = 2

        /** ToDos in their own block under the events, among them by time, or above them. */
        const val TODO_BELOW = 0
        const val TODO_MIXED = 1
        const val TODO_ABOVE = 2

        /** A todo done stays crossed out until the end of the day, or goes at once (with an undo). */
        const val TODO_DONE_STRIKE = 0
        const val TODO_DONE_HIDE = 1

        fun fromJson(o: JSONObject): CalendarWidgetConfig = CalendarWidgetConfig().apply {
            val cal = o.optJSONArray("cal")
            calendars = if (cal == null) emptySet() else (0 until cal.length()).map { cal.getLong(it) }.toSet()
            pastMode = o.optInt("past", PAST_DIM)
            days = o.optInt("days", 1).coerceIn(1, 14)
            showHeader = o.optBoolean("hdr", true)
            showWeek = o.optBoolean("wk", true)
            showClock = o.optBoolean("clk", true)
            shortDate = o.optBoolean("sd", false)
            progressFill = o.optInt("pf", 0)
            progressTrack = o.optInt("pt", 0)
            swipeDays = o.optBoolean("swp", true)
            showArrows = o.optBoolean("arr", false)
            showProgress = o.optBoolean("prg", true)
            showStatus = o.optBoolean("sts", true)
            showAllDay = o.optBoolean("all", true)
            showDeclined = o.optBoolean("dec", false)
            background = o.optInt("bg", 0)
            card = o.optBoolean("card", true)
            showWeather = o.optBoolean("wx", true)
            todoFilter = o.optInt("tdf", if (o.optBoolean("td", true)) TODO_SHOW_ALL else TODO_HIDE_ALL).coerceIn(0, 2)
            todoPlace = o.optInt("tdp", TODO_MIXED).coerceIn(0, 2)
            todoDoneMode = o.optInt("tdd", TODO_DONE_STRIKE).coerceIn(0, 1)
            val tdc = o.optJSONArray("tdc")
            todoCategories = if (tdc == null) emptySet() else (0 until tdc.length()).map { tdc.getLong(it) }.toSet()
            val txt = o.optJSONObject("txt")
            CalendarText.entries.forEach { t ->
                val s = txt?.optJSONObject(t.key) ?: return@forEach
                texts[t] = CalendarTextStyle(
                    font = s.optString("f"),
                    sizeSp = s.optInt("s", t.defaultSize),
                    style = s.optInt("y", t.defaultStyle),
                    color = s.optInt("c", 0),
                )
            }
        }
    }
}

/** Stores each calendar widget's settings by its (negative) id. */
class CalendarWidgetRepository(context: Context) {
    private val prefs = context.getSharedPreferences("app.minimalauncher.calendar", Context.MODE_PRIVATE)

    fun load(id: Int): CalendarWidgetConfig = try {
        prefs.getString("w$id", null)?.let { CalendarWidgetConfig.fromJson(JSONObject(it)) } ?: CalendarWidgetConfig()
    } catch (e: Exception) {
        e.printStackTrace()
        CalendarWidgetConfig()
    }

    fun save(id: Int, config: CalendarWidgetConfig) = prefs.edit { putString("w$id", config.toJson().toString()) }

    fun delete(id: Int) = prefs.edit { remove("w$id") }
}
