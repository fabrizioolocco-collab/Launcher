package app.olauncher.ui.pdf

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.helper.MenuEntry
import app.olauncher.helper.dpToPx
import app.olauncher.helper.pillButton
import app.olauncher.helper.showMenuDialog
import app.olauncher.helper.showToast
import app.olauncher.ui.UiKit
import app.olauncher.ui.scanner.ScannerActivity
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Page thumbnails of a PDF: drag to reorder, long press to rotate, delete or restore; "Scan" adds
 * scanned pages at the end. The result (order and rotations, indexes of the original pages) goes
 * back to the viewer; when pages were scanned, also the path of the working copy that has them
 * after the original ones (the original indexes don't change).
 */
class PdfPagesActivity : AppCompatActivity() {

    private lateinit var file: File
    private var renderer: PdfRenderer? = null
    private var total = 0
    /** Current order (original indexes); deleted pages are kept aside to be restorable. */
    private val order = mutableListOf<Int>()
    private val deleted = mutableListOf<Int>()
    private val rotation = mutableMapOf<Int, Int>()
    private val thumbs = HashMap<Int, Bitmap>()
    private lateinit var grid: RecyclerView
    private lateinit var restoreButton: TextView

    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val added = result.data?.getStringExtra(ScannerActivity.EXTRA_PDF_PATH)?.let { File(it) } ?: return@registerForActivityResult
        addScanned(added)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        file = File(intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return })
        intent.getIntArrayExtra(EXTRA_ORDER)?.let { order.addAll(it.toList()) }
        intent.getIntArrayExtra(EXTRA_ROTATION_KEYS)?.let { keys ->
            val values = intent.getIntArrayExtra(EXTRA_ROTATION_VALUES) ?: IntArray(keys.size)
            keys.forEachIndexed { i, k -> rotation[k] = values[i] }
        }
        try {
            renderer = PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
            total = renderer!!.pageCount
        } catch (e: Exception) {
            showToast(getString(R.string.ml_pdf_cannot_open))
            finish()
            return
        }
        if (order.isEmpty()) order.addAll(0 until total)
        deleted.addAll((0 until total).filter { it !in order })
        setContentView(buildLayout())
    }

    override fun onDestroy() {
        super.onDestroy()
        renderer?.close()
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        restoreButton = pillButton(this, getString(R.string.ml_pdf_restore_page), filled = false, color = UiKit.primary(this)) { restore() }.apply {
            textSize = 13f
            setPadding(14.dpToPx(), 0, 14.dpToPx(), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 34.dpToPx()).apply { marginEnd = 4.dpToPx() }
        }
        val appBar = UiKit.appBar(
            this, getString(R.string.ml_pdf_pages), { finish() },
            listOf(
                restoreButton,
                UiKit.iconButton(this, R.drawable.ic_m_scan, getString(R.string.ml_scan_add_pages)) { scanLauncher.launch(ScannerActivity.pickIntent(this)) },
                UiKit.iconButton(this, R.drawable.ic_m_check, getString(R.string.ml_done)) { done() },
            ),
        )
        appBar.view.setPadding(4.dpToPx(), 0, 4.dpToPx(), 0)
        root.addView(appBar.view)
        root.addView(TextView(this).apply {
            setText(R.string.ml_pdf_pages_hint)
            setTextColor(UiKit.secondary(this@PdfPagesActivity))
            textSize = 13f
            setPadding(20.dpToPx(), 0, 20.dpToPx(), 8.dpToPx())
        })
        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@PdfPagesActivity, 3)
            adapter = PagesAdapter()
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(8.dpToPx(), 0, 8.dpToPx(), 16.dpToPx())
            clipToPadding = false
        }
        dragHelper.attachToRecyclerView(grid)
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        updateRestore()
        return root
    }

    private fun updateRestore() {
        restoreButton.isVisible = deleted.isNotEmpty()
    }

    private fun restore() {
        showMenuDialog(getString(R.string.ml_pdf_restore_page), deleted.map { index ->
            MenuEntry(getString(R.string.ml_pdf_page_of, index + 1, total)) {
                deleted.remove(index)
                order.add(index)
                grid.adapter?.notifyDataSetChanged()
                updateRestore()
            }
        })
    }

    private fun done() {
        if (order.isEmpty()) {
            showToast(getString(R.string.ml_pdf_nothing))
            return
        }
        setResult(RESULT_OK, Intent()
            .putExtra(EXTRA_PATH, file.absolutePath)
            .putExtra(EXTRA_ORDER, order.toIntArray())
            .putExtra(EXTRA_ROTATION_KEYS, rotation.keys.toIntArray())
            .putExtra(EXTRA_ROTATION_VALUES, rotation.keys.map { rotation.getValue(it) }.toIntArray()))
        finish()
    }

    /**
     * Appends the scanned pages: a working copy in the cache gets the current pages followed by the
     * scanned ones, the thumbnails switch to it, and the new pages join the end of the order.
     */
    private fun addScanned(added: File) {
        lifecycleScope.launch {
            val work = File(File(cacheDir, "pdf").apply { mkdirs() }, "work-${System.currentTimeMillis()}.pdf")
            val ok = withContext(Dispatchers.IO) {
                try {
                    PDFBoxResourceLoader.init(applicationContext)
                    PDDocument.load(file).use { doc ->
                        doc.isAllSecurityToBeRemoved = true
                        PDDocument.load(added).use { scan ->
                            scan.pages.forEach { doc.importPage(it) }
                            doc.save(work)
                        }
                    }
                    true
                } catch (e: Throwable) {
                    e.printStackTrace()
                    work.delete()
                    false
                } finally {
                    added.delete()
                }
            }
            val opened = if (ok) try {
                PdfRenderer(ParcelFileDescriptor.open(work, ParcelFileDescriptor.MODE_READ_ONLY))
            } catch (e: Exception) {
                null
            } else null
            if (opened == null) {
                work.delete()
                showToast(getString(R.string.ml_scan_failed))
                return@launch
            }
            renderer?.let { synchronized(it) { it.close() } }
            renderer = opened
            file = work
            val before = total
            total = opened.pageCount
            order.addAll(before until total)
            grid.adapter?.notifyItemRangeInserted(order.size - (total - before), total - before)
            grid.scrollToPosition(order.lastIndex)
        }
    }

    private fun pageMenu(position: Int) {
        val index = order[position]
        val entries = mutableListOf<MenuEntry>()
        if (position > 0) entries += MenuEntry(getString(R.string.ml_pdf_move_left)) { move(position, position - 1) }
        if (position < order.lastIndex) entries += MenuEntry(getString(R.string.ml_pdf_move_right)) { move(position, position + 1) }
        entries += MenuEntry(getString(R.string.ml_pdf_rotate)) {
            rotation[index] = ((rotation[index] ?: 0) + 90) % 360
            grid.adapter?.notifyItemChanged(position)
        }
        entries += MenuEntry(getString(R.string.ml_pdf_delete_page)) {
            order.removeAt(position)
            deleted.add(index)
            grid.adapter?.notifyItemRemoved(position)
            updateRestore()
        }
        showMenuDialog(getString(R.string.ml_pdf_page_of, index + 1, total), entries)
    }

    private fun move(from: Int, to: Int) {
        order.add(to, order.removeAt(from))
        grid.adapter?.notifyItemMoved(from, to)
    }

    private var dragMoved = false
    private var dragPos = -1
    private val dragHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
        override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder) =
            makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0)

        override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
            val a = from.bindingAdapterPosition
            val b = to.bindingAdapterPosition
            if (a < 0 || b < 0) return false
            order.add(b, order.removeAt(a))
            rv.adapter?.notifyItemMoved(a, b)
            dragMoved = true
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                dragMoved = false
                dragPos = viewHolder.bindingAdapterPosition
                viewHolder.itemView.alpha = 0.6f
            }
        }

        override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(rv, viewHolder)
            viewHolder.itemView.alpha = 1f
            // A long press without moving opens the page menu
            if (!dragMoved && dragPos in order.indices) pageMenu(dragPos)
            dragPos = -1
        }
    })

    private suspend fun thumbnail(index: Int, width: Int): Bitmap? {
        thumbs[index]?.let { return it }
        val r = renderer ?: return null
        val bitmap = withContext(Dispatchers.IO) {
            try {
                synchronized(r) {
                    r.openPage(index).use { page ->
                        val h = width * page.height / page.width
                        Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888).also { b ->
                            b.eraseColor(Color.WHITE)
                            page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
        if (bitmap != null) thumbs[index] = bitmap
        return bitmap
    }

    private inner class PagesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = order.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val side = (parent.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) / 3
            val cell = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(6.dpToPx(), 6.dpToPx(), 6.dpToPx(), 10.dpToPx())
                isClickable = true
                layoutParams = RecyclerView.LayoutParams(side, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val box = FrameLayout(parent.context).apply { background = UiKit.border(parent.context, 6, 0xFF333333.toInt()) }
            box.addView(ImageView(parent.context).apply { scaleType = ImageView.ScaleType.FIT_CENTER },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val inner = side - 12.dpToPx()
            cell.addView(box, LinearLayout.LayoutParams(inner, inner * 4 / 3))
            // Page number, then (when turned) a rotate icon with the degrees
            val label = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 4.dpToPx(), 0, 0)
            }
            label.addView(TextView(parent.context).apply {
                setTextColor(UiKit.primary(parent.context))
                textSize = 12f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            label.addView(UiKit.icon(parent.context, R.drawable.ic_m_rotate, UiKit.secondary(parent.context), 14).apply {
                (layoutParams as LinearLayout.LayoutParams).marginStart = 8.dpToPx()
            })
            label.addView(TextView(parent.context).apply {
                setTextColor(UiKit.secondary(parent.context))
                textSize = 12f
                maxLines = 1
                setPadding(3.dpToPx(), 0, 0, 0)
            })
            cell.addView(label)
            return object : RecyclerView.ViewHolder(cell) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val index = order[position]
            val cell = holder.itemView as LinearLayout
            val box = cell.getChildAt(0) as FrameLayout
            val image = box.getChildAt(0) as ImageView
            val label = cell.getChildAt(1) as LinearLayout
            val rot = rotation[index] ?: 0
            (label.getChildAt(0) as TextView).text = "${index + 1}"
            label.getChildAt(1).isVisible = rot != 0
            (label.getChildAt(2) as TextView).apply {
                isVisible = rot != 0
                text = "$rot\u00B0"
            }
            image.rotation = rot.toFloat()
            image.tag = index
            image.setImageBitmap(null)
            lifecycleScope.launch {
                val b = thumbnail(index, box.layoutParams.width.coerceAtLeast(64)) ?: return@launch
                if (image.tag == index) image.setImageBitmap(b)
            }
            cell.setOnClickListener { pageMenu(holder.bindingAdapterPosition) }
        }
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_ORDER = "order"
        const val EXTRA_ROTATION_KEYS = "rot_keys"
        const val EXTRA_ROTATION_VALUES = "rot_values"

        fun intent(context: Context, file: File, layout: PageLayout) = Intent(context, PdfPagesActivity::class.java)
            .putExtra(EXTRA_PATH, file.absolutePath)
            .putExtra(EXTRA_ORDER, layout.order.toIntArray())
            .putExtra(EXTRA_ROTATION_KEYS, layout.rotation.keys.toIntArray())
            .putExtra(EXTRA_ROTATION_VALUES, layout.rotation.keys.map { layout.rotation.getValue(it) }.toIntArray())
    }
}
