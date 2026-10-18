package app.olauncher.ui.scanner

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
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
import app.olauncher.helper.showConfirmDialog
import app.olauncher.helper.showMenuDialog
import app.olauncher.helper.showTextInputDialog
import app.olauncher.helper.showToast
import app.olauncher.ui.FileExplorerActivity
import app.olauncher.ui.FolderPickerActivity
import app.olauncher.ui.UiKit
import app.olauncher.ui.pdf.PdfViewerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Scanner: Google's scanner takes the sheets (finds the edges, shoots by itself, crops), then this
 * screen reviews them: drag to reorder, tap for rotate, scan again or delete, "Add" for more pages.
 * The bar picks colour or black and white, A4 or the photo's size, and the searchable text (OCR).
 * "Save" asks for a name and a folder and writes the PDF. With [EXTRA_PICK] (from the PDF tool's
 * pages) the PDF goes to the cache and its path comes back as [EXTRA_PDF_PATH].
 */
class ScannerActivity : AppCompatActivity() {

    private val pages = mutableListOf<ScanPage>()
    private val thumbs = HashMap<String, Bitmap>()
    private lateinit var sessionDir: File
    private lateinit var prefs: SharedPreferences
    private var blackWhite = false
    private var a4 = true
    private var searchable = true
    private var pickMode = false
    /** While the PDF is being made: everything else waits. */
    private var busy = false
    /** Saved and not changed since: leaving doesn't ask. */
    private var saved = false
    /** Some pages arrived at least once: cancelling a later scan doesn't close the screen. */
    private var hadPages = false
    /** Page being scanned again (-1: the scan adds pages). */
    private var redoPosition = -1
    private var pendingName = ""

