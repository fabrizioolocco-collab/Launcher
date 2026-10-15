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
import app.olauncher.data.HomeWidget
import app.olauncher.data.HomeWidgetsRepository
import app.olauncher.data.Prefs
import app.olauncher.helper.MenuEntry
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getScreenDimensions
import app.olauncher.helper.showConfirmDialog
import app.olauncher.helper.showMenuDialog
import app.olauncher.helper.showToast
import app.olauncher.ui.HomeScrollView
import kotlinx.coroutines.CoroutineScope

/**
 * Widget area at the top of home: widgets stacked vertically, full width, each with its own height.
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
) {
    private val manager = AppWidgetManager.getInstance(activity)
    private val host = MinimaWidgetHost(activity.applicationContext, HOST_ID)
    private val repo = HomeWidgetsRepository(activity)
    private var widgets = repo.load()

    private var renderedColumns = 0

    init {
        current = this
        grid.resizeListener = resizeListener()
    }

    private fun log(message: String) = WidgetLog.log(activity, message)

    fun startListening() = try {
        host.startListening()
    } catch (e: Exception) {
        e.printStackTrace()
    }

    fun stopListening() = try {
        host.stopListening()
    } catch (e: Exception) {
        e.printStackTrace()
    }

    fun release() {
        if (current === this) current = null
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
        val removed = mutableListOf<HomeWidget>()
        var changed = false
        widgets.forEach { widget ->
            val info = manager.getAppWidgetInfo(widget.appWidgetId)
            if (info == null) {
                // The app was uninstalled or the widget is gone
                removed += widget
                return@forEach
            }
            if (widget.colSpan <= 0) {
                // Saved by the older stacked layout: full width, same height, first free rows
                widget.colSpan = columns
                widget.rowSpan = ceilDiv(widget.legacyHeightDp.dpToPx(), cellPx()).coerceAtLeast(1)
                val (c, r) = findFreeSpot(widget.colSpan, widget.rowSpan, except = widget)
                widget.col = c; widget.row = r
                changed = true
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
        grid.visibility = if (widgets.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Called on resume: rebuilds only if the column setting changed meanwhile. */
    fun refreshIfNeeded() {
        if (renderedColumns != prefs.widgetColumns) render()
    }

    private fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b

    private fun widgetView(widget: HomeWidget, info: AppWidgetProviderInfo): View {
        val hostView = host.createView(activity, widget.appWidgetId, info) as MinimaWidgetHostView
        hostView.setPadding(0, 0, 0, 0)
        hostView.tag = widget
        hostView.layoutParams = WidgetGridLayout.CellParams(widget.col, widget.row, widget.colSpan, widget.rowSpan)
        hostView.listener = dragListener(info)
        updateSize(hostView, widget)
        return hostView
    }

    @Suppress("DEPRECATION")
    private fun updateSize(view: MinimaWidgetHostView, widget: HomeWidget) {
        val density = activity.resources.displayMetrics.density
        val cellDp = (cellPx() / density).toInt()
        val w = widget.colSpan * cellDp
        val h = widget.rowSpan * cellDp
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

    private fun dragListener(info: AppWidgetProviderInfo) = object : MinimaWidgetHostView.Listener {
        override fun onLongPress(view: MinimaWidgetHostView) {
            endEditing()
            scrollView.gesturesEnabled = false
            grid.editing = true
        }

        override fun onDrag(view: MinimaWidgetHostView, dx: Float, dy: Float) {
            val widget = view.tag as HomeWidget
            view.translationX = dx
            view.translationY = dy
            view.alpha = 0.8f
            val (col, row) = grid.dropCellFor(view, dx, dy)
            grid.showDropPreview(col, row, widget.colSpan, widget.rowSpan, isFree(col, row, widget.colSpan, widget.rowSpan, widget))
        }

        override fun onDrop(view: MinimaWidgetHostView, dx: Float, dy: Float) {
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

        override fun onMenu(view: MinimaWidgetHostView) {
            grid.editing = false
            scrollView.gesturesEnabled = true
            showWidgetMenu(view.tag as HomeWidget, info, view)
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
                host.createView(activity.applicationContext, id, info) as MinimaWidgetHostView
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
        val rowSpan = ceilDiv(info.minHeight, cell).coerceAtLeast(1)
        val (col, row) = findFreeSpot(colSpan, rowSpan)
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
        private const val HOST_ID = 2026
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
