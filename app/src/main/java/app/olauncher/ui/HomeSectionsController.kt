package app.olauncher.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.HomeSection
import app.olauncher.data.HomeSectionsRepository
import app.olauncher.data.Prefs
import app.olauncher.data.SectionApp
import app.olauncher.helper.AppNotifications
import app.olauncher.helper.CustomIcons
import app.olauncher.helper.DetoxManager
import app.olauncher.helper.FontManager
import app.olauncher.helper.IconChooser
import app.olauncher.helper.IconPackManager
import app.olauncher.helper.MenuEntry
import app.olauncher.helper.SwipeActions
import app.olauncher.helper.FolderShortcuts
import app.olauncher.helper.AppRename
import app.olauncher.helper.ParkingShortcut
import app.olauncher.helper.NotificationRepository
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getUserHandleFromString
import app.olauncher.helper.openAppInfo
import app.olauncher.helper.showConfirmDialog
import app.olauncher.helper.showIconGridDialog
import app.olauncher.helper.showMenuDialog
import app.olauncher.helper.showMultiSelectDialog
import app.olauncher.helper.showTextInputDialog
import app.olauncher.helper.showToast
import app.olauncher.helper.uninstallEntry
import app.olauncher.ui.widgets.HomeWidgetsController
import app.olauncher.ui.widgets.SectionBlockView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Renders the user's sections on the home grid (one [SectionBlockView] each, handed to [grid] as
 * blocks that the user can drag to another row) and handles all section/app editing. Apps with
 * notifications are handed to the grid's news strip.
 * Data lives in [HomeSectionsRepository]; every change is saved immediately and re-rendered.
 */
