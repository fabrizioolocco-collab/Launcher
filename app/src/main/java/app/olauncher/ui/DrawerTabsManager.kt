package app.olauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.DrawerTab
import app.olauncher.data.DrawerTabsRepository
import app.olauncher.data.Prefs
import app.olauncher.helper.MenuEntry
import app.olauncher.helper.PillSwitch
import app.olauncher.helper.createDialog
import app.olauncher.helper.dpToPx
import app.olauncher.helper.showConfirmDialog
import app.olauncher.helper.showMenuDialog
import app.olauncher.helper.showMultiSelectDialog
import app.olauncher.helper.showTextInputDialog

/**
 * "Manage tabs": every tab in one list, "All" first and "Hidden" last. Drag the handle to reorder
 * the tabs, the switch shows or hides one in the drawer, a tap opens what can be done with it
 * (rename, choose its apps, back to A-Z, delete); "+ New tab" at the bottom. Changes apply at once.
 */
object DrawerTabsManager {

    /**
     * [apps]: the apps listed in the drawer (not hidden), [hidden]: the hidden ones.
     * [onChanged] after any change to the tabs, [onHiddenChanged] after the hidden apps change.
     */
    @SuppressLint("ClickableViewAccessibility", "NotifyDataSetChanged")
    fun show(
        context: Context,
        apps: List<AppModel.App>,
        hidden: List<AppModel.App>,
        onChanged: () -> Unit,
        onHiddenChanged: () -> Unit,
    ) {
        val repo = DrawerTabsRepository(context)
        val prefs = Prefs(context)
        val tabs = repo.load()
        var shown = apps.distinctBy { DrawerTabsRepository.key(it) }
        var hiddenNow = hidden.distinctBy { DrawerTabsRepository.key(it) }

        lateinit var adapter: RecyclerView.Adapter<RecyclerView.ViewHolder>
        fun changed() {
            repo.save(tabs)
            adapter.notifyDataSetChanged()
            onChanged()
        }

        /** Rows: null is "All", then the tabs, then [HIDDEN_ROW]. */
        fun rowCount() = tabs.size + 2
        fun tabAt(position: Int): DrawerTab? = if (position in 1..tabs.size) tabs[position - 1] else null

        fun count(position: Int): Int = when (position) {
            0 -> {
                val placed = tabs.filter { it.enabled }.flatMap { it.apps }.toHashSet()
                shown.count { DrawerTabsRepository.key(it) !in placed }
            }
            rowCount() - 1 -> hiddenNow.size
            else -> {
                val keys = tabAt(position)!!.apps
                shown.count { DrawerTabsRepository.key(it) in keys }
            }
        }

        fun chooseApps(tab: DrawerTab) {
            val list = shown.sortedBy { it.appLabel.lowercase() }
            val labels = list.map { app ->
                val key = DrawerTabsRepository.key(app)
                val elsewhere = tabs.filter { it !== tab && key in it.apps }.joinToString { it.name }
                if (elsewhere.isEmpty()) app.appLabel else app.appLabel + " · " + context.getString(R.string.ml_tab_in, elsewhere)
            }
            val checked = list.indices.filter { DrawerTabsRepository.key(list[it]) in tab.apps }.toSet()
            context.showMultiSelectDialog(tab.name, labels, checked, context.getString(R.string.ml_done)) { selected ->
                list.forEachIndexed { i, app ->
                    val key = DrawerTabsRepository.key(app)
                    if (i in selected) {
                        // An app is in one tab: choosing it here takes it out of the others
                        tabs.forEach { if (it !== tab) it.apps.remove(key) }
                        tab.apps.add(key)
                    } else tab.apps.remove(key)
                }
                changed()
            }
        }

        fun chooseHidden() {
            val pool = (shown + hiddenNow).distinctBy { DrawerTabsRepository.key(it) }.sortedBy { it.appLabel.lowercase() }
            val hiddenKeys = hiddenNow.map { DrawerTabsRepository.key(it) }.toSet()
            val checked = pool.indices.filter { DrawerTabsRepository.key(pool[it]) in hiddenKeys }.toSet()
            context.showMultiSelectDialog(
                context.getString(R.string.ml_tab_hidden_choose), pool.map { it.appLabel }, checked, context.getString(R.string.ml_done)
            ) { selected ->
                val poolKeys = pool.map { DrawerTabsRepository.key(it) }.toSet()
                val chosen = selected.map { DrawerTabsRepository.key(pool[it]) }
                prefs.hiddenApps = (prefs.hiddenApps.filter { it !in poolKeys } + chosen).toMutableSet()
                hiddenNow = pool.filterIndexed { i, _ -> i in selected }
                shown = pool.filterIndexed { i, _ -> i !in selected }
                adapter.notifyDataSetChanged()
                onHiddenChanged()
            }
        }

        fun newTab() {
            context.showTextInputDialog(
                title = context.getString(R.string.ml_tab_new),
                initial = "",
                hint = context.getString(R.string.ml_tab_name_hint),
                action = context.getString(R.string.ml_save),
            ) { name ->
                val tab = DrawerTab(name = name)
                tabs.add(tab)
                changed()
                chooseApps(tab)
            }
        }

        fun backToAz(id: String) = MenuEntry(context.getString(R.string.ml_tab_az)) {
            repo.setOrder(id, emptyList())
            changed()
        }

        fun openRow(position: Int) {
            val tab = tabAt(position)
            when {
                position == 0 -> if (repo.hasOrder(DrawerTabsRepository.ALL_ID))
                    context.showMenuDialog(context.getString(R.string.ml_tab_all), listOf(backToAz(DrawerTabsRepository.ALL_ID)))
                tab == null -> chooseHidden()
                else -> {
                    val entries = mutableListOf(
                        MenuEntry(context.getString(R.string.ml_section_rename)) {
                            context.showTextInputDialog(
                                title = context.getString(R.string.ml_section_rename),
                                initial = tab.name,
                                hint = context.getString(R.string.ml_tab_name_hint),
                                action = context.getString(R.string.ml_save),
                            ) { name ->
                                tab.name = name
                                changed()
                            }
                        },
                        MenuEntry(context.getString(R.string.ml_tab_choose_apps)) { chooseApps(tab) },
                    )
                    if (repo.hasOrder(tab.id)) entries += backToAz(tab.id)
                    entries += MenuEntry(context.getString(R.string.ml_tab_delete), destructive = true) {
                        context.showConfirmDialog(
                            title = context.getString(R.string.ml_tab_delete),
                            message = context.getString(R.string.ml_tab_delete_confirm, tab.name),
                            action = context.getString(R.string.ml_delete),
                        ) {
                            tabs.remove(tab)
                            repo.setOrder(tab.id, emptyList())
                            changed()
                        }
                    }
                    context.showMenuDialog(tab.name, entries)
                }
            }
        }

        lateinit var touch: ItemTouchHelper
        adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = rowCount() + 1

            override fun getItemViewType(position: Int) = if (position == rowCount()) 1 else 0

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val ctx = parent.context
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = 58.dpToPx()
                    setPadding(4.dpToPx(), 4.dpToPx(), 6.dpToPx(), 4.dpToPx())
                    background = UiKit.ripple(ctx)
                    isClickable = true
                    isFocusable = true
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                if (viewType == 1) {
                    // "+ New tab"
                    row.addView(UiKit.icon(ctx, R.drawable.ic_m_add, UiKit.primary(ctx), 20).apply {
                        (layoutParams as LinearLayout.LayoutParams).apply { marginStart = 6.dpToPx(); marginEnd = 14.dpToPx() }
                    })
                    row.addView(TextView(ctx).apply {
                        text = ctx.getString(R.string.ml_tab_new)
                        textSize = 16f
                        setTextColor(UiKit.primary(ctx))
                    })
                    row.setOnClickListener { newTab() }
                    return object : RecyclerView.ViewHolder(row) {}
                }
                row.addView(UiKit.icon(ctx, R.drawable.ic_m_list, UiKit.secondary(ctx), 20).apply {
                    contentDescription = ctx.getString(R.string.ml_w_drag)
                    (layoutParams as LinearLayout.LayoutParams).apply { width = 32.dpToPx(); marginEnd = 8.dpToPx() }
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                })
                val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                texts.addView(TextView(ctx).apply {
                    textSize = 16f
                    setTextColor(UiKit.primary(ctx))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                texts.addView(TextView(ctx).apply {
                    textSize = 12.5f
                    setTextColor(UiKit.secondary(ctx))
                })
                row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(PillSwitch(ctx), LinearLayout.LayoutParams(46.dpToPx(), 28.dpToPx()).apply { marginStart = 8.dpToPx() })
                row.addView(UiKit.icon(ctx, R.drawable.ic_m_chevron_right, UiKit.secondary(ctx), 18).apply {
                    (layoutParams as LinearLayout.LayoutParams).marginStart = 6.dpToPx()
                })
                return object : RecyclerView.ViewHolder(row) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                if (getItemViewType(position) == 1) return
                val row = holder.itemView as LinearLayout
                val ctx = row.context
                val tab = tabAt(position)
                val isAll = position == 0
                val handle = row.getChildAt(0)
                val texts = row.getChildAt(1) as LinearLayout
                val switch = row.getChildAt(2) as PillSwitch
                val chevron = row.getChildAt(3)

                val name = when {
                    isAll -> ctx.getString(R.string.ml_tab_all)
                    tab == null -> ctx.getString(R.string.ml_tab_hidden)
                    else -> tab.name
                }
                val n = count(position)
                val id = if (isAll) DrawerTabsRepository.ALL_ID else tab?.id
                var info = if (n == 0) ctx.getString(R.string.ml_tab_empty) else ctx.getString(R.string.ml_tab_count, n)
                if (id != null && repo.hasOrder(id)) info += " · " + ctx.getString(R.string.ml_order_custom)
                (texts.getChildAt(0) as TextView).text = name
                (texts.getChildAt(1) as TextView).text = info

                // "All" and "Hidden" stay first and last: only the user's tabs have a handle
                handle.visibility = if (tab != null) View.VISIBLE else View.INVISIBLE
                handle.setOnTouchListener { _, e ->
                    if (tab != null && e.actionMasked == MotionEvent.ACTION_DOWN) touch.startDrag(holder)
                    false
                }
                switch.onChange = null
                switch.checked = when {
                    isAll -> repo.allEnabled
                    tab == null -> repo.hiddenEnabled
                    else -> tab.enabled
                }
                switch.onChange = { on ->
                    when {
                        isAll -> repo.allEnabled = on
                        tab == null -> repo.hiddenEnabled = on
                        else -> tab.enabled = on
                    }
                    changed()
                }
                chevron.visibility = if (isAll && !repo.hasOrder(DrawerTabsRepository.ALL_ID)) View.INVISIBLE else View.VISIBLE
                row.setOnClickListener { openRow(holder.bindingAdapterPosition) }
            }
        }

        touch = ItemTouchHelper(object : ItemTouchHelper.Callback() {
            override fun isLongPressDragEnabled() = false

            override fun getMovementFlags(rv: RecyclerView, holder: RecyclerView.ViewHolder) =
                if (tabAt(holder.bindingAdapterPosition) != null) makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) else 0

            override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
                val a = from.bindingAdapterPosition
                val b = to.bindingAdapterPosition
                if (tabAt(a) == null || tabAt(b) == null) return false
                tabs.add(b - 1, tabs.removeAt(a - 1))
                adapter.notifyItemMoved(a, b)
                return true
            }

            override fun clearView(rv: RecyclerView, holder: RecyclerView.ViewHolder) {
                super.clearView(rv, holder)
                repo.save(tabs)
                onChanged()
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
        })

