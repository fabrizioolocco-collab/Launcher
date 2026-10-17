package app.olauncher.ui.bookmarks

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Process
import androidx.core.content.edit
import app.olauncher.R
import app.olauncher.helper.MenuEntry
import app.olauncher.helper.showMenuDialog
import app.olauncher.helper.showToast

/**
 * Samsung Internet: the browser bookmarks open in, and the button left of the home search that
 * shows its open tabs. The browser has no public way to open its tab list, so the button uses
 * one of its shortcuts (the ones its icon shows on a long press): the one about tabs if there is
 * one, or the one picked with a long press on the button; otherwise it opens the browser, on the
 * last tab.
 */
object SamsungBrowser {
    private val PACKAGES = listOf("com.sec.android.app.sbrowser", "com.sec.android.app.sbrowser.beta")
    private const val PREFS = "app.minimalauncher.bookmarks"
    private const val KEY_SHORTCUT = "sbrowser_tabs_shortcut"

    /** The installed Samsung Internet's package, or null. */
    fun installed(context: Context): String? = PACKAGES.firstOrNull { pkg ->
        try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun shortcuts(context: Context, pkg: String): List<ShortcutInfo> {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        return try {
            if (!launcherApps.hasShortcutHostPermission()) return emptyList()
            val query = LauncherApps.ShortcutQuery().setPackage(pkg).setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
            )
            launcherApps.getShortcuts(query, Process.myUserHandle()).orEmpty().filter { it.isEnabled }.distinctBy { it.id }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun label(s: ShortcutInfo) = (s.shortLabel ?: s.longLabel ?: s.id).toString()

    /** A shortcut that looks like the tab list (not "new tab"). */
    private fun tabsShortcut(list: List<ShortcutInfo>): ShortcutInfo? = list.firstOrNull { s ->
        val text = "${s.id} ${s.shortLabel} ${s.longLabel}".lowercase()
        val tabs = listOf("tab_manager", "tabmanager", "tab manager", "open tabs", "schede", "tabs").any { it in text }
        val fresh = listOf("new", "nuov", "secret", "segret").any { it in text }
        tabs && !fresh
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Opens the open tabs (see the class comment). */
    fun openTabs(context: Context) {
        val pkg = installed(context) ?: run {
            context.showToast(context.getString(R.string.ml_sb_missing))
            return
        }
        val list = shortcuts(context, pkg)
        val saved = prefs(context).getString(KEY_SHORTCUT, null)
        val shortcut = if (saved == "") null else list.firstOrNull { it.id == saved } ?: tabsShortcut(list)
        if (shortcut != null && start(context, shortcut)) return
        launchApp(context, pkg)
    }

    private fun start(context: Context, shortcut: ShortcutInfo): Boolean = try {
        context.getSystemService(LauncherApps::class.java).startShortcut(shortcut, null, null)
        true
    } catch (e: Exception) {
        false
    }

    private fun launchApp(context: Context, pkg: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return
        try {
            context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            context.showToast(context.getString(R.string.ml_sb_missing))
        }
    }

    /** Long press: which of the browser's shortcuts the button opens ("the browser" = none). */
    fun chooseTabsShortcut(context: Context) {
        val pkg = installed(context) ?: run {
            context.showToast(context.getString(R.string.ml_sb_missing))
            return
        }
        val list = shortcuts(context, pkg)
        val saved = prefs(context).getString(KEY_SHORTCUT, null)
        val current = if (saved == "") null else list.firstOrNull { it.id == saved } ?: tabsShortcut(list)
        val entries = mutableListOf(
            MenuEntry(context.getString(R.string.ml_sb_open_app), checked = null, value = if (current == null) "✓" else null) {
                prefs(context).edit { putString(KEY_SHORTCUT, "") }
            }
        )
        list.forEach { s ->
            entries += MenuEntry(label(s), value = if (s.id == current?.id) "✓" else null) {
                prefs(context).edit { putString(KEY_SHORTCUT, s.id) }
                start(context, s)
            }
        }
        context.showMenuDialog(context.getString(R.string.ml_sb_choose), entries)
    }
}
