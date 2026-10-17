package app.olauncher.ui.weather

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.edit
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.helper.PillSwitch
import app.olauncher.helper.createDialog
import app.olauncher.helper.dpToPx
import app.olauncher.ui.UiKit

/**
 * Which values the hours view shows, and in which order: kept by key, so the sections added later
 * show up at the end. The editor is a list to drag (by the handle) with a switch per value.
 */
object WeatherSections {
    const val TEMP = "temp"
    const val FEELS = "feels"
    const val PRECIP = "precip"
    const val WIND = "wind"
    const val PRESSURE = "pressure"
    const val CLOUD = "cloud"
    const val HUMIDITY = "humidity"
    const val DEW = "dew"
    const val UV = "uv"
    val ALL = listOf(TEMP, FEELS, PRECIP, WIND, PRESSURE, CLOUD, HUMIDITY, DEW, UV)

    private const val PREFS = "app.minimalauncher.weather"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The keys in the chosen order, the hidden ones included. */
    fun order(context: Context): List<String> {
        val saved = prefs(context).getString("order", null)?.split(",")?.filter { it in ALL }.orEmpty()
        return saved + ALL.filter { it !in saved }
    }

    fun hidden(context: Context): Set<String> = prefs(context).getStringSet("hidden", emptySet()).orEmpty()

    /** The keys shown, in order. */
    fun shown(context: Context): List<String> = hidden(context).let { h -> order(context).filter { it !in h } }

    private fun save(context: Context, order: List<String>, hidden: Set<String>) = prefs(context).edit {
        putString("order", order.joinToString(","))
        putStringSet("hidden", hidden)
    }

    fun label(context: Context, key: String): String = context.getString(
        when (key) {
            TEMP -> R.string.ml_w_temp
            FEELS -> R.string.ml_w_feels
            PRECIP -> R.string.ml_w_precip
            WIND -> R.string.ml_w_wind
            PRESSURE -> R.string.ml_w_pressure
            CLOUD -> R.string.ml_w_cloud
            HUMIDITY -> R.string.ml_w_humidity
            DEW -> R.string.ml_w_dew
            else -> R.string.ml_w_uv
        }
    )

    fun icon(key: String): Int = when (key) {
        TEMP -> R.drawable.ic_m_thermo
        FEELS -> R.drawable.ic_m_feels
        PRECIP -> R.drawable.ic_m_rain_line
        WIND -> R.drawable.ic_m_wind
        PRESSURE -> R.drawable.ic_m_gauge
        CLOUD -> R.drawable.ic_m_cloud_line
        HUMIDITY -> R.drawable.ic_m_drop
        DEW -> R.drawable.ic_m_dew
        else -> R.drawable.ic_m_uv
    }

    /** The editor: drag the handle to reorder, the switch to show or hide; [onDone] after saving. */
    @SuppressLint("ClickableViewAccessibility")
    fun edit(context: Context, onDone: () -> Unit) {
        val order = order(context).toMutableList()
        val hidden = hidden(context).toMutableSet()
        lateinit var touch: ItemTouchHelper
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = order.size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val ctx = parent.context
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = 52.dpToPx()
                    setPadding(4.dpToPx(), 0, 6.dpToPx(), 0)
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                row.addView(UiKit.icon(ctx, R.drawable.ic_m_list, UiKit.secondary(ctx), 20).apply {
                    contentDescription = ctx.getString(R.string.ml_w_drag)
                    setPadding(0, 0, 0, 0)
                    (layoutParams as LinearLayout.LayoutParams).marginEnd = 12.dpToPx()
                })
                row.addView(UiKit.icon(ctx, R.drawable.ic_m_thermo, UiKit.secondary(ctx), 20).apply {
                    (layoutParams as LinearLayout.LayoutParams).marginEnd = 12.dpToPx()
                })
                row.addView(TextView(ctx).apply {
                    textSize = 16f
                    setTextColor(UiKit.primary(ctx))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(PillSwitch(ctx), LinearLayout.LayoutParams(46.dpToPx(), 28.dpToPx()))
                return object : RecyclerView.ViewHolder(row) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val row = holder.itemView as LinearLayout
                val key = order[position]
                val ctx = row.context
                (row.getChildAt(1) as android.widget.ImageView).setImageResource(icon(key))
                (row.getChildAt(2) as TextView).text = label(ctx, key)
                (row.getChildAt(3) as PillSwitch).apply {
                    onChange = null
                    checked = key !in hidden
                    onChange = { on -> if (on) hidden -= key else hidden += key }
                }
                row.getChildAt(0).setOnTouchListener { _, e ->
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) touch.startDrag(holder)
                    false
                }
            }
        }
        touch = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun isLongPressDragEnabled() = true

            override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
                val a = from.bindingAdapterPosition
                val b = to.bindingAdapterPosition
                if (a < 0 || b < 0) return false
                order.add(b, order.removeAt(a))
                adapter.notifyItemMoved(a, b)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
        })
        val dialog = context.createDialog(
            title = R.string.ml_w_customize,
            action = R.string.ml_done,
            onAction = {
                save(context, order, hidden)
                onDone()
            },
        ) { parent ->
            val ctx = parent.context
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(ctx).apply {
                    setText(R.string.ml_w_customize_hint)
                    textSize = 13f
                    setTextColor(UiKit.secondary(ctx))
                    setPadding(4.dpToPx(), 12.dpToPx(), 0, 8.dpToPx())
                })
                addView(RecyclerView(ctx).apply {
                    layoutManager = LinearLayoutManager(ctx)
                    this.adapter = adapter
                    isNestedScrollingEnabled = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                    background = UiKit.border(ctx, 16)
                    addItemDecoration(object : RecyclerView.ItemDecoration() {
                        private val paint = android.graphics.Paint().apply { color = UiKit.line(ctx) }
                        override fun onDraw(c: android.graphics.Canvas, parent: RecyclerView, state: RecyclerView.State) {
                            for (i in 1 until parent.childCount) {
                                val v = parent.getChildAt(i)
                                c.drawRect(v.left.toFloat(), v.top.toFloat() + v.translationY, v.right.toFloat(), v.top + v.translationY + 1.dpToPx(), paint)
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
