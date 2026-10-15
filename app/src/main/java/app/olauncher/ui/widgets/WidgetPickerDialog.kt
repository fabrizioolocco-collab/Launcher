package app.olauncher.ui.widgets

import android.annotation.SuppressLint
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.helper.OlDialog
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.getScreenDimensions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Nova-style widget picker: widgets grouped by app, each with its preview, name and size in cells,
 * plus a search field (the keyboard opens only when it's tapped).
 */
object WidgetPickerDialog {

    class Item(val app: String, val label: String, val info: AppWidgetProviderInfo, val cols: Int, val rows: Int)

    private sealed class Row {
        class Header(val app: String) : Row()
        class Widget(val item: Item) : Row()
    }

    fun show(context: Context, items: List<Item>, scope: CoroutineScope, onPick: (AppWidgetProviderInfo) -> Unit) {
        val dialog = OlDialog(context)
        val ctx = dialog.context
        val primary = ctx.getColorFromAttr(R.attr.primaryColor)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dpToPx(), 20.dpToPx(), 16.dpToPx(), 16.dpToPx())
        }
        val titleRow = FrameLayout(ctx)
        titleRow.addView(TextView(ctx, null, 0, R.style.TextLarge).apply {
            text = ctx.getString(R.string.ml_add_widget)
            setTextColor(primary)
        })
        titleRow.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_close)
            alpha = 0.5f
            setPadding(8.dpToPx(), 6.dpToPx(), 8.dpToPx(), 6.dpToPx())
            setOnClickListener { dialog.dismiss() }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
        root.addView(titleRow)

        val search = EditText(ctx).apply {
            hint = ctx.getString(R.string.ml_search)
            setSingleLine(true)
            textSize = 16f
            setTextColor(primary)
            setHintTextColor(ctx.getColorFromAttr(R.attr.primaryColorTrans50))
            backgroundTintList = ColorStateList.valueOf(primary)
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 12.dpToPx()
        })

        fun rowsFor(query: String): List<Row> {
            val q = query.trim()
            val filtered = if (q.isEmpty()) items
            else items.filter { it.app.contains(q, true) || it.label.contains(q, true) }
            val rows = mutableListOf<Row>()
            var lastApp: String? = null
            filtered.forEach { item ->
                if (item.app != lastApp) {
                    rows += Row.Header(item.app)
                    lastApp = item.app
                }
                rows += Row.Widget(item)
            }
            return rows
        }

        var rows = rowsFor("")
        val previewMaxHeight = 140.dpToPx()

        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = rows.size
            override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view: View = if (viewType == 0) TextView(parent.context, null, 0, R.style.TextSmall).apply {
                    setTextColor(primary)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    alpha = 0.7f
                    setPadding(0, 18.dpToPx(), 0, 6.dpToPx())
                } else LinearLayout(parent.context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 6.dpToPx(), 0, 10.dpToPx())
                    isClickable = true
                    isFocusable = true
                    addView(FrameLayout(parent.context).apply {
                        id = R.id.ml_widget_preview
                        setBackgroundResource(R.drawable.rounded_rect_shade_color)
                        setPadding(8.dpToPx(), 8.dpToPx(), 8.dpToPx(), 8.dpToPx())
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    addView(TextView(parent.context, null, 0, R.style.TextSmall).apply {
                        id = R.id.ml_widget_label
                        setTextColor(primary)
                        setPadding(0, 6.dpToPx(), 0, 0)
                    })
                }
                view.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                return object : RecyclerView.ViewHolder(view) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                when (val row = rows[position]) {
                    is Row.Header -> (holder.itemView as TextView).text = row.app
                    is Row.Widget -> {
                        val item = row.item
                        val container = holder.itemView.findViewById<FrameLayout>(R.id.ml_widget_preview)
                        holder.itemView.findViewById<TextView>(R.id.ml_widget_label).text =
                            ctx.getString(R.string.ml_widget_label_size, item.label, item.cols, item.rows)
                        holder.itemView.setOnClickListener {
                            search.clearFocus()
                            dialog.dismiss()
                            onPick(item.info)
                        }
                        bindPreview(ctx, container, item.info, previewMaxHeight, scope)
                    }
                }
            }
        }

        val list = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            this.adapter = adapter
        }
        root.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (getScreenDimensions(ctx).second * 0.6f).toInt()
        ).apply { topMargin = 8.dpToPx() })

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            @SuppressLint("NotifyDataSetChanged")
            override fun afterTextChanged(s: Editable?) {
                rows = rowsFor(s?.toString().orEmpty())
                adapter.notifyDataSetChanged()
            }
        })

        dialog.setView(root)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        dialog.showRespectingStatusBar()
    }

    /** Live preview layout (Android 12+), else the preview image, else the app icon. */
    private fun bindPreview(ctx: Context, container: FrameLayout, info: AppWidgetProviderInfo, maxPreviewHeight: Int, scope: CoroutineScope) {
        container.removeAllViews()
        container.tag = info.provider
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.previewLayout != 0) {
            try {
                val preview = RemoteViews(info.provider.packageName, info.previewLayout).apply(ctx, container)
                container.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                container.layoutParams = container.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
                return
            } catch (e: Exception) {
                container.removeAllViews()
            }
        }
        val image = ImageView(ctx).apply {
            adjustViewBounds = true
            this.maxHeight = maxPreviewHeight
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        container.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        val density = ctx.resources.displayMetrics.densityDpi
        scope.launch {
            val drawable: Drawable? = withContext(Dispatchers.IO) {
                try {
                    info.loadPreviewImage(ctx, density) ?: info.loadIcon(ctx, density)
                } catch (e: Exception) {
                    null
                }
            }
            if (container.tag == info.provider) image.setImageDrawable(drawable)
            image.isVisible = drawable != null
        }
    }
}