class HomeSectionsController(
    private val context: Context,
    private val grid: HomeWidgetsController,
    private val emptyHint: View,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val onLaunch: (AppModel.App) -> Unit,
    /** Opens the system photo picker and calls back with the chosen image. */
    private val pickImage: ((Uri) -> Unit) -> Unit,
    /** Keys (package|user) of apps placed as icons on the home grids (they show their own badge). */
    private val gridAppKeys: () -> Set<String> = { emptySet() },
    /** The dock above the search bar: app menus offer to put an app there. */
    private val dock: DockController? = null,
) {
    private val repo = HomeSectionsRepository(context)
    private var sections: MutableList<HomeSection> = mutableListOf()
    private var apps: List<AppModel.App> = emptyList()
    private var appsLoaded = false
    private var notifications: Map<String, AppNotifications> = emptyMap()

    init {
        migrateOlauncherHomeApps()
        sections = repo.load()
        grid.onBlockMoved = { block ->
            sections.firstOrNull { it.id == block.id }?.row = block.row
            repo.save(sections)
        }
    }

    fun setApps(list: List<AppModel.App>) {
        apps = list
        appsLoaded = true
        render()
    }

    /**
     * Apps with notifications (hidden apps included, grid icons excluded): shown in red at the top
     * of home, newest first, until their notifications are gone. Apps from sections leave their
     * place meanwhile and go back to it afterwards.
     */
    private fun looseApps(): List<AppModel.App> {
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

    /** Closes every open section (tap on empty home space). */
    fun collapseAll() {
        if (sections.all { it.collapsed }) return
        sections.forEach { it.collapsed = true }
        save()
    }

    /** Re-reads sections from storage (they can change from the app drawer) and redraws. */
    fun reload() {
        sections = repo.load()
        render()
    }

    private fun save() {
        repo.save(sections)
        render()
    }

    /** The installed app for a section entry; null if missing or blocked by a detox session. */
    private fun findApp(sectionApp: SectionApp): AppModel.App? {
        if (!DetoxManager.isAllowed(context, sectionApp.packageName)) return null
        return apps.firstOrNull { sectionApp.matches(it) }
            ?: apps.firstOrNull { it.appPackage == sectionApp.packageName && it.user.toString() == sectionApp.user }
    }

    // ---------------------------------------------------------------- rendering

    fun render() {
        if (!appsLoaded) {
            emptyHint.isVisible = false
            return
        }
        emptyHint.isVisible = sections.isEmpty() && grid.itemCount() == 0
        val gravity = prefs.homeAlignment
        val detox = DetoxManager.isActive(context)
        // Apps with notifications first (newest first), above the grid. They are shown only up
        // there, not in their section.
        val loose = looseApps()
        val notifiedKeys = loose.map { NotificationRepository.key(it.appPackage, it.user.toString()) }.toSet()
        fun atItsPlace(app: AppModel.App) = NotificationRepository.key(app.appPackage, app.user.toString()) !in notifiedKeys
        grid.setNews(loose) { app -> showLooseAppMenu(app) }
        val blocks = sections.mapIndexedNotNull { index, section ->
            // During detox, sections without allowed apps disappear
            if (detox && section.apps.none { findApp(it) != null }) return@mapIndexedNotNull null
            val block = SectionBlockView(context)
            block.setPadding(BLOCK_PADDING_DP.dpToPx(), 0, BLOCK_PADDING_DP.dpToPx(), 6.dpToPx())
            val header = headerView(section, gravity)
            block.addView(header)
            block.header = header
            if (!section.collapsed) {
                val views = section.apps.mapNotNull { sectionApp ->
                    findApp(sectionApp)?.takeIf(::atItsPlace)?.let { appView(index, sectionApp, it, gravity) }
                }
                addAppRows(block, views, gravity)
                if (section.apps.none { findApp(it) != null }) block.addView(hintView(gravity))
            }
            HomeWidgetsController.Block(section.id, section.row, block) { showSectionMenu(index) }
        }
        grid.setBlocks(blocks)
    }

    private fun lp(gravity: Int, topMargin: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            this.gravity = gravity
            this.topMargin = topMargin
        }

    private fun headerView(section: HomeSection, gravity: Int): View =
        TextView(context, null, 0, R.style.TextSmall).apply {
            val arrow = if (section.collapsed) "▸" else "▾"
            val header = context.getString(R.string.ml_section_header_format, section.name, arrow)
            val total = sectionNotificationCount(section)
            text = if (section.collapsed && total > 0) "$header  " + context.getString(R.string.ml_section_news, total) else header
            setTypeface(typeface, Typeface.BOLD)
            FontManager.apply(this, prefs.sectionFont, prefs.sectionTextSize)
            letterSpacing = 0.06f
            if (total > 0) setTextColor(NOTIFICATION_COLOR) else alpha = 0.7f
            this.gravity = gravity
            setPadding(0, 10.dpToPx(), 0, 6.dpToPx())
            isClickable = true
            // The long press belongs to the block: it drags the section, or opens its menu
            isLongClickable = false
            setOnClickListener {
                // Accordion: opening a section closes the others
                val open = section.collapsed
                sections.forEach { it.collapsed = true }
                section.collapsed = !open
                save()
            }
            layoutParams = lp(gravity)
        }

    private fun hintView(gravity: Int): View =
        TextView(context, null, 0, R.style.TextSmall).apply {
            setText(R.string.ml_section_empty_hint)
            alpha = 0.45f
            setPadding(0, 4.dpToPx(), 0, 4.dpToPx())
            layoutParams = lp(gravity)
        }

    /** One column with names: icon beside the name. More columns or no names: icon above, centered. */
    private fun iconOnTop() = prefs.homeColumns > 1 || !prefs.homeShowLabels

    /** Adds app views to [container], [Prefs.homeColumns] per row. */
    private fun addAppRows(container: LinearLayout, views: List<View>, gravity: Int) {
        val columns = prefs.homeColumns
        if (columns <= 1) {
            views.forEach { container.addView(it) }
            return
        }
        views.chunked(columns).forEach { chunk ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { v ->
                row.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            // Keep cells the same width in the last, shorter row
            repeat(columns - chunk.size) { row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f)) }
            container.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    /** App row/cell. With notifications its name and icon are red, and a tap lists them instead of opening the app. */
    private fun appView(sectionIndex: Int, sectionApp: SectionApp, app: AppModel.App, gravity: Int): View =
        labelView(sectionIndex, sectionApp, app, gravity)

    private fun labelView(sectionIndex: Int, sectionApp: SectionApp, app: AppModel.App, gravity: Int): TextView =
        TextView(context, null, 0, R.style.TextLarge).apply {
            FontManager.apply(this, prefs.appFont, prefs.appTextSize)
            val count = notificationCount(app)
            val onTop = iconOnTop()
            // With notifications: bold red name (and red icon, see loadIcon)
            text = if (prefs.homeShowLabels) badgeText(app.appLabel, if (count > 0) -1 else 0) else ""
            if (onTop) setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            if (count > 0) setTextColor(NOTIFICATION_COLOR)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            contentDescription = app.appLabel
            this.gravity = if (onTop) Gravity.CENTER else Gravity.CENTER_VERTICAL or gravity
            val padV = context.resources.getDimensionPixelSize(R.dimen.home_app_padding_vertical)
            setPadding(0, padV, 0, padV)
            isClickable = true
            isLongClickable = true
            setTag(R.id.ml_swipe_app, app)
            setOnClickListener {
                if (notificationCount(app) > 0) NotificationBadge.showNotifications(context, app) { onLaunch(app) }
                else onLaunch(app)
            }
            setOnLongClickListener {
                if (ParkingShortcut.isParking(app.appPackage) && ParkingShortcut.saved(context) != null) ParkingShortcut.openMaps(context)
                else showAppMenu(sectionIndex, sectionApp, app)
                true
            }
            layoutParams = lp(gravity)
            // Without names the icon is all there is: show it even if icons are turned off
            if (prefs.showIcons || !prefs.homeShowLabels) loadIcon(this, app, onTop)
        }

    /** Apps that appear more than once in a section are counted once. */
    private fun sectionNotificationCount(section: HomeSection): Int =
        section.apps.mapNotNull { findApp(it) }.distinct().sumOf { notificationCount(it) }

    private fun loadIcon(view: TextView, app: AppModel.App, onTop: Boolean = false) {
        val size = if (prefs.homeIconSize > 0) prefs.homeIconSize.dpToPx() else
            if (onTop) 44.dpToPx() else (view.textSize * 1.15f).toInt()
        view.compoundDrawablePadding = if (onTop) 4.dpToPx() else 14.dpToPx()
        fun place(d: android.graphics.drawable.Drawable) =
            if (onTop) view.setCompoundDrawablesRelative(null, d, null, null)
            else view.setCompoundDrawablesRelative(d, null, null, null)
        // Transparent placeholder keeps the text from shifting when the icon arrives
        place(ColorDrawable(0).apply { setBounds(0, 0, size, size) })
        val iconPack = prefs.iconPack
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                IconPackManager.getIcon(context, app.appPackage, app.activityClassName, app.user, iconPack, size)
            } ?: return@launch
            place(NotificationBadge.tint(IconPackManager.toDrawable(context, bitmap, size), notificationCount(app) > 0))
        }
    }

    // ---------------------------------------------------------------- menus

    /** Long press on an empty cell of the home grid: what to put there (or elsewhere on home). */
    fun showHomeMenu(row: Int?, openSettings: () -> Unit, addApp: () -> Unit, addWidget: () -> Unit, addFolder: () -> Unit, parking: () -> Unit, detox: () -> Unit) {
        context.showMenuDialog(
            context.getString(R.string.app_name),
            listOf(
                MenuEntry(context.getString(R.string.ml_add_app), section = context.getString(R.string.ml_menu_add)) { addApp() },
                MenuEntry(context.getString(R.string.ml_add_widget)) { addWidget() },
                MenuEntry(context.getString(R.string.ml_section_new)) { createSection(row = row) },
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

    private fun showSectionMenu(index: Int) {
        val section = sections.getOrNull(index) ?: return
        val entries = mutableListOf(
            MenuEntry(context.getString(R.string.ml_section_rename)) { renameSection(index) },
            MenuEntry(context.getString(R.string.ml_section_edit_apps)) { editSectionApps(index) },
        )
        entries += MenuEntry(context.getString(R.string.ml_section_new)) { createSection() }
        entries += MenuEntry(context.getString(R.string.ml_section_delete)) { deleteSection(index) }
        context.showMenuDialog(section.name, entries)
    }

    private fun showAppMenu(sectionIndex: Int, sectionApp: SectionApp, app: AppModel.App) {
        if (sectionIndex == LOOSE) {
            showLooseAppMenu(app)
            return
        }
        val section = sections.getOrNull(sectionIndex) ?: return
        val position = section.indexOf(sectionApp)
        val entries = mutableListOf<MenuEntry>()
        if (position > 0)
            entries += MenuEntry(context.getString(R.string.ml_move_up)) { moveApp(sectionIndex, position, -1) }
        if (position in 0 until section.apps.lastIndex)
            entries += MenuEntry(context.getString(R.string.ml_move_down)) { moveApp(sectionIndex, position, 1) }
        entries += MenuEntry(context.getString(R.string.ml_app_move_to)) {
            pickOtherSection(sectionIndex) { target -> copyApp(sectionApp, target, removeFrom = sectionIndex) }
        }
        entries += MenuEntry(context.getString(R.string.ml_app_copy_to)) {
            pickOtherSection(sectionIndex) { target -> copyApp(sectionApp, target, removeFrom = null) }
        }
        dockEntry(app)?.let { entries += it }
        entries += MenuEntry(context.getString(R.string.ml_app_remove_from_section)) {
            section.apps.removeAll { it.samePackageAndUser(sectionApp) }
            save()
        }
        entries += MenuEntry(context.getString(R.string.ml_change_icon)) {
            IconChooser.show(context, app, scope, pickImage) { render() }
        }
        entries += MenuEntry(context.getString(R.string.ml_app_info)) {
            openAppInfo(context, getUserHandleFromString(context, sectionApp.user), sectionApp.packageName)
        }
        // As in the app drawer; with app info it also gives the menu its tiles on top
        context.uninstallEntry(app.appPackage, app.user)?.let { entries += it }
        context.showMenuDialog(app.appLabel, appMenuTail(app, entries), header = AppNotificationsView.create(context, app))
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

    /** Menu of an app shown only because of its notifications. */
    private fun showLooseAppMenu(app: AppModel.App) {
        val sectionApp = SectionApp.from(app)
        val entries = mutableListOf(
            MenuEntry(context.getString(R.string.ml_add_to_section)) {
                pickOtherSection(LOOSE) { target -> copyApp(sectionApp, target, removeFrom = null) }
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

    // ---------------------------------------------------------------- operations

    /** [row]: the grid row the user long-pressed, if any; the grid moves the section if it doesn't fit there. */
    private fun createSection(row: Int? = null, onCreated: (Int) -> Unit = {}) {
        context.showTextInputDialog(
            title = context.getString(R.string.ml_section_new),
            initial = "",
            hint = context.getString(R.string.ml_section_name_hint),
            action = context.getString(R.string.ml_save),
        ) { name ->
            sections.add(HomeSection(name = name, row = row ?: -1))
            save()
            onCreated(sections.lastIndex)
        }
    }

    private fun renameSection(index: Int) {
        val section = sections.getOrNull(index) ?: return
        context.showTextInputDialog(
            title = context.getString(R.string.ml_section_rename),
            initial = section.name,
            hint = context.getString(R.string.ml_section_name_hint),
            action = context.getString(R.string.ml_save),
        ) { name ->
            section.name = name
            save()
        }
    }

    private fun deleteSection(index: Int) {
        val section = sections.getOrNull(index) ?: return
        context.showConfirmDialog(
            title = context.getString(R.string.ml_section_delete),
            message = context.getString(R.string.ml_section_delete_confirm, section.name),
            action = context.getString(R.string.ml_delete),
        ) {
            sections.remove(section)
            save()
        }
    }

    private fun moveApp(sectionIndex: Int, position: Int, delta: Int) {
        val list = sections.getOrNull(sectionIndex)?.apps ?: return
        val target = position + delta
        if (position !in list.indices || target !in list.indices) return
        list.add(target, list.removeAt(position))
        save()
    }

    private fun copyApp(sectionApp: SectionApp, targetIndex: Int, removeFrom: Int?) {
        if (targetIndex == UNGROUPED) {
            // "Home": an icon on the home grid
            val app = findApp(sectionApp) ?: return
            if (!grid.addApp(app) && removeFrom == null) {
                context.showToast(context.getString(R.string.ml_already_on_home))
                return
            }
            if (removeFrom != null) sections.getOrNull(removeFrom)?.apps?.removeAll { it.samePackageAndUser(sectionApp) }
            save()
            return
        }
        val target = sections.getOrNull(targetIndex) ?: return
        if (target.contains(sectionApp)) {
            if (removeFrom == null) {
                context.showToast(context.getString(R.string.ml_already_in_section, target.name))
                return
            }
        } else target.apps.add(sectionApp)
        if (removeFrom != null) sections.getOrNull(removeFrom)?.apps?.removeAll { it.samePackageAndUser(sectionApp) }
        save()
    }

    /** Lets the user pick a section other than [excludeIndex], or create a new one. */
    private fun pickOtherSection(excludeIndex: Int, onPicked: (Int) -> Unit) {
        val home = listOf(MenuEntry(context.getString(R.string.ml_home)) { onPicked(UNGROUPED) })
        val entries = home + sections.mapIndexedNotNull { i, s ->
            if (i == excludeIndex) null else MenuEntry(s.name) { onPicked(i) }
        } + MenuEntry(context.getString(R.string.ml_section_new_ellipsis)) { createSection(onCreated = onPicked) }
        context.showMenuDialog(context.getString(R.string.ml_section), entries)
    }

    private fun editSectionApps(index: Int) {
        val section = sections.getOrNull(index) ?: return
        val labels = apps.map { app ->
            if (app.user == android.os.Process.myUserHandle()) app.appLabel else "${app.appLabel} ●"
        }
        val checked = apps.indices.filter { section.contains(SectionApp.from(apps[it])) }.toSet()
        context.showMultiSelectDialog(
            title = section.name,
            labels = labels,
            initiallyChecked = checked,
            action = context.getString(R.string.ml_done),
        ) { selected ->
            val selectedApps = selected.sorted().map { SectionApp.from(apps[it]) }
            // Keep the user's order for apps that stay; entries not in the list (e.g. currently
            // unavailable apps) are kept untouched.
            val kept = section.apps.filter { existing ->
                val match = findApp(existing) ?: return@filter true
                selectedApps.any { it.samePackageAndUser(SectionApp.from(match)) }
            }.toMutableList()
            selectedApps.forEach { candidate -> if (kept.none { it.samePackageAndUser(candidate) }) kept.add(candidate) }
            section.apps.clear()
            section.apps.addAll(kept)
            save()
        }
    }

    /** One-time import of Olauncher's fixed home apps into a first section. */
    private fun migrateOlauncherHomeApps() {
        if (repo.isInitialized()) return
        val imported = (1..8).mapNotNull { i ->
            val pkg = prefs.getAppPackage(i)
            if (pkg.isBlank() || prefs.getIsShortcut(i)) null
            else SectionApp(pkg, prefs.getAppActivityClassName(i), prefs.getAppUser(i))
        }.distinct()
        val initial = if (imported.isEmpty()) emptyList()
        else listOf(HomeSection(name = context.getString(R.string.ml_default_section_name), apps = imported.toMutableList()))
        repo.save(initial)
    }

    companion object {
        private const val LOOSE = -1
        /** Target "Home" in the move/copy menus: an icon on the home grid. */
        private const val UNGROUPED = -2
        private const val BLOCK_PADDING_DP = 4

        /** Red used for apps and sections that have notifications. */
        const val NOTIFICATION_COLOR = 0xFFE53935.toInt()

        /** "Name 3": name in bold followed by the count; just the name when there's nothing new. */
        fun badgeText(label: String, count: Int): CharSequence {
            if (count == 0) return label
            // -1: bold name only (the count is shown in a pill)
            if (count < 0) return SpannableStringBuilder().apply {
                append(label, StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            return SpannableStringBuilder().apply {
                append(label, StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                append("  ")
                append(count.toString(), RelativeSizeSpan(0.75f), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        /** Used by the app drawer: add [app] to a section chosen by the user. */
        fun showAddToSectionDialog(context: Context, app: AppModel.App) {
            val repo = HomeSectionsRepository(context)
            val sections = repo.load()
            val sectionApp = SectionApp.from(app)

            fun addTo(index: Int) {
                val target = sections[index]
                if (target.contains(sectionApp)) {
                    context.showToast(context.getString(R.string.ml_already_in_section, target.name))
                    return
                }
                target.apps.add(sectionApp)
                repo.save(sections)
                context.showToast(context.getString(R.string.ml_added_to_section, target.name))
            }

            val home = MenuEntry(context.getString(R.string.ml_home)) {
                context.showToast(
                    context.getString(if (HomeWidgetsController.addAppToHome(context, app)) R.string.ml_added_to_home else R.string.ml_already_on_home)
                )
            }
            val entries = listOf(home) + sections.mapIndexed { i, s -> MenuEntry(s.name) { addTo(i) } } +
                    MenuEntry(context.getString(R.string.ml_section_new_ellipsis)) {
                        context.showTextInputDialog(
                            title = context.getString(R.string.ml_section_new),
                            initial = "",
                            hint = context.getString(R.string.ml_section_name_hint),
                            action = context.getString(R.string.ml_save),
                        ) { name ->
                            sections.add(HomeSection(name = name))
                            addTo(sections.lastIndex)
                        }
                    }
            context.showMenuDialog(app.appLabel, entries)
        }
    }
}
