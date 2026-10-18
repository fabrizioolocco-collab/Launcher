package app.olauncher.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** How the file explorer shows a folder. */
data class FolderView(
    val grid: Boolean = false,
    val columns: Int = 3,
    val sort: String = SORT_NAME,
    val descending: Boolean = false,
    val foldersFirst: Boolean = true,
    val showSize: Boolean = true,
    val showDate: Boolean = true,
    val showExtension: Boolean = true,
    val showThumbnails: Boolean = true,
    val showNames: Boolean = true,
    val showHidden: Boolean = false,
) {
    fun toJson(): String = JSONObject()
        .put("grid", grid).put("columns", columns).put("sort", sort).put("desc", descending)
        .put("foldersFirst", foldersFirst).put("size", showSize).put("date", showDate).put("ext", showExtension)
        .put("thumbs", showThumbnails).put("names", showNames).put("hidden", showHidden).toString()

    companion object {
        const val SORT_NAME = "name"
        const val SORT_DATE = "date"
        const val SORT_SIZE = "size"
        const val SORT_TYPE = "type"
        const val SORT_MANUAL = "manual"

        fun fromJson(raw: String): FolderView? = try {
            val o = JSONObject(raw)
            FolderView(
                grid = o.optBoolean("grid"), columns = o.optInt("columns", 3).coerceIn(2, 6),
                sort = o.optString("sort", SORT_NAME), descending = o.optBoolean("desc"),
                foldersFirst = o.optBoolean("foldersFirst", true), showSize = o.optBoolean("size", true),
                showDate = o.optBoolean("date", true), showExtension = o.optBoolean("ext", true),
                showThumbnails = o.optBoolean("thumbs", true), showNames = o.optBoolean("names", true),
                showHidden = o.optBoolean("hidden"),
            )
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Per-folder view settings of the file explorer. A folder without its own settings uses those of
 * the nearest parent folder that has some (or the defaults). The manual order is per folder only.
 */
class FolderViewsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("app.minimalauncher.explorer", Context.MODE_PRIVATE)

    private fun path(dir: File) = dir.absolutePath.trimEnd('/')

    /** The settings for [dir] and the folder they come from (null = defaults). */
    fun effective(dir: File): Pair<FolderView, File?> {
        var current: File? = dir
        while (current != null) {
            prefs.getString(KEY_VIEW + path(current), null)?.let { raw ->
                FolderView.fromJson(raw)?.let { return it to current }
            }
            current = current.parentFile
        }
        return FolderView() to null
    }

    fun save(dir: File, view: FolderView) = prefs.edit { putString(KEY_VIEW + path(dir), view.toJson()) }

    /** Back to the settings of the parent folders. */
    fun reset(dir: File) = prefs.edit { remove(KEY_VIEW + path(dir)) }

    fun manualOrder(dir: File): List<String> = try {
        val array = JSONArray(prefs.getString(KEY_ORDER + path(dir), "[]"))
        List(array.length()) { array.getString(it) }
    } catch (e: Exception) {
        emptyList()
    }

    fun saveManualOrder(dir: File, names: List<String>) =
        prefs.edit { putString(KEY_ORDER + path(dir), JSONArray(names).toString()) }

    /** Names hidden in [dir] by the user (shown only with "Hidden files" on), files kept as they are. */
    fun hidden(dir: File): Set<String> = try {
        val array = JSONArray(prefs.getString(KEY_HIDDEN + path(dir), "[]"))
        List(array.length()) { array.getString(it) }.toSet()
    } catch (e: Exception) {
        emptySet()
    }

    fun setHidden(dir: File, names: Collection<String>, hide: Boolean) {
        val now = if (hide) hidden(dir) + names else hidden(dir) - names.toSet()
        prefs.edit { if (now.isEmpty()) remove(KEY_HIDDEN + path(dir)) else putString(KEY_HIDDEN + path(dir), JSONArray(now.toList()).toString()) }
    }

    companion object {
        private const val KEY_VIEW = "view:"
        private const val KEY_ORDER = "order:"
        private const val KEY_HIDDEN = "hidden:"
    }
}
