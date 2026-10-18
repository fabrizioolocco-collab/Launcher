package app.olauncher.ui.pdf

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.helper.MenuEntry
import app.olauncher.helper.dpToPx
import app.olauncher.helper.pillButton
import app.olauncher.helper.showConfirmDialog
import app.olauncher.helper.showMenuDialog
import app.olauncher.helper.showTextInputDialog
import app.olauncher.helper.showToast
import app.olauncher.ui.FileExplorerActivity
import app.olauncher.ui.FolderPickerActivity
import app.olauncher.ui.UiKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

/**
 * PDF viewer and editor. Pages scroll vertically (pinch to zoom, double tap). Tools: text, check
 * marks, free drawing (pen, highlighter, eraser), the saved signature; a palette with colours and
 * sizes opens with the tool and can be folded. Tap an annotation to select it: drag moves it, the
 * handles rotate, resize or delete it. "Pages" reorders, rotates or deletes pages. "Save" asks for
 * a name and a folder and writes a flattened copy (annotations drawn into the pages, text kept).
 */
class PdfViewerActivity : AppCompatActivity() {

    /** The pages shown and edited: [sourceFile], or a working copy with scanned pages added in "Pages". */
    private lateinit var file: File
    /** The PDF that was opened: its name, folder and "replace" when saving. */
    private lateinit var sourceFile: File
    private var renderer: PdfRenderer? = null
    private var total = 0
    private var layout = PageLayout(mutableListOf())
    private val annotations = mutableListOf<Annotation>()
    private val renders = HashMap<Int, Bitmap>()

    private lateinit var pager: RecyclerView
    private lateinit var appBar: UiKit.AppBar
    private lateinit var toolbar: LinearLayout
    private lateinit var hint: TextView
    private lateinit var palette: LinearLayout
    private var paletteOpen = true
    private var tool: Tool = Tool.NONE
    /** The text being typed in place on its page, if any. */
    private var editing: Annotation.Text? = null
    private var signature: Bitmap? = null
    private var selected: Annotation? = null

    private var penColor = COLORS[0]
    private var highlightColor = 0xFFFDD835.toInt()
    private var widthIndex = 1
    private var drawMode = DrawMode.PEN
    private var textSize = SIZES[1]
    private var checkSize = CHECK_SIZES[1]
    private var cross = false
    private var pendingSaveName = ""

    // Text search: the bar, the document's text (read the first time) and the matches
    private lateinit var searchBar: LinearLayout
    private lateinit var searchInput: android.widget.EditText
    private lateinit var searchCount: TextView
    private var pdfText: PdfText? = null
    /** OCR progress shown next to the count, while scanned pages are being read. */
    private var ocrStatus = ""
    /** Text selected with the "Select" tool: its page and the selection. */
    private var textSelection: Pair<Int, PdfText.Selection>? = null
    private var selectStart: PointF? = null
    private lateinit var selectionBar: LinearLayout
    private lateinit var selectionText: TextView
    private var hits: List<PdfSearch.Hit> = emptyList()
    private var hitIndex = -1
    private var searchJob: kotlinx.coroutines.Job? = null

    private enum class Tool { NONE, TEXT, CHECK, DRAW, SIGN, SELECT }
    private enum class DrawMode { PEN, HIGHLIGHTER, ERASER }

    private val inkColor get() = if (drawMode == DrawMode.HIGHLIGHTER) highlightColor else penColor

