package app.olauncher.ui

import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
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
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.showMenuDialog
import app.olauncher.helper.showMultiSelectDialog
import app.olauncher.helper.showTextInputDialog
import app.olauncher.helper.showToast

/**
 * Tab bar of the app drawer: "All", the user's tabs and "Hidden", each shown or not (see
 * [DrawerTabsManager], opened by a long press on a tab). Tap a tab to show its apps, "+" creates
 * one. An app is in one tab at most; an app in no tab shown is in "All". Every tab shown is in the
 * bar, empty ones included, so an app can always be moved there. Search looks through all apps
 * (handled by the fragment).
 *
 * While an app is dragged in the grid the chips (and "+") are drop targets: see [targetAt].
 * With a single tab the bar is hidden and "+" moves next to the search field.
 */
class DrawerTabsController(
    private val context: Context,
    private val bar: ViewGroup,
    /** The view holding [bar], hidden together with it. */
    private val barContainer: View,
    /** The row of the search field, where "+" goes while the bar is hidden. */
    private val searchRow: LinearLayout,
    /** All launchable apps, used when editing a tab's apps. */
    private val allApps: () -> List<AppModel.App>,
    /** Hidden apps, listed by the "Hidden" tab. */
    private val hiddenApps: () -> List<AppModel>,
    private val onSelectionChanged: () -> Unit,
    /** Hides an app (dropped on "Hidden"). */
    private val hideApp: (AppModel.App) -> Unit,
    /** "Manage tabs". */
    private val manage: () -> Unit,
) {
    private val repo = DrawerTabsRepository(context)
    private var tabs = repo.load()

    /** "+" next to the search field, while the bar is hidden. */
    private var searchPlus: View? = null

    /**
     * The tab chosen, null for none: the drawer opens on every app, a tap on a tab shows only its
     * apps, a tap on it again every app.
     */
    private var selected: String? = null

    /** Tab id under the dragged app, highlighted. */
    private var hovered: String? = null
    private var dragging = false

    /** Visible tabs in order: (All) + enabled user tabs + (Hidden). */
    private fun visibleIds(): List<String> =
        (if (repo.allEnabled) listOf(DrawerTabsRepository.ALL_ID) else emptyList()) + tabs.filter { it.enabled }.map { it.id } +
                (if (repo.hiddenEnabled) listOf(DrawerTabsRepository.HIDDEN_ID) else emptyList())

    /** Reads the tabs again (after "Manage tabs"). */
    fun reload() {
        tabs = repo.load()
        render()
    }

    /** True when the "Hidden" tab is the one shown. */
    val showingHidden get() = selectedId() == DrawerTabsRepository.HIDDEN_ID

    fun selectedId(): String? = selected?.takeIf { it in visibleIds() }

    /** Name of the tab shown ("All apps" with none). */
    fun selectedName(): String = selectedId()?.let { nameOf(it) } ?: context.getString(R.string.ml_all_apps)

    private fun nameOf(id: String) = when (id) {
        DrawerTabsRepository.ALL_ID -> context.getString(R.string.ml_tab_all)
        DrawerTabsRepository.HIDDEN_ID -> context.getString(R.string.ml_tab_hidden)
        else -> tabs.firstOrNull { it.id == id }?.name ?: context.getString(R.string.ml_tab_all)
    }

    /** Whether the tab shown has its own order (apps dragged into place). Every app: always A-Z. */
    fun selectedHasOrder() = selectedId()?.let { repo.hasOrder(it) } == true

    /** Apps can be dragged into place only inside a tab (every app is listed A-Z). */
    fun canReorder() = selectedId().let { it != null && it != DrawerTabsRepository.HIDDEN_ID }

    /** Back to A-Z in the tab shown. */
    fun resetSelectedOrder() {
        val id = selectedId() ?: return
        repo.setOrder(id, emptyList())
        onSelectionChanged()
    }

    /** Saves the order of the tab shown, after an app was dragged into place. */
    fun saveSelectedOrder(apps: List<AppModel>) {
        val id = selectedId() ?: return
        repo.setOrder(id, apps.map { DrawerTabsRepository.key(it) })
    }

    /** A new tab, as from "+". */
    fun newTab() = createTab()

    /** Apps to list in the selected tab, in its order. */
    fun filter(apps: List<AppModel>): List<AppModel> {
        val id = selectedId() ?: return apps
        return if (id == DrawerTabsRepository.HIDDEN_ID) appsOf(id, apps) else repo.sorted(id, appsOf(id, apps))
    }

    private fun appsOf(id: String, apps: List<AppModel>): List<AppModel> {
        // "All" lists what is in no other tab shown: an app is either hidden, in a tab, or here
        if (id == DrawerTabsRepository.ALL_ID) {
            val placed = tabs.filter { it.enabled }.flatMap { it.apps }.toHashSet()
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
            searchPlus = chip("+", false, PLUS_ID).apply {
                minWidth = 48.dpToPx()
                minHeight = 48.dpToPx()
                setPadding(0, 0, 0, 0)
                (background as? GradientDrawable)?.cornerRadius = 24.dpToPx().toFloat()
                setOnClickListener { createTab() }
                setOnLongClickListener {
                    manage()
                    true
                }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    // The search field already leaves 16dp on its right
                    marginStart = (-4).dpToPx(); marginEnd = 16.dpToPx()
                }
            }
            searchRow.addView(searchPlus, 1)
            return
        }
        visible.forEach { id ->
            bar.addView(chip(nameOf(id), id == selected, id).apply {
                setOnClickListener {
                    this@DrawerTabsController.selected = if (this@DrawerTabsController.selected == id) null else id
                    render()
                    onSelectionChanged()
                }
                setOnLongClickListener {
                    manage()
                    true
                }
            })
        }
        bar.addView(chip("+", false, PLUS_ID).apply {
            setOnClickListener { createTab() }
            setOnLongClickListener {
                manage()
                true
            }
        })
    }

    /**
     * A pill as wide as its name: the selected tab filled with the text colour, the others with a
     * thin border. "+" is a round one. While an app is dragged the targets get a thicker border,
     * the one under the finger is filled.
     */
    private fun chip(text: String, selected: Boolean, id: String) = TextView(context, null, 0, R.style.TextSmall).apply {
        this.text = text
        tag = id
        textSize = 13f
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        styleChip(this, selected, false)
        val plus = text == "+"
        setPadding(if (plus) 0 else 14.dpToPx(), 0, if (plus) 0 else 14.dpToPx(), 0)
        minWidth = if (plus) 34.dpToPx() else 0
        isClickable = true
        isLongClickable = true
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 34.dpToPx()).apply {
            marginStart = 4.dpToPx(); marginEnd = 4.dpToPx()
            // Rows of tabs, when they do not fit on one
            topMargin = 3.dpToPx(); bottomMargin = 3.dpToPx()
        }
    }

    private fun styleChip(view: TextView, selected: Boolean, target: Boolean) {
        val primary = context.getColorFromAttr(R.attr.primaryColor)
        val filled = selected && !dragging || target
        val radius = if (view.tag == PLUS_ID && view.parent === searchRow) 24 else 17
        view.background = GradientDrawable().apply {
            cornerRadius = radius.dpToPx().toFloat()
            if (filled) setColor(primary)
            else if (dragging) setStroke(2.dpToPx(), primary)
            else setStroke(1.dpToPx(), context.getColorFromAttr(R.attr.cardStrokeColor))
        }
        view.setTextColor(if (filled) context.getColorFromAttr(R.attr.cardColor) else primary)
        view.alpha = if (filled || dragging) 1f else 0.85f
        view.scaleX = if (target) 1.08f else 1f
        view.scaleY = view.scaleX
    }

    private fun targets(): List<TextView> {
        val list = mutableListOf<TextView>()
        if (barContainer.visibility == View.VISIBLE) for (i in 0 until bar.childCount) (bar.getChildAt(i) as? TextView)?.let { list += it }
        (searchPlus as? TextView)?.let { list += it }
        return list
    }

    // ---------------------------------------------------------------- drag and drop

    /** An app started or stopped being dragged in the grid: the chips become drop targets. */
    fun setDragging(on: Boolean) {
        dragging = on
        hovered = null
        val selected = selectedId()
        targets().forEach { styleChip(it, it.tag == selected, false) }
    }

    /**
     * The tab (or [PLUS_ID]) under the screen point [x], [y] while dragging, highlighted; null
     * when none (or the tab shown, where the app already is).
     */
    fun targetAt(x: Float, y: Float): String? {
        val rect = Rect()
        val slop = 8.dpToPx()
        val selected = selectedId()
        val hit = targets().firstOrNull {
            it.getGlobalVisibleRect(rect) && rect.apply { inset(-slop, -slop) }.contains(x.toInt(), y.toInt())
        }?.tag as? String
        val target = hit?.takeIf { it != selected }
        if (target != hovered) {
            hovered = target
            targets().forEach { styleChip(it, it.tag == selected, it.tag == target) }
            if (target != null) bar.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        }
        return target
    }

    /** [app] was dropped on [target] (a tab id or [PLUS_ID]). */
    fun drop(app: AppModel.App, target: String) {
        when (target) {
            PLUS_ID -> createTab { moveTo(app, it) }
            DrawerTabsRepository.HIDDEN_ID -> hideApp(app)
            DrawerTabsRepository.ALL_ID -> moveTo(app, null)
            else -> tabs.firstOrNull { it.id == target }?.let { moveTo(app, it) }
        }
    }

    /** Moves [app] to [tab] (null: "All"), out of every other tab. */
    private fun moveTo(app: AppModel.App, tab: DrawerTab?) {
        val key = DrawerTabsRepository.key(app)
        tabs.forEach { it.apps.remove(key) }
        if (tab != null) tab.apps.add(key) else repo.allEnabled = true
        save()
        context.showToast(context.getString(R.string.ml_tab_moved, app.appLabel, tab?.name ?: context.getString(R.string.ml_tab_all)))
    }

    private fun save() {
        repo.save(tabs)
        render()
        onSelectionChanged()
    }

    // ---------------------------------------------------------------- tab operations

    /** New tab; by default its apps are picked right away. */
    private fun createTab(then: ((DrawerTab) -> Unit)? = null) {
        context.showTextInputDialog(
            title = context.getString(R.string.ml_tab_new),
            initial = "",
            hint = context.getString(R.string.ml_tab_name_hint),
            action = context.getString(R.string.ml_save),
        ) { name ->
            val tab = DrawerTab(name = name)
            tabs.add(tab)
            selected = tab.id
            save()
            if (then != null) then(tab) else chooseApps(tab)
        }
    }

    private fun chooseApps(tab: DrawerTab) {
        val apps = allApps().distinctBy { DrawerTabsRepository.key(it) }.sortedBy { it.appLabel.lowercase() }
        val labels = apps.map { app ->
            val key = DrawerTabsRepository.key(app)
            val elsewhere = tabs.filter { it !== tab && key in it.apps }.joinToString { it.name }
            if (elsewhere.isEmpty()) app.appLabel else app.appLabel + " · " + context.getString(R.string.ml_tab_in, elsewhere)
        }
        val checked = apps.indices.filter { DrawerTabsRepository.key(apps[it]) in tab.apps }.toSet()
        context.showMultiSelectDialog(tab.name, labels, checked, context.getString(R.string.ml_done)) { selected ->
            apps.forEachIndexed { i, app ->
                val key = DrawerTabsRepository.key(app)
                if (i in selected) {
                    tabs.forEach { if (it !== tab) it.apps.remove(key) }
                    tab.apps.add(key)
                } else tab.apps.remove(key)
            }
            save()
        }
    }

    /**
     * From the app menu: moves [app] to "All" or to one of the tabs shown (or a new one), out of
     * every other tab. The place it is in now is ticked.
     */
    fun showMoveToTab(app: AppModel.App) {
        val key = DrawerTabsRepository.key(app)
        val shown = tabs.filter { it.enabled }
        val current = shown.filter { key in it.apps }
        val check = "✓"
        val entries = listOf(MenuEntry(context.getString(R.string.ml_tab_all), value = if (current.isEmpty()) check else null) { moveTo(app, null) }) +
                shown.map { tab -> MenuEntry(tab.name, value = if (tab in current) check else null) { moveTo(app, tab) } } +
                MenuEntry(context.getString(R.string.ml_tab_new_ellipsis)) { createTab { moveTo(app, it) } }
        context.showMenuDialog(context.getString(R.string.ml_move_to_tab), entries)
    }

    companion object {
        /** Tag of the "+" chip, a drop target that makes a new tab. */
        const val PLUS_ID = "+"
    }
}
