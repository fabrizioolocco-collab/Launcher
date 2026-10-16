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
import app.olauncher.data.AppModel
import app.olauncher.data.HomeWidget
import app.olauncher.data.HomeWidgetsRepository
import app.olauncher.data.Prefs
import app.olauncher.helper.AppNotifications
import app.olauncher.helper.DetoxManager
import app.olauncher.helper.IconChooser
import app.olauncher.helper.IconPackManager
import app.olauncher.ui.AppNotificationsView
import app.olauncher.ui.NotificationBadge
import app.olauncher.helper.NotificationRepository
import app.olauncher.helper.openAppInfo
import app.olauncher.helper.MenuEntry
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getScreenDimensions
import app.olauncher.helper.showConfirmDialog
import app.olauncher.helper.showMenuDialog
import app.olauncher.helper.showToast
import app.olauncher.ui.HomeScrollView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A home grid: the widget area at the top, or the fixed bottom area (widgets and app icons).
 *
 * Adding a widget: pick a provider from our list -> bind (asks the user if needed) -> configure
 * (if the widget has a setup screen) -> saved. Bind/configure results come back through
 * MainActivity.onActivityResult, forwarded to [onActivityResult].
 */
class HomeWidgetsController(
    private val activity: Activity,
    private val grid: WidgetGridLayout,
    private val scrollView: HomeScrollView,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    /** Called before launching an external screen, so the launcher doesn't jump back to home meanwhile. */
    private val keepScreen: (Boolean) -> Unit,
    /** Bottom area: holds app icons too and keeps a minimum height. */
    private val bottom: Boolean = false,
    private val onLaunchApp: (AppModel.App) -> Unit = {},
    private val pickImage: ((android.net.Uri) -> Unit) -> Unit = {},
) {
    private val manager = AppWidgetManager.getInstance(activity)
    private val host = sharedHost(activity)
    private val repo = HomeWidgetsRepository(activity, if (bottom) HomeWidgetsRepository.KEY_BOTTOM else HomeWidgetsRepository.KEY)
    private var widgets = repo.load()
    private var apps: List<AppModel.App> = emptyList()
    private var appsSignature = ""
    private var notifications: Map<String, AppNotifications> = emptyMap()

    /** Where a new item goes when added from a long press on an empty cell. */
    private var preferredCell: Pair<Int, Int>? = null

    private var renderedColumns = 0
    private var renderedLabels = false

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
        grid.resizeListener = resizeListener()
        if (bottom) grid.minRows = 1
        else {
            grid.rowDivisions = TOP_ROW_DIVISIONS
            if (repo.migrateRowDivisions(TOP_ROW_DIVISIONS)) widgets = repo.load()
        }
    }

    private val rowDivisions get() = if (bottom) 1 else TOP_ROW_DIVISIONS

    /** Row height in px (cells are split vertically in the top grid). */
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

    private fun updateBadges() {
        for (i in 0 until grid.childCount) {
            val view = grid.getChildAt(i) as? IconCellView ?: continue
            val item = view.tag as HomeWidget
            view.setNotified((notifications[NotificationRepository.key(item.packageName.orEmpty(), item.user.orEmpty())]?.count ?: 0) > 0)
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
        val columns = prefs.widgetColumns
        grid.columns = columns
        renderedColumns = columns
        renderedLabels = prefs.bottomLabels
        val removed = mutableListOf<HomeWidget>()
        var changed = false
        widgets.forEach { widget ->
            if (widget.isApp) {
                if (!DetoxManager.isAllowed(activity, widget.packageName.orEmpty())) return@forEach
                // Apps not loaded yet, uninstalled or hidden: skip without deleting
                val app = findApp(widget) ?: return@forEach
                grid.addView(iconView(widget, app))
                return@forEach
            }
            val info = manager.getAppWidgetInfo(widget.appWidgetId)
            if (info == null) {
                // The app was uninstalled or the widget is gone
                removed += widget
                return@forEach
            }
            if (widget.colSpan <= 0) {
                // Saved by the older stacked layout: full width, same height, first free rows
                widget.colSpan = columns
                widget.rowSpan = ceilDiv(widget.legacyHeightDp.dpToPx(), rowPx()).coerceAtLeast(1)
                val (c, r) = findFreeSpot(widget.colSpan, widget.rowSpan, except = widget)
                widget.col = c; widget.row = r
                changed = true
            }
            if (!DetoxManager.isAllowed(activity, info.provider.packageName)) {
                // Detox: widgets of blocked apps are hidden (kept in place for later)
                return@forEach
            }
            if (widget.col + widget.colSpan > columns) {
                // Fewer columns than when it was placed
                widget.colSpan = widget.colSpan.coerceAtMost(columns)
                widget.col = widget.col.coerceAtMost(columns - widget.colSpan)
                changed = true
            }
            grid.addView(widgetView(widget, info))
        }
        if (removed.isNotEmpty()) {
            removed.forEach { host.deleteAppWidgetId(it.appWidgetId) }
            widgets.removeAll(removed)
            changed = true
        }
        if (changed) repo.save(widgets)
        grid.visibility = if (grid.childCount == 0 && !bottom) View.GONE else View.VISIBLE
        updateBadges()
    }

    /** Called on resume: rebuilds only if the column setting changed meanwhile. */
    fun refreshIfNeeded() {
        if (renderedColumns != prefs.widgetColumns || (bottom && renderedLabels != prefs.bottomLabels)) render()
    }

    private fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b

    private fun widgetView(widget: HomeWidget, info: AppWidgetProviderInfo): View {
        val hostView = host.createView(widgetContext, widget.appWidgetId, info) as MinimaWidgetHostView
        hostView.setPadding(0, 0, 0, 0)
        hostView.tag = widget
        hostView.layoutParams = WidgetGridLayout.CellParams(widget.col, widget.row, widget.colSpan, widget.rowSpan)
        hostView.touch.listener = dragListener(info)
        updateSize(hostView, widget)
        return hostView
    }

    private fun iconView(item: HomeWidget, app: AppModel.App): View {
        val view = IconCellView(activity) { onLaunchApp(app) }
        view.onNotifiedTap = { NotificationBadge.showNotifications(activity, app) { onLaunchApp(app) } }
        view.tag = item
        view.layoutParams = WidgetGridLayout.CellParams(item.col, item.row, 1, 1)
        val showLabel = prefs.bottomLabels
        val size = view.iconSizeFor(cellPx(), showLabel)
        view.bind(app.appLabel, showLabel, size)
        view.touch.listener = dragListener(null)
        val iconPack = prefs.iconPack
        val context = activity.applicationContext
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                IconPackManager.getIcon(context, app.appPackage, app.activityClassName, app.user, iconPack, size)
            }
            view.setIcon(bitmap)
        }
        return view
    }

    @Suppress("DEPRECATION")
    private fun updateSize(view: MinimaWidgetHostView, widget: HomeWidget) {
        val density = activity.resources.displayMetrics.density
        val cellDp = (cellPx() / density).toInt()
        val w = widget.colSpan * cellDp
        val h = widget.rowSpan * cellDp / rowDivisions
        try {
            view.updateAppWidgetSize(Bundle(), w, h, w, h)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun overlaps(a: HomeWidget, col: Int, row: Int, colSpan: Int, rowSpan: Int) =
        col < a.col + a.colSpan && a.col < col + colSpan && row < a.row + a.rowSpan && a.row < row + rowSpan

    private fun isFree(col: Int, row: Int, colSpan: Int, rowSpan: Int, except: HomeWidget?): Boolean =
        col >= 0 && row >= 0 && col + colSpan <= prefs.widgetColumns &&
                widgets.none { it !== except && it.colSpan > 0 && overlaps(it, col, row, colSpan, rowSpan) }

    private fun findFreeSpot(colSpan: Int, rowSpan: Int, except: HomeWidget? = null): Pair<Int, Int> {
        val columns = prefs.widgetColumns
        var row = 0
        while (true) {
            for (col in 0..(columns - colSpan).coerceAtLeast(0))
                if (isFree(col, row, colSpan, rowSpan, except)) return col to row
            row++
        }
    }

    // ---------------------------------------------------------------- move (drag) and resize

    private fun dragListener(info: AppWidgetProviderInfo?) = object : GridItemListener {
        override fun onLongPress(view: View) {
            endEditing()
            scrollView.gesturesEnabled = false
            grid.editing = true
        }

        override fun onDrag(view: View, dx: Float, dy: Float) {
            val widget = view.tag as HomeWidget
            view.translationX = dx
            view.translationY = dy
            view.alpha = 0.8f
            val (col, row) = grid.dropCellFor(view, dx, dy)
            grid.showDropPreview(col, row, widget.colSpan, widget.rowSpan, isFree(col, row, widget.colSpan, widget.rowSpan, widget))
        }

        override fun onDrop(view: View, dx: Float, dy: Float) {
            val widget = view.tag as HomeWidget
            val (col, row) = grid.dropCellFor(view, dx, dy)
            view.translationX = 0f
            view.translationY = 0f
            view.alpha = 1f
            grid.hideDropPreview()
            if (isFree(col, row, widget.colSpan, widget.rowSpan, widget)) {
                widget.col = col
                widget.row = row
                (view.layoutParams as WidgetGridLayout.CellParams).apply { this.col = col; this.row = row }
                repo.save(widgets)
            }
            grid.editing = false
            scrollView.gesturesEnabled = true
        }

        override fun onMenu(view: View) {
            grid.editing = false
            scrollView.gesturesEnabled = true
            val item = view.tag as HomeWidget
            if (info != null) showWidgetMenu(item, info, view as MinimaWidgetHostView) else showIconMenu(item)
        }
    }

    private fun resizeListener() = object : WidgetGridLayout.ResizeListener {
        override fun canPlace(view: View, col: Int, row: Int, colSpan: Int, rowSpan: Int) =
            isFree(col, row, colSpan, rowSpan, view.tag as HomeWidget)

        override fun onResizeDragging(active: Boolean) {
            scrollView.gesturesEnabled = !active
        }

        override fun onResized(view: View) {
            val widget = view.tag as HomeWidget
            val p = view.layoutParams as WidgetGridLayout.CellParams
            widget.col = p.col; widget.row = p.row; widget.colSpan = p.colSpan; widget.rowSpan = p.rowSpan
            repo.save(widgets)
            updateSize(view as MinimaWidgetHostView, widget)
        }

        override fun onTapOutsideFrame() = endEditing()
    }

    /** Leaves resize mode (tap outside the frame, or anywhere on home). */
    fun endEditing() {
        if (grid.resizeTarget != null) grid.resizeTarget = null
        scrollView.gesturesEnabled = true
    }

    // ---------------------------------------------------------------- adding

    /** Bottom area: long press on an empty cell offers adding an app or a widget there. */
    fun showAddMenu(col: Int? = null, row: Int? = null) {
        preferredCell = if (col != null && row != null) col to row else null
        activity.showMenuDialog(
            activity.getString(R.string.ml_bottom_area),
            listOf(
                MenuEntry(activity.getString(R.string.ml_add_app)) { showAddAppDialog() },
                MenuEntry(activity.getString(R.string.ml_add_widget)) { showAddWidgetDialog() },
            )
        )
    }

    private fun showAddAppDialog() {
        val entries = apps.sortedBy { it.appLabel.lowercase() }.map { app ->
            MenuEntry(app.appLabel) {
                val item = HomeWidget(
                    appWidgetId = -1, colSpan = 1, rowSpan = 1,
                    packageName = app.appPackage, activityClassName = app.activityClassName, user = app.user.toString(),
                )
                val (c, r) = placeFor(1, 1)
                item.col = c; item.row = r
                widgets.add(item)
                repo.save(widgets)
                render()
            }
        }
        activity.showMenuDialog(activity.getString(R.string.ml_add_app), entries)
    }

    /** The long-pressed cell when the item fits there, otherwise the first free spot. */
    private fun placeFor(colSpan: Int, rowSpan: Int): Pair<Int, Int> {
        val preferred = preferredCell
        preferredCell = null
        if (preferred != null) {
            val col = preferred.first.coerceAtMost(prefs.widgetColumns - colSpan).coerceAtLeast(0)
            if (isFree(col, preferred.second, colSpan, rowSpan, null)) return col to preferred.second
        }
        return findFreeSpot(colSpan, rowSpan)
    }

    private fun showIconMenu(item: HomeWidget) {
        val app = findApp(item) ?: return
        activity.showMenuDialog(
            app.appLabel,
            listOf(
                MenuEntry(activity.getString(R.string.ml_remove_from_home)) {
                    widgets.remove(item)
                    repo.save(widgets)
                    render()
                },
                MenuEntry(activity.getString(R.string.ml_change_icon)) {
                    IconChooser.show(activity, app, scope, pickImage) { render() }
                },
                MenuEntry(activity.getString(R.string.ml_app_info)) {
                    openAppInfo(activity, app.user, app.appPackage)
                },
            ),
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
        if (providers.isEmpty()) {
            activity.showToast(activity.getString(R.string.ml_no_widgets))
            return
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
        WidgetPickerDialog.show(activity, items, scope) { startAdding(it) }
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
        val cell = cellPx()
        val colSpan = ceilDiv(info.minWidth, cell).coerceIn(1, prefs.widgetColumns)
        val rowSpan = ceilDiv(info.minHeight, rowPx()).coerceAtLeast(1)
        val (col, row) = placeFor(colSpan, rowSpan)
        widgets.add(HomeWidget(id, col, row, colSpan, rowSpan))
        repo.save(widgets)
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
            MenuEntry(activity.getString(R.string.ml_widget_resize)) { grid.resizeTarget = hostView },
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
                repo.save(widgets)
                render()
            }
        }
        val title = try {
            info.loadLabel(activity.packageManager)
        } catch (e: Exception) {
            null
        } ?: activity.getString(R.string.ml_widget)
        activity.showMenuDialog(title, entries)
    }

    companion object {
        /** The top grid's rows are half a column tall, so widgets fit without large gaps. */
        const val TOP_ROW_DIVISIONS = 2

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
