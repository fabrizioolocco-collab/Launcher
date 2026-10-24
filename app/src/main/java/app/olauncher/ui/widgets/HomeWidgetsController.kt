package app.olauncher.ui.widgets

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import app.olauncher.R
import android.Manifest
import androidx.core.app.ActivityCompat
import app.olauncher.data.AppModel
import app.olauncher.data.CalendarWidgetRepository
import app.olauncher.helper.CalendarEvents
import app.olauncher.data.HomeWidget
import app.olauncher.data.HomeWidgetsRepository
import app.olauncher.data.PinnedAppsRepository
import app.olauncher.data.Prefs
import app.olauncher.helper.AppNotifications
import app.olauncher.helper.DetoxManager
import app.olauncher.helper.IconChooser
import app.olauncher.helper.IconPackManager
import app.olauncher.ui.AppNotificationsView
import app.olauncher.ui.DetoxController
import app.olauncher.helper.SwipeActions
import app.olauncher.helper.FolderShortcuts
import app.olauncher.helper.AppRename
import app.olauncher.helper.ParkingAuto
import app.olauncher.helper.ParkingShortcut
import app.olauncher.ui.NotificationBadge
import app.olauncher.helper.NotificationRepository
import app.olauncher.helper.openAppInfo
import app.olauncher.helper.uninstallEntry
import app.olauncher.helper.MenuEntry
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getScreenDimensions
import app.olauncher.helper.showConfirmDialog
import app.olauncher.helper.showMenuDialog
import app.olauncher.helper.HomeLock.showHomeItemMenu
import app.olauncher.helper.showToast
import app.olauncher.ui.HomeScrollView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The home items: app icons and the news strip on the fixed [grid], widgets at free positions on
 * [free], the layer over the whole home.
 *
 * Adding a widget: pick a provider from our list -> bind (asks the user if needed) -> configure
 * (if the widget has a setup screen) -> saved. Bind/configure results come back through
 * MainActivity.onActivityResult, forwarded to [onActivityResult].
 */
