package app.olauncher.helper

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.edit
import app.olauncher.R
import app.olauncher.data.AppModel
import org.json.JSONObject

/**
 * Nova-style swipe actions on home app icons: a swipe up or down on an app opens another app, an
 * app shortcut (e.g. a WhatsApp chat) or a file instead of the app itself. Set from the app's
 * long-press menu; stored per app (package|user) and direction.
 */
object SwipeActions {

    class Action(
        val type: String,
        val label: String,
        val packageName: String? = null,
        val className: String? = null,
        val user: String? = null,
        val shortcutId: String? = null,
        val uri: String? = null,
        val mime: String? = null,
    ) {
        fun toJson(): String = JSONObject().put("type", type).put("label", label).put("pkg", packageName).put("cls", className)
            .put("user", user).put("shortcut", shortcutId).put("uri", uri).put("mime", mime).toString()

        companion object {
            fun fromJson(raw: String): Action? = try {
                val o = JSONObject(raw)
                Action(
                    o.getString("type"), o.optString("label"), o.optString("pkg").ifBlank { null }, o.optString("cls").ifBlank { null },
                    o.optString("user").ifBlank { null }, o.optString("shortcut").ifBlank { null },
                    o.optString("uri").ifBlank { null }, o.optString("mime").ifBlank { null },
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    const val TYPE_APP = "app"
    const val TYPE_SHORTCUT = "shortcut"
    const val TYPE_FILE = "file"
    const val TYPE_FOLDER = "folder"

    /** Opens the system file picker and calls back with the chosen file (set by the home screen). */
    var filePicker: (((Uri) -> Unit) -> Unit)? = null

    /** Opens the system folder picker and calls back with the folder's path (set by the home screen). */
    var folderPicker: (((String) -> Unit) -> Unit)? = null

    /** Reloads home after folder shortcuts change (set by the home screen). */
    var onFoldersChanged: (() -> Unit)? = null

    private fun prefs(context: Context) = context.getSharedPreferences("app.minimalauncher.swipe", Context.MODE_PRIVATE)

    private fun key(app: AppModel.App, up: Boolean) = "${app.appPackage}|${app.user}|" + if (up) "up" else "down"

    fun get(context: Context, app: AppModel.App, up: Boolean): Action? =
        prefs(context).getString(key(app, up), null)?.let { Action.fromJson(it) }

    fun set(context: Context, app: AppModel.App, up: Boolean, action: Action?) = prefs(context).edit {
        if (action == null) remove(key(app, up)) else putString(key(app, up), action.toJson())
    }

    // ---------------------------------------------------------------- running

    /** Runs the action set for [app] in that direction; false when there is none. */
    fun run(context: Context, app: AppModel.App, up: Boolean): Boolean {
        val action = get(context, app, up) ?: return false
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        try {
            when (action.type) {
                TYPE_FOLDER -> FolderShortcuts.openStored(context, action.uri ?: return false)

                TYPE_APP -> {
                    val pkg = action.packageName ?: return false
                    if (FolderShortcuts.isFolder(pkg)) {
                        FolderShortcuts.open(context, pkg)
                        return true
                    }
                    if (!DetoxManager.isAllowed(context, pkg)) {
                        context.showToast(context.getString(R.string.ml_detox_blocked))
                        return true
                    }
                    val user = getUserHandleFromString(context, action.user.orEmpty())
                    val component = action.className?.let { ComponentName(pkg, it) }
                        ?: launcherApps.getActivityList(pkg, user).firstOrNull()?.componentName ?: return false
                    launcherApps.startMainActivity(component, user, null, null)
                }

                TYPE_SHORTCUT -> {
                    val pkg = action.packageName ?: return false
                    if (!DetoxManager.isAllowed(context, pkg)) {
                        context.showToast(context.getString(R.string.ml_detox_blocked))
                        return true
                    }
                    val user = getUserHandleFromString(context, action.user.orEmpty())
                    val shortcut = shortcutsOf(context, pkg, user).firstOrNull { it.id == action.shortcutId }
                    if (shortcut == null) context.showToast(context.getString(R.string.shortcut_not_found))
                    else launcherApps.startShortcut(shortcut, null, null)
                }

                TYPE_FILE -> context.startActivity(
                    Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(action.uri), action.mime ?: "*/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            context.showToast(context.getString(R.string.ml_search_cannot_open))
        }
        return true
    }

    /** Shortcuts an app offers (static and dynamic), as a launcher sees them. */
    private fun shortcutsOf(context: Context, pkg: String, user: android.os.UserHandle): List<ShortcutInfo> {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        if (!launcherApps.hasShortcutHostPermission()) return emptyList()
        val query = LauncherApps.ShortcutQuery().setPackage(pkg).setQueryFlags(
            LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
        )
        return try {
            launcherApps.getShortcuts(query, user).orEmpty().filter { it.isEnabled }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---------------------------------------------------------------- settings

    /** The two long-press menu entries ("Swipe up: …", "Swipe down: …") for [app]. */
    fun menuEntries(context: Context, app: AppModel.App, apps: () -> List<AppModel.App>): List<MenuEntry> =
        listOf(true, false).map { up ->
            val current = get(context, app, up)?.label ?: context.getString(R.string.ml_swipe_none)
            MenuEntry(context.getString(if (up) R.string.ml_swipe_up_format else R.string.ml_swipe_down_format, current)) {
                choose(context, app, up, apps)
            }
        }

    private fun choose(context: Context, app: AppModel.App, up: Boolean, apps: () -> List<AppModel.App>) {
        val title = context.getString(if (up) R.string.ml_swipe_up else R.string.ml_swipe_down)
        val save: (Action?) -> Unit = { set(context, app, up, it) }
        val entries = mutableListOf(
            MenuEntry(context.getString(R.string.ml_swipe_none)) { save(null) },
            MenuEntry(context.getString(R.string.ml_swipe_open_app)) {
                pickApp(context, apps(), context.getString(R.string.ml_swipe_open_app)) { target ->
                    save(Action(TYPE_APP, target.appLabel, target.appPackage, target.activityClassName, target.user.toString()))
                }
            },
        )
        if (shortcutsOf(context, app.appPackage, app.user).isNotEmpty())
            entries += MenuEntry(context.getString(R.string.ml_swipe_shortcut_of, app.appLabel)) { pickShortcut(context, app, save) }
        entries += MenuEntry(context.getString(R.string.ml_swipe_shortcut_other)) {
            val withShortcuts = apps().filter { shortcutsOf(context, it.appPackage, it.user).isNotEmpty() }
            pickApp(context, withShortcuts, context.getString(R.string.ml_swipe_shortcut_other)) { target -> pickShortcut(context, target, save) }
        }
        folderPicker?.let { picker ->
            entries += MenuEntry(context.getString(R.string.ml_swipe_open_folder)) {
                picker { path -> save(Action(TYPE_FOLDER, app.olauncher.ui.FileExplorerActivity.folderName(context, path), uri = path)) }
            }
        }
        filePicker?.let { picker ->
            entries += MenuEntry(context.getString(R.string.ml_swipe_open_file)) {
                picker { uri ->
                    try {
                        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (e: Exception) {
                        // Some providers don't offer lasting access: the file may stop opening later
                    }
                    save(Action(TYPE_FILE, fileName(context, uri), uri = uri.toString(), mime = context.contentResolver.getType(uri)))
                }
            }
        }
        context.showMenuDialog(title, entries)
    }

    private fun pickApp(context: Context, apps: List<AppModel.App>, title: String, onPick: (AppModel.App) -> Unit) {
        val sorted = apps.distinctBy { "${it.appPackage}|${it.activityClassName}|${it.user}" }.sortedBy { it.appLabel.lowercase() }
        context.showMenuDialog(title, sorted.map { app -> MenuEntry(app.appLabel) { onPick(app) } })
    }

    private fun pickShortcut(context: Context, app: AppModel.App, save: (Action?) -> Unit) {
        val shortcuts = shortcutsOf(context, app.appPackage, app.user)
        context.showMenuDialog(app.appLabel, shortcuts.map { s ->
            val label = (s.shortLabel ?: s.longLabel ?: s.id).toString()
            MenuEntry(label) {
                save(Action(TYPE_SHORTCUT, "${app.appLabel}: $label", app.appPackage, user = app.user.toString(), shortcutId = s.id))
            }
        })
    }

    private fun fileName(context: Context, uri: Uri): String = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()
    } catch (e: Exception) {
        uri.lastPathSegment.orEmpty()
    }
}
