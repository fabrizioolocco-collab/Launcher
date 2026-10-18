package app.olauncher.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.isVisible
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.HomeSectionsRepository
import app.olauncher.data.Prefs
import app.olauncher.data.SectionApp
import app.olauncher.helper.AppNotifications
import app.olauncher.helper.DetoxManager
import app.olauncher.helper.IconChooser
import app.olauncher.helper.IconPackManager
import app.olauncher.helper.MenuEntry
import app.olauncher.helper.NotificationRepository
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.AppRename
import app.olauncher.helper.openAppInfo
import app.olauncher.helper.showMenuDialog
import app.olauncher.helper.showToast
import app.olauncher.helper.uninstallEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The dock: a card above the search bar with one app per column of the home grid, each on its own
 * tile (red when the app has notifications), lined up with the grid's cells. Hidden while empty; apps get there from their menu or the home
 * menu.
 */
class DockController(
    private val context: Context,
    private val container: FrameLayout,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val onLaunch: (AppModel.App) -> Unit,
    private val pickImage: ((Uri) -> Unit) -> Unit,
    /** Called when the dock's apps change (their notifications move between dock and home). */
    private val onChanged: () -> Unit = {},
) {
    private val repo = HomeSectionsRepository(context)
    private var dock: MutableList<SectionApp> = repo.loadDock()
    private var apps: List<AppModel.App> = emptyList()
    private var notifications: Map<String, AppNotifications> = emptyMap()

    fun setApps(list: List<AppModel.App>) {
        apps = list
        render()
    }

    fun setNotifications(map: Map<String, AppNotifications>) {
        if (map == notifications) return
        notifications = map
        render()
    }

    /** Keys (package|user) of the dock's apps: they show their notifications here, not on home. */
    fun appKeys(): Set<String> = dock.map { NotificationRepository.key(it.packageName, it.user) }.toSet()

    fun contains(app: AppModel.App) = dock.any { it.samePackageAndUser(SectionApp.from(app)) }

    /** As many apps as the home grid has columns (6 by default), so the tiles line up with it. */
    private val max get() = prefs.widgetColumns

    fun isFull() = dock.size >= max

    /** Adds [app] at the end; tells why not when it's already there or the dock is full. */
    fun add(app: AppModel.App) {
        when {
            contains(app) -> context.showToast(context.getString(R.string.ml_dock_already))
            isFull() -> context.showToast(context.getString(R.string.ml_dock_full, max))
            else -> {
                dock += SectionApp.from(app)
                save()
            }
        }
    }

    /** Picks an app to add, from all apps. */
    fun showAddDialog() {
        if (isFull()) {
            context.showToast(context.getString(R.string.ml_dock_full, max))
            return
        }
        val entries = apps.filter { !contains(it) }.sortedBy { it.appLabel.lowercase() }.map { app ->
            MenuEntry(app.appLabel) { add(app) }
        }
        context.showMenuDialog(context.getString(R.string.ml_dock_add), entries)
    }

    private fun save() {
        repo.saveDock(dock)
        render()
        onChanged()
    }

    private fun findApp(dockApp: SectionApp): AppModel.App? {
        if (!DetoxManager.isAllowed(context, dockApp.packageName)) return null
        return apps.firstOrNull { dockApp.matches(it) }
            ?: apps.firstOrNull { it.appPackage == dockApp.packageName && it.user.toString() == dockApp.user }
    }

    private fun notificationCount(app: AppModel.App): Int =
        notifications[NotificationRepository.key(app.appPackage, app.user.toString())]?.count ?: 0

    // ---------------------------------------------------------------- rendering

    fun render() {
        container.removeAllViews()
        val shown = dock.mapNotNull { d -> findApp(d)?.let { d to it } }.take(max)
        container.isVisible = shown.isNotEmpty()
        if (!container.isVisible) return
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = UiKit.cardBackground(context, 20)
            // No side padding: the card is as wide as the home grid, the tiles sit in its columns
            setPadding(0, 6.dpToPx(), 0, 6.dpToPx())
            // Long press on the card itself (between the tiles): add an app
            isLongClickable = true
            setOnLongClickListener {
                showAddDialog()
                true
            }
        }
        // One slot per grid column (with the grid's gap around each tile), so they keep their size
        // however many apps there are and stay aligned with the home's icons
        for (i in 0 until max) {
            val lp = LinearLayout.LayoutParams(0, 46.dpToPx(), 1f).apply {
                marginStart = GRID_GAP_DP.dpToPx()
                marginEnd = GRID_GAP_DP.dpToPx()
            }
            val entry = shown.getOrNull(i)
            row.addView(if (entry == null) FrameLayout(context) else tile(i, entry.first, entry.second), lp)
        }
        container.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun tile(index: Int, dockApp: SectionApp, app: AppModel.App) = ImageView(context).apply {
        val notified = notificationCount(app) > 0
        background = GradientDrawable().apply {
            cornerRadius = 16.dpToPx().toFloat()
            if (notified) {
                setColor(TILE_RED)
                setStroke(1.dpToPx(), HomeSectionsController.NOTIFICATION_COLOR)
            } else setColor(context.getColorFromAttr(R.attr.tileColor))
        }
        foreground = UiKit.ripple(context)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = app.appLabel
        isClickable = true
        isLongClickable = true
        setOnClickListener {
            if (notificationCount(app) > 0) NotificationBadge.showNotifications(context, app) { onLaunch(app) }
            else onLaunch(app)
        }
        setOnLongClickListener {
            showAppMenu(index, dockApp, app)
            true
        }
        val size = 28.dpToPx()
        val iconPack = prefs.iconPack
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                IconPackManager.getIcon(context, app.appPackage, app.activityClassName, app.user, iconPack, size)
            } ?: return@launch
            setImageDrawable(IconPackManager.toDrawable(context, bitmap, size))
        }
    }

    private fun showAppMenu(index: Int, dockApp: SectionApp, app: AppModel.App) {
        val position = dock.indexOfFirst { it.samePackageAndUser(dockApp) }.takeIf { it >= 0 } ?: index
        val entries = mutableListOf<MenuEntry>()
        if (position > 0) entries += MenuEntry(context.getString(R.string.ml_dock_move_left)) { move(position, -1) }
        if (position < dock.lastIndex) entries += MenuEntry(context.getString(R.string.ml_dock_move_right)) { move(position, 1) }
        if (!isFull()) entries += MenuEntry(context.getString(R.string.ml_dock_add)) { showAddDialog() }
        entries += MenuEntry(context.getString(R.string.ml_dock_remove)) {
            dock.removeAll { it.samePackageAndUser(dockApp) }
            save()
        }
        entries += MenuEntry(context.getString(R.string.ml_change_icon)) {
            IconChooser.show(context, app, scope, pickImage) { render() }
        }
        AppRename.menuEntry(context, app)?.let { entries += it }
        entries += MenuEntry(context.getString(R.string.ml_app_info)) {
            openAppInfo(context, app.user, app.appPackage)
        }
        context.uninstallEntry(app.appPackage, app.user)?.let { entries += it }
        context.showMenuDialog(app.appLabel, entries, header = AppNotificationsView.create(context, app))
    }

    private fun move(position: Int, delta: Int) {
        val target = position + delta
        if (target !in dock.indices) return
        dock.add(target, dock.removeAt(position))
        save()
    }

    companion object {
        /** The gap [app.olauncher.ui.widgets.WidgetGridLayout] leaves around each cell's content. */
        private const val GRID_GAP_DP = 4
        private const val TILE_RED = 0xFF3A1715.toInt()
    }
}
