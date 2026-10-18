package app.olauncher.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** An app reference stored for the dock. [user] is UserHandle.toString(), as used elsewhere in Prefs. */
data class PinnedApp(
    val packageName: String,
    val activityClassName: String?,
    val user: String,
) {
    fun matches(app: AppModel.App): Boolean =
        app.appPackage == packageName && app.user.toString() == user &&
                (activityClassName.isNullOrBlank() || app.activityClassName == activityClassName)

    fun samePackageAndUser(other: PinnedApp): Boolean =
        packageName == other.packageName && user == other.user &&
                (activityClassName.isNullOrBlank() || other.activityClassName.isNullOrBlank() ||
                        activityClassName == other.activityClassName)

    companion object {
        fun from(app: AppModel.App) = PinnedApp(app.appPackage, app.activityClassName, app.user.toString())
    }
}

/**
 * Persists the dock's apps (and the apps once pinned on home as a list, until they are moved onto
 * the grid) as JSON in a dedicated SharedPreferences file.
 */
class PinnedAppsRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /** Apps once pinned on home as a list, in the user's order (only read to move them onto the grid). */
    fun loadUngrouped(): MutableList<PinnedApp> = loadApps(KEY_UNGROUPED)

    fun saveUngrouped(apps: List<PinnedApp>) = saveApps(KEY_UNGROUPED, apps)

    /** The apps of the dock, the bar above the search, left to right. */
    fun loadDock(): MutableList<PinnedApp> = loadApps(KEY_DOCK)

    fun saveDock(apps: List<PinnedApp>) = saveApps(KEY_DOCK, apps)

    private fun loadApps(key: String): MutableList<PinnedApp> {
        val raw = prefs.getString(key, null) ?: return mutableListOf()
        return try {
            val array = JSONArray(raw)
            MutableList(array.length()) { i ->
                val a = array.getJSONObject(i)
                PinnedApp(a.getString("p"), a.optString("c").ifBlank { null }, a.getString("u"))
            }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun saveApps(key: String, apps: List<PinnedApp>) {
        val array = JSONArray()
        apps.forEach { array.put(JSONObject().put("p", it.packageName).put("c", it.activityClassName ?: "").put("u", it.user)) }
        prefs.edit { putString(key, array.toString()) }
    }

    companion object {
        /** File name kept from the home sections (removed), so the saved dock is still found. */
        private const val PREFS_FILE = "app.minimalauncher.sections"
        private const val KEY_UNGROUPED = "UNGROUPED"
        private const val KEY_DOCK = "DOCK"
    }
}
