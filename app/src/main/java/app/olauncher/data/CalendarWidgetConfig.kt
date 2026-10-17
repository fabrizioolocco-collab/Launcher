package app.olauncher.data

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** The texts of the calendar widget, each with its own font, size, style and color. */
enum class CalendarText(val key: String, val defaultSize: Int, val defaultStyle: Int) {
    HEADER("hd", 18, Typeface.BOLD),
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
    var showProgress = true
    var showStatus = true
    var showAllDay = true
    var showDeclined = false
    /** Background opacity in percent (0 = none). */
    var background = 0
    val texts: MutableMap<CalendarText, CalendarTextStyle> =
        CalendarText.entries.associateWith { CalendarTextStyle(sizeSp = it.defaultSize, style = it.defaultStyle) }.toMutableMap()

    fun text(t: CalendarText): CalendarTextStyle = texts.getValue(t)

    fun toJson(): JSONObject = JSONObject()
        .put("cal", JSONArray(calendars.toList()))
        .put("past", pastMode)
        .put("days", days)
        .put("hdr", showHeader)
        .put("prg", showProgress)
        .put("sts", showStatus)
        .put("all", showAllDay)
        .put("dec", showDeclined)
        .put("bg", background)
        .put("txt", JSONObject().also { o ->
            texts.forEach { (t, s) ->
                o.put(t.key, JSONObject().put("f", s.font).put("s", s.sizeSp).put("y", s.style).put("c", s.color))
            }
        })

    companion object {
        const val PAST_SHOW = 0
        const val PAST_DIM = 1
        const val PAST_HIDE = 2

        fun fromJson(o: JSONObject): CalendarWidgetConfig = CalendarWidgetConfig().apply {
            val cal = o.optJSONArray("cal")
            calendars = if (cal == null) emptySet() else (0 until cal.length()).map { cal.getLong(it) }.toSet()
            pastMode = o.optInt("past", PAST_DIM)
            days = o.optInt("days", 1).coerceIn(1, 14)
            showHeader = o.optBoolean("hdr", true)
            showProgress = o.optBoolean("prg", true)
            showStatus = o.optBoolean("sts", true)
            showAllDay = o.optBoolean("all", true)
            showDeclined = o.optBoolean("dec", false)
            background = o.optInt("bg", 0)
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
