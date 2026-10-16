package app.olauncher.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * An item on home: an app icon (when [packageName] is set, appWidgetId -1) on the grid at cell
 * ([col], [row]); or a widget placed freely at ([x], [y]) with size [w] x [h], all in dp.
 * A widget with [w] == 0 was saved by an older layout: on the grid ([colSpan] > 0) or stacked
 * ([legacyHeightDp]); it gets a free position the first time it's shown.
 */
data class HomeWidget(
    val appWidgetId: Int,
    var col: Int = 0,
    var row: Int = 0,
    var colSpan: Int = 0,
    var rowSpan: Int = 0,
    val legacyHeightDp: Int = 0,
    val packageName: String? = null,
    val activityClassName: String? = null,
    val user: String? = null,
    var x: Int = 0,
    var y: Int = 0,
    var w: Int = 0,
    var h: Int = 0,
) {
    val isApp get() = packageName != null
}

/** Persists the home widgets as JSON in a dedicated SharedPreferences file. */
class HomeWidgetsRepository(context: Context, private val key: String = KEY) {

    private val prefs = context.getSharedPreferences("app.minimalauncher.widgets", Context.MODE_PRIVATE)

    fun load(): MutableList<HomeWidget> {
        val raw = prefs.getString(key, null) ?: return mutableListOf()
        return try {
            val array = JSONArray(raw)
            MutableList(array.length()) { i ->
                val o = array.getJSONObject(i)
                HomeWidget(
                    appWidgetId = o.getInt("id"),
                    col = o.optInt("c", 0),
                    row = o.optInt("r", 0),
                    colSpan = o.optInt("cs", 0),
                    rowSpan = o.optInt("rs", 0),
                    legacyHeightDp = o.optInt("h", 0),
                    packageName = o.optString("p").ifBlank { null },
                    activityClassName = o.optString("cl").ifBlank { null },
                    user = o.optString("u").ifBlank { null },
                    x = o.optInt("x", 0),
                    y = o.optInt("y", 0),
                    w = o.optInt("w", 0),
                    h = o.optInt("h", 0),
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            mutableListOf()
        }
    }

    /** The stored JSON as is, to detect changes made from elsewhere without parsing. */
    fun raw(): String? = prefs.getString(key, null)

    fun save(widgets: List<HomeWidget>) {
        val array = JSONArray()
        widgets.forEach {
            val o = JSONObject().put("id", it.appWidgetId).put("c", it.col).put("r", it.row)
                .put("cs", it.colSpan).put("rs", it.rowSpan)
            if (it.packageName != null) o.put("p", it.packageName).put("cl", it.activityClassName ?: "").put("u", it.user ?: "")
            if (it.w > 0) o.put("x", it.x).put("y", it.y).put("w", it.w).put("h", it.h)
            array.put(o)
        }
        prefs.edit { putString(key, array.toString()) }
    }

    /**
     * Converts positions saved with square rows to rows split in [divisions] (once): row and
     * height are multiplied so widgets keep their size. Returns true when something changed.
     */
    fun migrateRowDivisions(divisions: Int): Boolean {
        val flag = "${key}_ROW_DIVISIONS"
        val saved = prefs.getInt(flag, 1)
        if (saved == divisions) return false
        val widgets = load()
        widgets.forEach {
            if (it.colSpan > 0) {
                it.row = it.row * divisions / saved
                it.rowSpan = (it.rowSpan * divisions / saved).coerceAtLeast(1)
            }
        }
        save(widgets)
        prefs.edit { putInt(flag, divisions) }
        return widgets.isNotEmpty()
    }

    companion object {
        const val KEY = "WIDGETS"
        const val KEY_BOTTOM = "BOTTOM"
    }
}
