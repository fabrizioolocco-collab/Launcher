package app.olauncher.ui

import android.content.Context
import android.graphics.Bitmap
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.helper.MenuEntry
import app.olauncher.helper.OlDialog
import app.olauncher.helper.baseDialog
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.showMenuDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * An open folder of apps: its name on top (tap to rename) and its apps in a grid. Tap launches an
 * app, a long press offers to take it out of the folder (back on home).
 */
object AppFolderDialog {

    private const val COLUMNS = 4

    fun show(
        context: Context,
        name: String,
        apps: List<AppModel.App>,
        scope: CoroutineScope,
        loadIcon: (AppModel.App, Int) -> Bitmap?,
        /** Notifications of an app (0 = none): its name turns red. */
        notifications: (AppModel.App) -> Int,
        onLaunch: (AppModel.App) -> Unit,
        onTakeOut: (AppModel.App) -> Unit,
        onRename: () -> Unit,
    ): OlDialog {
        val (dialog, binding) = context.baseDialog(name)
        val ctx = dialog.context
        binding.tvTitle.setOnClickListener {
            dialog.dismiss()
            onRename()
        }
        val primary = ctx.getColorFromAttr(R.attr.primaryColor)
        val iconSize = 52.dpToPx()
        val grid = GridLayout(ctx).apply {
            columnCount = COLUMNS
            useDefaultMargins = false
        }
        apps.forEach { app ->
            val icon = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            val label = TextView(ctx, null, 0, R.style.TextSmall).apply {
                text = app.appLabel
                textSize = 12f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setTextColor(if (notifications(app) > 0) NotificationBadge.NOTIFICATION_COLOR else primary)
            }
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(2.dpToPx(), 8.dpToPx(), 2.dpToPx(), 8.dpToPx())
                foreground = UiKit.ripple(ctx)
                contentDescription = app.appLabel
                addView(icon, LinearLayout.LayoutParams(iconSize, iconSize))
                addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = 4.dpToPx()
                })
                setOnClickListener {
                    dialog.dismiss()
                    onLaunch(app)
                }
                setOnLongClickListener {
                    ctx.showMenuDialog(
                        app.appLabel,
                        listOf(MenuEntry(ctx.getString(R.string.ml_folder_take_out)) {
                            dialog.dismiss()
                            onTakeOut(app)
                        })
                    )
                    true
                }
            }
            grid.addView(cell, GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)
            ).apply { width = 0 })
            scope.launch {
                val bitmap = withContext(Dispatchers.IO) { loadIcon(app, iconSize) }
                icon.setImageBitmap(bitmap)
            }
        }
        // Fewer apps than a row: empty cells keep the columns as wide as in a full row
        repeat((COLUMNS - apps.size).coerceAtLeast(0)) {
            grid.addView(android.view.View(ctx), GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)
            ).apply { width = 0; height = 1 })
        }
        val scroll = android.widget.ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(grid)
        }
        binding.contentContainer.addView(scroll, ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = 16.dpToPx()
            bottomMargin = 8.dpToPx()
            marginEnd = 8.dpToPx()
        })
        binding.contentContainer.isVisible = true
        dialog.showRespectingStatusBar()
        return dialog
    }
}
