package app.olauncher.ui.gallery

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.helper.MediaThumbnails
import app.olauncher.helper.MediaTrash
import app.olauncher.helper.MenuEntry
import app.olauncher.helper.dpToPx
import app.olauncher.helper.showConfirmDialog
import app.olauncher.helper.showMenuDialog
import app.olauncher.helper.showToast
import app.olauncher.ui.DragSelect
import app.olauncher.ui.UiKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The gallery's recycle bin: the photos and videos deleted from the gallery, the albums and the
 * viewer, newest first, each with the days left before it goes for good. Tap or long press selects
 * them (sliding selects more); the bar restores them where they were or deletes them forever.
 * The ⋮ empties the whole bin.
 */
class TrashActivity : AppCompatActivity() {

    private var entries: List<MediaTrash.Entry> = emptyList()
    private val selected = LinkedHashSet<String>()
    private val selecting get() = selected.isNotEmpty()
    private var loaded = false

    private lateinit var bar: UiKit.AppBar
    private lateinit var title: TextView
    private lateinit var backButton: ImageView
    private lateinit var menuButton: ImageView
    private lateinit var selectionBar: LinearLayout
    private lateinit var grid: RecyclerView
    private lateinit var emptyView: View
    private lateinit var dragSelect: DragSelect

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = if (selecting) clearSelection() else finish()
        })
        reload()
    }

    private fun reload() {
        lifecycleScope.launch {
            entries = withContext(Dispatchers.IO) {
                MediaTrash.purgeExpired(this@TrashActivity)
                MediaTrash.list(this@TrashActivity)
            }
            loaded = true
            selected.retainAll(entries.map { it.file.absolutePath }.toSet())
            updateBars()
            grid.adapter?.notifyDataSetChanged()
        }
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // A solid background, like the gallery (the theme shows the wallpaper)
        val night = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        root.setBackgroundColor(if (night) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top + 8.dpToPx(), 0, bars.bottom)
            insets
        }
        menuButton = UiKit.iconButton(this, R.drawable.ic_m_more, getString(R.string.ml_explorer_more)) { showMenu() }
        bar = UiKit.appBar(this, getString(R.string.ml_trash), onBack = { if (selecting) clearSelection() else finish() },
            actions = listOf(menuButton))
        bar.view.setPadding(6.dpToPx(), 0, 6.dpToPx(), 8.dpToPx())
        backButton = bar.back
        title = bar.title
        root.addView(bar.view)

        val content = FrameLayout(this)
        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@TrashActivity, COLUMNS)
            adapter = TrashAdapter()
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(8.dpToPx(), 0, 8.dpToPx(), 24.dpToPx())
        }
        content.addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        emptyView = UiKit.emptyState(this, R.drawable.ic_m_delete, getString(R.string.ml_trash_empty)).apply { isVisible = false }
        content.addView(emptyView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        dragSelect = DragSelect(grid, { selecting },
            isSelected = { p -> entries.getOrNull(p)?.file?.absolutePath in selected },
            setSelected = { p, on -> entries.getOrNull(p)?.let { if (on) selected.add(it.file.absolutePath) else selected.remove(it.file.absolutePath) } },
            onChanged = {
                updateBars()
                grid.adapter?.notifyDataSetChanged()
            })

        selectionBar = UiKit.toolBar(this).apply { isVisible = false }
        listOf(
            Triple(R.drawable.ic_m_undo, R.string.ml_trash_restore) { restoreSelected() },
            Triple(R.drawable.ic_m_delete, R.string.ml_trash_delete_forever) { deleteSelected() },
            Triple(R.drawable.ic_m_select, R.string.ml_explorer_select_all) { selectAll() },
        ).forEach { (icon, label, action) ->
            selectionBar.addView(UiKit.toolButton(this, icon, getString(label)) { action() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(selectionBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(12.dpToPx(), 8.dpToPx(), 12.dpToPx(), 8.dpToPx())
        })
        return root
    }

    private fun updateBars() {
        backButton.setImageResource(if (selecting) R.drawable.ic_m_close else R.drawable.ic_m_arrow_back)
        backButton.contentDescription = getString(if (selecting) R.string.close else R.string.ml_cal_back)
        title.text = if (selecting) getString(R.string.ml_explorer_selected, selected.size) else getString(R.string.ml_trash)
        UiKit.setSubtitle(bar,
            if (selecting || entries.isEmpty()) null else getString(R.string.ml_trash_subtitle, entries.size, MediaTrash.KEEP_DAYS))
        menuButton.isVisible = !selecting && entries.isNotEmpty()
        selectionBar.isVisible = selecting
        emptyView.isVisible = loaded && entries.isEmpty()
    }

    private fun toggle(entry: MediaTrash.Entry) {
        val key = entry.file.absolutePath
        if (!selected.remove(key)) selected.add(key)
        updateBars()
        grid.adapter?.notifyDataSetChanged()
    }

    private fun clearSelection() {
        selected.clear()
        updateBars()
        grid.adapter?.notifyDataSetChanged()
    }

    private fun selectAll() {
        entries.forEach { selected.add(it.file.absolutePath) }
        updateBars()
        grid.adapter?.notifyDataSetChanged()
    }

    private fun selectedEntries() = entries.filter { it.file.absolutePath in selected }

    private fun restoreSelected() {
        val chosen = selectedEntries()
        if (chosen.isEmpty()) return
        lifecycleScope.launch {
            val restored = withContext(Dispatchers.IO) { MediaTrash.restore(chosen) }
            MediaTrash.scan(this@TrashActivity, restored)
            if (restored.size < chosen.size) showToast(getString(R.string.ml_explorer_failed, getString(R.string.ml_trash_restore)))
            else showToast(getString(R.string.ml_trash_restored, restored.size))
            selected.clear()
            reload()
        }
    }

    private fun deleteSelected() {
        val chosen = selectedEntries()
        if (chosen.isEmpty()) return
        askDeleteForever(chosen, getString(R.string.ml_trash_delete_forever_confirm, chosen.size))
    }

    private fun askDeleteForever(chosen: List<MediaTrash.Entry>, message: String) {
        showConfirmDialog(getString(R.string.ml_trash_delete_forever), message, getString(R.string.ml_trash_delete_forever)) {
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { MediaTrash.deleteForever(chosen) }
                if (!ok) showToast(getString(R.string.ml_explorer_failed, getString(R.string.ml_trash_delete_forever)))
                selected.clear()
                reload()
            }
        }
    }

    private fun showMenu() {
        if (entries.isEmpty()) return
        showMenuDialog(getString(R.string.ml_trash), listOf(
            MenuEntry(getString(R.string.ml_explorer_select_all), icon = R.drawable.ic_m_select) { selectAll() },
            MenuEntry(getString(R.string.ml_trash_empty_action), icon = R.drawable.ic_m_delete) {
                askDeleteForever(entries, getString(R.string.ml_trash_empty_confirm, entries.size))
            },
        ))
    }

    private inner class TrashAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val side = parent.width.takeIf { it > 0 }?.let { (it - parent.paddingLeft - parent.paddingRight) / COLUMNS }
                ?: (resources.displayMetrics.widthPixels / COLUMNS)
            val cell = FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(side, side)
                setPadding(2.dpToPx(), 2.dpToPx(), 2.dpToPx(), 2.dpToPx())
                isClickable = true
                isLongClickable = true
            }
            cell.addView(ImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply {
                    cornerRadius = 12.dpToPx().toFloat()
                    setColor(0x22888888)
                }
                clipToOutline = true
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // Play mark of the videos
            cell.addView(FrameLayout(parent.context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x99000000.toInt())
                }
                addView(ImageView(parent.context).apply {
                    setImageResource(R.drawable.ic_m_play)
                    imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
                }, FrameLayout.LayoutParams(18.dpToPx(), 18.dpToPx(), Gravity.CENTER))
                isVisible = false
            }, FrameLayout.LayoutParams(34.dpToPx(), 34.dpToPx(), Gravity.CENTER))
            // Selection mark
            cell.addView(FrameLayout(parent.context).apply { isVisible = false }, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END
            ).apply { setMargins(0, 8.dpToPx(), 8.dpToPx(), 0) })
            // Days left before it goes for good
            cell.addView(TextView(parent.context).apply {
                textSize = 11f
                setTextColor(0xFFFFFFFF.toInt())
                background = GradientDrawable().apply {
                    cornerRadius = 8.dpToPx().toFloat()
                    setColor(0x99000000.toInt())
                }
                setPadding(6.dpToPx(), 1.dpToPx(), 6.dpToPx(), 1.dpToPx())
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
                setMargins(8.dpToPx(), 0, 0, 8.dpToPx())
            })
            return object : RecyclerView.ViewHolder(cell) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val entry = entries[position]
            val cell = holder.itemView as FrameLayout
            val image = cell.getChildAt(0) as ImageView
            val play = cell.getChildAt(1)
            val mark = cell.getChildAt(2) as FrameLayout
            val days = cell.getChildAt(3) as TextView
            val isSelected = entry.file.absolutePath in selected
            play.isVisible = MediaThumbnails.isVideo(entry.file)
            days.text = getString(R.string.ml_trash_days_left, entry.daysLeft())
            mark.removeAllViews()
            if (selecting) mark.addView(UiKit.checkBadge(this@TrashActivity, isSelected, 24))
            mark.isVisible = selecting
            image.alpha = if (selecting && !isSelected) 0.6f else 1f
            image.scaleX = if (isSelected) 0.9f else 1f
            image.scaleY = image.scaleX
            cell.setOnClickListener { toggle(entry) }
            cell.setOnLongClickListener {
                if (entry.file.absolutePath !in selected) toggle(entry)
                dragSelect.start(entries.indexOf(entry))
                true
            }
            MediaThumbnails.load(lifecycleScope, image, entry.file, cell.layoutParams.width.coerceAtLeast(64))
        }
    }

    companion object {
        private const val COLUMNS = 3

        fun open(context: Context) = context.startActivity(Intent(context, TrashActivity::class.java))
    }
}
