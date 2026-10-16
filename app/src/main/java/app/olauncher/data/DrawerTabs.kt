package app.olauncher.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A user-made tab of the app drawer. [apps] holds "package|UserHandle.toString()" keys. */
data class DrawerTab(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var enabled: Boolean = true,
    val apps: MutableSet<String> = mutableSetOf(),
)

/**
 * Drawer tabs: the built-in "All" tab (always first, can be turned off) followed by the user's tabs.
 */
class DrawerTabsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("app.minimalauncher.tabs", Context.MODE_PRIVATE)

    var allEnabled: Boolean
        get() = prefs.getBoolean(KEY_ALL_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_ALL_ENABLED, value) }

    /** The "Hidden" tab (hidden apps), last in the bar. Off by default. */
    var hiddenEnabled: Boolean
        get() = prefs.getBoolean(KEY_HIDDEN_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_HIDDEN_ENABLED, value) }

    /** Last selected tab id ([ALL_ID] for "All"). */
    var selected: String
        get() = prefs.getString(KEY_SELECTED, ALL_ID) ?: ALL_ID
        set(value) = prefs.edit { putString(KEY_SELECTED, value) }

    fun load(): MutableList<DrawerTab> {
        val raw = prefs.getString(KEY_TABS, null) ?: return mutableListOf()
        return try {
            val array = JSONArray(raw)
            MutableList(array.length()) { i ->
                val o = array.getJSONObject(i)
                val apps = o.optJSONArray("apps") ?: JSONArray()
                DrawerTab(
                    id = o.getString("id"),
                    name = o.optString("name"),
                    enabled = o.optBoolean("enabled", true),
                    apps = MutableList(apps.length()) { apps.getString(it) }.toMutableSet(),
                )
            }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save(tabs: List<DrawerTab>) {
        val array = JSONArray()
        tabs.forEach { t ->
            array.put(
                JSONObject().put("id", t.id).put("name", t.name).put("enabled", t.enabled)
                    .put("apps", JSONArray().apply { t.apps.forEach { put(it) } })
            )
        }
        prefs.edit { putString(KEY_TABS, array.toString()) }
    }

    companion object {
        const val ALL_ID = "all"
        const val HIDDEN_ID = "hidden"
        private const val KEY_HIDDEN_ENABLED = "HIDDEN_ENABLED"
        private const val KEY_TABS = "TABS"
        private const val KEY_ALL_ENABLED = "ALL_ENABLED"
        private const val KEY_SELECTED = "SELECTED"

        fun key(app: AppModel) = "${app.appPackage}|${app.user}"
    }
}
