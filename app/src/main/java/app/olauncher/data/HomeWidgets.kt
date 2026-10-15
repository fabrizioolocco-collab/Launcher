package app.olauncher.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** A widget placed in the home widget area, top to bottom. */
data class HomeWidget(val appWidgetId: Int, var heightDp: Int)

/** Persists the home widgets as JSON in a dedicated SharedPreferences file. */
class HomeWidgetsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("app.minimalauncher.widgets", Context.MODE_PRIVATE)

    fun load(): MutableList<HomeWidget> {
        val raw = prefs.getString(KEY, null) ?: return mutableListOf()
        return try {
            val array = JSONArray(raw)
            MutableList(array.length()) { i ->
                val o = array.getJSONObject(i)
                HomeWidget(o.getInt("id"), o.optInt("h", 160))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            mutableListOf()
        }
    }

    fun save(widgets: List<HomeWidget>) {
        val array = JSONArray()
        widgets.forEach { array.put(JSONObject().put("id", it.appWidgetId).put("h", it.heightDp)) }
        prefs.edit { putString(KEY, array.toString()) }
    }

    companion object {
        private const val KEY = "WIDGETS"
    }
}
