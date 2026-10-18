package app.olauncher.data

import android.content.Context
import androidx.core.content.edit
import app.olauncher.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A user-made tab of the app drawer. [apps] holds "package|UserHandle.toString()" keys
 * ("package/activity|user" for the launcher's own apps, see [DrawerTabsRepository.key]).
 */
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
                    apps = MutableList(apps.length()) { apps.getString(it) }.flatMap { migrate(it) }.toMutableSet(),
                )
            }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    /** Order of the apps in a tab set by dragging them (keys); empty: A-Z. */
    fun order(id: String): List<String> {
        val raw = prefs.getString(KEY_ORDER + id, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            List(array.length()) { array.getString(it) }.flatMap { migrate(it) }.distinct()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setOrder(id: String, keys: List<String>) = prefs.edit {
        if (keys.isEmpty()) remove(KEY_ORDER + id) else putString(KEY_ORDER + id, JSONArray(keys).toString())
    }

    fun hasOrder(id: String) = prefs.contains(KEY_ORDER + id)

    /** [apps] in the tab's own order: those placed by hand first, the others after them A-Z. */
    fun <T : AppModel> sorted(id: String, apps: List<T>): List<T> {
        val order = order(id)
        if (order.isEmpty()) return apps
        val index = HashMap<String, Int>(order.size * 2).apply { order.forEachIndexed { i, k -> put(k, i) } }
        return apps.sortedBy { index[key(it)] ?: Int.MAX_VALUE }
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

    /**
     * Before, the launcher's own apps (File, ToDo, Scanner) shared one key: the old key stands for
     * the three of them, as it did then.
     */
    private fun migrate(key: String): List<String> {
        val prefix = BuildConfig.APPLICATION_ID + "|"
        if (!key.startsWith(prefix)) return listOf(key)
        val user = key.removePrefix(prefix)
        return LEGACY_OWN_APPS.map { "${BuildConfig.APPLICATION_ID}/$it|$user" }
    }

    companion object {
        const val ALL_ID = "all"
        const val HIDDEN_ID = "hidden"
        private const val KEY_HIDDEN_ENABLED = "HIDDEN_ENABLED"
        private const val KEY_TABS = "TABS"
        private const val KEY_ALL_ENABLED = "ALL_ENABLED"
        private const val KEY_SELECTED = "SELECTED"
        private const val KEY_ORDER = "ORDER_"

        /** The launcher's own apps from before the Compass, which shared one key. */
        val LEGACY_OWN_APPS = listOf("app.olauncher.FilesAlias", "app.olauncher.TodoAlias", "app.olauncher.ScannerAlias")

        /**
         * Key of an app in the tabs and in the hidden apps. The launcher's own apps all have its
         * package, so they are told apart by their activity.
         */
        fun key(app: AppModel): String {
            val activity = (app as? AppModel.App)?.activityClassName
            return if (app.appPackage == BuildConfig.APPLICATION_ID && activity != null) "${app.appPackage}/$activity|${app.user}"
            else "${app.appPackage}|${app.user}"
        }
    }
}
