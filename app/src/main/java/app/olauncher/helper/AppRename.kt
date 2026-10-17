package app.olauncher.helper

import android.content.Context
import android.content.pm.LauncherApps
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Prefs

/**
 * Renaming an app from its home menu (grid or section). The name is the same one the drawer's
 * "Rename" sets, so the app shows it everywhere; an empty name gives it back its own.
 */
object AppRename {

    /** Set by the home: reloads the apps so the new name shows. */
    var onRenamed: (() -> Unit)? = null

    /** Folder shortcuts have their own name, test apps none to keep. */
    fun canRename(app: AppModel.App) =
        !FolderShortcuts.isFolder(app.appPackage) && !TestNotifications.isFake(app.appPackage)

    fun menuEntry(context: Context, app: AppModel.App): MenuEntry? =
        if (canRename(app)) MenuEntry(context.getString(R.string.rename)) { ask(context, app) } else null

    fun ask(context: Context, app: AppModel.App) {
        context.showTextInputDialog(
            title = context.getString(R.string.rename),
            initial = app.appLabel,
            hint = originalName(context, app),
            action = context.getString(R.string.ml_save),
            allowBlank = true,
        ) { label ->
            Prefs(context).setAppRenameLabel(app.appPackage, label)
            onRenamed?.invoke()
        }
    }

    private fun originalName(context: Context, app: AppModel.App): String {
        if (ParkingShortcut.isParking(app.appPackage)) return context.getString(R.string.ml_parking)
        return try {
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            launcherApps.getActivityList(app.appPackage, app.user).firstOrNull()?.label?.toString() ?: app.appLabel
        } catch (e: Exception) {
            app.appLabel
        }
    }
}
