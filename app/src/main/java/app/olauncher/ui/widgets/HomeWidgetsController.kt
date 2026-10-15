package app.olauncher.ui.widgets

import android.annotation.SuppressLint
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import app.olauncher.R
import app.olauncher.data.HomeWidget
import app.olauncher.data.HomeWidgetsRepository
import app.olauncher.helper.MenuEntry
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getScreenDimensions
import app.olauncher.helper.showConfirmDialog
import app.olauncher.helper.showMenuDialog
import app.olauncher.helper.showToast
import app.olauncher.ui.HomeScrollView

/**
 * Widget area at the top of home: widgets stacked vertically, full width, each with its own height.
 *
 * Adding a widget: pick a provider from our list -> bind (asks the user if needed) -> configure
 * (if the widget has a setup screen) -> saved. Bind/configure results come back through
 * MainActivity.onActivityResult, forwarded to [onActivityResult].
 */
class HomeWidgetsController(
    private val activity: Activity,
    private val container: LinearLayout,
    private val scrollView: HomeScrollView,
    /** Called before launching an external screen, so the launcher doesn't jump back to home meanwhile. */
    private val keepScreen: (Boolean) -> Unit,
) {
    private val manager = AppWidgetManager.getInstance(activity)
    private val host = MinimaWidgetHost(activity.applicationContext, HOST_ID)
    private val repo = HomeWidgetsRepository(activity)
    private var widgets = repo.load()

    init {
        current = this
    }

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

    fun render() {
        container.removeAllViews()
        val removed = mutableListOf<HomeWidget>()
        widgets.forEach { widget ->
            val info = manager.getAppWidgetInfo(widget.appWidgetId)
            if (info == null) {
                // The app was uninstalled or the widget is gone
                removed += widget
                return@forEach
            }
            container.addView(widgetView(widget, info))
        }
        if (removed.isNotEmpty()) {
            removed.forEach { host.deleteAppWidgetId(it.appWidgetId) }
            widgets.removeAll(removed)
            repo.save(widgets)
        }
        container.visibility = if (widgets.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun widgetView(widget: HomeWidget, info: AppWidgetProviderInfo): View {
        val wrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 8.dpToPx()
            }
        }
        val hostView = host.createView(activity, widget.appWidgetId, info) as MinimaWidgetHostView
        hostView.setPadding(0, 0, 0, 0)
        hostView.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, widget.heightDp.dpToPx())
        hostView.onLongPress = { showWidgetMenu(widget, info, wrapper, hostView) }
        updateSize(hostView, widget.heightDp)
        wrapper.addView(hostView)
        return wrapper
    }

    private fun widthDp(): Int {
        val density = activity.resources.displayMetrics.density
        val width = container.width.takeIf { it > 0 } ?: getScreenDimensions(activity).first
        return ((width - container.paddingLeft - container.paddingRight) / density).toInt()
    }

    @Suppress("DEPRECATION")
    private fun updateSize(view: MinimaWidgetHostView, heightDp: Int) {
        val w = widthDp()
        try {
            view.updateAppWidgetSize(Bundle(), w, heightDp, w, heightDp)
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
        val entries = providers.map { info ->
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
            Triple(app, label, info)
        }.sortedWith(compareBy({ it.first.lowercase() }, { it.second.lowercase() }))
            .map { (app, label, info) -> MenuEntry(if (label == app) app else "$app · $label") { startAdding(info) } }
        activity.showMenuDialog(activity.getString(R.string.ml_add_widget), entries)
    }

    private fun startAdding(info: AppWidgetProviderInfo) {
        val id = host.allocateAppWidgetId()
        pendingId = id
        pendingInfo = info
        val bound = try {
            manager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)
        } catch (e: Exception) {
            false
        }
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

    /** Opens the widget's own setup screen. Returns false if it couldn't be started. */
    private fun launchConfigure(id: Int, info: AppWidgetProviderInfo, requestCode: Int): Boolean = try {
        launchForResult(requestCode) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                host.startAppWidgetConfigureActivityForResult(activity, id, 0, requestCode, null)
            else {
                @Suppress("DEPRECATION")
                activity.startActivityForResult(
                    Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                        .setComponent(info.configure)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                    requestCode
                )
            }
        }
        true
    } catch (e: Exception) {
        e.printStackTrace()
        keepScreen(false)
        false
    }

    private fun launchForResult(@Suppress("UNUSED_PARAMETER") requestCode: Int, start: () -> Unit) {
        keepScreen(true)
        start()
    }

    private fun finishAdding(id: Int, info: AppWidgetProviderInfo) {
        pendingId = -1
        pendingInfo = null
        val density = activity.resources.displayMetrics.density
        val defaultHeight = (info.minHeight / density).toInt().coerceIn(MIN_HEIGHT_DP, 360)
        widgets.add(HomeWidget(id, defaultHeight))
        repo.save(widgets)
        render()
    }

    private fun cancelAdding() {
        if (pendingId != -1) host.deleteAppWidgetId(pendingId)
        pendingId = -1
        pendingInfo = null
    }

    /** Returns true when the result belonged to the widget flow. */
    fun onActivityResult(requestCode: Int, resultCode: Int): Boolean {
        if (requestCode != REQUEST_BIND && requestCode != REQUEST_CONFIGURE && requestCode != REQUEST_RECONFIGURE) return false
        keepScreen(false)
        val ok = resultCode == Activity.RESULT_OK
        when (requestCode) {
            REQUEST_BIND -> {
                val info = pendingInfo
                if (ok && info != null) configureOrFinish(pendingId, info) else cancelAdding()
            }

            REQUEST_CONFIGURE -> {
                val info = pendingInfo
                if (ok && info != null) finishAdding(pendingId, info) else cancelAdding()
            }

            REQUEST_RECONFIGURE -> render()
        }
        return true
    }

    // ---------------------------------------------------------------- menu, resize, remove

    private fun showWidgetMenu(widget: HomeWidget, info: AppWidgetProviderInfo, wrapper: LinearLayout, hostView: MinimaWidgetHostView) {
        val entries = mutableListOf(
            MenuEntry(activity.getString(R.string.ml_widget_resize)) { startResize(widget, wrapper, hostView) },
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

    /** Shows a drag bar under the widget; dragging it changes the height, "Done" saves it. */
    @SuppressLint("ClickableViewAccessibility")
    private fun startResize(widget: HomeWidget, wrapper: LinearLayout, hostView: MinimaWidgetHostView) {
        if (wrapper.childCount > 1) return
        scrollView.gesturesEnabled = false
        val maxHeight = (getScreenDimensions(activity).second * 0.8f).toInt()
        val minHeight = MIN_HEIGHT_DP.dpToPx()

        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.rounded_rect_shade_color)
            setPadding(16.dpToPx(), 0, 8.dpToPx(), 0)
        }
        val handle = TextView(activity, null, 0, R.style.TextSmall).apply {
            text = activity.getString(R.string.ml_widget_drag_hint)
            gravity = Gravity.CENTER
            setPadding(0, 14.dpToPx(), 0, 14.dpToPx())
        }
        val done = TextView(activity, null, 0, R.style.TextSmallBold).apply {
            text = activity.getString(R.string.ml_done)
            setPadding(16.dpToPx(), 14.dpToPx(), 8.dpToPx(), 14.dpToPx())
        }
        bar.addView(handle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(done)
        wrapper.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 4.dpToPx()
        })

        var startY = 0f
        var startHeight = 0
        handle.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    startY = event.rawY
                    startHeight = hostView.height
                }

                MotionEvent.ACTION_MOVE -> {
                    val newHeight = (startHeight + (event.rawY - startY)).toInt().coerceIn(minHeight, maxHeight)
                    hostView.layoutParams = hostView.layoutParams.apply { height = newHeight }
                }
            }
            true
        }
        done.setOnClickListener {
            val density = activity.resources.displayMetrics.density
            widget.heightDp = (hostView.height / density).toInt().coerceAtLeast(MIN_HEIGHT_DP)
            repo.save(widgets)
            updateSize(hostView, widget.heightDp)
            wrapper.removeView(bar)
            scrollView.gesturesEnabled = true
        }
    }

    companion object {
        private const val HOST_ID = 2026
        private const val MIN_HEIGHT_DP = 48
        const val REQUEST_BIND = 7101
        const val REQUEST_CONFIGURE = 7102
        const val REQUEST_RECONFIGURE = 7103

        // The add flow spans activity results; keep its state outside the fragment view
        private var pendingId = -1
        private var pendingInfo: AppWidgetProviderInfo? = null

        /** Controller of the visible home, used by MainActivity to forward activity results. */
        var current: HomeWidgetsController? = null
            private set
    }
}
