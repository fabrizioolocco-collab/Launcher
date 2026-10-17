package app.olauncher.ui

import android.content.Context
import android.graphics.Typeface
import app.olauncher.helper.getColorFromAttr
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.DrawerTab
import app.olauncher.data.DrawerTabsRepository
import app.olauncher.helper.MenuEntry
import app.olauncher.helper.dpToPx
import app.olauncher.helper.showConfirmDialog
import app.olauncher.helper.showMenuDialog
import app.olauncher.helper.showMultiSelectDialog
import app.olauncher.helper.showTextInputDialog
import app.olauncher.helper.showToast

/**
 * Tab bar of the app drawer: "All" (can be turned off) plus the user's tabs, each switchable on/off.
 * Tap a tab to show its apps; long press for rename / edit apps / move / turn off / delete;
 * "+" creates a tab. Search always looks through all apps (handled by the fragment).
 *
 * Tabs without apps are not shown. With a single tab left the bar is hidden and "+" moves next to
 * the search field (a long press there opens the menu of that tab).
 */
class DrawerTabsController(
    private val context: Context,
    private val bar: LinearLayout,
    /** The view holding [bar], hidden together with it. */
    private val barContainer: View,
    /** The row of the search field, where "+" goes while the bar is hidden. */
    private val searchRow: LinearLayout,
    /** All launchable apps, used when editing a tab's apps. */
    private val allApps: () -> List<AppModel.App>,
    /** Hidden apps, listed by the "Hidden" tab. */
    private val hiddenApps: () -> List<AppModel>,
    private val onSelectionChanged: () -> Unit,
) {
    private val repo = DrawerTabsRepository(context)
    private var tabs = repo.load()

    /** The apps the drawer lists (null until known): tabs left without any of them are not shown. */
    private var available: List<AppModel>? = null

    /** "+" next to the search field, while the bar is hidden. */
    private var searchPlus: View? = null

    /** Visible tabs in order: (All) + enabled user tabs + (Hidden), only those with apps. */
    private fun visibleIds(): List<String> {
        val ids = (if (repo.allEnabled) listOf(DrawerTabsRepository.ALL_ID) else emptyList()) + tabs.filter { it.enabled }.map { it.id } +
                (if (repo.hiddenEnabled) listOf(DrawerTabsRepository.HIDDEN_ID) else emptyList())
        val apps = available ?: return ids
        return ids.filter { appsOf(it, apps).isNotEmpty() }
    }

    /** Updates the apps the drawer lists; re-renders when that changes which tabs are shown. */
    fun setApps(apps: List<AppModel>) {
        val before = visibleIds()
        available = apps
        if (visibleIds() != before) render()
    }

    /** True when the "Hidden" tab is the one shown. */
    val showingHidden get() = selectedId() == DrawerTabsRepository.HIDDEN_ID

    private fun selectedId(): String {
        val visible = visibleIds()
        val sel = repo.selected
        return if (sel in visible) sel else visible.firstOrNull() ?: DrawerTabsRepository.ALL_ID
    }

    /** Name of the tab shown. */
    fun selectedName(): String = when (val id = selectedId()) {
        DrawerTabsRepository.ALL_ID -> context.getString(R.string.ml_tab_all)
        DrawerTabsRepository.HIDDEN_ID -> context.getString(R.string.ml_tab_hidden)
        else -> tabs.firstOrNull { it.id == id }?.name ?: context.getString(R.string.ml_tab_all)
    }

    /** A new tab, as from "+". */
    fun newTab() = createTab()

    /** Apps to list in the selected tab. */
    fun filter(apps: List<AppModel>): List<AppModel> = appsOf(selectedId(), apps)

    private fun appsOf(id: String, apps: List<AppModel>): List<AppModel> {
        // "All" lists what is in no other tab: an app is either hidden, in a tab, or here
        if (id == DrawerTabsRepository.ALL_ID) {
            val placed = tabs.flatMap { it.apps }.toHashSet()
            return apps.filter { DrawerTabsRepository.key(it) !in placed }
        }
        if (id == DrawerTabsRepository.HIDDEN_ID) return hiddenApps()
        val tab = tabs.firstOrNull { it.id == id } ?: return apps
        return apps.filter { DrawerTabsRepository.key(it) in tab.apps }
    }

    fun render() {
        bar.removeAllViews()
        val selected = selectedId()
        val visible = visibleIds()
        val single = visible.size <= 1
        barContainer.visibility = if (single) View.GONE else View.VISIBLE
        searchPlus?.let { searchRow.removeView(it) }
        searchPlus = null
        if (single) {
            searchPlus = chip("+", false).apply {
                minWidth = 48.dpToPx()
                minHeight = 48.dpToPx()
                setPadding(0, 0, 0, 0)
                (background as? android.graphics.drawable.GradientDrawable)?.cornerRadius = 24.dpToPx().toFloat()
                setOnClickListener { createTab() }
                setOnLongClickListener {
                    visible.firstOrNull()?.let { showTabMenu(it) }
                    true
                }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    // The search field already leaves 16dp on its right
                    marginStart = (-4).dpToPx(); marginEnd = 16.dpToPx()
                }
            }
            searchRow.addView(searchPlus, 1)
            return
        }
        visible.forEach { id ->
            val name = when (id) {
                DrawerTabsRepository.ALL_ID -> context.getString(R.string.ml_tab_all)
                DrawerTabsRepository.HIDDEN_ID -> context.getString(R.string.ml_tab_hidden)
                else -> tabs.first { it.id == id }.name
            }
            bar.addView(chip(name, id == selected).apply {
                setOnClickListener {
                    repo.selected = id
                    render()
                    onSelectionChanged()
                }
                setOnLongClickListener {
                    showTabMenu(id)
                    true
                }
            })
        }
        bar.addView(chip("+", false).apply {
            setOnClickListener { createTab() }
        })
    }

    /**
     * A pill as wide as its name: the selected tab filled with the text colour, the others with a
     * thin border. "+" is a round one.
     */
    private fun chip(text: String, selected: Boolean) = TextView(context, null, 0, R.style.TextSmall).apply {
        this.text = text
        textSize = 13f
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = android.view.Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        val primary = context.getColorFromAttr(R.attr.primaryColor)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 17.dpToPx().toFloat()
            if (selected) setColor(primary)
            else setStroke(1.dpToPx(), context.getColorFromAttr(R.attr.cardStrokeColor))
        }
        setTextColor(if (selected) context.getColorFromAttr(R.attr.cardColor) else primary)
        if (!selected) alpha = 0.85f
        val plus = text == "+"
        setPadding(if (plus) 0 else 14.dpToPx(), 0, if (plus) 0 else 14.dpToPx(), 0)
        minWidth = if (plus) 34.dpToPx() else 0
        isClickable = true
        isLongClickable = true
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 34.dpToPx()).apply {
            marginStart = 4.dpToPx(); marginEnd = 4.dpToPx()
        }
    }

    private fun save() {
        repo.save(tabs)
        render()
        onSelectionChanged()
    }

    // ---------------------------------------------------------------- tab operations

    /** New tab; by default its apps are picked right away, since a tab without apps is not shown. */
    private fun createTab(then: (DrawerTab) -> Unit = { editApps(it) }) {
        context.showTextInputDialog(
            title = context.getString(R.string.ml_tab_new),
            initial = "",
            hint = context.getString(R.string.ml_tab_name_hint),
            action = context.getString(R.string.ml_save),
        ) { name ->
            val tab = DrawerTab(name = name)
            tabs.add(tab)
            repo.selected = tab.id
            save()
            then(tab)
        }
    }

    private fun showTabMenu(id: String) {
        if (id == DrawerTabsRepository.ALL_ID) {
            context.showMenuDialog(
                context.getString(R.string.ml_tab_all),
                listOf(
                    MenuEntry(context.getString(if (repo.hiddenEnabled) R.string.ml_tab_hidden_off else R.string.ml_tab_hidden_on)) {
                        repo.hiddenEnabled = !repo.hiddenEnabled
                        render()
                        onSelectionChanged()
                    },
                    MenuEntry(context.getString(R.string.ml_tab_disable)) {
                        repo.allEnabled = false
                        render()
                        onSelectionChanged()
                    },
                )
            )
            return
        }
        if (id == DrawerTabsRepository.HIDDEN_ID) {
            context.showMenuDialog(
                context.getString(R.string.ml_tab_hidden),
                listOf(MenuEntry(context.getString(R.string.ml_tab_disable)) {
                    repo.hiddenEnabled = false
                    render()
                    onSelectionChanged()
                })
            )
            return
        }
        val tab = tabs.firstOrNull { it.id == id } ?: return
        val index = tabs.indexOf(tab)
        val entries = mutableListOf(
            MenuEntry(context.getString(R.string.ml_section_rename)) {
                context.showTextInputDialog(
                    title = context.getString(R.string.ml_section_rename),
                    initial = tab.name,
                    hint = context.getString(R.string.ml_tab_name_hint),
                    action = context.getString(R.string.ml_save),
                ) { name ->
                    tab.name = name
                    save()
                }
            },
            MenuEntry(context.getString(R.string.ml_tab_edit_apps)) { editApps(tab) },
        )
        if (index > 0) entries += MenuEntry(context.getString(R.string.ml_move_left)) {
            tabs.add(index - 1, tabs.removeAt(index)); save()
        }
        if (index < tabs.lastIndex) entries += MenuEntry(context.getString(R.string.ml_move_right)) {
            tabs.add(index + 1, tabs.removeAt(index)); save()
        }
        entries += MenuEntry(context.getString(R.string.ml_tab_disable)) {
            tab.enabled = false
            save()
        }
        entries += MenuEntry(context.getString(R.string.ml_tab_delete)) {
            context.showConfirmDialog(
                title = context.getString(R.string.ml_tab_delete),
                message = context.getString(R.string.ml_tab_delete_confirm, tab.name),
                action = context.getString(R.string.ml_delete),
            ) {
                tabs.remove(tab)
                save()
            }
        }
        context.showMenuDialog(tab.name, entries)
    }

    private fun editApps(tab: DrawerTab) {
        val apps = allApps().distinctBy { DrawerTabsRepository.key(it) }
        val checked = apps.indices.filter { DrawerTabsRepository.key(apps[it]) in tab.apps }.toSet()
        context.showMultiSelectDialog(tab.name, apps.map { it.appLabel }, checked, context.getString(R.string.ml_done)) { selected ->
            // Keep keys of apps not listed now (e.g. hidden or temporarily unavailable)
            val listed = apps.map { DrawerTabsRepository.key(it) }.toSet()
            tab.apps.removeAll { it in listed }
            tab.apps.addAll(selected.map { DrawerTabsRepository.key(apps[it]) })
            save()
        }
    }

    /** The tabs [app] is in (then it isn't in "All"). */
    fun tabsOf(app: AppModel): List<DrawerTab> = tabs.filter { DrawerTabsRepository.key(app) in it.apps }

    /** From the app menu: takes [app] out of every tab, so it's back in "All". */
    fun backToAll(app: AppModel) {
        val key = DrawerTabsRepository.key(app)
        tabs.forEach { it.apps.remove(key) }
        save()
        context.showToast(context.getString(R.string.ml_tab_back_to_all_done, app.appLabel))
    }

    /** From the app menu: add [app] to a tab (or a new one). */
    fun showAddToTab(app: AppModel.App) {
        val key = DrawerTabsRepository.key(app)
        fun addTo(tab: DrawerTab) {
            tab.apps.add(key)
            save()
            context.showToast(context.getString(R.string.ml_added_to_section, tab.name))
        }
        val entries = tabs.map { tab -> MenuEntry(tab.name) { addTo(tab) } } +
                MenuEntry(context.getString(R.string.ml_tab_new_ellipsis)) { createTab { addTo(it) } }
        context.showMenuDialog(context.getString(R.string.ml_tab), entries)
    }

    companion object {
        /** Settings: turn each tab (and "All") on or off, hidden ones included. */
        fun showTabsSettings(context: Context) {
            val repo = DrawerTabsRepository(context)
            val tabs = repo.load()
            val labels = listOf(context.getString(R.string.ml_tab_all)) + tabs.map { it.name } + context.getString(R.string.ml_tab_hidden)
            val hiddenIndex = labels.lastIndex
            val checked = buildSet {
                if (repo.allEnabled) add(0)
                tabs.forEachIndexed { i, t -> if (t.enabled) add(i + 1) }
                if (repo.hiddenEnabled) add(hiddenIndex)
            }
            context.showMultiSelectDialog(context.getString(R.string.ml_tabs_settings), labels, checked, context.getString(R.string.ml_done)) { selected ->
                repo.allEnabled = 0 in selected
                repo.hiddenEnabled = hiddenIndex in selected
                tabs.forEachIndexed { i, t -> t.enabled = (i + 1) in selected }
                repo.save(tabs)
            }
        }
    }
}
