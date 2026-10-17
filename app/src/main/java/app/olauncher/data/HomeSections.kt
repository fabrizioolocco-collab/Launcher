package app.olauncher.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** An app reference stored in a home section. [user] is UserHandle.toString(), as used elsewhere in Prefs. */
data class SectionApp(
    val packageName: String,
    val activityClassName: String?,
    val user: String,
) {
    fun matches(app: AppModel.App): Boolean =
        app.appPackage == packageName && app.user.toString() == user &&
                (activityClassName.isNullOrBlank() || app.activityClassName == activityClassName)

    fun samePackageAndUser(other: SectionApp): Boolean =
        packageName == other.packageName && user == other.user &&
                (activityClassName.isNullOrBlank() || other.activityClassName.isNullOrBlank() ||
                        activityClassName == other.activityClassName)

    companion object {
        fun from(app: AppModel.App) = SectionApp(app.appPackage, app.activityClassName, app.user.toString())
    }
}

data class HomeSection(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var collapsed: Boolean = false,
    val apps: MutableList<SectionApp> = mutableListOf(),
    /** Logical row on the home grid; -1 until the grid places it. */
    var row: Int = -1,
) {
    fun contains(app: SectionApp): Boolean = apps.any { it.samePackageAndUser(app) }

    fun indexOf(app: SectionApp): Int = apps.indexOfFirst { it.samePackageAndUser(app) }
}

/** Persists the user's home sections as JSON in a dedicated SharedPreferences file. */
class HomeSectionsRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun load(): MutableList<HomeSection> {
        val raw = prefs.getString(KEY_SECTIONS, null) ?: return mutableListOf()
        return try {
            val array = JSONArray(raw)
            MutableList(array.length()) { i -> array.getJSONObject(i).toSection() }
        } catch (e: Exception) {
            e.printStackTrace()
            mutableListOf()
        }
    }

    fun save(sections: List<HomeSection>) {
        val array = JSONArray()
        sections.forEach { array.put(it.toJson()) }
        prefs.edit { putString(KEY_SECTIONS, array.toString()) }
    }

    /** True once sections have ever been saved (even an empty list), so we migrate Olauncher's home apps only once. */
    fun isInitialized(): Boolean = prefs.contains(KEY_SECTIONS)

    /** Apps pinned on home outside any section, in the user's order. */
    fun loadUngrouped(): MutableList<SectionApp> = loadApps(KEY_UNGROUPED)

    fun saveUngrouped(apps: List<SectionApp>) = saveApps(KEY_UNGROUPED, apps)

    /** The apps of the dock, the bar above the search, left to right. */
    fun loadDock(): MutableList<SectionApp> = loadApps(KEY_DOCK)

    fun saveDock(apps: List<SectionApp>) = saveApps(KEY_DOCK, apps)

    private fun loadApps(key: String): MutableList<SectionApp> {
        val raw = prefs.getString(key, null) ?: return mutableListOf()
        return try {
            val array = JSONArray(raw)
            MutableList(array.length()) { i ->
                val a = array.getJSONObject(i)
                SectionApp(a.getString("p"), a.optString("c").ifBlank { null }, a.getString("u"))
            }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun saveApps(key: String, apps: List<SectionApp>) {
        val array = JSONArray()
        apps.forEach { array.put(JSONObject().put("p", it.packageName).put("c", it.activityClassName ?: "").put("u", it.user)) }
        prefs.edit { putString(key, array.toString()) }
    }

    private fun HomeSection.toJson() = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("collapsed", collapsed)
        put("row", row)
        put("apps", JSONArray().apply {
            apps.forEach { app ->
                put(JSONObject().apply {
                    put("p", app.packageName)
                    put("c", app.activityClassName ?: "")
                    put("u", app.user)
                })
            }
        })
    }

    private fun JSONObject.toSection(): HomeSection {
        val appsJson = optJSONArray("apps") ?: JSONArray()
        val apps = MutableList(appsJson.length()) { i ->
            val a = appsJson.getJSONObject(i)
            SectionApp(
                packageName = a.getString("p"),
                activityClassName = a.optString("c").ifBlank { null },
                user = a.getString("u"),
            )
        }
        return HomeSection(
            id = optString("id").ifBlank { UUID.randomUUID().toString() },
            name = optString("name"),
            collapsed = optBoolean("collapsed", false),
            apps = apps,
            row = optInt("row", -1),
        )
    }

    companion object {
        private const val PREFS_FILE = "app.minimalauncher.sections"
        private const val KEY_SECTIONS = "SECTIONS"
        private const val KEY_UNGROUPED = "UNGROUPED"
        private const val KEY_DOCK = "DOCK"
    }
}
