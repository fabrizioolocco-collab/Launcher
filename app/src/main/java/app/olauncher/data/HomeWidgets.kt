package app.olauncher.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * An item in a home grid: a widget, or (when [packageName] is set) an app icon with appWidgetId -1.
 * Position is the top-left cell, size in cells.
 * [colSpan] == 0 marks a widget saved by the older stacked layout (only [legacyHeightDp] known),
 * placed on the grid the first time it's shown.
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
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            mutableListOf()
        }
    }

    fun save(widgets: List<HomeWidget>) {
        val array = JSONArray()
        widgets.forEach {
            val o = JSONObject().put("id", it.appWidgetId).put("c", it.col).put("r", it.row)
                .put("cs", it.colSpan).put("rs", it.rowSpan)
            if (it.packageName != null) o.put("p", it.packageName).put("cl", it.activityClassName ?: "").put("u", it.user ?: "")
            array.put(o)
        }
        prefs.edit { putString(key, array.toString()) }
    }

    companion object {
        const val KEY = "WIDGETS"
        const val KEY_BOTTOM = "BOTTOM"
    }
}