        val dialog = context.createDialog(title = R.string.ml_tabs_manage, action = R.string.ml_done) { parent ->
            val ctx = parent.context
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(ctx).apply {
                    setText(R.string.ml_tabs_manage_hint)
                    textSize = 13f
                    setTextColor(UiKit.secondary(ctx))
                    setPadding(4.dpToPx(), 12.dpToPx(), 0, 10.dpToPx())
                })
                val maxHeight = (ctx.resources.displayMetrics.heightPixels * 0.55f).toInt()
                addView(object : RecyclerView(ctx) {
                    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
                    }
                }.apply {
                    layoutManager = LinearLayoutManager(ctx)
                    this.adapter = adapter
                    overScrollMode = View.OVER_SCROLL_NEVER
                    background = UiKit.border(ctx, 16)
                    addItemDecoration(object : RecyclerView.ItemDecoration() {
                        private val paint = android.graphics.Paint().apply { color = UiKit.line(ctx) }
                        override fun onDraw(c: android.graphics.Canvas, parent: RecyclerView, state: RecyclerView.State) {
                            for (i in 1 until parent.childCount) {
                                val v = parent.getChildAt(i)
                                c.drawRect(v.left.toFloat(), v.top + v.translationY, v.right.toFloat(), v.top + v.translationY + 1.dpToPx(), paint)
                            }
                        }
                    })
                    touch.attachToRecyclerView(this)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = 12.dpToPx()
                })
            }
        }
        dialog.showRespectingStatusBar()
    }
}