    private lateinit var appBar: UiKit.AppBar
    private lateinit var hint: TextView
    private lateinit var grid: RecyclerView
    private lateinit var emptyView: View
    private lateinit var tools: LinearLayout
    private lateinit var colorTool: LinearLayout
    private lateinit var sizeTool: LinearLayout
    private lateinit var textTool: LinearLayout

    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val uris = try {
            DocScanner.pages(result)
        } catch (e: Throwable) {
            CrashLog.save(this, e)
            emptyList()
        }
        if (uris.isEmpty()) {
            redoPosition = -1
            if (!hadPages) finish()
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) { DocScanner.copyInto(this@ScannerActivity, uris, sessionDir) }
            if (files.isEmpty()) showToast(getString(R.string.ml_scan_failed))
            if (files.isEmpty()) return@launch
            hadPages = true
            if (redoPosition in pages.indices) {
                pages[redoPosition].file.delete()
                pages[redoPosition] = ScanPage(files[0])
                grid.adapter?.notifyItemChanged(redoPosition)
            } else {
                val start = pages.size
                pages.addAll(files.map { ScanPage(it) })
                grid.adapter?.notifyItemRangeInserted(start, files.size)
                grid.scrollToPosition(pages.lastIndex)
            }
            redoPosition = -1
            changed()
        }
    }

    private val folderLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val path = result.data?.getStringExtra(FileExplorerActivity.EXTRA_LOCAL_PATH) ?: return@registerForActivityResult
        prefs.edit().putString(KEY_LAST_DIR, path).apply()
        val target = File(path, pendingName)
        if (target.exists()) showConfirmDialog(
            getString(R.string.ml_pdf_save_as), getString(R.string.ml_pdf_overwrite, target.name), getString(R.string.ml_pdf_replace),
        ) { build(target) { onSaved(it) } }
        else build(target) { onSaved(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        blackWhite = prefs.getBoolean(KEY_BW, false)
        a4 = prefs.getBoolean(KEY_A4, true)
        searchable = prefs.getBoolean(KEY_OCR, true)
        pickMode = intent.getBooleanExtra(EXTRA_PICK, false)
        if (savedInstanceState != null) {
            sessionDir = File(savedInstanceState.getString(STATE_SESSION) ?: newSession().absolutePath).apply { mkdirs() }
            val paths = savedInstanceState.getStringArray(STATE_PAGES) ?: emptyArray()
            val rotations = savedInstanceState.getIntArray(STATE_ROTATIONS) ?: IntArray(paths.size)
            paths.forEachIndexed { i, p -> File(p).takeIf { it.exists() }?.let { pages += ScanPage(it, rotations.getOrElse(i) { 0 }) } }
            redoPosition = savedInstanceState.getInt(STATE_REDO, -1)
            pendingName = savedInstanceState.getString(STATE_NAME).orEmpty()
            saved = savedInstanceState.getBoolean(STATE_SAVED)
            hadPages = savedInstanceState.getBoolean(STATE_HAD) || pages.isNotEmpty()
        } else {
            DocScanner.cleanup(this)
            sessionDir = newSession()
        }
        setContentView(buildLayout())
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (busy) return
                if (pages.isNotEmpty() && !saved) showConfirmDialog(
                    getString(R.string.ml_edit_discard_title), getString(R.string.ml_scan_discard), getString(R.string.ml_edit_discard_action),
                ) { finish() }
                else finish()
            }
        })
        if (savedInstanceState == null) {
            // The scanner closed by itself last time: show the error, to send it, then start
            val crash = CrashLog.take(this)
            if (crash != null) showConfirmDialog(getString(R.string.ml_scan_crash_title), crash.take(1200), getString(R.string.ml_scan_crash_copy)) {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("MinimaLauncher", crash))
                showToast(getString(R.string.ml_pdf_copied))
            }.setOnDismissListener { if (!isFinishing && pages.isEmpty() && !hadPages) scan() }
            else scan()
        }
    }

    private fun newSession() = File(cacheDir, "scan/${System.currentTimeMillis()}").apply { mkdirs() }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SESSION, sessionDir.absolutePath)
        outState.putStringArray(STATE_PAGES, pages.map { it.file.absolutePath }.toTypedArray())
        outState.putIntArray(STATE_ROTATIONS, pages.map { it.rotation }.toIntArray())
        outState.putInt(STATE_REDO, redoPosition)
        outState.putString(STATE_NAME, pendingName)
        outState.putBoolean(STATE_SAVED, saved)
        outState.putBoolean(STATE_HAD, hadPages)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) sessionDir.deleteRecursively()
    }

    // ---------------------------------------------------------------- layout

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom + 8.dpToPx())
            insets
        }
        appBar = UiKit.appBar(
            this, getString(if (pickMode) R.string.ml_scan_add_pages else R.string.ml_scan_app), { onBackPressedDispatcher.onBackPressed() },
            listOf(UiKit.iconButton(this, R.drawable.ic_m_check, getString(if (pickMode) R.string.ml_scan_add else R.string.ml_scan_save)) { save() }),
        )
        appBar.view.setPadding(4.dpToPx(), 0, 4.dpToPx(), 0)
        root.addView(appBar.view)
        hint = TextView(this).apply {
            setTextColor(UiKit.secondary(this@ScannerActivity))
            textSize = 13f
            setPadding(20.dpToPx(), 0, 20.dpToPx(), 8.dpToPx())
        }
        root.addView(hint)
        val area = FrameLayout(this)
        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@ScannerActivity, 3)
            adapter = PagesAdapter()
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(8.dpToPx(), 0, 8.dpToPx(), 16.dpToPx())
            clipToPadding = false
        }
        dragHelper.attachToRecyclerView(grid)
        area.addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        emptyView = UiKit.emptyState(this, R.drawable.ic_m_scan, getString(R.string.ml_scan_empty), getString(R.string.ml_scan_start)) { scan() }
        area.addView(emptyView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(area, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        tools = UiKit.toolBar(this)
        val add = UiKit.toolButton(this, R.drawable.ic_m_add, getString(R.string.ml_scan_add)) { if (!busy) scan() }
        colorTool = UiKit.toolButton(this, R.drawable.ic_m_image, "") {
            if (busy) return@toolButton
            blackWhite = !blackWhite
            prefs.edit().putBoolean(KEY_BW, blackWhite).apply()
            grid.adapter?.notifyDataSetChanged()
            changed()
        }
        sizeTool = UiKit.toolButton(this, R.drawable.ic_m_pages, "") {
            if (busy) return@toolButton
            a4 = !a4
            prefs.edit().putBoolean(KEY_A4, a4).apply()
            changed()
        }
        textTool = UiKit.toolButton(this, R.drawable.ic_m_text, getString(R.string.ml_scan_text)) {
            if (busy) return@toolButton
            searchable = !searchable
            prefs.edit().putBoolean(KEY_OCR, searchable).apply()
            showToast(getString(if (searchable) R.string.ml_scan_text_on else R.string.ml_scan_text_off))
            changed()
        }
        listOf(add, colorTool, sizeTool, textTool).forEach {
            tools.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(tools, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(12.dpToPx(), 8.dpToPx(), 12.dpToPx(), 0)
        })
        updateState()
        return root
    }

    private fun setToolLabel(tool: LinearLayout, text: String) {
        (tool.getChildAt(1) as TextView).text = text
        tool.contentDescription = text
    }

    private fun changed() {
        saved = false
        updateState()
    }

    private fun updateState() {
        UiKit.setSubtitle(appBar, if (pages.isEmpty()) null else getString(R.string.ml_scan_count, pages.size))
        grid.isVisible = pages.isNotEmpty()
        emptyView.isVisible = pages.isEmpty() && hadPages
        if (!busy) hint.setText(R.string.ml_scan_hint)
        hint.isVisible = pages.isNotEmpty()
        setToolLabel(colorTool, getString(if (blackWhite) R.string.ml_scan_bw else R.string.ml_scan_color))
        setToolLabel(sizeTool, getString(if (a4) R.string.ml_scan_a4 else R.string.ml_scan_original))
        UiKit.setToolActive(textTool, searchable)
        tools.alpha = if (busy) 0.4f else 1f
    }

    // ---------------------------------------------------------------- scanning

    private fun scan(replace: Int = -1) {
        redoPosition = replace
        DocScanner.launch(this, scanLauncher, pageLimit = if (replace >= 0) 1 else 0) {
            redoPosition = -1
            if (!hadPages) finish()
        }
    }

    private fun pageMenu(position: Int) {
        if (busy || position !in pages.indices) return
        val page = pages[position]
        val entries = mutableListOf<MenuEntry>()
        if (position > 0) entries += MenuEntry(getString(R.string.ml_pdf_move_left)) { move(position, position - 1) }
        if (position < pages.lastIndex) entries += MenuEntry(getString(R.string.ml_pdf_move_right)) { move(position, position + 1) }
        entries += MenuEntry(getString(R.string.ml_pdf_rotate)) {
            page.rotation = (page.rotation + 90) % 360
            grid.adapter?.notifyItemChanged(position)
            changed()
        }
        entries += MenuEntry(getString(R.string.ml_scan_redo)) { scan(position) }
        entries += MenuEntry(getString(R.string.ml_pdf_delete_page), destructive = true) {
            pages.removeAt(position).file.delete()
            grid.adapter?.notifyItemRemoved(position)
            grid.adapter?.notifyItemRangeChanged(position, pages.size - position)
            changed()
        }
        showMenuDialog(getString(R.string.ml_scan_page, position + 1), entries)
    }

    private fun move(from: Int, to: Int) {
        pages.add(to, pages.removeAt(from))
        grid.adapter?.notifyItemMoved(from, to)
        grid.adapter?.notifyItemRangeChanged(minOf(from, to), 2)
        changed()
    }

    // ---------------------------------------------------------------- saving

    private fun startDir(): File {
        intent.getStringExtra(EXTRA_START_DIR)?.let { File(it) }?.takeIf { it.isDirectory }?.let { return it }
        prefs.getString(KEY_LAST_DIR, null)?.let { File(it) }?.takeIf { it.isDirectory }?.let { return it }
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).also { it.mkdirs() }
    }

    /** Asks for the name, then the folder (our picker), then writes the PDF. In pick mode, returns it. */
    private fun save() {
        if (busy) return
        if (pages.isEmpty()) {
            showToast(getString(R.string.ml_scan_empty))
            return
        }
        if (pickMode) {
            build(File(cacheDir, "scan/added-${System.currentTimeMillis()}.pdf")) { pdf ->
                setResult(RESULT_OK, Intent().putExtra(EXTRA_PDF_PATH, pdf.absolutePath))
                finish()
            }
            return
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.getDefault()).format(Date())
        val dir = startDir()
        showTextInputDialog(getString(R.string.ml_scan_save), getString(R.string.ml_scan_default_name, stamp), getString(R.string.ml_pdf_file_name), getString(R.string.ml_pdf_choose_folder)) { name ->
            val clean = name.trim().removeSuffix(".pdf").removeSuffix(".PDF").replace('/', '_').ifBlank { return@showTextInputDialog }
            pendingName = "$clean.pdf"
            folderLauncher.launch(Intent(this, FolderPickerActivity::class.java).putExtra(FileExplorerActivity.EXTRA_LOCAL_PATH, dir.absolutePath))
        }
    }

    /** Makes the PDF into [target] (through a temporary file), showing the progress, then [done]. */
    private fun build(target: File, done: (File) -> Unit) {
        busy = true
        updateState()
        val list = pages.map { ScanPage(it.file, it.rotation) }
        val options = ScanPdf.Options(blackWhite, a4, searchable)
        hint.text = getString(R.string.ml_scan_making, 1, list.size)
        hint.isVisible = true
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val tmp = File(cacheDir, "scan/out-${System.currentTimeMillis()}.tmp")
                try {
                    ScanPdf.write(this@ScannerActivity, list, tmp, options) { i, n ->
                        runOnUiThread { if (busy) hint.text = getString(R.string.ml_scan_making, i, n) }
                    }
                    target.parentFile?.mkdirs()
                    tmp.copyTo(target, overwrite = true)
                    if (!target.absolutePath.startsWith(cacheDir.absolutePath)) {
                        MediaScannerConnection.scanFile(applicationContext, arrayOf(target.absolutePath), null, null)
                    }
                    true
                } catch (e: Throwable) {
                    e.printStackTrace()
                    false
                } finally {
                    tmp.delete()
                }
            }
            busy = false
            updateState()
            if (ok) done(target) else showToast(getString(R.string.ml_explorer_failed, getString(R.string.ml_save)))
        }
    }

    private fun onSaved(target: File) {
        saved = true
        showMenuDialog(getString(R.string.ml_pdf_saved, target.name), listOf(
            MenuEntry(getString(R.string.ml_search_open)) {
                PdfViewerActivity.open(this, target)
                finish()
            },
            MenuEntry(getString(R.string.ml_explorer_share)) { share(target) },
            MenuEntry(getString(R.string.ml_scan_open_folder)) {
                target.parentFile?.let { FileExplorerActivity.openLocal(this, it) }
                finish()
            },
            MenuEntry(getString(R.string.ml_scan_new)) {
                pages.forEach { it.file.delete() }
                pages.clear()
                thumbs.clear()
                grid.adapter?.notifyDataSetChanged()
                saved = false
                updateState()
                scan()
            },
        ))
    }

    private fun share(f: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        val send = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, f.name))
    }

    // ---------------------------------------------------------------- grid

    private var dragMoved = false
    private var dragPos = -1
    private val dragHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
        override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder) =
            if (busy) 0 else makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0)

        override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
            val a = from.bindingAdapterPosition
            val b = to.bindingAdapterPosition
            if (a < 0 || b < 0) return false
            pages.add(b, pages.removeAt(a))
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
            if (dragMoved) {
                // Page numbers follow the new order
                rv.post { rv.adapter?.notifyDataSetChanged() }
                changed()
            } else if (dragPos in pages.indices) pageMenu(dragPos) // a long press without moving opens the page menu
            dragPos = -1
        }
    })

    private fun thumbKey(page: ScanPage) = "${page.file.absolutePath}|${page.rotation}|$blackWhite"

    private suspend fun thumbnail(page: ScanPage, width: Int): Bitmap? {
        val key = thumbKey(page)
        thumbs[key]?.let { return it }
        val bitmap = withContext(Dispatchers.Default) {
            val b = ScanPdf.load(page.file, width * 2, page.rotation) ?: return@withContext null
            if (blackWhite) ScanPdf.blackWhitePreview(b).also { b.recycle() } else b
        }
        if (bitmap != null) thumbs[key] = bitmap
        return bitmap
    }

    private inner class PagesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = pages.size

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
            cell.addView(TextView(parent.context).apply {
                setTextColor(UiKit.primary(parent.context))
                textSize = 12f
                maxLines = 1
                setPadding(0, 4.dpToPx(), 0, 0)
            })
            return object : RecyclerView.ViewHolder(cell) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val page = pages[position]
            val cell = holder.itemView as LinearLayout
            val box = cell.getChildAt(0) as FrameLayout
            val image = box.getChildAt(0) as ImageView
            (cell.getChildAt(1) as TextView).text = "${position + 1}"
            val key = thumbKey(page)
            image.tag = key
            image.setImageBitmap(thumbs[key])
            if (thumbs[key] == null) lifecycleScope.launch {
                val b = thumbnail(page, box.layoutParams.width.coerceAtLeast(64)) ?: return@launch
                if (image.tag == key) image.setImageBitmap(b)
            }
            cell.setOnClickListener { pageMenu(holder.bindingAdapterPosition) }
        }
    }

    companion object {
        /** Folder offered first when saving (e.g. the one open in the explorer). */
        const val EXTRA_START_DIR = "start_dir"
        /** Return the pages as a PDF in the cache instead of saving it. */
        const val EXTRA_PICK = "pick"
        const val EXTRA_PDF_PATH = "pdf_path"

        private const val PREFS = "app.minimalauncher.scanner"
        private const val KEY_BW = "bw"
        private const val KEY_A4 = "a4"
        private const val KEY_OCR = "ocr"
        private const val KEY_LAST_DIR = "last_dir"
        private const val STATE_SESSION = "session"
        private const val STATE_PAGES = "pages"
        private const val STATE_ROTATIONS = "rotations"
        private const val STATE_REDO = "redo"
        private const val STATE_NAME = "name"
        private const val STATE_SAVED = "saved"
        private const val STATE_HAD = "had"

        fun open(context: Context, startDir: File? = null) = context.startActivity(
            Intent(context, ScannerActivity::class.java).apply { startDir?.let { putExtra(EXTRA_START_DIR, it.absolutePath) } }
        )

        fun pickIntent(context: Context) = Intent(context, ScannerActivity::class.java).putExtra(EXTRA_PICK, true)
    }
}
