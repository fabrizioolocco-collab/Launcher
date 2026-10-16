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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Renders the user's sections on the home screen and handles all section/app editing.
 * Data lives in [HomeSectionsRepository]; every change is saved immediately and re-rendered.
 */
class HomeSectionsController(
    private val context: Context,
    private val container: LinearLayout,
    private val emptyHint: View,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val onLaunch: (AppModel.App) -> Unit,
    /** Opens the system photo picker and calls back with the chosen image. */
    private val pickImage: ((Uri) -> Unit) -> Unit,
    /** Keys (package|user) of apps already placed in the bottom area. */
    private val bottomAppKeys: () -> Set<String> = { emptySet() },
) {
    private var hiddenApps: List<AppModel.App> = emptyList()
    private val repo = HomeSectionsRepository(context)
    private var sections: MutableList<HomeSection> = mutableListOf()
    private var ungrouped: MutableList<SectionApp> = mutableListOf()
    private var apps: List<AppModel.App> = emptyList()
    private var appsLoaded = false
    private var notifications: Map<String, AppNotifications> = emptyMap()

    init {
        migrateOlauncherHomeApps()
        sections = repo.load()
        ungrouped = repo.loadUngrouped()
    }

    fun setApps(list: List<AppModel.App>) {
        apps = list
        appsLoaded = true
        render()
    }

    fun setHiddenApps(list: List<AppModel.App>) {
        hiddenApps = list
        if (notifications.isNotEmpty()) render()
    }

    /**
     * Apps with notifications that aren't in any section nor in the bottom area (hidden apps
     * included): shown in red above the sections until their notifications are gone.
     */
    private fun looseApps(): List<AppModel.App> {
        if (notifications.isEmpty()) return emptyList()
        val placed = (sections.flatMap { it.apps } + ungrouped).map { NotificationRepository.key(it.packageName, it.user) }.toSet() +
                bottomAppKeys()
        return (apps + hiddenApps)
            .filter { notificationCount(it) > 0 }
            .filter { NotificationRepository.key(it.appPackage, it.user.toString()) !in placed }
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
        ungrouped = repo.loadUngrouped()
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
        container.removeAllViews()
        emptyHint.isVisible = sections.isEmpty()
        if (!appsLoaded) return
        val gravity = prefs.homeAlignment
        val detox = DetoxManager.isActive(context)
        // Pinned apps outside groups (user order), then apps shown only for their notifications
        val top = ungrouped.mapNotNull { sectionApp -> findApp(sectionApp)?.let { appView(UNGROUPED, sectionApp, it, gravity) } } +
                looseApps().map { app -> appView(LOOSE, SectionApp.from(app), app, gravity) }
        addAppRows(top, gravity)
        if (top.isNotEmpty()) container.addView(View(context), LinearLayout.LayoutParams(1, 12.dpToPx()))
        sections.forEachIndexed { index, section ->
            // During detox, sections without allowed apps disappear
            if (detox && section.apps.none { findApp(it) != null }) return@forEachIndexed
            container.addView(headerView(index, section, gravity))
            if (section.collapsed) return@forEachIndexed
            val views = section.apps.mapNotNull { sectionApp ->
                findApp(sectionApp)?.let { appView(index, sectionApp, it, gravity) }
            }
            addAppRows(views, gravity)
            if (views.isEmpty()) container.addView(hintView(gravity))
        }
    }

    private fun lp(gravity: Int, topMargin: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            this.gravity = gravity
            this.topMargin = topMargin
        }

    private fun headerView(index: Int, section: HomeSection, gravity: Int): View =
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
            isLongClickable = true
            setOnClickListener {
                // Accordion: opening a section closes the others
                val open = section.collapsed
                sections.forEach { it.collapsed = true }
                section.collapsed = !open
                save()
            }
            setOnLongClickListener {
                showSectionMenu(index)
                true
            }
            layoutParams = lp(gravity, if (index == 0) 0 else 18.dpToPx())
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

    /** Adds app views to the container, [Prefs.homeColumns] per row. */
    private fun addAppRows(views: List<View>, gravity: Int) {
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

    /** App row/cell: the label view plus a red pill with the count; tapping the pill lists the notifications. */
    private fun appView(sectionIndex: Int, sectionApp: SectionApp, app: AppModel.App, gravity: Int): View {
        val label = labelView(sectionIndex, sectionApp, app, gravity)
        val count = notificationCount(app)
        if (count <= 0) return label
        val badge = NotificationBadge.create(context)
        NotificationBadge.bind(badge, count) { NotificationBadge.showNotifications(context, app) { onLaunch(app) } }
        return if (iconOnTop()) {
            android.widget.FrameLayout(context).apply {
                addView(label, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(badge, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                    marginEnd = 4.dpToPx()
                    topMargin = 2.dpToPx()
                })
                layoutParams = lp(gravity)
            }
        } else {
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                this.gravity = Gravity.CENTER_VERTICAL
                addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f).apply { weight = 0f })
                addView(badge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = 10.dpToPx()
                })
                layoutParams = lp(gravity)
            }
        }
    }

    private fun labelView(sectionIndex: Int, sectionApp: SectionApp, app: AppModel.App, gravity: Int): TextView =
        TextView(context, null, 0, R.style.TextLarge).apply {
            FontManager.apply(this, prefs.appFont, prefs.appTextSize)
            val count = notificationCount(app)
            val onTop = iconOnTop()
            // The count goes in a red pill next to the app (see withBadge)
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
            setOnClickListener { onLaunch(app) }
            setOnLongClickListener {
                showAppMenu(sectionIndex, sectionApp, app)
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
            place(IconPackManager.toDrawable(context, bitmap, size))
        }
    }

    // ---------------------------------------------------------------- menus

    fun showHomeMenu(openSettings: () -> Unit, addWidget: () -> Unit, addToBottom: () -> Unit) {
        context.showMenuDialog(
            context.getString(R.string.app_name),
            listOf(
                MenuEntry(context.getString(R.string.ml_section_new)) { createSection() },
                MenuEntry(context.getString(R.string.ml_add_widget_top)) { addWidget() },
                MenuEntry(context.getString(R.string.ml_add_to_bottom)) { addToBottom() },
                MenuEntry(context.getString(R.string.ml_settings)) { openSettings() },
            )
        )
    }

    private fun showSectionMenu(index: Int) {
        val section = sections.getOrNull(index) ?: return
        val entries = mutableListOf(
            MenuEntry(context.getString(R.string.ml_section_rename)) { renameSection(index) },
            MenuEntry(context.getString(R.string.ml_section_edit_apps)) { editSectionApps(index) },
        )
        if (index > 0) entries += MenuEntry(context.getString(R.string.ml_move_up)) { moveSection(index, -1) }
        if (index < sections.lastIndex) entries += MenuEntry(context.getString(R.string.ml_move_down)) { moveSection(index, 1) }
        entries += MenuEntry(context.getString(R.string.ml_section_new)) { createSection() }
        entries += MenuEntry(context.getString(R.string.ml_section_delete)) { deleteSection(index) }
        context.showMenuDialog(section.name, entries)
    }

    private fun showAppMenu(sectionIndex: Int, sectionApp: SectionApp, app: AppModel.App) {
        if (sectionIndex == LOOSE) {
            showLooseAppMenu(app)
            return
        }
        if (sectionIndex == UNGROUPED) {
            showUngroupedAppMenu(sectionApp, app)
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
        context.showMenuDialog(app.appLabel, entries, header = AppNotificationsView.create(context, app))
    }

    /** Menu of an app pinned on home outside any group. */
    private fun showUngroupedAppMenu(sectionApp: SectionApp, app: AppModel.App) {
        val position = ungrouped.indexOfFirst { it.samePackageAndUser(sectionApp) }
        val entries = mutableListOf<MenuEntry>()
        if (position > 0) entries += MenuEntry(context.getString(R.string.ml_move_up)) { moveUngrouped(position, -1) }
        if (position in 0 until ungrouped.lastIndex) entries += MenuEntry(context.getString(R.string.ml_move_down)) { moveUngrouped(position, 1) }
        entries += MenuEntry(context.getString(R.string.ml_app_move_to)) {
            pickOtherSection(UNGROUPED) { target ->
                removeUngrouped(sectionApp)
                copyApp(sectionApp, target, removeFrom = null)
            }
        }
        entries += MenuEntry(context.getString(R.string.ml_remove_from_home)) {
            removeUngrouped(sectionApp)
            render()
        }
        entries += MenuEntry(context.getString(R.string.ml_change_icon)) {
            IconChooser.show(context, app, scope, pickImage) { render() }
        }
        entries += MenuEntry(context.getString(R.string.ml_app_info)) {
            openAppInfo(context, app.user, app.appPackage)
        }
        context.showMenuDialog(app.appLabel, entries, header = AppNotificationsView.create(context, app))
    }

    private fun moveUngrouped(position: Int, delta: Int) {
        val target = position + delta
        if (position !in ungrouped.indices || target !in ungrouped.indices) return
        ungrouped.add(target, ungrouped.removeAt(position))
        repo.saveUngrouped(ungrouped)
        render()
    }

    private fun removeUngrouped(sectionApp: SectionApp) {
        ungrouped.removeAll { it.samePackageAndUser(sectionApp) }
        repo.saveUngrouped(ungrouped)
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
        context.showMenuDialog(app.appLabel, entries, header = AppNotificationsView.create(context, app))
    }

    // ---------------------------------------------------------------- operations

    private fun createSection(onCreated: (Int) -> Unit = {}) {
        context.showTextInputDialog(
            title = context.getString(R.string.ml_section_new),
            initial = "",
            hint = context.getString(R.string.ml_section_name_hint),
            action = context.getString(R.string.ml_save),
        ) { name ->
            sections.add(HomeSection(name = name))
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

    private fun moveSection(index: Int, delta: Int) {
        val target = index + delta
        if (target !in sections.indices) return
        sections.add(target, sections.removeAt(index))
        save()
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
            // "Home": the apps outside any group
            if (ungrouped.none { it.samePackageAndUser(sectionApp) }) {
                ungrouped.add(sectionApp)
                repo.saveUngrouped(ungrouped)
            } else if (removeFrom == null) {
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
        val home = if (excludeIndex == UNGROUPED) emptyList()
        else listOf(MenuEntry(context.getString(R.string.ml_home)) { onPicked(UNGROUPED) })
        val entries = home + sections.mapIndexedNotNull { i, s ->
            if (i == excludeIndex) null else MenuEntry(s.name) { onPicked(i) }
        } + MenuEntry(context.getString(R.string.ml_section_new_ellipsis)) { createSection(onPicked) }
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
            // Keep the user's order for apps that stay; entries not in the list (e.g. hidden or
            // currently unavailable apps) are kept untouched.
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
        private const val UNGROUPED = -2

        /** Pins [app] on home outside any group. Returns false if it was already there. */
        fun addUngrouped(context: Context, app: AppModel.App): Boolean {
            val repo = HomeSectionsRepository(context)
            val list = repo.loadUngrouped()
            val entry = SectionApp.from(app)
            if (list.any { it.samePackageAndUser(entry) }) return false
            list.add(entry)
            repo.saveUngrouped(list)
            return true
        }

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
                    context.getString(if (addUngrouped(context, app)) R.string.ml_added_to_home else R.string.ml_already_on_home)
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
