package app.olauncher.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * A widget in the home grid: top-left cell and size in cells.
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
)

/** Persists the home widgets as JSON in a dedicated SharedPreferences file. */
class HomeWidgetsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("app.minimalauncher.widgets", Context.MODE_PRIVATE)

    fun load(): MutableList<HomeWidget> {
        val raw = prefs.getString(KEY, null) ?: return mutableListOf()
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
            array.put(
                JSONObject().put("id", it.appWidgetId).put("c", it.col).put("r", it.row)
                    .put("cs", it.colSpan).put("rs", it.rowSpan)
            )
        }
        prefs.edit { putString(KEY, array.toString()) }
    }

    companion object {
        private const val KEY = "WIDGETS"
    }
}