    private val pagesLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data ?: return@registerForActivityResult
        val order = data.getIntArrayExtra(PdfPagesActivity.EXTRA_ORDER) ?: return@registerForActivityResult
        val keys = data.getIntArrayExtra(PdfPagesActivity.EXTRA_ROTATION_KEYS) ?: IntArray(0)
        val values = data.getIntArrayExtra(PdfPagesActivity.EXTRA_ROTATION_VALUES) ?: IntArray(0)
        // Scanned pages were added: switch to the working copy (same indexes for the original pages)
        data.getStringExtra(PdfPagesActivity.EXTRA_PATH)?.takeIf { it != file.absolutePath }?.let { path ->
            val previous = file
            file = File(path)
            if (!openRenderer()) {
                file = previous
                openRenderer()
                return@registerForActivityResult
            }
            pdfText = null
            hits = emptyList()
            hitIndex = -1
        }
        layout = PageLayout(order.toMutableList(), keys.indices.associate { keys[it] to values[it] }.toMutableMap())
        renders.clear()
        pager.adapter?.notifyDataSetChanged()
        updateTitle()
    }

    private val signatureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        signature = SavedSignature.load(this)
        if (signature != null) setTool(Tool.SIGN)
    }

    private val folderLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val path = result.data?.getStringExtra(FileExplorerActivity.EXTRA_LOCAL_PATH) ?: return@registerForActivityResult
        val target = File(path, pendingSaveName)
        if (target.exists()) showConfirmDialog(
            getString(R.string.ml_pdf_save_as), getString(R.string.ml_pdf_overwrite, target.name), getString(R.string.ml_pdf_replace),
        ) { writeTo(target) }
        else writeTo(target)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        // A link from the browser: download it first
        val link = intent.data?.takeIf { it.scheme == "http" || it.scheme == "https" }
        if (link != null) {
            setContentView(TextView(this).apply {
                setText(R.string.ml_pdf_downloading)
                setTextColor(UiKit.primary(this@PdfViewerActivity))
                textSize = 16f
                gravity = Gravity.CENTER
                setBackgroundColor(0xFF202020.toInt())
            })
            lifecycleScope.launch {
                val downloaded = withContext(Dispatchers.IO) { download(link) }
                if (downloaded == null) {
                    showToast(getString(R.string.ml_pdf_cannot_open))
                    finish()
                } else start(downloaded)
            }
            return
        }
        val opened = resolveFile(intent) ?: run {
            showToast(getString(R.string.ml_pdf_cannot_open))
            finish()
            return
        }
        start(opened)
    }

    /** Downloads the PDF at [uri] into the cache (following redirects); null if it fails or isn't a PDF. */
    private fun download(uri: Uri): File? = try {
        fun open(u: java.net.URL) = (u.openConnection() as java.net.HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        var url = java.net.URL(uri.toString())
        var connection = open(url)
        var redirects = 0
        // Redirects between http and https aren't followed by HttpURLConnection
        while (connection.responseCode in 300..399 && redirects++ < 5) {
            val next = connection.getHeaderField("Location") ?: break
            connection.disconnect()
            url = java.net.URL(url, next)
            connection = open(url)
        }
        if (connection.responseCode !in 200..299) null
        else {
            val fromHeader = connection.getHeaderField("Content-Disposition")
                ?.let { Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
                ?.let { Uri.decode(it) }
            val name = (fromHeader ?: uri.lastPathSegment)?.substringAfterLast('/')
                ?.takeIf { it.isNotBlank() }?.let { if (it.endsWith(".pdf", true)) it else "$it.pdf" } ?: "documento.pdf"
            val dir = File(cacheDir, "pdf").apply { mkdirs() }
            val target = File(dir, name.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
            connection.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
            connection.disconnect()
            // A PDF starts with "%PDF"
            val head = target.inputStream().use { s -> ByteArray(4).also { s.read(it) } }
            if (String(head) == "%PDF") target else null
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }

    private fun start(opened: File) {
        file = opened
        sourceFile = opened
        if (!openRenderer()) {
            showToast(getString(R.string.ml_pdf_cannot_open))
            finish()
            return
        }
        signature = SavedSignature.load(this)
        getSharedPreferences("app.minimalauncher.hints", MODE_PRIVATE).let { prefs ->
            if (!prefs.getBoolean("pdf_select", false)) {
                prefs.edit().putBoolean("pdf_select", true).apply()
                showToast(getString(R.string.ml_pdf_move_hint), android.widget.Toast.LENGTH_LONG)
            }
        }
        setContentView(buildLayout())
        // After the "downloading" page the insets aren't sent again by themselves: the bar went under the status bar
        ViewCompat.requestApplyInsets(window.decorView)
        offerDefault()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (editing != null) stopEdit()
                else if (selected != null) select(null)
                else if (tool != Tool.NONE) setTool(Tool.NONE)
                else if (annotations.isNotEmpty() || !layout.isIdentity(total)) showConfirmDialog(
                    getString(R.string.ml_edit_discard_title), getString(R.string.ml_pdf_discard), getString(R.string.ml_edit_discard_action),
                ) { finish() }
                else finish()
            }
        })
    }

    private fun openRenderer(): Boolean = try {
        renderer?.close()
        renderer = PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
        total = renderer!!.pageCount
        layout = PageLayout.identity(total)
        true
    } catch (e: Exception) {
        renderer = null
        false
    }

    /** The file to open: a path from our explorer, or a content/file URI from another app (copied to the cache). */
    private fun resolveFile(intent: Intent): File? {
        intent.getStringExtra(EXTRA_PATH)?.let { return File(it).takeIf { f -> f.exists() } }
        @Suppress("DEPRECATION")
        val uri = intent.data ?: (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri) ?: return null
        if (uri.scheme == "file") return uri.path?.let { File(it) }?.takeIf { it.exists() }
        return try {
            val name = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }?.takeIf { it.endsWith(".pdf", true) } ?: "documento.pdf"
            val dir = File(cacheDir, "pdf").apply { mkdirs() }
            val target = File(dir, name)
            contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } } ?: return null
            target
        } catch (e: Exception) {
            null
        }
    }

    private val fromCache get() = sourceFile.absolutePath.startsWith(cacheDir.absolutePath)

    override fun onDestroy() {
        super.onDestroy()
        renderer?.close()
    }

    // ---------------------------------------------------------------- layout

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF202020.toInt())
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        appBar = UiKit.appBar(
            this, sourceFile.name, { onBackPressedDispatcher.onBackPressed() },
            listOf(
                UiKit.iconButton(this, R.drawable.ic_m_search, getString(R.string.ml_search)) { toggleSearch() },
                UiKit.iconButton(this, R.drawable.ic_m_pages, getString(R.string.ml_pdf_pages)) { openPages() },
                UiKit.iconButton(this, R.drawable.ic_m_share, getString(R.string.ml_explorer_share)) { share(sourceFile) },
                UiKit.iconButton(this, R.drawable.ic_m_check, getString(R.string.ml_pdf_save)) { save() },
            ),
        )
        appBar.title.ellipsize = TextUtils.TruncateAt.MIDDLE
        appBar.view.setBackgroundColor(UiKit.card(this))
        appBar.view.setPadding(4.dpToPx(), 0, 4.dpToPx(), 0)
        root.addView(appBar.view)
        root.addView(divider())
        root.addView(buildSearchBar())

        hint = TextView(this).apply {
            setTextColor(UiKit.secondary(this@PdfViewerActivity))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(12.dpToPx(), 4.dpToPx(), 12.dpToPx(), 2.dpToPx())
            isVisible = false
        }
        root.addView(hint)
        root.addView(buildSelectionBar())

        pager = ZoomRecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@PdfViewerActivity)
            adapter = PagesAdapter()
            overScrollMode = View.OVER_SCROLL_NEVER
            setItemViewCacheSize(2)
        }
        pager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = updateTitle()
        })
        root.addView(pager, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // The tool palette: colours, sizes and modes; folds to one row
        palette = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiKit.cardBackground(this@PdfViewerActivity, 18)
            setPadding(10.dpToPx(), 6.dpToPx(), 10.dpToPx(), 6.dpToPx())
            isVisible = false
        }
        root.addView(palette, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(8.dpToPx(), 6.dpToPx(), 8.dpToPx(), 0)
        })

        toolbar = UiKit.toolBar(this)
        listOf(
            Triple(Tool.TEXT, R.string.ml_pdf_text, R.drawable.ic_m_text),
            Triple(Tool.CHECK, R.string.ml_pdf_check, R.drawable.ic_m_check),
            Triple(Tool.DRAW, R.string.ml_pdf_draw, R.drawable.ic_m_draw),
            Triple(Tool.SIGN, R.string.ml_pdf_sign, R.drawable.ic_m_signature),
            Triple(Tool.SELECT, R.string.ml_pdf_select_text, R.drawable.ic_m_select),
        ).forEach { (t, label, icon) ->
            toolbar.addView(UiKit.toolButton(this, icon, getString(label)) { onToolTapped(t) }.apply {
                tag = t
                setOnLongClickListener {
                    if (t == Tool.SIGN) signatureMenu()
                    t == Tool.SIGN
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        toolbar.addView(
            UiKit.toolButton(this, R.drawable.ic_m_undo, getString(R.string.ml_pdf_undo)) { undo() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(8.dpToPx(), 6.dpToPx(), 8.dpToPx(), 6.dpToPx())
        })
        updateTitle()
        return root
    }

    /** A thin line under a bar. */
    private fun divider() = View(this).apply {
        setBackgroundColor(UiKit.stroke(this@PdfViewerActivity))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1.dpToPx())
    }

    /** A bar under the app bar (search, selection): the card color, a line under it. */
    private fun barBackground() = android.graphics.drawable.LayerDrawable(
        arrayOf<android.graphics.drawable.Drawable>(
            android.graphics.drawable.ColorDrawable(UiKit.stroke(this)),
            android.graphics.drawable.ColorDrawable(UiKit.card(this)),
        )
    ).apply { setLayerInset(1, 0, 0, 0, 1.dpToPx()) }

    // ---------------------------------------------------------------- text selection

    private fun buildSelectionBar(): View {
        selectionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = barBackground()
            setPadding(16.dpToPx(), 6.dpToPx(), 4.dpToPx(), 6.dpToPx())
            isVisible = false
        }
        selectionText = TextView(this).apply {
            setTextColor(UiKit.secondary(this@PdfViewerActivity))
            textSize = 13f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        selectionBar.addView(selectionText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        selectionBar.addView(
            pillButton(this, getString(R.string.ml_pdf_copy), filled = true, color = UiKit.primary(this)) { copySelection() }.apply {
                setPadding(18.dpToPx(), 0, 18.dpToPx(), 0)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 36.dpToPx()).apply { marginStart = 10.dpToPx() },
        )
        selectionBar.addView(UiKit.iconButton(this, R.drawable.ic_m_close, getString(R.string.close)) { clearTextSelection() })
        return selectionBar
    }

    private fun clearTextSelection() {
        if (!::selectionBar.isInitialized || (textSelection == null && !selectionBar.isVisible)) return
        textSelection = null
        selectStart = null
        selectionBar.isVisible = false
        invalidatePages()
    }

    private fun copySelection() {
        val text = textSelection?.second?.text ?: return
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(file.name, text))
        showToast(getString(R.string.ml_pdf_copied))
        clearTextSelection()
    }

    /** A drag on [page] with the "Select" tool, at ([fx], [fy]) of the page as shown. */
    private fun dragSelection(page: Int, action: Int, fx: Float, fy: Float) {
        val engine = textEngine() ?: return
        val p = toOriginal(page, fx, fy)
        if (action == MotionEvent.ACTION_DOWN) {
            selectStart = p
            // A scan: read its text first
            if (!engine.isRecognised(page) && !engine.pageHasText(page)) {
                selectStart = null
                hint.setText(R.string.ml_pdf_ocr_page)
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.Default) { engine.recognise(page) }
                    updateHint()
                    if (!ok) showToast(getString(R.string.ml_pdf_ocr_failed))
                }
            }
            return
        }
        val start = selectStart ?: return
        val selection = engine.select(page, start, p)
        textSelection = selection?.let { page to it }
        invalidatePages()
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            selectionBar.isVisible = selection != null
            selectionText.text = selection?.text?.replace('\n', ' ')
        }
    }

    /** From the page as shown (turned in "Pages") back to the page itself. */
    private fun toOriginal(page: Int, x: Float, y: Float): PointF = when ((layout.rotation[page] ?: 0).mod(360)) {
        90 -> PointF(y, 1f - x)
        180 -> PointF(1f - x, 1f - y)
        270 -> PointF(1f - y, x)
        else -> PointF(x, y)
    }

    // ---------------------------------------------------------------- search

    private fun buildSearchBar(): View {
        searchBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = barBackground()
            setPadding(12.dpToPx(), 6.dpToPx(), 4.dpToPx(), 6.dpToPx())
            isVisible = false
        }
        searchInput = android.widget.EditText(this).apply {
            setSingleLine(true)
            UiKit.styleInput(this)
            setHint(R.string.ml_pdf_search_hint)
            textSize = 16f
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, _, _ ->
                if (hits.isNotEmpty() && searchJob?.isActive != true) moveHit(1) else runSearch()
                true
            }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) {
                    removeCallbacks(searchSoon)
                    postDelayed(searchSoon, 400)
                }
            })
        }
        searchBar.addView(searchInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        searchCount = TextView(this).apply {
            setTextColor(UiKit.secondary(this@PdfViewerActivity))
            textSize = 13f
            setPadding(8.dpToPx(), 0, 4.dpToPx(), 0)
        }
        searchBar.addView(searchCount)
        searchBar.addView(UiKit.iconButton(this, R.drawable.ic_m_chevron_up, getString(R.string.ml_cal_prev)) { moveHit(-1) })
        searchBar.addView(UiKit.iconButton(this, R.drawable.ic_m_chevron_down, getString(R.string.ml_cal_next)) { moveHit(1) })
        searchBar.addView(UiKit.iconButton(this, R.drawable.ic_m_close, getString(R.string.close)) { toggleSearch() })
        return searchBar
    }

    private val searchSoon = Runnable { runSearch() }

    private fun toggleSearch() {
        if (searchBar.isVisible) {
            searchBar.isVisible = false
            searchInput.removeCallbacks(searchSoon)
            searchJob?.cancel()
            hideKeyboard(searchInput)
            hits = emptyList()
            hitIndex = -1
            invalidatePages()
            return
        }
        searchBar.isVisible = true
        searchCount.text = ""
        searchInput.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard(v: View) {
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
    }

    /** Finds the text typed (reading the document's text the first time), then goes to the first match. */
    private fun runSearch() {
        searchInput.removeCallbacks(searchSoon)
        val query = searchInput.text.toString()
        searchJob?.cancel()
        if (query.isBlank()) {
            hits = emptyList()
            hitIndex = -1
            searchCount.text = ""
            invalidatePages()
            return
        }
        val engine = textEngine() ?: return
        searchCount.setText(R.string.ml_pdf_searching)
        ocrStatus = ""
        searchJob = lifecycleScope.launch {
            // In the order the pages are shown (after any reordering), deleted pages left out
            val pages = layout.order.toList()
            val scanned = mutableListOf<Int>()
            val found = withContext(Dispatchers.Default) {
                pages.flatMap { p ->
                    if (engine.isRecognised(p) || engine.pageHasText(p)) engine.search(p, query)
                    else emptyList<PdfSearch.Hit>().also { scanned += p }
                }
            }
            hits = found
            hitIndex = -1
            if (hits.isNotEmpty()) moveHit(1) else updateCount()
            invalidatePages()
            // Scanned pages: read their text (OCR) one by one, adding their matches as they come
            scanned.forEachIndexed { i, p ->
                ocrStatus = getString(R.string.ml_pdf_ocr_progress, i + 1, scanned.size)
                updateCount()
                val more = withContext(Dispatchers.Default) {
                    if (engine.recognise(p)) engine.search(p, query) else emptyList()
                }
                if (more.isNotEmpty()) {
                    val current = hits.getOrNull(hitIndex)
                    hits = (hits + more).sortedBy { pages.indexOf(it.page) }
                    if (current == null) moveHit(1) else {
                        hitIndex = hits.indexOf(current)
                        updateCount()
                    }
                    invalidatePages()
                }
            }
            ocrStatus = ""
            updateCount()
        }
    }

    private fun textEngine(): PdfText? = pdfText ?: renderer?.let { PdfText(this, file, it).also { t -> pdfText = t } }

    private fun updateCount() {
        val count = when {
            hits.isEmpty() && ocrStatus.isEmpty() -> getString(R.string.ml_pdf_search_none)
            hits.isEmpty() -> ""
            else -> "${hitIndex + 1}/${hits.size}"
        }
        searchCount.text = listOf(count, ocrStatus).filter { it.isNotEmpty() }.joinToString("  ·  ")
    }

    private fun moveHit(delta: Int) {
        if (hits.isEmpty()) return
        hitIndex = if (hitIndex < 0) 0 else (hitIndex + delta).mod(hits.size)
        updateCount()
        hideKeyboard(searchInput)
        invalidatePages()
        val hit = hits[hitIndex]
        val position = layout.order.indexOf(hit.page)
        val lm = pager.layoutManager as LinearLayoutManager
        lm.scrollToPositionWithOffset(position, 0)
        // Once laid out, bring the match to a third of the screen
        pager.post {
            val view = lm.findViewByPosition(position) as? PageView ?: return@post
            val box = hitBox(hit.page, hit.rects.first())
            val y = view.top + view.paddingTop + box.top * (view.height - view.paddingTop - view.paddingBottom)
            pager.scrollBy(0, (y - pager.height / 3f).toInt())
            updateTitle()
        }
    }

    /** A match's box on the page as shown, turned with the page if it was rotated in "Pages". */
    private fun hitBox(page: Int, f: RectF): RectF = when ((layout.rotation[page] ?: 0).mod(360)) {
        90 -> RectF(1f - f.bottom, f.left, 1f - f.top, f.right)
        180 -> RectF(1f - f.right, 1f - f.bottom, 1f - f.left, 1f - f.top)
        270 -> RectF(f.top, 1f - f.right, f.bottom, 1f - f.left)
        else -> RectF(f)
    }

    private fun updateTitle() {
        val lm = pager.layoutManager as? LinearLayoutManager
        val pos = lm?.findFirstVisibleItemPosition()?.takeIf { it >= 0 } ?: 0
        appBar.title.text = sourceFile.name
        UiKit.setSubtitle(appBar, getString(R.string.ml_pdf_page_of, pos + 1, layout.order.size))
    }

    // ---------------------------------------------------------------- tools

    private fun onToolTapped(t: Tool) {
        select(null)
        if (tool == t) return setTool(Tool.NONE)
        when (t) {
            Tool.SIGN -> if (signature == null) signatureLauncher.launch(SignatureActivity.intent(this)) else setTool(Tool.SIGN)
            else -> setTool(t)
        }
    }

    private fun signatureMenu() {
        val entries = mutableListOf(MenuEntry(getString(R.string.ml_signature_draw)) { signatureLauncher.launch(SignatureActivity.intent(this)) })
        if (signature != null) entries += MenuEntry(getString(R.string.ml_signature_delete)) {
            SavedSignature.delete(this)
            signature = null
            if (tool == Tool.SIGN) setTool(Tool.NONE)
        }
        showMenuDialog(getString(R.string.ml_signature), entries)
    }

    private fun setTool(t: Tool) {
        if (t != Tool.SELECT) clearTextSelection()
        tool = t
        for (i in 0 until toolbar.childCount) {
            val v = toolbar.getChildAt(i)
            UiKit.setToolActive(v, v.tag == t)
        }
        if (t == Tool.NONE) drawMode = if (drawMode == DrawMode.ERASER) DrawMode.PEN else drawMode
        updateHint()
        buildPalette()
        invalidatePages()
    }

    private fun updateHint() {
        val sel = selected
        hint.isVisible = tool != Tool.NONE || sel != null
        hint.text = when {
            sel != null -> getString(R.string.ml_pdf_select_hint)
            tool == Tool.TEXT -> getString(R.string.ml_pdf_text_hint)
            tool == Tool.CHECK -> getString(R.string.ml_pdf_check_hint)
            tool == Tool.DRAW -> getString(R.string.ml_pdf_draw_hint)
            tool == Tool.SIGN -> getString(R.string.ml_pdf_sign_hint)
            tool == Tool.SELECT -> getString(R.string.ml_pdf_select_text_hint)
            else -> ""
        }
    }

    /** Selects (or deselects with null) an annotation: the frame with handles appears on its page. */
    private fun select(a: Annotation?) {
        if (editing != null && editing !== a) stopEdit()
        selected = a
        updateHint()
        buildPalette()
        invalidatePages()
    }

    private fun invalidatePages() {
        for (i in 0 until pager.childCount) (pager.getChildAt(i) as? PageView)?.invalidate()
    }

    // ---------------------------------------------------------------- palette

    /** Colour and size palette for the active tool, or for the selected annotation. */
    private fun buildPalette() {
        palette.removeAllViews()
        val sel = selected
        val colourTarget = sel != null && sel !is Annotation.Signature
        palette.isVisible = colourTarget || tool == Tool.DRAW || tool == Tool.TEXT || tool == Tool.CHECK
        if (!palette.isVisible) return
        val current = when {
            sel is Annotation.Text -> sel.color
            sel is Annotation.Check -> sel.color
            sel is Annotation.Stroke -> sel.color
            else -> inkColor
        }
        if (!paletteOpen) {
            // Folded: the current colour, the mode, and the button to open
            val row = row()
            row.addView(swatch(current, true, 24) { paletteOpen = true; buildPalette() })
            row.addView(TextView(this).apply {
                text = modeLabel()
                setTextColor(UiKit.secondary(this@PdfViewerActivity))
                textSize = 13f
                setPadding(8.dpToPx(), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(foldButton(R.drawable.ic_m_chevron_up) { paletteOpen = true; buildPalette() })
            palette.addView(row)
            return
        }
        // Colours on two rows
        COLORS.chunked(6).forEach { chunk ->
            val row = row()
            chunk.forEach { color ->
                row.addView(swatch(color, color == current, 34) {
                    when (sel) {
                        is Annotation.Text -> sel.color = color
                        is Annotation.Check -> sel.color = color
                        is Annotation.Stroke -> sel.color = color
                        else -> if (drawMode == DrawMode.HIGHLIGHTER) highlightColor = color else penColor = color
                    }
                    if (drawMode == DrawMode.ERASER) drawMode = DrawMode.PEN
                    buildPalette()
                    invalidatePages()
                }, LinearLayout.LayoutParams(0, 34.dpToPx(), 1f))
            }
            palette.addView(row)
        }
        val options = row()
        when {
            sel is Annotation.Stroke || (sel == null && tool == Tool.DRAW) -> {
                if (sel == null) {
                    options.addView(optionButton(getString(R.string.ml_pdf_pen), drawMode == DrawMode.PEN) { drawMode = DrawMode.PEN; buildPalette() })
                    options.addView(optionButton(getString(R.string.ml_pdf_highlighter), drawMode == DrawMode.HIGHLIGHTER) { drawMode = DrawMode.HIGHLIGHTER; buildPalette() })
                    options.addView(optionButton(getString(R.string.ml_pdf_eraser), drawMode == DrawMode.ERASER) { drawMode = DrawMode.ERASER; buildPalette() })
                    options.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                    WIDTHS.forEachIndexed { i, _ ->
                        options.addView(dot(6 + i * 5, i == widthIndex) { widthIndex = i; buildPalette() })
                    }
                } else {
                    options.addView(TextView(this).apply {
                        text = getString(if ((sel as Annotation.Stroke).highlighter) R.string.ml_pdf_highlighter else R.string.ml_pdf_pen)
                        setTextColor(UiKit.secondary(this@PdfViewerActivity))
                        textSize = 13f
                        setPadding(8.dpToPx(), 0, 0, 0)
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
            }

            sel is Annotation.Text || (sel == null && tool == Tool.TEXT) -> {
                SIZES.forEachIndexed { i, size ->
                    val active = if (sel is Annotation.Text) false else size == textSize
                    options.addView(optionButton("A", active, 11f + i * 3) {
                        if (sel is Annotation.Text) pageViewOf(sel.page)?.resizeText(sel, size) else textSize = size
                        buildPalette()
                        invalidatePages()
                    })
                }
                options.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                options.addView(optionButton(getString(R.string.ml_pdf_edit_text)) {
                    if (sel is Annotation.Text) editText(sel)
                })
            }

            sel is Annotation.Check || (sel == null && tool == Tool.CHECK) -> {
                val isCross = (sel as? Annotation.Check)?.cross ?: cross
                options.addView(optionIcon(R.drawable.ic_m_check, !isCross, getString(R.string.ml_pdf_check)) { setCross(sel, false) })
                options.addView(optionIcon(R.drawable.ic_m_close, isCross, getString(R.string.ml_pdf_check)) { setCross(sel, true) })
                options.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                if (sel == null) CHECK_SIZES.forEachIndexed { i, size ->
                    options.addView(dot(8 + i * 5, size == checkSize) { checkSize = size; buildPalette() })
                }
            }
        }
        options.addView(foldButton(R.drawable.ic_m_chevron_down) { paletteOpen = false; buildPalette() })
        palette.addView(options)
    }

    private fun setCross(sel: Annotation?, value: Boolean) {
        if (sel is Annotation.Check) sel.cross = value else cross = value
        buildPalette()
        invalidatePages()
    }

    private fun modeLabel(): String {
        val sel = selected
        return when {
            sel is Annotation.Text -> getString(R.string.ml_pdf_text)
            sel is Annotation.Check -> getString(R.string.ml_pdf_check)
            sel is Annotation.Stroke -> getString(if (sel.highlighter) R.string.ml_pdf_highlighter else R.string.ml_pdf_pen)
            tool == Tool.DRAW -> when (drawMode) {
                DrawMode.PEN -> getString(R.string.ml_pdf_pen)
                DrawMode.HIGHLIGHTER -> getString(R.string.ml_pdf_highlighter)
                DrawMode.ERASER -> getString(R.string.ml_pdf_eraser)
            } + "  ·  " + getString(R.string.ml_pdf_width) + " " + (widthIndex + 1)
            tool == Tool.TEXT -> getString(R.string.ml_pdf_text)
            tool == Tool.CHECK -> getString(R.string.ml_pdf_check)
            else -> ""
        }
    }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 3.dpToPx(), 0, 3.dpToPx())
    }

    private fun swatch(color: Int, active: Boolean, sizeDp: Int, onClick: () -> Unit) = FrameLayout(this).apply {
        addView(View(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(color)
                setStroke(if (active) 3.dpToPx() else 1.dpToPx(), if (active) UiKit.primary(context) else UiKit.stroke(context))
            }
        }, FrameLayout.LayoutParams(sizeDp.dpToPx(), sizeDp.dpToPx(), Gravity.CENTER))
        background = UiKit.roundRipple(context)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun dot(sizeDp: Int, active: Boolean, onClick: () -> Unit) = FrameLayout(this).apply {
        val ink = UiKit.primary(context)
        addView(View(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(if (active) ink else androidx.core.graphics.ColorUtils.setAlphaComponent(ink, 0x88))
            }
        }, FrameLayout.LayoutParams(sizeDp.dpToPx(), sizeDp.dpToPx(), Gravity.CENTER))
        background = if (active) optionBackground(true) else UiKit.roundRipple(context)
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(32.dpToPx(), 36.dpToPx())
    }

    /** The chevron that folds or opens the palette. */
    private fun foldButton(res: Int, onClick: () -> Unit) = UiKit.iconButton(this, res, getString(R.string.ml_pdf_tool), onClick = onClick).apply {
        val p = 6.dpToPx()
        setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(36.dpToPx(), 36.dpToPx())
    }

    /** A bordered pill; [active] fills it lightly with the text color. */
    private fun optionBackground(active: Boolean) = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = 18.dpToPx().toFloat()
        if (active) {
            setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(UiKit.primary(this@PdfViewerActivity), 0x26))
            setStroke(1.dpToPx(), UiKit.primary(this@PdfViewerActivity))
        } else setStroke(1.dpToPx(), UiKit.stroke(this@PdfViewerActivity))
    }

    private fun optionButton(label: String, bold: Boolean = false, size: Float = 13f, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = size
        setTextColor(UiKit.primary(this@PdfViewerActivity))
        setTypeface(null, if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        background = optionBackground(bold)
        setPadding(10.dpToPx(), 6.dpToPx(), 10.dpToPx(), 6.dpToPx())
        gravity = Gravity.CENTER
        minHeight = 36.dpToPx()
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = 4.dpToPx() }
    }

    /** Like [optionButton], with a line icon. */
    private fun optionIcon(res: Int, active: Boolean, description: String, onClick: () -> Unit) = android.widget.ImageView(this).apply {
        setImageResource(res)
        imageTintList = android.content.res.ColorStateList.valueOf(UiKit.primary(this@PdfViewerActivity))
        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        contentDescription = description
        background = optionBackground(active)
        val p = 7.dpToPx()
        setPadding(p, p, p, p)
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(46.dpToPx(), 36.dpToPx()).apply { marginEnd = 4.dpToPx() }
    }

    /** Types the text in place: a text field sits on the annotation's box on the page. */
    private fun editText(a: Annotation.Text) {
        if (selected !== a) select(a)
        editing = a
        updateHint()
        pageViewOf(a.page)?.startEdit(a)
    }

    /** Ends in-place typing; an empty text is removed. */
    private fun stopEdit() {
        val a = editing ?: return
        editing = null
        pageViewOf(a.page)?.stopEdit()
        if (a.text.isBlank()) {
            annotations.remove(a)
            if (selected === a) select(null)
        }
        updateHint()
        invalidatePages()
    }

    private fun delete(a: Annotation) {
        annotations.remove(a)
        if (selected === a) select(null) else invalidatePages()
    }

    private fun undo() {
        if (annotations.isEmpty()) return
        val last = annotations.removeAt(annotations.lastIndex)
        if (selected === last) select(null) else pageViewOf(last.page)?.invalidate()
    }

    private fun pageViewOf(original: Int): PageView? {
        for (i in 0 until pager.childCount) {
            val v = pager.getChildAt(i) as? PageView ?: continue
            if (v.original == original) return v
        }
        return null
    }

    private fun openPages() = pagesLauncher.launch(PdfPagesActivity.intent(this, file, layout))

    // ---------------------------------------------------------------- save / share

    private fun uniqueName(dir: File, base: String): String {
        if (!File(dir, "$base.pdf").exists()) return base
        var i = 1
        while (File(dir, "$base ($i).pdf").exists()) i++
        return "$base ($i)"
    }

    /** Asks for the file name, then the folder (our picker), then writes the copy. */
    private fun save() {
        if (annotations.isEmpty() && layout.isIdentity(total)) {
            showToast(getString(R.string.ml_pdf_nothing))
            return
        }
        select(null)
        setTool(Tool.NONE)
        val startDir = if (fromCache) android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).also { it.mkdirs() }
        else sourceFile.parentFile!!
        val suggested = if (fromCache) uniqueName(startDir, sourceFile.nameWithoutExtension) else uniqueName(startDir, sourceFile.nameWithoutExtension + " (1)")
        showTextInputDialog(getString(R.string.ml_pdf_save_as), suggested, getString(R.string.ml_pdf_file_name), getString(R.string.ml_pdf_choose_folder)) { name ->
            val clean = name.trim().removeSuffix(".pdf").replace('/', '_').ifBlank { return@showTextInputDialog }
            pendingSaveName = "$clean.pdf"
            folderLauncher.launch(Intent(this, FolderPickerActivity::class.java).putExtra(FileExplorerActivity.EXTRA_LOCAL_PATH, startDir.absolutePath))
        }
    }

    private fun writeTo(target: File) {
        showToast(getString(R.string.ml_pdf_saving))
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val tmp = File(cacheDir, "pdf-save.tmp")
                try {
                    // Written to a temporary file first: the source is read while saving and may be the target itself
                    PdfSaver.save(this@PdfViewerActivity, file, tmp, layout, annotations)
                    target.parentFile?.mkdirs()
                    tmp.copyTo(target, overwrite = true)
                    MediaScannerConnection.scanFile(applicationContext, arrayOf(target.absolutePath), null, null)
                    true
                } catch (e: Throwable) {
                    e.printStackTrace()
                    false
                } finally {
                    tmp.delete()
                }
            }
            if (!ok) {
                showToast(getString(R.string.ml_explorer_failed, getString(R.string.ml_save)))
                return@launch
            }
            if (target.absolutePath == sourceFile.absolutePath) {
                // Replaced the open file: show the saved version
                file = sourceFile
                pdfText = null
                annotations.clear()
                renders.clear()
                openRenderer()
                pager.adapter?.notifyDataSetChanged()
                updateTitle()
                showToast(getString(R.string.ml_pdf_saved, target.name))
                return@launch
            }
            showMenuDialog(getString(R.string.ml_pdf_saved, target.name), listOf(
                MenuEntry(getString(R.string.ml_pdf_share_copy)) { share(target) },
                MenuEntry(getString(R.string.ml_search_open)) { open(this@PdfViewerActivity, target); finish() },
            ))
        }
    }

    // ---------------------------------------------------------------- default PDF app

    /** The app that opens PDFs without asking: null when none is set (the system asks). */
    private fun defaultPdfApp(): String? {
        val probe = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://$packageName.files/probe.pdf"), "application/pdf")
        val info = packageManager.resolveActivity(probe, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY) ?: return null
        return info.activityInfo?.packageName?.takeIf { it != "android" && !it.contains("resolver", true) }
    }

    /** Asks once to become the app for PDFs, when it isn't already. */
    private fun offerDefault() {
        val prefs = getSharedPreferences("app.minimalauncher.hints", MODE_PRIVATE)
        if (prefs.getBoolean("pdf_default_asked", false) || defaultPdfApp() == packageName) return
        prefs.edit().putBoolean("pdf_default_asked", true).apply()
        showConfirmDialog(getString(R.string.ml_pdf_default_title), getString(R.string.ml_pdf_default_message), getString(R.string.ml_pdf_default_action)) {
            makeDefault()
        }
    }

    /**
     * Android doesn't let an app make itself the default: with another app set, its settings page
     * opens (to clear its defaults); with none, the system's choice opens on this PDF, to pick
     * this app with "Always".
     */
    private fun makeDefault() {
        val current = defaultPdfApp()
        when {
            current == packageName -> showToast(getString(R.string.ml_pdf_default_already))
            current != null -> {
                showToast(getString(R.string.ml_pdf_default_clear), android.widget.Toast.LENGTH_LONG)
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$current")))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            else -> {
                showToast(getString(R.string.ml_pdf_default_pick), android.widget.Toast.LENGTH_LONG)
                try {
                    val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    finish()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun share(f: File) {
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        val intent = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, f.name))
    }

    // ---------------------------------------------------------------- rendering

    private suspend fun render(original: Int, width: Int): Bitmap? {
        renders[original]?.takeIf { it.width == width }?.let { return it }
        val r = renderer ?: return null
        val rotation = layout.rotation[original] ?: 0
        val bitmap = withContext(Dispatchers.IO) {
            try {
                synchronized(r) {
                    r.openPage(original).use { page ->
                        val rotated = rotation % 180 != 0
                        val pw = if (rotated) page.height else page.width
                        val ph = if (rotated) page.width else page.height
                        val h = max(1, width * ph / pw)
                        val b = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
                        b.eraseColor(Color.WHITE)
                        val m = Matrix()
                        val scale = width.toFloat() / pw
                        m.postScale(scale, scale)
                        if (rotation != 0) {
                            m.postTranslate(-page.width * scale / 2f, -page.height * scale / 2f)
                            m.postRotate(rotation.toFloat())
                            m.postTranslate(width / 2f, h / 2f)
                        }
                        page.render(b, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        b
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
        if (bitmap != null) renders[original] = bitmap
        return bitmap
    }

    private inner class PagesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = layout.order.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(PageView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val view = holder.itemView as PageView
            val original = layout.order[position]
            view.bind(original)
            val width = (pager.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) - 16.dpToPx()
            lifecycleScope.launch {
                val b = render(original, width) ?: return@launch
                if (view.original == original) view.setPage(b)
            }
        }
    }

    /**
     * The page list with pinch zoom: the whole content (pages and annotations alike) is scaled
     * around the fingers, so the tools stay precise when zoomed. One finger scrolls the list
     * vertically as usual and pans sideways when zoomed. Double tap resets.
     */
    private inner class ZoomRecyclerView(context: Context) : RecyclerView(context) {
        private var scale = 1f
        private var panX = 0f
        private var panY = 0f
        private var lastPanX = 0f
        private var lastPanY = 0f
        private var panning = false
        private val detector = android.view.ScaleGestureDetector(context, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
                val old = scale
                scale = (scale * d.scaleFactor).coerceIn(1f, 5f)
                // Keep the point under the fingers still
                val f = scale / old
                panX = d.focusX - (d.focusX - panX) * f
                panY = d.focusY - (d.focusY - panY) * f
                clampPan()
                invalidate()
                return true
            }
        })
        private val taps = android.view.GestureDetector(context, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (tool != Tool.NONE || selected != null) return false
                if (scale > 1.05f) { scale = 1f; panX = 0f; panY = 0f } else {
                    scale = 2.5f
                    panX = e.x - e.x * scale
                    panY = e.y - e.y * scale
                    clampPan()
                }
                invalidate()
                return true
            }
        })

        private fun clampPan() {
            panX = panX.coerceIn(width - width * scale, 0f)
            panY = panY.coerceIn(height - height * scale, 0f)
        }

        /** Converts a touch into content coordinates (undo the zoom), so children see the right position. */
        private fun unzoom(e: MotionEvent): MotionEvent = MotionEvent.obtain(e).also {
            it.setLocation((e.x - panX) / scale, (e.y - panY) / scale)
        }

        override fun dispatchDraw(canvas: Canvas) {
            canvas.save()
            canvas.translate(panX, panY)
            canvas.scale(scale, scale)
            super.dispatchDraw(canvas)
            canvas.restore()
        }

        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            detector.onTouchEvent(e)
            taps.onTouchEvent(e)
            if (e.pointerCount > 1 || detector.isInProgress) {
                // Pinch: cancel whatever the children were doing, pan with the gesture's focus
                if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                    val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(unzoom(cancel).also { cancel.recycle() })
                    panning = true
                    lastPanX = detector.focusX
                    lastPanY = detector.focusY
                } else if (e.actionMasked == MotionEvent.ACTION_MOVE && scale > 1f) {
                    panX += detector.focusX - lastPanX
                    panY += detector.focusY - lastPanY
                    lastPanX = detector.focusX
                    lastPanY = detector.focusY
                    clampPan()
                    invalidate()
                }
                return true
            }
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (panning) {
                    panning = false
                    return true
                }
            }
            val u = unzoom(e)
            val handled = super.dispatchTouchEvent(u)
            u.recycle()
            return handled
        }

        override fun onScrolled(dx: Int, dy: Int) {
            super.onScrolled(dx, dy)
            invalidate()
        }
    }

    /** One page: the rendered bitmap with the annotations of that page drawn on top; handles the tools' touches. */
    private inner class PageView(context: Context) : FrameLayout(context) {
        var original = -1
            private set
        private val image = android.widget.ImageView(context).apply {
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }
        private val overlay = Overlay(context)

        init {
            setPadding(8.dpToPx(), 6.dpToPx(), 8.dpToPx(), 6.dpToPx())
            addView(image, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(overlay, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        fun bind(original: Int) {
            if (editor != null) this@PdfViewerActivity.stopEdit()
            this.original = original
            image.setImageBitmap(null)
            overlay.invalidate()
        }

        fun setPage(b: Bitmap) {
            image.setImageBitmap(b)
            image.layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            overlay.invalidate()
        }

        override fun invalidate() {
            super.invalidate()
            overlay.invalidate()
        }

        /**
         * Page rectangle in the coordinates of the children (overlay, text field): they are laid
         * out inside this view's padding, so the page starts at their 0, 0 (the image fills the width).
         */
        private fun pageRect(): RectF {
            val d = image.drawable ?: return RectF()
            val w = (width - paddingLeft - paddingRight).toFloat()
            val h = w * d.intrinsicHeight / d.intrinsicWidth
            return RectF(0f, 0f, w, h)
        }

        /** Recomputes the width of a text box from its text and height. */
        fun fitText(a: Annotation.Text) = overlay.fitText(pageRect(), a)

        private var editor: android.widget.EditText? = null

        /** Puts a text field over [a]'s box and opens the keyboard; typing updates the annotation. */
        fun startEdit(a: Annotation.Text) {
            stopEdit()
            val r = pageRect()
            if (r.isEmpty) return
            val et = android.widget.EditText(context).apply {
                background = null
                setPadding(0, 0, 0, 0)
                includeFontPadding = false
                isSingleLine = true
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setTextColor(a.color)
                highlightColor = 0x402979FF
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, a.box.height() * r.height() * TEXT_FACTOR)
                minWidth = 40.dpToPx()
                setText(a.text)
                setSelection(length())
                typeface = this@PageView.overlay.textTypeface()
            }
            et.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) {
                    a.text = s?.toString() ?: ""
                    overlay.fitText(pageRect(), a)
                    place(et, a)
                    overlay.invalidate()
                }
            })
            et.setOnEditorActionListener { _, id, _ ->
                if (id == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { this@PdfViewerActivity.stopEdit(); true } else false
            }
            addView(et, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            editor = et
            place(et, a)
            et.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(et, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            // Once the keyboard is up, bring the field into view
            postDelayed({ et.requestRectangleOnScreen(android.graphics.Rect(0, -40.dpToPx(), et.width, et.height + 40.dpToPx()), false) }, 400)
            overlay.invalidate()
        }

        /** Aligns the field's baseline with the drawn text's baseline, with the annotation's rotation. */
        private fun place(et: android.widget.EditText, a: Annotation.Text) {
            val r = pageRect()
            val v = RectF(r.left + a.box.left * r.width(), r.top + a.box.top * r.height(), r.left + a.box.right * r.width(), r.top + a.box.bottom * r.height())
            val lp = et.layoutParams as LayoutParams
            lp.leftMargin = v.left.toInt()
            lp.topMargin = (v.top + v.height() * TEXT_FACTOR + et.paint.fontMetrics.ascent).toInt()
            et.layoutParams = lp
            et.rotation = a.rotation
            et.pivotX = v.width() / 2
            et.pivotY = v.centerY() - lp.topMargin
        }

        fun stopEdit() {
            val et = editor ?: return
            editor = null
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(et.windowToken, 0)
            removeView(et)
            overlay.invalidate()
        }

        /** Gives a text box the height of [size] (a fraction of the page width), keeping its left-top corner. */
        fun resizeText(a: Annotation.Text, size: Float) {
            val r = pageRect()
            if (r.isEmpty) return
            a.box.bottom = a.box.top + size * r.width() / r.height()
            overlay.fitText(r, a)
        }

        private inner class Overlay(context: Context) : View(context) {
            private val density = resources.displayMetrics.density
            private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF2979FF.toInt()
                style = Paint.Style.STROKE
                strokeWidth = 1.5f * density
            }
            private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            private val handleText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF2979FF.toInt()
                textAlign = Paint.Align.CENTER
                textSize = 13f * density
                isFakeBoldText = true
            }
            private val handleR = 12f * density
            /** The handles' line icons, tinted like the frame. */
            private val handleIcons = HashMap<Int, android.graphics.drawable.Drawable>()
            private val rotateGap = 30f * density

            private var current: MutableList<Pair<Float, Float>>? = null

            // Selection gesture state
            private var mode = Mode.NONE
            private var pressed: Annotation? = null
            private var downX = 0f
            private var downY = 0f
            private var lastFx = 0f
            private var lastFy = 0f
            private var startBox = RectF()
            private var startWidth = 0f
            private var startDist = 1f

            // ------------------------------------------------------------ drawing

            override fun onDraw(canvas: Canvas) {
                val r = pageRect()
                if (r.isEmpty) return
                drawHits(canvas, r)
                annotations.filter { it.page == original }.forEach { draw(canvas, r, it) }
                current?.let { pts ->
                    drawStroke(canvas, r, pts, WIDTHS[widthIndex] * (if (drawMode == DrawMode.HIGHLIGHTER) HIGHLIGHT_FACTOR else 1f), inkColor, drawMode == DrawMode.HIGHLIGHTER)
                }
                selected?.takeIf { it.page == original }?.let { drawSelection(canvas, r, it) }
            }

            private val hitPaint = Paint().apply { color = 0x66FFEB3B }
            private val currentHitPaint = Paint().apply { color = 0x88FF9800.toInt() }
            private val selectionPaint = Paint().apply { color = 0x552979FF }

            /** The search matches on this page; the current one stronger. */
            private fun drawHits(canvas: Canvas, r: RectF) {
                val current = hits.getOrNull(hitIndex)
                hits.forEach { hit ->
                    if (hit.page != original) return@forEach
                    val paint = if (hit === current) currentHitPaint else hitPaint
                    hit.rects.forEach { canvas.drawRect(toView(r, hitBox(original, it)), paint) }
                }
                textSelection?.takeIf { it.first == original }?.second?.rects?.forEach {
                    canvas.drawRect(toView(r, hitBox(original, it)), selectionPaint)
                }
            }

            private fun toView(r: RectF, f: RectF) = RectF(r.left + f.left * r.width(), r.top + f.top * r.height(), r.left + f.right * r.width(), r.top + f.bottom * r.height())

            private fun drawStroke(canvas: Canvas, r: RectF, pts: List<Pair<Float, Float>>, widthFraction: Float, color: Int, highlighter: Boolean) {
                if (pts.size < 2) return
                ink.color = if (highlighter) (color and 0x00FFFFFF) or 0x59000000 else color
                ink.strokeWidth = max(1f, widthFraction * r.width())
                ink.strokeCap = if (highlighter) Paint.Cap.SQUARE else Paint.Cap.ROUND
                val path = Path()
                path.moveTo(r.left + pts[0].first * r.width(), r.top + pts[0].second * r.height())
                pts.drop(1).forEach { (x, y) -> path.lineTo(r.left + x * r.width(), r.top + y * r.height()) }
                canvas.drawPath(path, ink)
            }

            private fun draw(canvas: Canvas, r: RectF, a: Annotation) {
                val v = toView(r, a.box)
                canvas.save()
                if (a.rotation != 0f) canvas.rotate(a.rotation, v.centerX(), v.centerY())
                when (a) {
                    is Annotation.Stroke -> drawStroke(canvas, r, a.currentPoints(), a.widthFraction, a.color, a.highlighter)
                    is Annotation.Check -> {
                        ink.color = a.color
                        ink.strokeWidth = max(2f, min(v.width(), v.height()) / 8f)
                        ink.strokeCap = Paint.Cap.ROUND
                        val p = Path()
                        if (a.cross) {
                            p.moveTo(v.left + v.width() * 0.15f, v.top + v.height() * 0.15f)
                            p.lineTo(v.left + v.width() * 0.85f, v.top + v.height() * 0.85f)
                            p.moveTo(v.left + v.width() * 0.85f, v.top + v.height() * 0.15f)
                            p.lineTo(v.left + v.width() * 0.15f, v.top + v.height() * 0.85f)
                        } else {
                            p.moveTo(v.left + v.width() * 0.1f, v.top + v.height() * 0.55f)
                            p.lineTo(v.left + v.width() * 0.4f, v.top + v.height() * 0.9f)
                            p.lineTo(v.left + v.width() * 0.95f, v.top + v.height() * 0.1f)
                        }
                        canvas.drawPath(p, ink)
                    }

                    is Annotation.Text -> if (editing !== a) {
                        textPaint.color = a.color
                        textPaint.textSize = v.height() * TEXT_FACTOR
                        canvas.drawText(a.text, v.left, v.top + v.height() * TEXT_FACTOR, textPaint)
                    }

                    is Annotation.Signature -> canvas.drawBitmap(a.bitmap, null, v, null)
                }
                canvas.restore()
            }

            /** The selection frame, drawn in the annotation's rotated frame: delete top-left, edit top-right (text), rotate above, resize bottom-right. */
            private fun drawSelection(canvas: Canvas, r: RectF, a: Annotation) {
                val v = toView(r, a.box)
                canvas.save()
                if (a.rotation != 0f) canvas.rotate(a.rotation, v.centerX(), v.centerY())
                canvas.drawRect(v, frame)
                canvas.drawLine(v.centerX(), v.top, v.centerX(), v.top - rotateGap, frame)
                handle(canvas, v.left, v.top, R.drawable.ic_m_close)
                if (a is Annotation.Text) handle(canvas, v.right, v.top, R.drawable.ic_m_edit)
                handle(canvas, v.centerX(), v.top - rotateGap, R.drawable.ic_m_rotate)
                handle(canvas, v.right, v.bottom, R.drawable.ic_m_resize)
                canvas.restore()
            }

            private fun handle(canvas: Canvas, x: Float, y: Float, icon: Int) {
                canvas.drawCircle(x, y, handleR, handleFill)
                canvas.drawCircle(x, y, handleR, frame)
                val d = handleIcons.getOrPut(icon) {
                    ContextCompat.getDrawable(context, icon)?.mutate()?.apply { setTint(handleText.color) } ?: return
                }
                val half = (handleR * 0.62f).toInt()
                d.setBounds(x.toInt() - half, y.toInt() - half, x.toInt() + half, y.toInt() + half)
                d.draw(canvas)
            }

            fun textTypeface(): android.graphics.Typeface = textPaint.typeface ?: android.graphics.Typeface.DEFAULT

            fun fitText(r: RectF, a: Annotation.Text) {
                if (r.isEmpty) return
                textPaint.textSize = a.box.height() * r.height() * TEXT_FACTOR
                a.box.right = a.box.left + (textPaint.measureText(a.text) + 4f * density) / r.width()
            }

            // ------------------------------------------------------------ hit testing

            /** A view point brought into the annotation's unrotated frame (around its box centre). */
            private fun toLocal(r: RectF, a: Annotation, x: Float, y: Float): Pair<Float, Float> {
                if (a.rotation == 0f) return x to y
                val v = toView(r, a.box)
                val rad = Math.toRadians(-a.rotation.toDouble())
                val dx = x - v.centerX()
                val dy = y - v.centerY()
                return (v.centerX() + dx * cos(rad) - dy * sin(rad)).toFloat() to (v.centerY() + dx * sin(rad) + dy * cos(rad)).toFloat()
            }

            private fun hits(r: RectF, a: Annotation, x: Float, y: Float): Boolean {
                val (lx, ly) = toLocal(r, a, x, y)
                val v = toView(r, a.box)
                val tol = 10f * density
                return when (a) {
                    is Annotation.Stroke -> {
                        val tolF = 0.025f
                        val fx = (lx - r.left) / r.width()
                        val fy = (ly - r.top) / r.height()
                        a.currentPoints().any { (px, py) -> abs(px - fx) < tolF && abs(py - fy) < tolF }
                    }
                    else -> lx in v.left - tol..v.right + tol && ly in v.top - tol..v.bottom + tol
                }
            }

            /** The topmost annotation of this page under a view point. */
            private fun annotationAt(r: RectF, x: Float, y: Float): Annotation? =
                annotations.filter { it.page == original }.lastOrNull { hits(r, it, x, y) }

            /** Which part of the selection frame is under a view point. */
            private fun handleAt(r: RectF, a: Annotation, x: Float, y: Float): Mode {
                val (lx, ly) = toLocal(r, a, x, y)
                val v = toView(r, a.box)
                val reach = handleR * 1.6f
                fun near(hx: Float, hy: Float) = hypot(lx - hx, ly - hy) < reach
                return when {
                    near(v.left, v.top) -> Mode.DELETE
                    a is Annotation.Text && near(v.right, v.top) -> Mode.EDIT
                    near(v.centerX(), v.top - rotateGap) -> Mode.ROTATE
                    near(v.right, v.bottom) -> Mode.RESIZE
                    v.contains(lx, ly) || hits(r, a, x, y) -> Mode.MOVE
                    else -> Mode.NONE
                }
            }

            // ------------------------------------------------------------ touches

            override fun onTouchEvent(e: MotionEvent): Boolean {
                val r = pageRect()
                if (r.isEmpty) return false
                // Two fingers: let the zoom handler have it (ends any stroke or drag in progress)
                if (e.pointerCount > 1) {
                    current = null
                    mode = Mode.NONE
                    pressed = null
                    invalidate()
                    return false
                }
                val fx = ((e.x - r.left) / r.width()).coerceIn(0f, 1f)
                val fy = ((e.y - r.top) / r.height()).coerceIn(0f, 1f)
                if (tool == Tool.SELECT) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    dragSelection(original, e.actionMasked, fx, fy)
                    return true
                }
                val sel = selected?.takeIf { it.page == original }
                if (sel != null && handleSelection(e, r, sel, fx, fy)) return true
                if (tool == Tool.NONE || (tool != Tool.DRAW && e.actionMasked == MotionEvent.ACTION_DOWN && annotationAt(r, e.x, e.y) != null) || pressed != null)
                    return handlePick(e, r)
                parent?.requestDisallowInterceptTouchEvent(true)
                when (tool) {
                    Tool.DRAW -> if (drawMode == DrawMode.ERASER) {
                        if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) {
                            (annotationAt(r, e.x, e.y) as? Annotation.Stroke)?.let { annotations.remove(it) }
                        }
                    } else when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> current = mutableListOf(fx to fy)
                        MotionEvent.ACTION_MOVE -> current?.add(fx to fy)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            current?.let {
                                if (it.size > 1) {
                                    val highlighter = drawMode == DrawMode.HIGHLIGHTER
                                    annotations += Annotation.Stroke(original, it.toList(), WIDTHS[widthIndex] * (if (highlighter) HIGHLIGHT_FACTOR else 1f), inkColor, highlighter)
                                }
                            }
                            current = null
                        }
                    }

                    Tool.CHECK -> if (e.actionMasked == MotionEvent.ACTION_UP) {
                        val w = checkSize
                        val h = w * r.width() / r.height()
                        annotations += Annotation.Check(original, RectF(fx - w / 2, fy - h / 2, fx + w / 2, fy + h / 2), penColor, cross)
                    }

                    Tool.TEXT -> if (e.actionMasked == MotionEvent.ACTION_UP) {
                        val h = textSize * r.width() / r.height()
                        val a = Annotation.Text(original, "", RectF(fx, fy - h / 2, fx, fy + h / 2), penColor)
                        fitText(r, a)
                        annotations += a
                        setTool(Tool.NONE)
                        editText(a)
                    }

                    Tool.SIGN -> if (e.actionMasked == MotionEvent.ACTION_UP) signature?.let { sig ->
                        val w = 0.3f
                        val h = w * sig.height / sig.width * r.width() / r.height()
                        val a = Annotation.Signature(original, sig, RectF(fx - w / 2, fy - h / 2, fx + w / 2, fy + h / 2))
                        annotations += a
                        setTool(Tool.NONE)
                        select(a)
                    }

                    Tool.NONE, Tool.SELECT -> Unit
                }
                invalidate()
                return true
            }

            /** A tap on an annotation selects it; a drag scrolls the list as usual. */
            private fun handlePick(e: MotionEvent, r: RectF): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        pressed = annotationAt(r, e.x, e.y) ?: return false
                        downX = e.x
                        downY = e.y
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (abs(e.x - downX) > 12 * density || abs(e.y - downY) > 12 * density) pressed = null
                        return pressed != null
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val a = pressed
                        pressed = null
                        if (a != null && e.actionMasked == MotionEvent.ACTION_UP) select(a)
                        return a != null
                    }
                }
                return false
            }

            /** Handles the selected annotation's frame; returns false when the touch is outside it (deselects on a tap). */
            private fun handleSelection(e: MotionEvent, r: RectF, a: Annotation, fx: Float, fy: Float): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        mode = handleAt(r, a, e.x, e.y)
                        if (mode == Mode.NONE) {
                            select(null)
                            return false
                        }
                        parent?.requestDisallowInterceptTouchEvent(true)
                        downX = e.x
                        downY = e.y
                        lastFx = fx
                        lastFy = fy
                        startBox = RectF(a.box)
                        startWidth = (a as? Annotation.Stroke)?.widthFraction ?: 0f
                        val v = toView(r, a.box)
                        startDist = max(1f, hypot(e.x - v.centerX(), e.y - v.centerY()))
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val v = toView(r, startBox)
                        when (mode) {
                            Mode.MOVE -> {
                                a.box.offset(fx - lastFx, fy - lastFy)
                                lastFx = fx
                                lastFy = fy
                            }

                            Mode.ROTATE -> {
                                var deg = Math.toDegrees(atan2((e.y - v.centerY()).toDouble(), (e.x - v.centerX()).toDouble())).toFloat() + 90f
                                val snap = round(deg / 90f) * 90f
                                if (abs(deg - snap) < 4f) deg = snap
                                a.rotation = ((deg % 360f) + 360f) % 360f
                            }

                            Mode.RESIZE -> {
                                val s = (hypot(e.x - v.centerX(), e.y - v.centerY()) / startDist).coerceAtLeast(0.05f)
                                val hw = startBox.width() / 2 * s
                                val hh = startBox.height() / 2 * s
                                if (hw * r.width() < 6 * density) return true
                                a.box.set(startBox.centerX() - hw, startBox.centerY() - hh, startBox.centerX() + hw, startBox.centerY() + hh)
                                if (a is Annotation.Stroke) a.widthFraction = startWidth * s
                            }

                            else -> Unit
                        }
                        invalidate()
                        return true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val m = mode
                        mode = Mode.NONE
                        if (e.actionMasked == MotionEvent.ACTION_UP && abs(e.x - downX) < 12 * density && abs(e.y - downY) < 12 * density) {
                            when (m) {
                                Mode.DELETE -> delete(a)
                                Mode.EDIT -> if (a is Annotation.Text) editText(a)
                                else -> Unit
                            }
                        }
                        invalidate()
                        return true
                    }
                }
                return true
            }
        }
    }

    private enum class Mode { NONE, MOVE, ROTATE, RESIZE, DELETE, EDIT }

    companion object {
        const val EXTRA_PATH = "path"
        private val COLORS = listOf(
            0xFF1A1A99.toInt(), Color.BLACK, 0xFF616161.toInt(), 0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(),
            0xFF43A047.toInt(), 0xFF00897B.toInt(), 0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(), 0xFFD81B60.toInt(), 0xFF6D4C41.toInt(),
        )
        private val WIDTHS = listOf(0.0015f, 0.003f, 0.006f, 0.011f)
        private const val HIGHLIGHT_FACTOR = 4f
        private val SIZES = listOf(0.015f, 0.022f, 0.03f, 0.042f)
        private val CHECK_SIZES = listOf(0.025f, 0.035f, 0.05f)
        /** Font size as a fraction of the text box height (matches the saver). */
        private const val TEXT_FACTOR = 0.8f

        fun open(context: Context, file: File) = context.startActivity(
            Intent(context, PdfViewerActivity::class.java).putExtra(EXTRA_PATH, file.absolutePath).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
