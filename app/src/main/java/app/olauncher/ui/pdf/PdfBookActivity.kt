package app.olauncher.ui.pdf

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import app.olauncher.R
import app.olauncher.helper.MenuEntry
import app.olauncher.helper.showConfirmDialog
import app.olauncher.helper.showMenuDialog
import app.olauncher.helper.showTextInputDialog
import app.olauncher.helper.showToast
import app.olauncher.ui.FileExplorerActivity
import app.olauncher.ui.FolderPickerActivity
import app.olauncher.ui.PickFiles
import app.olauncher.ui.scanner.ScannerActivity
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * A PDF opened in the same page that writes the notebooks of the notes (assets/notes/Note.html):
 * the same tools, the same bars. The pages stay the PDF's own: this screen draws them for the page
 * when it asks, and what is written on them is put into the PDF itself when it is saved or shared
 * ([PdfSaver]), so that nothing added (a signature, a note) can be left behind by a copy sent on.
 */
class PdfBookActivity : AppCompatActivity() {

    /** The PDF opened: its name, its folder, and the file written over when saving in place. */
    private lateinit var source: File
    /** What is shown: [source], or a working copy with scanned pages after its own. */
    private lateinit var file: File
    private var renderer: PdfRenderer? = null
    private var words: PdfSearch? = null
    private var wordsRead = false
    private var text: PdfText? = null
    private lateinit var web: WebView
    /** Changes with every document shown, so that the page never shows a picture of the one before. */
    private var version = 0
    private var pendingName = ""
    private var pendingPayload = ""

    private val inPlace get() = intent.getBooleanExtra(EXTRA_IN_PLACE, false)
    private val fromCache get() = source.absolutePath.startsWith(cacheDir.absolutePath)

    private val picker = registerForActivityResult(PickFiles()) { uris ->
        val picked = uris.firstOrNull()?.path?.let { File(it) } ?: return@registerForActivityResult
        // A copy of its own: the page reads it from here, and so does the saving
        val copy = try {
            File(workDir(), "img-${System.currentTimeMillis()}.${picked.extension.ifBlank { "jpg" }}").also { picked.copyTo(it, overwrite = true) }
        } catch (e: Exception) {
            return@registerForActivityResult showToast(getString(R.string.ml_notes_failed))
        }
        call("Notes.inserted('image', ${JSONObject.quote(Uri.fromFile(copy).toString())}, ${JSONObject.quote(picked.name)})")
    }

