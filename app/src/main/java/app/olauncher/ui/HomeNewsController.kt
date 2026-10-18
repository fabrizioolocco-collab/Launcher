package app.olauncher.ui

import android.content.Context
import android.net.Uri
import android.view.View
import androidx.core.view.isVisible
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Prefs
import app.olauncher.helper.AppNotifications
import app.olauncher.helper.DetoxManager
import app.olauncher.helper.IconChooser
import app.olauncher.helper.MenuEntry
import app.olauncher.helper.SwipeActions
import app.olauncher.helper.FolderShortcuts
import app.olauncher.helper.AppRename
import app.olauncher.helper.ParkingShortcut
import app.olauncher.helper.NotificationRepository
import app.olauncher.helper.openAppInfo
import app.olauncher.helper.showMenuDialog
import app.olauncher.helper.showToast
import app.olauncher.helper.uninstallEntry
import app.olauncher.ui.widgets.HomeWidgetsController
import kotlinx.coroutines.CoroutineScope

/**
 * Hands the apps with notifications to the grid's news strip and shows their menu, plus the home
 * menu (long press on empty home space) and the hint shown while the home is empty.
 */
class HomeNewsController(
    private val context: Context,
    private val grid: HomeWidgetsController,
    private val emptyHint: View,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    /** Opens the system photo picker and calls back with the chosen image. */
    private val pickImage: ((Uri) -> Unit) -> Unit,
    /** Keys (package|user) of apps placed as icons on the home grids (they show their own badge). */
    private val gridAppKeys: () -> Set<String> = { emptySet() },
    /** The dock above the search bar: app menus offer to put an app there. */
    private val dock: DockController? = null,
) {
    private var apps: List<AppModel.App> = emptyList()
    private var appsLoaded = false
    private var notifications: Map<String, AppNotifications> = emptyMap()

    fun setApps(list: List<AppModel.App>) {
        apps = list
        appsLoaded = true
        render()
    }

    /**
     * Apps with notifications (hidden apps included, grid icons excluded): shown in the news strip,
     * newest first, until their notifications are gone.
     */
    private fun newsApps(): List<AppModel.App> {
        if (notifications.isEmpty()) return emptyList()
        val onGrid = gridAppKeys()
        return apps
            .filter { notificationCount(it) > 0 }
            .filter { NotificationRepository.key(it.appPackage, it.user.toString()) !in onGrid }
            .filter { DetoxManager.isAllowed(context, it.appPackage) }
            .distinctBy { NotificationRepository.key(it.appPackage, it.user.toString()) }
            .sortedWith(
                if (prefs.newsSortByCount) compareByDescending<AppModel.App> { notificationCount(it) }
                else compareByDescending { notifications[NotificationRepository.key(it.appPackage, it.user.toString())]?.lastPostTime ?: 0L }
            )
    }

    fun setNotifications(map: Map<String, AppNotifications>) {
        if (map == notifications) return
        notifications = map
        render()
    }

    private fun notificationCount(app: AppModel.App): Int =
        notifications[NotificationRepository.key(app.appPackage, app.user.toString())]?.count ?: 0

    fun render() {
        if (!appsLoaded) {
            emptyHint.isVisible = false
            return
        }
        emptyHint.isVisible = grid.itemCount() == 0
        grid.setNews(newsApps()) { app -> showAppMenu(app) }
    }

    // ---------------------------------------------------------------- menus

    /** Long press on an empty cell of the home grid: what to put there (or elsewhere on home). */
    fun showHomeMenu(openSettings: () -> Unit, addApp: () -> Unit, addWidget: () -> Unit, addFolder: () -> Unit, parking: () -> Unit, detox: () -> Unit) {
        context.showMenuDialog(
            context.getString(R.string.app_name),
            listOf(
                MenuEntry(context.getString(R.string.ml_add_app), section = context.getString(R.string.ml_menu_add)) { addApp() },
                MenuEntry(context.getString(R.string.ml_add_widget)) { addWidget() },
                MenuEntry(context.getString(R.string.ml_folder_new_ellipsis)) { addFolder() },
                MenuEntry(context.getString(R.string.ml_dock_add)) { dock?.showAddDialog() },
                MenuEntry(context.getString(R.string.ml_parking_menu), section = context.getString(R.string.ml_menu_tools)) { parking() },
                MenuEntry(context.getString(R.string.ml_news_zone_show)) { grid.showNewsStrip() },
                MenuEntry(context.getString(R.string.ml_explorer)) { FileExplorerActivity.openRoots(context) },
                MenuEntry(context.getString(R.string.ml_detox)) { detox() },
                MenuEntry(context.getString(R.string.ml_settings), section = "") { openSettings() },
            )
        )
    }

    /** Common end of the app menus: swipe actions and detox; folder shortcuts get their own entries instead. */
    private fun appMenuTail(app: AppModel.App, menu: List<MenuEntry>): List<MenuEntry> {
        // Rename goes right before "Change icon"
        val entries = AppRename.menuEntry(context, app)?.let { rename ->
            val at = menu.indexOfFirst { it.label == context.getString(R.string.ml_change_icon) }.takeIf { it >= 0 } ?: menu.size
            menu.toMutableList().apply { add(at, rename) }
        } ?: menu
        if (ParkingShortcut.isParking(app.appPackage)) {
            val info = context.getString(R.string.ml_app_info)
            val activity = context as? android.app.Activity ?: return entries.filter { it.label != info }
            return ParkingShortcut.menuEntries(activity) + entries.filter { it.label != info }
        }
        if (FolderShortcuts.isFolder(app.appPackage)) {
            val info = context.getString(R.string.ml_app_info)
            return entries.filter { it.label != info } +
                    FolderShortcuts.menuEntries(context, app) { SwipeActions.onFoldersChanged?.invoke() } +
                    SwipeActions.menuEntries(context, app) { apps }
        }
        return entries + SwipeActions.menuEntries(context, app) { apps } + DetoxController.whitelistEntry(context, app.appPackage)
    }

    /** "Add to dock", unless it's there already or full. */
    private fun dockEntry(app: AppModel.App): MenuEntry? {
        val dock = dock ?: return null
        if (dock.contains(app) || dock.isFull()) return null
        return MenuEntry(context.getString(R.string.ml_dock_add_app)) { dock.add(app) }
    }

    /** Menu of an app in the news strip (shown there only because of its notifications). */
    private fun showAppMenu(app: AppModel.App) {
        val entries = mutableListOf(
            MenuEntry(context.getString(R.string.ml_add_to_home)) {
                if (grid.addApp(app)) render()
                else context.showToast(context.getString(R.string.ml_already_on_home))
            },
            MenuEntry(context.getString(R.string.ml_change_icon)) {
                IconChooser.show(context, app, scope, pickImage) { render() }
            },
            MenuEntry(context.getString(R.string.ml_app_info)) {
                openAppInfo(context, app.user, app.appPackage)
            },
        )
        context.uninstallEntry(app.appPackage, app.user)?.let { entries += it }
        dockEntry(app)?.let { entries.add(1, it) }
        context.showMenuDialog(app.appLabel, appMenuTail(app, entries), header = AppNotificationsView.create(context, app))
    }
}
