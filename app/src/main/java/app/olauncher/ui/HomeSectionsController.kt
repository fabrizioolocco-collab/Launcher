package app.olauncher.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
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
import app.olauncher.helper.IconPackManager
import app.olauncher.helper.MenuEntry
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getUserHandleFromString
import app.olauncher.helper.openAppInfo
import app.olauncher.helper.showConfirmDialog
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
) {
    private val repo = HomeSectionsRepository(context)
    private var sections: MutableList<HomeSection> = mutableListOf()
    private var apps: List<AppModel.App> = emptyList()
    private var appsLoaded = false

    init {
        migrateOlauncherHomeApps()
        sections = repo.load()
    }

    fun setApps(list: List<AppModel.App>) {
        apps = list
        appsLoaded = true
        render()
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

    private fun findApp(sectionApp: SectionApp): AppModel.App? =
        apps.firstOrNull { sectionApp.matches(it) }
            ?: apps.firstOrNull { it.appPackage == sectionApp.packageName && it.user.toString() == sectionApp.user }

    // ---------------------------------------------------------------- rendering

    fun render() {
        container.removeAllViews()
        emptyHint.isVisible = sections.isEmpty()
        if (!appsLoaded) return
        val gravity = prefs.homeAlignment
        sections.forEachIndexed { index, section ->
            container.addView(headerView(index, section, gravity))
            if (section.collapsed) return@forEachIndexed
            var shown = 0
            section.apps.forEach { sectionApp ->
                val app = findApp(sectionApp) ?: return@forEach
                container.addView(appView(index, sectionApp, app, gravity))
                shown++
            }
            if (shown == 0) container.addView(hintView(gravity))
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
            text = context.getString(R.string.ml_section_header_format, section.name, arrow)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.06f
            alpha = 0.7f
            this.gravity = gravity
            setPadding(0, 10.dpToPx(), 0, 6.dpToPx())
            isClickable = true
            isLongClickable = true
            setOnClickListener {
                section.collapsed = !section.collapsed
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

    private fun appView(sectionIndex: Int, sectionApp: SectionApp, app: AppModel.App, gravity: Int): View =
        TextView(context, null, 0, R.style.TextLarge).apply {
            text = app.appLabel
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            this.gravity = Gravity.CENTER_VERTICAL or gravity
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
            if (prefs.showIcons) loadIcon(this, app)
        }

    private fun loadIcon(view: TextView, app: AppModel.App) {
        val size = (view.textSize * 1.15f).toInt()
        view.compoundDrawablePadding = 14.dpToPx()
        // Transparent placeholder keeps the text from shifting when the icon arrives
        view.setCompoundDrawablesRelative(ColorDrawable(0).apply { setBounds(0, 0, size, size) }, null, null, null)
        val iconPack = prefs.iconPack
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                IconPackManager.getIcon(context, app.appPackage, app.activityClassName, app.user, iconPack, size)
            } ?: return@launch
            view.setCompoundDrawablesRelative(IconPackManager.toDrawable(context, bitmap, size), null, null, null)
        }
    }

    // ---------------------------------------------------------------- menus

    fun showHomeMenu(openSettings: () -> Unit) {
        context.showMenuDialog(
            context.getString(R.string.app_name),
            listOf(
                MenuEntry(context.getString(R.string.ml_section_new)) { createSection() },
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
        entries += MenuEntry(context.getString(R.string.ml_app_info)) {
            openAppInfo(context, getUserHandleFromString(context, sectionApp.user), sectionApp.packageName)
        }
        context.showMenuDialog(app.appLabel, entries)
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
        val entries = sections.mapIndexedNotNull { i, s ->
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

            val entries = sections.mapIndexed { i, s -> MenuEntry(s.name) { addTo(i) } } +
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
