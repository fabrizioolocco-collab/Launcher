package app.olauncher.helper

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Process
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.drawable.toBitmap
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.ui.AlbumActivity
import app.olauncher.ui.FileExplorerActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Folder shortcuts on home: each one is shown as a pseudo-app (package "folder:<id>"), so it can sit
 * on the home grid, get a custom icon and swipe actions. Tapping it
 * opens the folder in the built-in explorer or in the file manager (Solid Explorer).
 */
object FolderShortcuts {

    const val PREFIX = "folder:"

    class Folder(val id: String, val path: String, val name: String, val inExplorer: Boolean)

    private fun prefs(context: Context) = context.getSharedPreferences("app.minimalauncher.folders", Context.MODE_PRIVATE)

    fun isFolder(packageName: String?) = packageName?.startsWith(PREFIX) == true

    fun all(context: Context): List<Folder> = try {
        val array = JSONArray(prefs(context).getString("FOLDERS", "[]"))
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            Folder(o.getString("id"), o.getString("path"), o.getString("name"), o.optBoolean("explorer", true))
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(context: Context, folders: List<Folder>) {
        val array = JSONArray()
        folders.forEach { array.put(JSONObject().put("id", it.id).put("path", it.path).put("name", it.name).put("explorer", it.inExplorer)) }
        prefs(context).edit { putString("FOLDERS", array.toString()) }
    }

    fun get(context: Context, packageName: String): Folder? =
        all(context).firstOrNull { PREFIX + it.id == packageName }

    fun add(context: Context, path: String, name: String, inExplorer: Boolean): Folder {
        val folder = Folder(UUID.randomUUID().toString(), path, name, inExplorer)
        save(context, all(context) + folder)
        return folder
    }

    fun update(context: Context, folder: Folder) = save(context, all(context).map { if (it.id == folder.id) folder else it })

    fun remove(context: Context, id: String) = save(context, all(context).filterNot { it.id == id })

    /** The folder shortcuts as apps, added to the home app list. */
    fun asApps(context: Context): List<AppModel.App> = all(context).map { toApp(it) }

    fun toApp(folder: Folder) = AppModel.App(
        appLabel = folder.name,
        key = null,
        appPackage = PREFIX + folder.id,
        activityClassName = null,
        user = Process.myUserHandle(),
    )

    fun open(context: Context, packageName: String) {
        val folder = get(context, packageName) ?: return
        openStored(context, folder.path, folder.inExplorer)
    }

    /** Opens a stored folder path: a phone folder, or a OneDrive folder (always in the built-in explorer). */
    fun openStored(context: Context, path: String, inExplorer: Boolean = true) {
        if (FileExplorerActivity.isCloudPath(path)) FileExplorerActivity.openCloudPath(context, path)
        else openPath(context, File(path), inExplorer)
    }

    /** Opens a phone folder in the built-in explorer or in the file manager. */
    fun openPath(context: Context, dir: File, inExplorer: Boolean = true) {
        if (inExplorer) {
            // A folder with only photos and videos opens as an album grid, like in the explorer
            if (MediaThumbnails.albumMedia(dir) != null) AlbumActivity.open(context, dir)
            else FileExplorerActivity.openLocal(context, dir)
            return
        }
        val intents = listOf(
            android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(dir), "resource/folder")
                .setPackage("pl.solidexplorer2"),
            android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(dir), "resource/folder"),
        )
        // file:// URIs to other apps are refused by default; folders have no content:// equivalent
        val policy = android.os.StrictMode.getVmPolicy()
        android.os.StrictMode.setVmPolicy(android.os.StrictMode.VmPolicy.Builder().build())
        try {
            for (intent in intents) {
                try {
                    context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    return
                } catch (e: Exception) {
                    // try the next one
                }
            }
        } finally {
            android.os.StrictMode.setVmPolicy(policy)
        }
        FileExplorerActivity.openLocal(context, dir)
    }

    /** Default icon: an outline folder in the theme's text colour. */
    fun icon(context: Context, sizePx: Int): Bitmap? {
        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_folder_outline)?.mutate() ?: return null
        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        DrawableCompat.setTint(drawable, if (night) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
        return drawable.toBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    }

    // ---------------------------------------------------------------- menu

    /** Long-press menu entries of a folder shortcut: opener, rename, delete. */
    fun menuEntries(context: Context, app: AppModel.App, onChanged: () -> Unit): List<MenuEntry> {
        val folder = get(context, app.appPackage) ?: return emptyList()
        return listOfNotNull(
            // OneDrive folders open only in the built-in explorer
            if (FileExplorerActivity.isCloudPath(folder.path)) null else MenuEntry(
                context.getString(
                    R.string.ml_folder_opens_in,
                    context.getString(if (folder.inExplorer) R.string.ml_explorer else R.string.ml_file_manager)
                )
            ) {
                update(context, Folder(folder.id, folder.path, folder.name, !folder.inExplorer))
                onChanged()
            },
            MenuEntry(context.getString(R.string.rename)) {
                context.showTextInputDialog(context.getString(R.string.rename), folder.name, folder.name, context.getString(R.string.ml_save)) { name ->
                    update(context, Folder(folder.id, folder.path, name, folder.inExplorer))
                    onChanged()
                }
            },
            MenuEntry(context.getString(R.string.ml_folder_delete)) {
                remove(context, folder.id)
                onChanged()
            },
        )
    }
}
