package app.olauncher.helper

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.UserHandle
import androidx.core.graphics.drawable.toBitmap
import app.olauncher.data.AppModel
import app.olauncher.data.shortcutIdentity

/**
 * Pinned shortcuts (e.g. a web app added from Chrome that can't be installed as an app) on home:
 * each one is shown as a pseudo-app with package "shortcut:<package>/<id>", like the folder
 * shortcuts, so it can sit on the home grid and in the dock, get a custom icon and be renamed.
 */
object HomeShortcuts {

    const val PREFIX = "shortcut:"

    fun isShortcut(packageName: String?) = packageName?.startsWith(PREFIX) == true

    fun toApp(shortcut: AppModel.PinnedShortcut) = AppModel.App(
        appLabel = shortcut.appLabel,
        key = shortcut.key,
        appPackage = PREFIX + shortcut.appPackage + "/" + shortcut.shortcutId,
        activityClassName = null,
        user = shortcut.user,
    )

    /** The real package and the shortcut id of a pseudo-app's package, or null. */
    private fun parts(packageName: String): Pair<String, String>? {
        if (!isShortcut(packageName)) return null
        val rest = packageName.removePrefix(PREFIX)
        val slash = rest.indexOf('/')
        if (slash <= 0) return null
        return rest.substring(0, slash) to rest.substring(slash + 1)
    }

    /** The pinned shortcut a pseudo-app stands for, to launch it. */
    fun toPinned(app: AppModel.App): AppModel.PinnedShortcut? = parts(app.appPackage)?.let { (pkg, id) ->
        AppModel.PinnedShortcut(appLabel = app.appLabel, key = null, appPackage = pkg, shortcutId = id, user = app.user)
    }

    /** The key the drawer's "Rename" uses for this shortcut, so both show the same name. */
    fun renameKey(app: AppModel.App): String? = parts(app.appPackage)?.let { (pkg, id) ->
        shortcutIdentity(pkg, id, app.user.toString())
    }

    private fun info(context: Context, packageName: String, user: UserHandle): ShortcutInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return null
        val (pkg, id) = parts(packageName) ?: return null
        val launcher = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps ?: return null
        val query = LauncherApps.ShortcutQuery().apply {
            setPackage(pkg)
            setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
        }
        return try {
            launcher.getShortcuts(query, user)?.firstOrNull { it.id == id }
        } catch (e: Exception) {
            null
        }
    }

    /** The shortcut's own icon (the web app's), or null to fall back to the app's icon. */
    fun icon(context: Context, packageName: String, user: UserHandle, sizePx: Int): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return null
        val shortcut = info(context, packageName, user) ?: return null
        val launcher = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps ?: return null
        return try {
            launcher.getShortcutIconDrawable(shortcut, context.resources.displayMetrics.densityDpi)
                ?.toBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            null
        }
    }

    /** The icon of the app that made the shortcut (e.g. Chrome), when the shortcut has none. */
    fun ownerPackage(packageName: String): String? = parts(packageName)?.first
}