    private val signatureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (SavedSignature.exists(this)) placeSignature()
    }

    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val added = result.data?.getStringExtra(ScannerActivity.EXTRA_PDF_PATH)?.let { File(it) } ?: return@registerForActivityResult
        addScanned(added)
    }

    private val folderLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val path = result.data?.getStringExtra(FileExplorerActivity.EXTRA_LOCAL_PATH) ?: return@registerForActivityResult
        val target = File(path, pendingName)
        if (target.exists()) showConfirmDialog(
            getString(R.string.ml_pdf_save_as), getString(R.string.ml_pdf_overwrite, target.name), getString(R.string.ml_pdf_replace),
        ) { write(pendingPayload, target, AFTER_SAVED) }
        else write(pendingPayload, target, AFTER_SAVED)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        source = File(intent.getStringExtra(EXTRA_PATH) ?: return finish())
        file = source
        if (!source.isFile || !openRenderer()) {
            showToast(getString(R.string.ml_pdf_cannot_open))
            return finish()
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        ViewCompat.setOnApplyWindowInsetsListener(column) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // The signature and the pictures laid on a page are files of the app
            settings.allowFileAccess = true
            settings.textZoom = 100
            overScrollMode = WebView.OVER_SCROLL_NEVER
            addJavascriptInterface(Host(), "NotesHost")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                    return true
                }

                /** The pages of the PDF, drawn when the page asks for them as pictures. */
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url
                    if (url.host != PAGE_HOST) return null
                    val index = url.lastPathSegment?.toIntOrNull() ?: return null
                    val width = (url.getQueryParameter("w")?.toIntOrNull() ?: 1200).coerceIn(80, 2400)
                    val turn = url.getQueryParameter("r")?.toIntOrNull() ?: 0
                    val bytes = picture(index, width, turn) ?: return WebResourceResponse("text/plain", "utf-8", 404, "Not found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    return WebResourceResponse("image/jpeg", null, 200, "OK", mapOf("Cache-Control" to "no-store", "Access-Control-Allow-Origin" to "*"), ByteArrayInputStream(bytes))
                }
            }
        }
        column.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(column)
        web.loadUrl("file:///android_asset/notes/Note.html?android=1")
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = close()
        })
    }

    override fun onDestroy() {
        runCatching { renderer?.let { synchronized(it) { it.close() } } }
        if (::web.isInitialized) {
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        super.onDestroy()
    }

    private fun call(script: String) = web.evaluateJavascript(script, null)

    private fun workDir() = File(cacheDir, "pdfbook").apply { mkdirs() }

    private fun openRenderer(): Boolean = try {
        renderer?.let { synchronized(it) { it.close() } }
        renderer = PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
        words = null
        wordsRead = false
        text = null
        version++
        true
    } catch (e: Exception) {
        renderer = null
        false
    }

    /** The sizes of the pages from [from] on, as the page wants them: [[width, height], ...] in points. */
    private fun sizes(from: Int = 0): JSONArray {
        val out = JSONArray()
        val r = renderer ?: return out
        synchronized(r) {
            for (i in from until r.pageCount) {
                try {
                    r.openPage(i).use { out.put(JSONArray().put(it.width).put(it.height)) }
                } catch (e: Exception) {
                    out.put(JSONArray().put(595).put(842))
                }
            }
        }
        return out
    }

    /** Gives the page the document to show. */
    private fun show() {
        val info = JSONObject().put("pages", sizes()).put("v", version).put("inPlace", inPlace)
        call("Notes.showPdf(${JSONObject.quote(source.nameWithoutExtension)}, $info)")
    }

    /** Page [index], [width] pixels wide, turned by [turn] degrees, as a JPEG. */
    private fun picture(index: Int, width: Int, turn: Int): ByteArray? = try {
        val r = renderer
        if (r == null) null else {
            val bmp = synchronized(r) {
                r.openPage(index).use { page ->
                    val turned = turn % 180 != 0
                    // The width asked is that of the page as shown: of its height, when it is turned on its side
                    val w = if (turned) (width.toFloat() * page.width / page.height).toInt().coerceAtLeast(1) else width
                    val h = (w.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
            val shown = if (turn % 360 == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(turn.toFloat()) }, true).also { bmp.recycle() }
            val out = ByteArrayOutputStream()
            shown.compress(Bitmap.CompressFormat.JPEG, if (width > 500) 88 else 72, out)
            shown.recycle()
            out.toByteArray()
        }
    } catch (e: Throwable) {
        e.printStackTrace()
        null
    }

    // ---------------------------------------------------------------- leaving

    /** Back: what is open over the page closes first; what was written is saved, or asked about. */
    private fun close() {
        web.evaluateJavascript("Notes.goBack()") { handled ->
            if (handled == "true") return@evaluateJavascript
            web.evaluateJavascript("Notes.pdfDirty() ? Notes.pdfPayload() : ''") { quoted ->
                val payload = unquote(quoted)
                when {
                    payload.isEmpty() -> finish()
                    // One beside a note: kept where it is, without asking
                    inPlace && !fromCache -> write(payload, source, AFTER_FINISH)
                    else -> showMenuDialog(
                        getString(R.string.ml_edit_discard_title),
                        listOf(
                            MenuEntry(getString(R.string.ml_save)) { save(payload) },
                            MenuEntry(getString(R.string.ml_edit_discard_action), destructive = true) { finish() },
                        )
                    )
                }
            }
        }
    }

    /** What evaluateJavascript gives back for a string: the string itself. */
    private fun unquote(quoted: String?): String = try {
        if (quoted == null || quoted == "null") "" else JSONArray("[$quoted]").getString(0)
    } catch (e: Exception) {
        ""
    }

    // ---------------------------------------------------------------- saving: what was written goes into the PDF

    private fun uniqueName(dir: File, base: String): String {
        if (!File(dir, "$base.pdf").exists()) return base
        var i = 1
        while (File(dir, "$base ($i).pdf").exists()) i++
        return "$base ($i)"
    }

    /** Asks for a name and a folder, then writes; in place, writes over the file opened. */
    private fun save(payload: String) {
        if (inPlace && !fromCache) return write(payload, source, AFTER_SAVED)
        val startDir = if (fromCache) android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).also { it.mkdirs() }
        else source.parentFile ?: return
        val suggested = if (fromCache) uniqueName(startDir, source.nameWithoutExtension) else uniqueName(startDir, source.nameWithoutExtension + " (1)")
        showTextInputDialog(getString(R.string.ml_pdf_save_as), suggested, getString(R.string.ml_pdf_file_name), getString(R.string.ml_pdf_choose_folder)) { name ->
            val clean = name.trim().removeSuffix(".pdf").replace('/', '_').ifBlank { return@showTextInputDialog }
            pendingName = "$clean.pdf"
            pendingPayload = payload
            folderLauncher.launch(Intent(this, FolderPickerActivity::class.java).putExtra(FileExplorerActivity.EXTRA_LOCAL_PATH, startDir.absolutePath))
        }
    }

    /** A picture laid on a page: only one of the app's own (the signature, a copy made by [picker]). */
    private fun pictureFile(src: String): File? {
        val path = Uri.parse(src).path ?: return null
        val f = File(path)
        val real = runCatching { f.canonicalPath }.getOrNull() ?: return null
        val own = real.startsWith(cacheDir.canonicalPath + File.separator) || real.startsWith(filesDir.canonicalPath + File.separator)
        return f.takeIf { own && it.isFile }
    }

    private fun color(hex: String): Int = try {
        Color.parseColor(hex)
    } catch (e: Exception) {
        Color.BLACK
    }

    /**
     * Writes the PDF with everything added to it drawn into its pages: [payload] is what the page
     * holds (the pages in their order, the blank ones added, what is written on each). Then
     * [after]: the file shown again, shared, or the screen closed.
     */
    private fun write(payload: String, target: File, after: Int) {
        showToast(getString(R.string.ml_pdf_saving))
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val tmp = File(workDir(), "save.tmp")
                var work: File? = null
                try {
                    val data = JSONObject(payload)
                    val pages = data.getJSONArray("pages")
                    val total = renderer?.pageCount ?: 0
                    // The blank pages added: put after the PDF's own in a copy, so that each has a number to be called by
                    val blanks = (0 until pages.length()).map { pages.getJSONObject(it) }.filter { it.optInt("o", -1) < 0 }
                    var from = file
                    if (blanks.isNotEmpty()) {
                        work = File(workDir(), "blank-${System.currentTimeMillis()}.pdf")
                        PDFBoxResourceLoader.init(applicationContext)
                        PDDocument.load(file).use { doc ->
                            doc.isAllSecurityToBeRemoved = true
                            blanks.forEach { doc.addPage(PDPage(PDRectangle(it.optDouble("w", 595.0).toFloat(), it.optDouble("h", 842.0).toFloat()))) }
                            doc.save(work)
                        }
                        from = work
                    }
                    val order = mutableListOf<Int>()
                    val rotation = mutableMapOf<Int, Int>()
                    val annotations = mutableListOf<Annotation>()
                    var blank = 0
                    for (k in 0 until pages.length()) {
                        val p = pages.getJSONObject(k)
                        val index = p.optInt("o", -1).let { if (it >= 0) it else total + blank++ }
                        order += index
                        p.optInt("r", 0).takeIf { it % 360 != 0 }?.let { rotation[index] = it }
                        val strokes = p.optJSONArray("s") ?: JSONArray()
                        for (i in 0 until strokes.length()) {
                            val s = strokes.getJSONObject(i)
                            val pts = s.getJSONArray("p")
                            val points = (0 until pts.length()).map { pts.getJSONArray(it).let { q -> Pair(q.getDouble(0).toFloat(), q.getDouble(1).toFloat()) } }
                            if (points.isEmpty()) continue
                            // A dot: a line too short to see, that the round end of the pen makes a dot of
                            val line = if (points.size == 1) points + Pair(points[0].first + 0.0005f, points[0].second) else points
                            annotations += Annotation.Stroke(index, line, s.getDouble("w").toFloat(), color(s.optString("c", "#000000")), s.optBoolean("m")).apply {
                                if (s.has("a")) alpha = s.getDouble("a").toFloat()
                            }
                        }
                        val texts = p.optJSONArray("t") ?: JSONArray()
                        for (i in 0 until texts.length()) {
                            val t = texts.getJSONObject(i)
                            val x = t.getDouble("x").toFloat()
                            val y = t.getDouble("y").toFloat()
                            annotations += Annotation.Text(index, t.getString("s"), RectF(x, y, x + t.getDouble("w").toFloat(), y + t.getDouble("h").toFloat()), color(t.optString("c", "#000000"))).apply {
                                this.rotation = t.optDouble("r", 0.0).toFloat()
                            }
                        }
                        val images = p.optJSONArray("i") ?: JSONArray()
                        for (i in 0 until images.length()) {
                            val im = images.getJSONObject(i)
                            val f = pictureFile(im.getString("src")) ?: continue
                            var bmp = BitmapFactory.decodeFile(f.absolutePath) ?: continue
                            // Only the part of it that was kept
                            im.optJSONArray("crop")?.let { c ->
                                val cx = c.getDouble(0).toInt().coerceIn(0, bmp.width - 1)
                                val cy = c.getDouble(1).toInt().coerceIn(0, bmp.height - 1)
                                val cw = c.getDouble(2).toInt().coerceIn(1, bmp.width - cx)
                                val ch = c.getDouble(3).toInt().coerceIn(1, bmp.height - cy)
                                if (cw != bmp.width || ch != bmp.height) bmp = Bitmap.createBitmap(bmp, cx, cy, cw, ch)
                            }
                            val x = im.getDouble("x").toFloat()
                            val y = im.getDouble("y").toFloat()
                            annotations += Annotation.Signature(index, bmp, RectF(x, y, x + im.getDouble("w").toFloat(), y + im.getDouble("h").toFloat())).apply {
                                this.rotation = im.optDouble("r", 0.0).toFloat()
                            }
                        }
                    }
                    // Written beside first: the source is read while saving, and may be the target itself
                    PdfSaver.save(this@PdfBookActivity, from, tmp, PageLayout(order, rotation), annotations)
                    target.parentFile?.mkdirs()
                    tmp.copyTo(target, overwrite = true)
                    MediaScannerConnection.scanFile(applicationContext, arrayOf(target.absolutePath), null, null)
                    true
                } catch (e: Throwable) {
                    e.printStackTrace()
                    false
                } finally {
                    tmp.delete()
                    work?.delete()
                }
            }
            if (!ok) return@launch showToast(getString(R.string.ml_explorer_failed, getString(R.string.ml_save)))
            when (after) {
                AFTER_FINISH -> finish()
                AFTER_SHARE -> shareFile(target)
                else -> {
                    showToast(getString(R.string.ml_pdf_saved, target.name))
                    if (target.absolutePath == source.absolutePath) {
                        // Written over the file opened: shown as it is now, with nothing left to save
                        file = source
                        if (openRenderer()) show() else finish()
                    } else showMenuDialog(
                        getString(R.string.ml_pdf_saved, target.name),
                        listOf(
                            MenuEntry(getString(R.string.ml_pdf_share_copy)) { shareFile(target) },
                            MenuEntry(getString(R.string.ml_search_open)) {
                                startActivity(intent(this@PdfBookActivity, target))
                                finish()
                            },
                        )
                    )
                }
            }
        }
    }

    private fun shareFile(f: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            val send = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, null))
        } catch (e: Exception) {
            showToast(getString(R.string.ml_search_cannot_open))
        }
    }

    // ---------------------------------------------------------------- the signature, scanned pages

    private fun placeSignature() {
        val f = SavedSignature.file(this)
        call("Notes.inserted('image', ${JSONObject.quote(Uri.fromFile(f).toString() + "?t=" + f.lastModified())}, 'firma')")
    }

    private fun signature() {
        if (!SavedSignature.exists(this)) return signatureLauncher.launch(SignatureActivity.intent(this))
        showMenuDialog(
            getString(R.string.ml_signature),
            listOf(
                MenuEntry(getString(R.string.ml_signature_use)) { placeSignature() },
                MenuEntry(getString(R.string.ml_signature_draw)) { signatureLauncher.launch(SignatureActivity.intent(this)) },
                MenuEntry(getString(R.string.ml_signature_delete), destructive = true) { SavedSignature.delete(this) },
            )
        )
    }

    /** The scanned pages go after the PDF's own, in a working copy that is shown from now on. */
    private fun addScanned(added: File) {
        lifecycleScope.launch {
            val before = renderer?.pageCount ?: 0
            val work = File(workDir(), "work-${System.currentTimeMillis()}.pdf")
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
            val old = file
            if (ok) file = work
            // The pictures of the pages already shown stay the same: the new ones only come after them
            val keep = version
            if (!ok || !openRenderer()) {
                file = old
                openRenderer()
                version = keep
                return@launch showToast(getString(R.string.ml_scan_failed))
            }
            version = keep
            call("Notes.pdfAdded(${sizes(before)}, $before)")
        }
    }

    // ---------------------------------------------------------------- what the page asks

    private inner class Host {

        @JavascriptInterface
        fun ready() = runOnUiThread { show() }

        /** Nothing is written by itself: a PDF is saved when asked to. */
        @JavascriptInterface
        fun save(page: String) = Unit

        @JavascriptInterface
        fun rename(title: String): String = source.nameWithoutExtension

        @JavascriptInterface
        fun chrome(shown: Boolean) = Unit

        @JavascriptInterface
        fun close() = runOnUiThread { this@PdfBookActivity.close() }

        @JavascriptInterface
        fun pick(kind: String) = runOnUiThread {
            when (kind) {
                "signature" -> signature()
                "scan" -> scanLauncher.launch(ScannerActivity.pickIntent(this@PdfBookActivity))
                else -> picker.launch(PickFiles.Request(types = arrayOf("image/*")))
            }
        }

        @JavascriptInterface
        fun open(href: String) = runOnUiThread { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(href))) } }

        @JavascriptInterface
        fun share(href: String, name: String) = Unit

        @JavascriptInterface
        fun readText(path: String): String = ""

        @JavascriptInterface
        fun saveText(name: String, text: String): String = ""

        @JavascriptInterface
        fun remove(path: String) = Unit

        /** Save ([mode] "save") or share ("share") the PDF with what [payload] says was added; empty: as it is. */
        @JavascriptInterface
        fun pdfSave(mode: String, payload: String) = runOnUiThread {
            when {
                mode == "share" && payload.isEmpty() -> shareFile(source)
                mode == "share" -> write(payload, File(File(cacheDir, "pdfshare").apply { deleteRecursively(); mkdirs() }, source.name), AFTER_SHARE)
                payload.isEmpty() -> showToast(getString(R.string.ml_pdf_nothing))
                else -> save(payload)
            }
        }

        /** The words of page [index] with their boxes (fractions of the page): [[word, x, y, w, h], ...]. */
        @JavascriptInterface
        fun pdfWords(index: Int): String {
            val out = JSONArray()
            try {
                if (!wordsRead) {
                    wordsRead = true
                    words = PdfSearch.load(this@PdfBookActivity, file)
                }
                words?.words(index)?.forEach { (word, box) -> out.put(wordJson(word, box)) }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
            return out.toString()
        }

        /** The same for a page that is a picture (a scan): its words read from the picture. May take a few seconds. */
        @JavascriptInterface
        fun pdfOcr(index: Int): String {
            val out = JSONArray()
            try {
                val r = renderer ?: return "[]"
                val engine = text ?: PdfText(this@PdfBookActivity, file, r).also { text = it }
                if (runBlocking { engine.recognise(index) }) engine.recognised(index)?.forEach { out.put(wordJson(it.text, it.box)) }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
            return out.toString()
        }

        @JavascriptInterface
        fun copy(text: String) = runOnUiThread {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("PDF", text))
        }

        @JavascriptInterface
        fun shareText(text: String) = runOnUiThread {
            runCatching { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)) }
        }

        /** The reader of before, for the same file. */
        @JavascriptInterface
        fun oldReader() = runOnUiThread { startActivity(PdfViewerActivity.oldIntent(this@PdfBookActivity, source)) }
    }

    private fun wordJson(word: String, box: RectF): JSONArray {
        fun r(v: Float) = Math.round(v * 10000) / 10000.0
        return JSONArray().put(word).put(r(box.left)).put(r(box.top)).put(r(box.width())).put(r(box.height()))
    }

    companion object {
        private const val EXTRA_PATH = "path"
        private const val EXTRA_IN_PLACE = "inPlace"
        const val PAGE_HOST = "pdf.minima.invalid"
        private const val AFTER_SAVED = 0
        private const val AFTER_SHARE = 1
        private const val AFTER_FINISH = 2

        fun intent(context: Context, file: File, inPlace: Boolean = false): Intent =
            Intent(context, PdfBookActivity::class.java).putExtra(EXTRA_PATH, file.absolutePath).putExtra(EXTRA_IN_PLACE, inPlace)
    }
}
