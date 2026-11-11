package app.olauncher.helper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Process
import androidx.core.content.edit
import app.olauncher.data.AppModel
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Folders of apps on home, made by dropping an app icon on another one. Each folder sits on the
 * home grid as a pseudo-app (package "appfolder:<id>"), so it can be moved, renamed and get a custom
 * icon like an app; without one its icon shows the first four apps inside.
 */
object AppFolders {

    const val PREFIX = "appfolder:"
    private const val PREFS_FILE = "app.minimalauncher.appfolders"
    private const val KEY = "FOLDERS"

    /** An app inside a folder, as a home icon names it (user is the UserHandle's text). */
    data class Member(val packageName: String, val activityClassName: String?, val user: String)

    class Folder(val id: String, val name: String, val apps: List<Member>) {
        val packageName get() = PREFIX + id
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun isAppFolder(packageName: String?) = packageName?.startsWith(PREFIX) == true

    fun all(context: Context): List<Folder> = try {
        val array = JSONArray(prefs(context).getString(KEY, "[]"))
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            val members = o.optJSONArray("apps") ?: JSONArray()
            Folder(
                o.getString("id"),
                o.optString("name"),
                List(members.length()) { j ->
                    val m = members.getJSONObject(j)
                    Member(m.getString("p"), m.optString("c").ifBlank { null }, m.optString("u"))
                },
            )
        }
    } catch (e: Exception) {
        e.printStackTrace()
        emptyList()
    }

    private fun save(context: Context, folders: List<Folder>) {
        val array = JSONArray()
        folders.forEach { f ->
            val members = JSONArray()
            f.apps.forEach { members.put(JSONObject().put("p", it.packageName).put("c", it.activityClassName ?: "").put("u", it.user)) }
            array.put(JSONObject().put("id", f.id).put("name", f.name).put("apps", members))
        }
        prefs(context).edit { putString(KEY, array.toString()) }
    }

    fun get(context: Context, packageName: String?): Folder? =
        if (!isAppFolder(packageName)) null else all(context).firstOrNull { it.packageName == packageName }

    fun create(context: Context, name: String, apps: List<Member>): Folder {
        val folder = Folder(UUID.randomUUID().toString(), name, apps.distinct())
        save(context, all(context) + folder)
        return folder
    }

    fun update(context: Context, folder: Folder) = save(context, all(context).map { if (it.id == folder.id) folder else it })

    fun rename(context: Context, folder: Folder, name: String) = update(context, Folder(folder.id, name, folder.apps))

    fun setApps(context: Context, folder: Folder, apps: List<Member>) = update(context, Folder(folder.id, folder.name, apps.distinct()))

    fun remove(context: Context, id: String) {
        val folder = all(context).firstOrNull { it.id == id } ?: return
        CustomIcons.reset(context, folder.packageName, null, Process.myUserHandle())
        save(context, all(context).filterNot { it.id == id })
    }

    /** The folder as an app, to reuse the icon chooser and the home icon views. */
    fun toApp(folder: Folder) = AppModel.App(
        appLabel = folder.name,
        key = null,
        appPackage = folder.packageName,
        activityClassName = null,
        user = Process.myUserHandle(),
    )

    /**
     * The folder's own icon: a translucent rounded square with the first four apps in a 2x2 grid.
     * [loadIcon] gives each app's icon at the requested size. Blocking.
     */
    fun previewIcon(sizePx: Int, apps: List<AppModel.App>, loadIcon: (AppModel.App, Int) -> Bitmap?): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40A0A0A0 }
        val radius = sizePx * 0.27f
        canvas.drawRoundRect(RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()), radius, radius, paint)
        val pad = sizePx * 0.12f
        val gap = sizePx * 0.06f
        val sub = ((sizePx - 2 * pad - gap) / 2).toInt().coerceAtLeast(1)
        apps.take(4).forEachIndexed { i, app ->
            val icon = loadIcon(app, sub) ?: return@forEachIndexed
            val x = pad + (i % 2) * (sub + gap)
            val y = pad + (i / 2) * (sub + gap)
            canvas.drawBitmap(icon, null, RectF(x, y, x + sub, y + sub), null)
        }
        return bitmap
    }
}