class HomeWidgetsController(
    private val activity: Activity,
    private val grid: WidgetGridLayout,
    private val free: FreeWidgetLayout,
    private val scrollView: HomeScrollView,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    /** Called before launching an external screen, so the launcher doesn't jump back to home meanwhile. */
    private val keepScreen: (Boolean) -> Unit,
    private val onLaunchApp: (AppModel.App) -> Unit = {},
    private val pickImage: ((android.net.Uri) -> Unit) -> Unit = {},
) {
    /**
     * A full-width block sharing the grid, [rowSpan] logical rows tall: the news strip. The grid
     * places it, lets the user drag it to another row and saves where it landed.
     */
    class Block(
        var row: Int,
        val rowSpan: Int = 1,
        val onMenu: () -> Unit,
    )

    /** The news strip: apps with notifications, on a reserved and movable row of the grid. */
    private val newsView = NewsStripView(activity)
    private val newsBlock = Block(prefs.newsRow, rowSpan = TOP_ROW_DIVISIONS) { newsView.flashOutline() }

    private val manager = AppWidgetManager.getInstance(activity)
    private val host = sharedHost(activity)
    private val repo = HomeWidgetsRepository(activity, HomeWidgetsRepository.KEY)
    private var widgets = repo.load()
    private var loadedRaw = repo.raw()
    private val blocks: List<Block> = listOf(newsBlock)
    private var apps: List<AppModel.App> = emptyList()
    private var appsSignature = ""
    private var notifications: Map<String, AppNotifications> = emptyMap()

    /** Where a new item goes when added from a long press on an empty cell. */
    private var preferredCell: Pair<Int, Int>? = null

    private var renderedColumns = 0
    private var renderedLabels = false
    private var renderedTiles = false
    private var renderedCards = false
    private var renderedIconSize = 0

    /**
     * Context used to inflate widgets: the plain app context with a device default theme, NOT the
     * AppCompat activity. AppCompat swaps TextView/ImageView etc. with its own classes during
     * inflation, which makes some widgets fail with "Couldn't add widget".
     */
    private val widgetContext: android.content.Context =
        android.view.ContextThemeWrapper(
            activity.applicationContext,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) android.R.style.Theme_DeviceDefault_DayNight
            else android.R.style.Theme_DeviceDefault_Light
        )

    init {
        free.resizeListener = resizeListener()
        grid.rowDivisions = TOP_ROW_DIVISIONS
        if (repo.migrateRowDivisions(TOP_ROW_DIVISIONS)) widgets = repo.load()
        migrateUngroupedApps()
        migrateBottomArea()
        // Whenever the grid's height changes (first layout included: the dock, the clock, the
        // search bar take their room), keep the lowest icons and the news strip on screen. Moving
        // them is saved, so it waits for the height to settle (the keyboard opens and closes in steps).
        grid.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) {
                grid.post { replaceNews() }
                grid.removeCallbacks(fitRunnable)
                grid.postDelayed(fitRunnable, FIT_DELAY_MS)
            }
        }
    }

    private val density get() = activity.resources.displayMetrics.density
    private fun Int.toDp(): Int = (this / density).toInt()

    /**
     * One-time move of the old bottom area: its app icons go on the grid, its widgets become free
     * widgets near the bottom of the home.
     */
    private fun migrateBottomArea() {
        val bottomRepo = HomeWidgetsRepository(activity, HomeWidgetsRepository.KEY_BOTTOM)
        val items = bottomRepo.load()
        if (items.isEmpty()) return
        val (screenW, screenH) = getScreenDimensions(activity)
        val cellDp = (screenW / prefs.widgetColumns).toDp()
        items.forEach { item ->
            if (item.isApp) {
                if (widgets.any { it.isApp && it.packageName == item.packageName && it.user == item.user }) return@forEach
                val spot = findFreeSpot(1, rowDivisions) ?: return@forEach
                widgets.add(item.copy(col = spot.first, row = spot.second, colSpan = 1, rowSpan = rowDivisions))
            } else {
                val w = (item.colSpan.coerceAtLeast(1) * cellDp)
                val h = (item.rowSpan.coerceAtLeast(1) * cellDp)
                widgets.add(item.copy(x = item.col * cellDp, y = (screenH.toDp() - 200 - h).coerceAtLeast(0), w = w, h = h))
            }
        }
        save()
        bottomRepo.save(emptyList())
    }

    /** One-time move of the apps once pinned on home as a list: they become icons on the grid. */
    private fun migrateUngroupedApps() {
        val pinnedRepo = PinnedAppsRepository(activity)
        val ungrouped = pinnedRepo.loadUngrouped()
        if (ungrouped.isEmpty()) return
        ungrouped.forEach { app ->
            if (widgets.any { it.isApp && it.packageName == app.packageName && it.user == app.user }) return@forEach
            val item = HomeWidget(
                appWidgetId = -1, colSpan = 1, rowSpan = rowDivisions,
                packageName = app.packageName, activityClassName = app.activityClassName, user = app.user,
            )
            val (c, r) = findFreeSpot(1, rowDivisions) ?: return@forEach
            item.col = c; item.row = r
            widgets.add(item)
        }
        save()
        pinnedRepo.saveUngrouped(emptyList())
    }

    private fun save() {
        // An icon placed over an undrawn one takes its cell for good
        widgets.filter { holdsCell(it) }.forEach { dropGhostsUnder(it) }
        repo.save(widgets)
        loadedRaw = repo.raw()
    }

    /** Re-reads the items when they were changed elsewhere (app drawer, folder shortcuts). */
    fun reloadIfChanged() {
        val raw = repo.raw()
        if (raw == loadedRaw) return
        loadedRaw = raw
        widgets = repo.load()
        render()
    }

    /**
     * The grid got shorter (e.g. the clock was turned on) and the lowest icons or the news strip
     * went past the bottom: hides as many empty rows above them as needed, so everything below
     * the gap is shown moved up together and stays visible. Only what is shown changes, the saved
     * positions stay: when the room comes back (a shorter moment while the window or the insets
     * settle, split screen...) the icons go back where they were. Moving an icon by hand while rows
     * are hidden keeps the layout as it is shown.
     */
    private val fitRunnable = Runnable { fitToHeight() }

    /** Rows (in saved positions) hidden by [fitToHeight]; empty when everything fits. */
    private var collapsedRows: List<Int> = emptyList()

    /** The row an item saved on [row] is shown on. */
    private fun shownRow(row: Int): Int = if (row < 0) row else row - collapsedRows.count { it < row }

    private fun fitToHeight() {
        if (grid.height == 0 || grid.editing) return
        // The keyboard (search, a rename dialog...) only borrows the room: the icons stay put
        if (androidx.core.view.ViewCompat.getRootWindowInsets(grid)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true) return
        val icons = widgets.filter { it.isApp && it.row >= 0 }
        // The news strip where it would be with every row shown
        val newsBase = if (newsBlock.row >= 0) newsRow() else -1
        val bottom = maxOf(
            icons.maxOfOrNull { it.row + it.rowSpan } ?: 0,
            if (newsBase >= 0) newsBase + newsBlock.rowSpan else 0,
        )
        val collapsed = mutableListOf<Int>()
        if (bottom > 0 && !grid.fits(0, bottom)) {
            val overflow = grid.rowTop(bottom) - (grid.height - grid.paddingBottom)
            var toRemove = (overflow + grid.rowSize() - 1) / grid.rowSize()
            fun empty(r: Int) = icons.none { r >= it.row && r < it.row + it.rowSpan } &&
                    (newsBase < 0 || r < newsBase || r >= newsBase + newsBlock.rowSpan)
            // Lowest empty rows first: the gap right above the bottom content closes
            for (r in bottom - 1 downTo 0) {
                if (toRemove == 0) break
                if (!empty(r)) continue
                collapsed += r
                toRemove--
            }
        }
        if (collapsed == collapsedRows && (newsBase < 0 || newsBlock.row == shownRow(newsBase))) return
        collapsedRows = collapsed
        if (newsBase >= 0) newsBlock.row = shownRow(newsBase)
        applyShownRows()
    }

    /** Puts every icon and the news strip on the row it is shown on. */
    private fun applyShownRows() {
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i)
            val params = child.layoutParams as? WidgetGridLayout.CellParams ?: continue
            when (val tag = child.tag) {
                is HomeWidget -> if (tag.isApp) params.row = shownRow(tag.row)
                is Block -> params.row = tag.row
            }
        }
        grid.requestLayout()
    }

    /**
     * About to move something by hand while rows are hidden: the layout as shown becomes the saved
     * one, so what the user sees is what they move.
     */
    private fun keepShownRows() {
        if (collapsedRows.isEmpty()) return
        widgets.filter { it.isApp && it.row >= 0 }.forEach { it.row = shownRow(it.row) }
        collapsedRows = emptyList()
        if (newsBlock.row >= 0 && prefs.newsRow >= 0) prefs.newsRow = newsBlock.row
        save()
    }

    /** Widgets and icons on home. */
    fun itemCount(): Int = widgets.size

    /** Puts the news strip on the grid (again), replacing its previous view. */
    private fun placeBlocks() {
        for (i in grid.childCount - 1 downTo 0) if (grid.getChildAt(i).tag is Block) grid.removeViewAt(i)
        if (grid.height == 0 && prefs.newsRow < 0) {
            // The strip's first place is the lowest row: it needs the grid's height
            grid.post { if (newsBlock.row < 0) placeBlocks() }
            return
        }
        newsBlock.row = newsRow()
        if (prefs.newsRow < 0) prefs.newsRow = newsBlock.row
        newsView.tag = newsBlock
        newsView.layoutParams = WidgetGridLayout.CellParams(0, newsBlock.row, prefs.widgetColumns, newsBlock.rowSpan)
        newsView.touch.listener = blockListener
        grid.addView(newsView)
    }

    private fun blockMoved(block: Block) {
        if (block === newsBlock) prefs.newsRow = block.row
    }

    /**
     * Where the news strip goes: the row the user put it on ([Prefs.newsRow], changed only by
     * dragging it) or, while that is taken or past the bottom, the nearest free row. That
     * fallback is not saved, so the strip goes back to its row as soon as it is free again.
     */
    private fun newsRow(): Int {
        val span = newsBlock.rowSpan
        val columns = prefs.widgetColumns
        val wanted = prefs.newsRow
        if (wanted >= 0) for (d in 0..MAX_NEWS_SHIFT) {
            if (wanted - d >= 0 && isFree(0, wanted - d, columns, span, newsBlock)) return wanted - d
            if (d > 0 && isFree(0, wanted + d, columns, span, newsBlock)) return wanted + d
        }
        return findFreeSpotFromBottom(span, newsBlock) ?: findFreeSpot(columns, span, newsBlock)?.second ?: wanted.coerceAtLeast(0)
    }

    /** The grid changed height: the news strip may fit on its own row again (or no longer). */
    private fun replaceNews() {
        if (newsBlock.row < 0 || prefs.newsRow < 0) return
        val row = newsRow()
        if (row == newsBlock.row) return
        newsBlock.row = row
        (newsView.layoutParams as? WidgetGridLayout.CellParams)?.row = row
        grid.requestLayout()
    }

    /** The lowest visible row where a full-width block of [rowSpan] rows is free. */
    private fun findFreeSpotFromBottom(rowSpan: Int, except: Any?): Int? {
        var row = 0
        while (grid.fits(row + 1, rowSpan)) row++
        while (row >= 0) {
            if (isFree(0, row, prefs.widgetColumns, rowSpan, except)) return row
            row--
        }
        return null
    }

    /** Fills the news strip with the apps that have notifications (red tiles). */
    fun setNews(apps: List<AppModel.App>, onMenu: (AppModel.App) -> Unit) {
        newsView.row.removeAllViews()
        val cell = cellPx()
        // Like the home icons: each tile is a cell minus the gap on both sides, so the tiles sit
        // on the grid columns (the strip itself starts one gap in) and a full row fits exactly
        val gap = grid.gap
        newsView.snapStep = cell
        val tile = prefs.iconTiles
        val iconPack = prefs.iconPack
        val context = activity.applicationContext
        apps.forEach { app ->
            val view = IconCellView(activity) { NotificationBadge.showNotifications(activity, app) { onLaunchApp(app) } }
            // Same icon size as on the home, so the strip looks like one more grid row
            val card = prefs.drawerCards
            val size = homeIconSize(view, false, card)
            view.bind(app.appLabel, false, size, tile, card)
            view.setNotified(notifications[NotificationRepository.key(app.appPackage, app.user.toString())]?.count?.coerceAtLeast(1) ?: 1)
            view.setTag(R.id.ml_swipe_app, app)
            view.touch.listener = object : GridItemListener {
                override fun onLongPress(view: View) {}
                override fun onDrag(view: View, dx: Float, dy: Float, rawY: Float) {}
                override fun onDrop(view: View, dx: Float, dy: Float) {}
                override fun onMenu(view: View) = onMenu(app)
            }
            newsView.row.addView(view, android.widget.LinearLayout.LayoutParams(cell - 2 * gap, android.view.ViewGroup.LayoutParams.MATCH_PARENT).apply {
                if (newsView.row.childCount > 0) leftMargin = 2 * gap
            })
            val bitmapSize = view.bitmapSizeFor(size, tile, card)
            scope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    IconPackManager.getIcon(context, app.appPackage, app.activityClassName, app.user, iconPack, bitmapSize)
                }
                view.setIcon(bitmap)
            }
        }
    }

    /** Home menu: shows where the (possibly empty) news strip is. */
    fun showNewsStrip() = newsView.flashOutline()

    /** The strip's rectangle in the free widget layer's coordinates (px), or null before layout. */
    private fun newsRectInFree(): android.graphics.Rect? {
        if (newsBlock.row < 0 || grid.height == 0) return null
        val g = IntArray(2)
        val f = IntArray(2)
        grid.getLocationOnScreen(g)
        free.getLocationOnScreen(f)
        val top = g[1] - f[1] + grid.rowTop(newsBlock.row)
        val bottom = g[1] - f[1] + grid.rowTop(newsBlock.row + newsBlock.rowSpan)
        return android.graphics.Rect(0, top, free.width, bottom)
    }

    /** True when a widget placed at these px bounds would cover the news strip. */
    private fun coversNews(left: Int, top: Int, width: Int, height: Int): Boolean {
        val r = newsRectInFree() ?: return false
        return android.graphics.Rect(left, top, left + width, top + height).intersect(r)
    }

    private val rowDivisions get() = TOP_ROW_DIVISIONS

    /** Names under the icons. */
    private val showLabels get() = prefs.homeShowLabels

    /** Row height in px (cells are split vertically). */
    private fun rowPx(): Int = (cellPx() / rowDivisions).coerceAtLeast(1)

    private fun log(message: String) = WidgetLog.log(activity, message)

    fun release() {
        if (current === this) current = null
    }

    fun setApps(list: List<AppModel.App>) {
        apps = list
        val signature = list.joinToString { "${it.appPackage}/${it.activityClassName}|${it.user}|${it.appLabel}" }
        if (signature != appsSignature) {
            appsSignature = signature
            if (widgets.any { it.isApp }) render()
        }
    }

    /** Keys (package|user) of the app icons placed here. */
    fun appKeys(): Set<String> =
        widgets.filter { it.isApp }.map { NotificationRepository.key(it.packageName.orEmpty(), it.user.orEmpty()) }.toSet()

    fun setNotifications(map: Map<String, AppNotifications>) {
        notifications = map
        updateBadges()
    }

    private fun findApp(item: HomeWidget): AppModel.App? =
        apps.firstOrNull { it.appPackage == item.packageName && it.user.toString() == item.user && it.activityClassName == item.activityClassName }
            ?: apps.firstOrNull { it.appPackage == item.packageName && it.user.toString() == item.user }

    /** First row after everything on the grid. */
    private fun usedRows(): Int = maxOf(
        widgets.filter { it.isApp && it.row >= 0 }.maxOfOrNull { it.row + it.rowSpan } ?: 0,
        blocks.filter { it.row >= 0 }.maxOfOrNull { it.row + it.rowSpan } ?: 0,
    )

    private fun updateBadges() {
        for (i in 0 until grid.childCount) {
            val view = grid.getChildAt(i) as? IconCellView ?: continue
            val item = view.tag as HomeWidget
            view.setNotified(notifications[NotificationRepository.key(item.packageName.orEmpty(), item.user.orEmpty())]?.count ?: 0)
        }
    }

    // ---------------------------------------------------------------- rendering

    /** Grid cell size in px, also before the grid has been laid out. */
    private fun cellPx(): Int {
        val width = grid.width.takeIf { it > 0 } ?: getScreenDimensions(activity).first
        return ((width - grid.paddingLeft - grid.paddingRight) / prefs.widgetColumns).coerceAtLeast(1)
    }

    fun render() {
        endEditing()
        grid.removeAllViews()
        free.removeAllViews()
        val columns = prefs.widgetColumns
        grid.columns = columns
        renderedColumns = columns
        renderedLabels = showLabels
        renderedTiles = prefs.iconTiles
        renderedCards = prefs.drawerCards
        renderedIconSize = prefs.homeIconSize
        val removed = mutableListOf<HomeWidget>()
        // The old battery widget is gone (the battery is in the calendar widget now)
        var changed = widgets.removeAll { it.isBuiltin && it.builtin == OLD_BATTERY_KIND }
        widgets.forEach { widget ->
            if (widget.isApp) {
                if (widget.colSpan != 1 || widget.rowSpan != rowDivisions) {
                    // Icons are one square cell
                    widget.colSpan = 1; widget.rowSpan = rowDivisions
                    changed = true
                }
                if (widget.col >= columns) {
                    widget.col = columns - 1
                    changed = true
                }
                // Added from the app drawer: placed once the grid and widgets are laid out
                if (widget.row < 0) return@forEach
                if (!DetoxManager.isAllowed(activity, widget.packageName.orEmpty())) return@forEach
                // Apps not loaded yet, uninstalled or hidden: skip without deleting
                val app = findApp(widget) ?: return@forEach
                grid.addView(iconView(widget, app))
                return@forEach
            }
            if (widget.isBuiltin) {
                when (widget.builtin) {
                    CalendarWidgetView.KIND -> free.addView(calendarView(widget))
                    DetoxRingView.KIND -> free.addView(detoxRingView(widget))
                }
                return@forEach
            }
            val info = manager.getAppWidgetInfo(widget.appWidgetId)
            if (info == null) {
                // The app was uninstalled or the widget is gone
                removed += widget
                return@forEach
            }
            if (widget.w <= 0) {
                // Saved by an older layout (grid cells or stacked): give it a free position
                val cellDp = cellPx().toDp()
                if (widget.colSpan > 0) {
                    widget.w = widget.colSpan * cellDp
                    widget.h = widget.rowSpan * cellDp / rowDivisions
                    widget.x = grid.paddingLeft.toDp() + widget.col * cellDp
                    widget.y = LEGACY_TOP_DP + widget.row * cellDp / rowDivisions
                } else {
                    widget.w = free.width.takeIf { it > 0 }?.toDp() ?: getScreenDimensions(activity).first.toDp()
                    widget.h = widget.legacyHeightDp.coerceAtLeast(48)
                    widget.x = 0
                    widget.y = LEGACY_TOP_DP
                }
                changed = true
            }
            if (!DetoxManager.isAllowed(activity, info.provider.packageName)) {
                // Detox: widgets of blocked apps are hidden (kept in place for later)
                return@forEach
            }
            free.addView(widgetView(widget, info))
        }
        if (removed.isNotEmpty()) {
            removed.forEach { host.deleteAppWidgetId(it.appWidgetId) }
            widgets.removeAll(removed)
            changed = true
        }
        if (changed) save()
        placeBlocks()
        updateBadges()
        pendingPlaceTries = 0
        placePendingApps()
        // Rows hidden to fit a shorter grid: the new views are already on their shown rows, the strip follows
        grid.post { fitToHeight() }
    }

    private var pendingPlaceTries = 0

    /**
     * Gives a cell to the icons added from the app drawer (row -1): the first free cell not covered
     * by a widget, else the first free cell, else below everything (clipped) with a "home full" toast.
     * Needs the grid and the widget layer laid out, so it waits for them.
     */
    private fun placePendingApps() {
        val pending = widgets.filter { it.isApp && it.row < 0 }
        if (pending.isEmpty()) return
        if (grid.height == 0 || free.width == 0) {
            if (pendingPlaceTries++ < MAX_PLACE_TRIES) grid.post { placePendingApps() }
            return
        }
        var full = false
        pending.forEach { item ->
            val spot = findFreeSpot(1, rowDivisions) { col, row -> !underWidget(col, row, rowDivisions) }
                ?: findFreeSpot(1, rowDivisions)
            if (spot == null) full = true
            item.col = spot?.first ?: 0
            item.row = spot?.second ?: usedRows()
            if (!DetoxManager.isAllowed(activity, item.packageName.orEmpty())) return@forEach
            findApp(item)?.let { grid.addView(iconView(item, it)) }
        }
        save()
        updateBadges()
        if (full) activity.showToast(activity.getString(R.string.ml_home_full))
    }

    /** True when the cell at ([col], [row]) is (even partly) under a widget of the free layer. */
    private fun underWidget(col: Int, row: Int, rowSpan: Int): Boolean {
        val g = IntArray(2)
        val f = IntArray(2)
        grid.getLocationOnScreen(g)
        free.getLocationOnScreen(f)
        val cell = cellPx()
        val left = g[0] - f[0] + grid.paddingLeft + col * cell
        val top = g[1] - f[1] + grid.rowTop(row)
        val bottom = g[1] - f[1] + grid.rowTop(row + rowSpan)
        val rect = android.graphics.Rect(left, top, left + cell, bottom)
        return widgets.any {
            !it.isApp && it.w > 0 && android.graphics.Rect.intersects(
                rect, android.graphics.Rect(it.x.dpToPx(), it.y.dpToPx(), (it.x + it.w).dpToPx(), (it.y + it.h).dpToPx())
            )
        }
    }

    /** Called on resume: rebuilds only if the column or label setting changed meanwhile. */
    fun refreshIfNeeded() {
        if (renderedColumns != prefs.widgetColumns || renderedLabels != showLabels || renderedTiles != prefs.iconTiles || renderedCards != prefs.drawerCards || renderedIconSize != prefs.homeIconSize) render()
    }

    private fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b

    private fun widgetView(widget: HomeWidget, info: AppWidgetProviderInfo): View {
        val hostView = host.createView(widgetContext, widget.appWidgetId, info) as MinimaWidgetHostView
        hostView.setPadding(0, 0, 0, 0)
        hostView.tag = widget
        hostView.layoutParams = android.widget.FrameLayout.LayoutParams(widget.w.dpToPx(), widget.h.dpToPx()).apply {
            leftMargin = widget.x.dpToPx()
            topMargin = widget.y.dpToPx()
        }
        hostView.touch.listener = dragListener(info)
        updateSize(hostView, widget)
        return hostView
    }

    private fun calendarView(widget: HomeWidget): View {
        val view = CalendarWidgetView(activity, widget.appWidgetId, scope)
        view.tag = widget
        view.layoutParams = android.widget.FrameLayout.LayoutParams(widget.w.dpToPx(), widget.h.dpToPx()).apply {
            leftMargin = widget.x.dpToPx()
            topMargin = widget.y.dpToPx()
        }
        view.touch.listener = dragListener(null)
        return view
    }

    private fun detoxRingView(widget: HomeWidget): View {
        val view = DetoxRingView(activity, widget.appWidgetId)
        view.tag = widget
        view.layoutParams = android.widget.FrameLayout.LayoutParams(widget.w.dpToPx(), widget.h.dpToPx()).apply {
            leftMargin = widget.x.dpToPx()
            topMargin = widget.y.dpToPx()
        }
        view.touch.listener = dragListener(null)
        return view
    }

    /** The home icon size setting (dp) wins over the automatic size from the cell. */
    private fun homeIconSize(view: IconCellView, showLabel: Boolean, card: Boolean = false): Int = when {
        // On a card the view itself shrinks the icon to leave room for the name
        prefs.homeIconSize > 0 -> prefs.homeIconSize.dpToPx().coerceAtMost(cellPx() - 4.dpToPx())
        card -> view.cardIconSizeFor(cellPx(), showLabel)
        else -> view.iconSizeFor(cellPx(), showLabel)
    }

    private fun iconView(item: HomeWidget, app: AppModel.App): View {
        val view = IconCellView(activity) { onLaunchApp(app) }
        view.onNotifiedTap = { NotificationBadge.showNotifications(activity, app) { onLaunchApp(app) } }
        view.tag = item
        view.setTag(R.id.ml_swipe_app, app)
        view.layoutParams = WidgetGridLayout.CellParams(item.col, shownRow(item.row), 1, rowDivisions)
        val showLabel = showLabels
        val tile = prefs.iconTiles
        // Cards like the app drawer's (the same setting)
        val card = prefs.drawerCards
        val size = homeIconSize(view, showLabel, card)
        view.bind(app.appLabel, showLabel, size, tile, card)
        view.touch.listener = dragListener(null)
        val iconPack = prefs.iconPack
        val context = activity.applicationContext
        val bitmapSize = view.bitmapSizeFor(size, tile, card)
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                IconPackManager.getIcon(context, app.appPackage, app.activityClassName, app.user, iconPack, bitmapSize)
            }
            view.setIcon(bitmap)
        }
        return view
    }

    @Suppress("DEPRECATION")
    private fun updateSize(view: MinimaWidgetHostView, widget: HomeWidget) {
        val w = widget.w
        val h = widget.h
        try {
            view.updateAppWidgetSize(Bundle(), w, h, w, h)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun overlaps(a: HomeWidget, col: Int, row: Int, colSpan: Int, rowSpan: Int) =
        col < a.col + a.colSpan && a.col < col + colSpan && row < a.row + a.rowSpan && a.row < row + rowSpan

    /**
     * [except] is the item being moved (an app [HomeWidget] or a [Block]), which never blocks
     * itself. Only icons and the news strip occupy the grid; widgets float over it. The cells must
     * be visible: the home doesn't scroll.
     */
    /**
     * An icon whose app isn't there any more (uninstalled, or its activity gone) is not drawn: it
     * doesn't hold its cell either. During detox, or before the apps are known, it still does.
     */
    private fun holdsCell(item: HomeWidget): Boolean =
        item.isApp && item.row >= 0 &&
                (apps.isEmpty() || !DetoxManager.isAllowed(activity, item.packageName.orEmpty()) || findApp(item) != null)

    /** Drops the undrawn icons under [item] (just placed there): their cell is taken now. */
    private fun dropGhostsUnder(item: HomeWidget) {
        if (apps.isEmpty()) return
        widgets.removeAll { it !== item && it.isApp && it.row >= 0 && !holdsCell(it) && overlaps(it, item.col, item.row, item.colSpan, item.rowSpan) }
    }

    private fun isFree(col: Int, row: Int, colSpan: Int, rowSpan: Int, except: Any?): Boolean =
        col >= 0 && row >= 0 && col + colSpan <= prefs.widgetColumns && grid.fits(row, rowSpan) &&
                widgets.none { it !== except && holdsCell(it) && overlaps(it, col, row, colSpan, rowSpan) } &&
                blocks.none { it !== except && it.row >= 0 && row < it.row + it.rowSpan && it.row < row + rowSpan }

    /** First free cell for an item of that size (also passing [accept]), or null when the home is full. */
    private fun findFreeSpot(
        colSpan: Int,
        rowSpan: Int,
        except: Any? = null,
        accept: (col: Int, row: Int) -> Boolean = { _, _ -> true },
    ): Pair<Int, Int>? {
        val columns = prefs.widgetColumns
        var row = 0
        while (grid.fits(row, rowSpan)) {
            for (col in 0..(columns - colSpan).coerceAtLeast(0))
                if (isFree(col, row, colSpan, rowSpan, except) && accept(col, row)) return col to row
            row++
        }
        return null
    }

    // ---------------------------------------------------------------- move (drag) and resize

    private var dragDx = 0f
    private var dragDy = 0f

    private fun startDrag(view: View) {
        endEditing()
        scrollView.gesturesEnabled = false
        if (view.tag is HomeWidget && !(view.tag as HomeWidget).isApp) {
            view.bringToFront()
        } else grid.editing = true
        newsView.setOutlined(true)
        dragDx = 0f
        dragDy = 0f
    }

    /** Moves the dragged view under the finger and previews where it would land. */
    private fun trackDrag(view: View, dx: Float, dy: Float) {
        keepShownRows()
        dragDx = dx; dragDy = dy
        view.translationX = dx
        view.translationY = dy
        view.alpha = 0.8f
        when (val item = view.tag) {
            is HomeWidget -> if (item.isApp) {
                val (col, row) = grid.dropCellFor(view, dx, dy)
                grid.showDropPreview(col, row, item.colSpan, item.rowSpan, isFree(col, row, item.colSpan, item.rowSpan, item))
            }

            is Block -> {
                view.translationX = 0f
                val row = grid.dropCellFor(view, 0f, dy).second
                grid.showDropPreview(0, row, prefs.widgetColumns, item.rowSpan, isFree(0, row, prefs.widgetColumns, item.rowSpan, item))
            }
        }
    }

    /** Puts the dragged view back in flow; returns the grid cell it was dropped on (widgets: unused). */
    private fun finishDrag(view: View): Pair<Int, Int> {
        val cell = if (view.parent === grid) grid.dropCellFor(view, if (view.tag is Block) 0f else dragDx, dragDy) else 0 to 0
        view.translationX = 0f
        view.translationY = 0f
        view.alpha = 1f
        grid.hideDropPreview()
        grid.editing = false
        newsView.setOutlined(false)
        scrollView.gesturesEnabled = true
        return cell
    }

    private fun dragListener(info: AppWidgetProviderInfo?) = object : GridItemListener {
        override fun onLongPress(view: View) = startDrag(view)

        override fun onDrag(view: View, dx: Float, dy: Float, rawY: Float) = trackDrag(view, dx, dy)

        override fun onDrop(view: View, dx: Float, dy: Float) {
            val widget = view.tag as HomeWidget
            val (col, row) = finishDrag(view)
            if (!widget.isApp) {
                // Free widget: lands where it was left, kept inside the home and off the news strip
                val p = view.layoutParams as android.widget.FrameLayout.LayoutParams
                val left = free.clampX(p.leftMargin + dx.toInt(), p.width)
                val top = free.clampY(p.topMargin + dy.toInt(), p.height)
                if (coversNews(left, top, p.width, p.height)) {
                    activity.showToast(activity.getString(R.string.ml_news_zone_blocked))
                    return
                }
                p.leftMargin = left
                p.topMargin = top
                view.layoutParams = p
                widget.x = p.leftMargin.toDp(); widget.y = p.topMargin.toDp()
                save()
                return
            }
            if (isFree(col, row, widget.colSpan, widget.rowSpan, widget)) {
                widget.col = col
                widget.row = row
                (view.layoutParams as WidgetGridLayout.CellParams).apply { this.col = col; this.row = row }
                grid.requestLayout()
                save()
            }
        }

        override fun onMenu(view: View) {
            finishDrag(view)
            val item = view.tag as HomeWidget
            // The parking button: a long press opens the saved position (its menu until one is saved)
            if (item.isApp && ParkingShortcut.isParking(item.packageName) && ParkingShortcut.longPressOpensMaps(activity)) {
                ParkingShortcut.openMaps(activity)
                return
            }
            when {
                view is CalendarWidgetView -> showCalendarMenu(item, view)
                view is DetoxRingView -> showDetoxRingMenu(item, view)
                info != null -> showWidgetMenu(item, info, view as MinimaWidgetHostView)
                else -> showIconMenu(item)
            }
        }
    }

    /** The news strip moves vertically only, one logical row at a time. */
    private val blockListener = object : GridItemListener {
        override fun onLongPress(view: View) = startDrag(view)

        override fun onDrag(view: View, dx: Float, dy: Float, rawY: Float) = trackDrag(view, dx, dy)

        override fun onDrop(view: View, dx: Float, dy: Float) {
            val block = view.tag as Block
            val row = finishDrag(view).second
            if (row != block.row && isFree(0, row, prefs.widgetColumns, block.rowSpan, block)) {
                block.row = row
                (view.layoutParams as WidgetGridLayout.CellParams).row = row
                grid.requestLayout()
                blockMoved(block)
            }
        }

        override fun onMenu(view: View) {
            finishDrag(view)
            (view.tag as Block).onMenu()
        }
    }

    private fun resizeListener() = object : FreeWidgetLayout.ResizeListener {
        override fun onResizeDragging(active: Boolean) {
            scrollView.gesturesEnabled = !active
            newsView.setOutlined(active)
        }

        override fun onResized(view: View) {
            val widget = view.tag as HomeWidget
            val p = view.layoutParams as android.widget.FrameLayout.LayoutParams
            if (coversNews(p.leftMargin, p.topMargin, p.width, p.height)) {
                // Back to the saved bounds: the news strip stays clear
                activity.showToast(activity.getString(R.string.ml_news_zone_blocked))
                p.leftMargin = widget.x.dpToPx(); p.topMargin = widget.y.dpToPx()
                p.width = widget.w.dpToPx(); p.height = widget.h.dpToPx()
                view.layoutParams = p
                return
            }
            widget.x = p.leftMargin.toDp(); widget.y = p.topMargin.toDp()
            widget.w = p.width.toDp(); widget.h = p.height.toDp()
            save()
            (view as? MinimaWidgetHostView)?.let { updateSize(it, widget) }
        }

        override fun onTapOutsideFrame() = endEditing()
    }

    /** Leaves resize mode (tap outside the frame, or anywhere on home). */
    fun endEditing() {
        if (free.editTarget != null) free.editTarget = null
        scrollView.gesturesEnabled = true
    }

    // ---------------------------------------------------------------- adding

    /** The next added item goes on this cell when it fits there (long press on an empty cell). */
    fun setPreferredCell(col: Int?, row: Int?) {
        preferredCell = if (col != null && row != null) col to row else null
    }

    fun showAddAppDialog() {
        val entries = apps.sortedBy { it.appLabel.lowercase() }.map { app ->
            MenuEntry(app.appLabel) { if (!addApp(app)) activity.showToast(activity.getString(R.string.ml_already_on_home)) }
        }
        activity.showMenuDialog(activity.getString(R.string.ml_add_app), entries)
    }

    /** Puts an icon of [app] on the grid (on the preferred cell, else the first free one). False if it was already there. */
    fun addApp(app: AppModel.App): Boolean {
        val user = app.user.toString()
        if (widgets.any { it.isApp && it.packageName == app.appPackage && it.user == user }) {
            preferredCell = null
            return false
        }
        val item = HomeWidget(
            appWidgetId = -1, colSpan = 1, rowSpan = rowDivisions,
            packageName = app.appPackage, activityClassName = app.activityClassName, user = app.user.toString(),
        )
        val (c, r) = placeFor(1, rowDivisions) ?: run {
            activity.showToast(activity.getString(R.string.ml_home_full))
            return true
        }
        item.col = c; item.row = r
        widgets.add(item)
        save()
        render()
        return true
    }

    /** The long-pressed cell when the item fits there, otherwise the first free spot; null when home is full. */
    private fun placeFor(colSpan: Int, rowSpan: Int): Pair<Int, Int>? {
        val preferred = preferredCell
        preferredCell = null
        if (preferred != null) {
            val col = preferred.first.coerceAtMost(prefs.widgetColumns - colSpan).coerceAtLeast(0)
            if (isFree(col, preferred.second, colSpan, rowSpan, null)) return col to preferred.second
        }
        return findFreeSpot(colSpan, rowSpan)
    }

    /** Where a new widget of [w] x [h] dp goes: the long-pressed cell, else the top left, lower for each widget there. */
    private fun freePositionFor(w: Int, h: Int): Pair<Int, Int> {
        val preferred = preferredCell
        preferredCell = null
        val layerW = (free.width.takeIf { it > 0 } ?: getScreenDimensions(activity).first).toDp()
        val layerH = (free.height.takeIf { it > 0 } ?: getScreenDimensions(activity).second).toDp()
        if (preferred != null) {
            val cellDp = cellPx().toDp()
            val x = (grid.paddingLeft.toDp() + preferred.first * cellDp).coerceIn(0, (layerW - w).coerceAtLeast(0))
            val y = (gridTopDp() + grid.rowTop(preferred.second).toDp()).coerceIn(0, (layerH - h).coerceAtLeast(0))
            return x to y
        }
        val others = widgets.filter { !it.isApp && it.w > 0 }
        var y = LEGACY_TOP_DP
        while (others.any { it.y == y && it.x == 0 } && y + h < layerH) y += 24
        return 0 to y.coerceIn(0, (layerH - h).coerceAtLeast(0))
    }

    /** The grid's top edge inside the free layer (dp). */
    private fun gridTopDp(): Int {
        val g = IntArray(2)
        val f = IntArray(2)
        grid.getLocationOnScreen(g)
        free.getLocationOnScreen(f)
        return (g[1] - f[1]).toDp()
    }

    private fun showIconMenu(item: HomeWidget) {
        val app = findApp(item) ?: return
        if (ParkingShortcut.isParking(app.appPackage)) {
            activity.showHomeItemMenu(
                ParkingShortcut.describe(activity),
                ParkingShortcut.menuEntries(activity) + listOfNotNull(
                    AppRename.menuEntry(activity, app),
                    MenuEntry(activity.getString(R.string.ml_change_icon)) { IconChooser.show(activity, app, scope, pickImage) { render() } },
                    MenuEntry(activity.getString(R.string.ml_remove_from_home)) {
                        widgets.remove(item)
                        save()
                        render()
                    },
                ) + ParkingAuto.menuEntries(activity)
            )
            return
        }
        activity.showHomeItemMenu(
            app.appLabel,
            listOfNotNull(
                MenuEntry(activity.getString(R.string.ml_remove_from_home)) {
                    widgets.remove(item)
                    save()
                    render()
                },
                AppRename.menuEntry(activity, app),
                MenuEntry(activity.getString(R.string.ml_change_icon)) {
                    IconChooser.show(activity, app, scope, pickImage) { render() }
                },
                MenuEntry(activity.getString(R.string.ml_app_info)) {
                    openAppInfo(activity, app.user, app.appPackage)
                },
                // As in the app drawer; with app info it also gives the menu its tiles on top
                activity.uninstallEntry(app.appPackage, app.user),
            ).let { base ->
                if (FolderShortcuts.isFolder(app.appPackage))
                    base.filter { it.label != activity.getString(R.string.ml_app_info) } +
                            FolderShortcuts.menuEntries(activity, app) { SwipeActions.onFoldersChanged?.invoke() } +
                            DetoxController.whitelistEntry(activity, app)
                else base + DetoxController.whitelistEntry(activity, app)
            } + SwipeActions.menuEntries(activity, app) { apps },
            header = AppNotificationsView.create(activity, app),
        )
    }

    fun showAddWidgetDialog() {
        val pm = activity.packageManager
        val providers = try {
            manager.installedProviders
        } catch (e: Exception) {
            emptyList()
        }
        val cell = cellPx()
        val items = providers.map { info ->
            val app = try {
                pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString()
            } catch (e: Exception) {
                info.provider.packageName
            }
            val label = try {
                info.loadLabel(pm)
            } catch (e: Exception) {
                null
            } ?: info.provider.shortClassName
            WidgetPickerDialog.Item(
                app = app,
                label = label,
                info = info,
                cols = ceilDiv(info.minWidth, cell).coerceIn(1, prefs.widgetColumns),
                rows = ceilDiv(info.minHeight, cell).coerceAtLeast(1),
            )
        }.sortedWith(compareBy({ it.app.lowercase() }, { it.label.lowercase() }))
        val builtIns = listOf(
            WidgetPickerDialog.BuiltIn(activity.getString(R.string.ml_cal_widget), R.drawable.ic_calendar) { addCalendarWidget() },
            WidgetPickerDialog.BuiltIn(activity.getString(R.string.ml_home_detox), R.drawable.ic_m_detox) { addDetoxRingWidget() },
        )
        WidgetPickerDialog.show(activity, items, scope, builtIns) { startAdding(it) }
    }

    /** Adds the launcher's calendar widget: full width, three cells tall; asks for the calendar access. */
    private fun addCalendarWidget() {
        val id = (widgets.filter { it.isBuiltin }.minOfOrNull { it.appWidgetId } ?: BUILTIN_FIRST_ID) - 1
        val cellDp = cellPx().toDp()
        val w = prefs.widgetColumns * cellDp
        val h = 3 * cellDp
        val (x, y) = freePositionFor(w, h)
        widgets.add(HomeWidget(id, x = x, y = y, w = w, h = h, builtin = CalendarWidgetView.KIND))
        save()
        render()
        if (!CalendarEvents.hasPermission(activity))
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.READ_CALENDAR), CalendarWidgetView.REQUEST_CALENDAR)
    }

    /** Adds the detox home ring: small and square, to be moved and resized at will. */
    private fun addDetoxRingWidget() {
        val id = (widgets.filter { it.isBuiltin }.minOfOrNull { it.appWidgetId } ?: BUILTIN_FIRST_ID) - 1
        val size = (cellPx().toDp() / 2).coerceAtLeast(DetoxRingView.MIN_SIZE_DP)
        val (x, y) = freePositionFor(size, size)
        widgets.add(HomeWidget(id, x = x, y = y, w = size, h = size, builtin = DetoxRingView.KIND))
        save()
        render()
        if (!prefs.homeDetox) activity.showToast(activity.getString(R.string.ml_home_detox_off_hint))
    }

    private fun showDetoxRingMenu(widget: HomeWidget, view: DetoxRingView) {
        activity.showHomeItemMenu(
            activity.getString(R.string.ml_home_detox),
            listOf(
                MenuEntry(activity.getString(R.string.ml_widget_resize)) { free.editTarget = view },
                MenuEntry(activity.getString(R.string.ml_widget_remove)) {
                    widgets.remove(widget)
                    save()
                    render()
                },
            )
        )
    }

    private fun showCalendarMenu(widget: HomeWidget, view: CalendarWidgetView) {
        activity.showHomeItemMenu(
            activity.getString(R.string.ml_cal_widget),
            listOf(
                MenuEntry(activity.getString(R.string.ml_cal_settings)) { CalendarWidgetSettings.show(activity, view, scope) },
                MenuEntry(activity.getString(R.string.ml_widget_resize)) { free.editTarget = view },
                MenuEntry(activity.getString(R.string.ml_widget_remove)) {
                    activity.showConfirmDialog(
                        title = activity.getString(R.string.ml_widget_remove),
                        message = activity.getString(R.string.ml_widget_remove_confirm),
                        action = activity.getString(R.string.ml_widget_remove),
                    ) {
                        CalendarWidgetRepository(activity).delete(widget.appWidgetId)
                        widgets.remove(widget)
                        save()
                        render()
                    }
                },
            )
        )
    }

    private fun startAdding(info: AppWidgetProviderInfo) {
        current = this
        val id = host.allocateAppWidgetId()
        pendingId = id
        pendingInfo = info
        log("add ${info.provider.flattenToShortString()} id=$id configure=${info.configure?.flattenToShortString()}")
        val bound = try {
            manager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)
        } catch (e: Exception) {
            log("bind error: $e")
            false
        }
        log("bindIfAllowed=$bound")
        if (bound) configureOrFinish(id, info)
        else {
            // The system asks the user to allow MinimaLauncher to create widgets
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
            launchForResult(REQUEST_BIND) {
                @Suppress("DEPRECATION")
                activity.startActivityForResult(intent, REQUEST_BIND)
            }
        }
    }

    private fun configureOrFinish(id: Int, info: AppWidgetProviderInfo) {
        if (info.configure == null) {
            finishAdding(id, info)
            return
        }
        if (!launchConfigure(id, info, REQUEST_CONFIGURE)) finishAdding(id, info)
    }

    /**
     * Opens the widget's own setup screen. Launched directly from the launcher when possible (many
     * apps, e.g. Google and Samsung ones, refuse setup requests that don't come from the host
     * itself), through the system otherwise. Returns false if it couldn't be started.
     */
    private fun launchConfigure(id: Int, info: AppWidgetProviderInfo, requestCode: Int): Boolean {
        val direct = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
            .setComponent(info.configure)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        val sameProfile = info.profile == android.os.Process.myUserHandle()
        val exported = try {
            activity.packageManager.getActivityInfo(info.configure, 0).exported
        } catch (e: Exception) {
            false
        }
        log("configure exported=$exported sameProfile=$sameProfile")
        configureLaunchTime = SystemClock.elapsedRealtime()
        if (requestCode == REQUEST_CONFIGURE) {
            // Hidden view for the new widget: if the app pushes content while (or after) being set up,
            // the widget was configured even if Android reports "cancelled"
            probeView = try {
                host.createView(widgetContext, id, info) as MinimaWidgetHostView
            } catch (e: Exception) {
                null
            }
        }
        if (sameProfile && exported) {
            try {
                launchForResult(requestCode) {
                    @Suppress("DEPRECATION")
                    activity.startActivityForResult(direct, requestCode)
                }
                log("configure started directly")
                return true
            } catch (e: Exception) {
                log("direct configure failed: $e")
                keepScreen(false)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                launchForResult(requestCode) {
                    host.startAppWidgetConfigureActivityForResult(activity, id, 0, requestCode, null)
                }
                log("configure started through the system")
                return true
            } catch (e: Exception) {
                log("system configure failed: $e")
                keepScreen(false)
            }
        }
        activity.showToast(activity.getString(R.string.ml_widget_setup_failed))
        return false
    }

    private fun launchForResult(@Suppress("UNUSED_PARAMETER") requestCode: Int, start: () -> Unit) {
        keepScreen(true)
        start()
    }

    private fun finishAdding(id: Int, info: AppWidgetProviderInfo) {
        log("added id=$id")
        pendingId = -1
        pendingInfo = null
        val cellDp = cellPx().toDp()
        // Sizes in whole cells, like a launcher would, but placed freely
        val w = (ceilDiv(info.minWidth.toDp(), cellDp) * cellDp).coerceIn(cellDp, prefs.widgetColumns * cellDp)
        val h = (ceilDiv(info.minHeight.toDp(), cellDp / rowDivisions) * cellDp / rowDivisions).coerceAtLeast(cellDp / rowDivisions)
        val (x, y) = freePositionFor(w, h)
        widgets.add(HomeWidget(id, x = x, y = y, w = w, h = h))
        save()
        render()
    }

    private fun cancelAdding() {
        log("cancelled id=$pendingId")
        if (pendingId != -1) host.deleteAppWidgetId(pendingId)
        pendingId = -1
        pendingInfo = null
    }

    /** Returns true when the result belonged to the widget flow. */
    fun onActivityResult(requestCode: Int, resultCode: Int): Boolean {
        if (requestCode != REQUEST_BIND && requestCode != REQUEST_CONFIGURE && requestCode != REQUEST_RECONFIGURE) return false
        val ok = resultCode == Activity.RESULT_OK
        val elapsed = SystemClock.elapsedRealtime() - configureLaunchTime
        log("result request=$requestCode ok=$ok after ${elapsed}ms")
        keepScreen(false)
        when (requestCode) {
            REQUEST_BIND -> {
                val info = pendingInfo
                if (ok && info != null) configureOrFinish(pendingId, info)
                else {
                    activity.showToast(activity.getString(R.string.ml_widget_bind_denied))
                    cancelAdding()
                }
            }

            REQUEST_CONFIGURE -> {
                val info = pendingInfo
                val configured = probeView?.receivedUpdate == true
                probeView = null
                if (info != null && (ok || configured)) {
                    if (!ok) log("reported cancelled, but the widget received content: adding it")
                    finishAdding(pendingId, info)
                } else {
                    activity.showToast(activity.getString(R.string.ml_widget_setup_cancelled))
                    cancelAdding()
                }
            }

            REQUEST_RECONFIGURE -> render()
        }
        return true
    }

    /**
     * Home is visible again. If a setup screen is still pending without having returned a result
     * (some apps finish without one), decide now from whether the widget received content.
     */
    fun onHomeResumed() {
        if (current !== this) return
        val info = pendingInfo ?: return
        val probe = probeView ?: return
        if (SystemClock.elapsedRealtime() - configureLaunchTime < 1000) return
        log("home resumed with setup pending, received content=${probe.receivedUpdate}")
        probeView = null
        keepScreen(false)
        if (probe.receivedUpdate) finishAdding(pendingId, info) else cancelAdding()
    }

    // ---------------------------------------------------------------- menu, resize, remove

    private fun showWidgetMenu(widget: HomeWidget, info: AppWidgetProviderInfo, hostView: MinimaWidgetHostView) {
        val entries = mutableListOf(
            MenuEntry(activity.getString(R.string.ml_widget_resize)) { free.editTarget = hostView },
        )
        if (info.configure != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            entries += MenuEntry(activity.getString(R.string.ml_widget_reconfigure)) {
                launchConfigure(widget.appWidgetId, info, REQUEST_RECONFIGURE)
            }
        entries += MenuEntry(activity.getString(R.string.ml_widget_remove)) {
            activity.showConfirmDialog(
                title = activity.getString(R.string.ml_widget_remove),
                message = activity.getString(R.string.ml_widget_remove_confirm),
                action = activity.getString(R.string.ml_widget_remove),
            ) {
                host.deleteAppWidgetId(widget.appWidgetId)
                widgets.remove(widget)
                save()
                render()
            }
        }
        val title = try {
            info.loadLabel(activity.packageManager)
        } catch (e: Exception) {
            null
        } ?: activity.getString(R.string.ml_widget)
        activity.showHomeItemMenu(title, entries)
    }

    companion object {
        /** Kind of the removed battery widget, dropped from the saved layouts. */
        private const val OLD_BATTERY_KIND = "battery"
        /** The grid's rows are half a column tall, so icons pack tighter. */
        const val TOP_ROW_DIVISIONS = 2

        /** How long the grid's height must stay the same before the icons are moved to fit it. */
        private const val FIT_DELAY_MS = 800L

        /** Where widgets from older layouts land (dp from the top of home, below the clock). */
        private const val LEGACY_TOP_DP = 140

        /** Ids of the launcher's own widgets count down from here (Android's ids are positive). */
        private const val BUILTIN_FIRST_ID = -1000

        /** How many rows away from its own row the news strip may go while that row is taken. */
        private const val MAX_NEWS_SHIFT = 64

        /** Frames to wait for the home layout before placing icons added from the app drawer. */
        private const val MAX_PLACE_TRIES = 20

        /**
         * Pins [app] on the main home grid from outside home (app drawer, folder shortcuts); the
         * home picks the change up when it's shown again. Returns false if it was already there.
         */
        fun addAppToHome(context: android.content.Context, app: AppModel.App): Boolean {
            val repo = HomeWidgetsRepository(context, HomeWidgetsRepository.KEY)
            val items = repo.load()
            val user = app.user.toString()
            if (items.any { it.isApp && it.packageName == app.appPackage && it.user == user }) return false
            items.add(
                HomeWidget(
                    // row -1: the home picks the cell, as it knows where the widgets are
                    appWidgetId = -1, col = 0, row = -1, colSpan = 1, rowSpan = TOP_ROW_DIVISIONS,
                    packageName = app.appPackage, activityClassName = app.activityClassName, user = user,
                )
            )
            repo.save(items)
            return true
        }

        private const val HOST_ID = 2026
        private var sharedHostInstance: MinimaWidgetHost? = null

        /** One host for all home grids: Android keeps a single listener per host id. */
        fun sharedHost(context: android.content.Context): MinimaWidgetHost =
            sharedHostInstance ?: MinimaWidgetHost(context.applicationContext, HOST_ID).also { sharedHostInstance = it }

        fun startListening(context: android.content.Context) = try {
            sharedHost(context).startListening()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        fun stopListening(context: android.content.Context) = try {
            sharedHost(context).stopListening()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        const val REQUEST_BIND = 7101
        const val REQUEST_CONFIGURE = 7102
        const val REQUEST_RECONFIGURE = 7103

        // The add flow spans activity results; keep its state outside the fragment view
        private var pendingId = -1
        private var pendingInfo: AppWidgetProviderInfo? = null
        private var configureLaunchTime = 0L
        private var probeView: MinimaWidgetHostView? = null

        /** Controller of the visible home, used by MainActivity to forward activity results. */
        var current: HomeWidgetsController? = null
            private set
    }
}
