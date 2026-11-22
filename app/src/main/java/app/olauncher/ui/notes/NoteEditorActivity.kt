package app.olauncher.ui.notes

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import app.olauncher.R
import app.olauncher.helper.MenuEntry
import app.olauncher.helper.showMenuDialog
import app.olauncher.helper.showToast
import app.olauncher.ui.PickFiles
import app.olauncher.ui.UiKit
import app.olauncher.ui.pdf.PdfViewerActivity
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * A note being written. The writing itself happens in a page (assets/notes/Note.html, the same
 * one that opens the notes on a computer); this screen gives it the note, writes to the disk what
 * it sends back, and does for it what a page cannot: choosing a picture or a file, opening a link.
 */
class NoteEditorActivity : AppCompatActivity() {

    private lateinit var root: File
    private lateinit var note: File
    private lateinit var web: WebView
    private var pickKind = "image"
    private var topBar: android.view.View? = null
    /** The PDF chosen to take pages from, and its pages being read. */
    private var pdfFile: File? = null
    private var pdf: PdfRenderer? = null
    /** The words of that PDF, read the first time a page of it is taken. */
    private var pdfWords: app.olauncher.ui.pdf.PdfSearch? = null
    private var pdfWordsRead = false
    /** A PDF being made of the notebook, as its pieces come from the page. */
    private var exportFile: File? = null
    private var exportOut: java.io.OutputStream? = null

    private val picker = registerForActivityResult(PickFiles()) { uris ->
        val source = uris.firstOrNull()?.path?.let { File(it) } ?: return@registerForActivityResult
        if (pickKind == "pdf") {
            pdfFile = source
            call("Notes.pdfPicked(${JSONObject.quote(source.absolutePath)}, ${JSONObject.quote(source.nameWithoutExtension)})")
            return@registerForActivityResult
        }
        val path = NotesStore.attach(note.parentFile ?: return@registerForActivityResult, source)
        if (path == null) showToast(getString(R.string.ml_notes_failed))
        else call("Notes.inserted(${JSONObject.quote(pickKind)}, ${JSONObject.quote(path)}, ${JSONObject.quote(source.name)})")
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        root = NotesStore.root(this)
        note = File(savedInstanceState?.getString(STATE_PATH) ?: intent.getStringExtra(EXTRA_PATH) ?: return finish())
        if (!note.isFile) return finish()

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        // The keyboard takes its room from the page, so that the bar of the buttons stays over it
        ViewCompat.setOnApplyWindowInsetsListener(column) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val bar = UiKit.appBar(this, getString(R.string.ml_notes_app), onBack = { close() })
        bar.view.setPadding(4, 4, 4, 4)
        topBar = bar.view
        UiKit.setSubtitle(bar, note.parentFile?.takeIf { it != root }?.relativeTo(root)?.invariantSeparatorsPath?.replace("/", " › "))
        column.addView(bar.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // The images of a note are files beside it
            settings.allowFileAccess = true
            settings.textZoom = 100
            overScrollMode = WebView.OVER_SCROLL_NEVER
            addJavascriptInterface(Host(), "NotesHost")
            webViewClient = object : WebViewClient() {
                // The page never leaves: a link tapped in a note is opened by the phone
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    open(request.url.toString())
                    return true
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::note.isInitialized) outState.putString(STATE_PATH, note.absolutePath)
    }

    private fun call(script: String) = web.evaluateJavascript(script, null)

    /** What is left to write is written, then the screen closes. */
    private fun close() {
        // A drawing or a small window open over the note: back closes that, not the note
        web.evaluateJavascript("Notes.goBack()") { handled ->
            if (handled != "true") web.evaluateJavascript("Notes.flush()") { finish() }
        }
    }

    override fun onPause() {
        super.onPause()
        if (::web.isInitialized) call("Notes.flush()")
    }

    override fun onDestroy() {
        runCatching { pdf?.close() }
        if (::web.isInitialized) {
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        super.onDestroy()
    }

    /** A link of the note: a file beside it, or an address. */
    private fun open(href: String) {
        try {
            val file = when {
                href.startsWith("file:") -> Uri.parse(href).path?.let { File(it) }
                !href.contains(":") -> File(note.parentFile, Uri.decode(href))
                else -> null
            }
            if (file != null) {
                if (!file.isFile) return showToast(getString(R.string.ml_search_cannot_open))
                // A PDF of the note opens in the launcher's own reader, to write on it and save it in place
                if (file.extension.equals("pdf", true)) return startActivity(PdfViewerActivity.editIntent(this, file))
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
                val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(href)))
        } catch (e: Exception) {
            showToast(getString(R.string.ml_search_cannot_open))
        }
    }

    /** What the page asks of the phone. Called off the main thread. */
    private inner class Host {

        @JavascriptInterface
        fun ready() = runOnUiThread {
            val base = Uri.fromFile(note.parentFile).toString().trimEnd('/') + "/"
            call("Notes.show(${JSONObject.quote(NotesStore.titleOf(note))}, ${JSONObject.quote(NotesStore.body(note))}, ${JSONObject.quote(base)})")
        }

        @JavascriptInterface
        fun save(page: String) {
            try {
                // Whole, or not at all: a note cut half way by a phone that dies is a note lost
                val tmp = File(note.parentFile, "." + note.name + ".tmp")
                tmp.writeText(page)
                if (!tmp.renameTo(note)) {
                    note.writeText(page)
                    tmp.delete()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread { showToast(getString(R.string.ml_notes_failed)) }
            }
        }

        /** A notebook takes the whole screen: the bar with the title goes ([shown] false), and comes back for a note of words. */
        @JavascriptInterface
        fun chrome(shown: Boolean) = runOnUiThread { topBar?.visibility = if (shown) android.view.View.VISIBLE else android.view.View.GONE }

        /** The arrow back, in the page's own bar when the title bar is hidden. */
        @JavascriptInterface
        fun close() = runOnUiThread { this@NoteEditorActivity.close() }

        @JavascriptInterface
        fun rename(title: String): String {
            note = NotesStore.rename(root, note, title)
            return NotesStore.titleOf(note)
        }

        @JavascriptInterface
        fun pick(kind: String) = runOnUiThread {
            pickKind = kind
            picker.launch(
                when (kind) {
                    "image" -> PickFiles.Request(types = arrayOf("image/*"))
                    "pdf" -> PickFiles.Request(types = arrayOf("application/pdf"), extensions = arrayOf("pdf"))
                    else -> PickFiles.Request()
                }
            )
        }

        @JavascriptInterface
        fun saveData(name: String, base64: String): String {
            val dir = note.parentFile ?: return ""
            return NotesStore.attachBytes(dir, Base64.decode(base64, Base64.DEFAULT), name).orEmpty()
        }

        @JavascriptInterface
        fun open(href: String) = runOnUiThread { this@NoteEditorActivity.open(href) }

        @JavascriptInterface
        fun share(href: String, name: String) = runOnUiThread { fileMenu(href, name) }

        /** A file beside the note, as text (a drawing, to be opened again on the board). */
        @JavascriptInterface
        fun readText(path: String): String = inside(path)?.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }.orEmpty()

        /** A picture beside the note as a data URL: one the page may read back after drawing it (for the PDF). */
        @JavascriptInterface
        fun readData(path: String): String {
            val file = inside(path)?.takeIf { it.isFile } ?: return ""
            return try {
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "image/jpeg"
                "data:$mime;base64," + Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
            } catch (e: Throwable) {
                ""
            }
        }

        @JavascriptInterface
        fun saveText(name: String, text: String): String {
            val dir = note.parentFile ?: return ""
            return NotesStore.attachBytes(dir, text.toByteArray(), name).orEmpty()
        }

        /** A file the page makes (the notebook as a PDF): begun, sent in pieces, then offered to share, save or open. */
        @JavascriptInterface
        fun exportBegin(name: String) {
            runCatching { exportOut?.close() }
            val dir = File(cacheDir, "note-export").apply { deleteRecursively(); mkdirs() }
            val safe = name.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), " ").trim().ifBlank { "Quaderno.pdf" }
            exportFile = File(dir, safe)
            exportOut = runCatching { exportFile!!.outputStream().buffered() }.getOrNull()
        }

        @JavascriptInterface
        fun exportChunk(base64: String) {
            runCatching { exportOut?.write(Base64.decode(base64, Base64.DEFAULT)) }
        }

        @JavascriptInterface
        fun exportEnd() {
            val ok = runCatching { exportOut?.close() }.isSuccess && exportOut != null
            exportOut = null
            val file = exportFile?.takeIf { ok && it.length() > 0 }
            runOnUiThread { if (file == null) showToast(getString(R.string.ml_notes_failed)) else fileMenu(file, file.name) }
        }

        /** The PDF just chosen, opened to take pages from: how many it has, -1 when it can't be read. */
        @JavascriptInterface
        fun pdfOpen(path: String): Int {
            val file = pdfFile?.takeIf { it.absolutePath == path } ?: return -1
            return try {
                pdf?.close()
                pdfWords = null
                pdfWordsRead = false
                pdf = PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
                pdf!!.pageCount
            } catch (e: Exception) {
                pdf = null
                -1
            }
        }

        /** A small picture of page [index], as a data URL. */
        @JavascriptInterface
        fun pdfThumb(index: Int): String {
            val bmp = renderPdf(index, 200) ?: return ""
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
            bmp.recycle()
            return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }

        /** Page [index] as a picture beside the note, to write over: its path and its size. */
        @JavascriptInterface
        fun pdfPage(index: Int): String {
            val dir = note.parentFile ?: return ""
            val page = pdfSize(index) ?: return ""
            val bmp = renderPdf(index, 1600) ?: return ""
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
            bmp.recycle()
            val path = NotesStore.attachBytes(dir, out.toByteArray(), "pagina pdf.jpg") ?: return ""
            // The words of the page and where each is, so that a search in the notebook finds them
            val text = org.json.JSONArray()
            try {
                if (!pdfWordsRead) {
                    pdfWordsRead = true
                    pdfWords = pdfFile?.let { app.olauncher.ui.pdf.PdfSearch.load(this@NoteEditorActivity, it) }
                }
                pdfWords?.words(index)?.forEach { (word, box) ->
                    fun r(v: Float) = Math.round(v * 10000) / 10000.0
                    text.put(org.json.JSONArray().put(word).put(r(box.left)).put(r(box.top)).put(r(box.width())).put(r(box.height())))
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
            return JSONObject().put("src", path).put("w", page.first).put("h", page.second).put("text", text).toString()
        }

        /** A file of the note's own `_allegati` folder no longer used (a drawing saved anew). */
        @JavascriptInterface
        fun remove(path: String) {
            val file = inside(path) ?: return
            if (file.parentFile?.name == NotesStore.FILES) file.delete()
        }
    }

    private fun pdfSize(index: Int): Pair<Int, Int>? = try {
        pdf?.openPage(index)?.use { it.width to it.height }
    } catch (e: Exception) {
        null
    }

    /** Page [index] of the PDF chosen, [width] pixels wide, on white. */
    private fun renderPdf(index: Int, width: Int): Bitmap? = try {
        pdf?.openPage(index)?.use { page ->
            val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                it.eraseColor(Color.WHITE)
                page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
    } catch (e: Throwable) {
        null
    }

    /** A path the page names, as a file in the note's folder; null when it would lead out of it. */
    private fun inside(path: String): File? {
        val dir = note.parentFile ?: return null
        val file = File(dir, path)
        return file.takeIf { it.canonicalPath.startsWith(dir.canonicalPath + File.separator) }
    }

    /** The file a link of the note points to, when it is one beside the note. */
    private fun fileOf(href: String): File? = when {
        href.startsWith("file:") -> Uri.parse(href).path?.let { File(it) }
        !href.contains(":") -> File(note.parentFile, Uri.decode(href))
        else -> null
    }?.takeIf { it.isFile }

    /** Share, save elsewhere or open with another app, an image or a file of the note. */
    private fun fileMenu(href: String, name: String) {
        fileMenu(fileOf(href) ?: return showToast(getString(R.string.ml_search_cannot_open)), name)
    }

    private fun fileMenu(file: File, name: String) {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
        val uri = try {
            FileProvider.getUriForFile(this, "$packageName.files", file)
        } catch (e: Exception) {
            return showToast(getString(R.string.ml_search_cannot_open))
        }
        fun go(intent: Intent) = try {
            startActivity(intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            showToast(getString(R.string.ml_search_cannot_open))
        }
        showMenuDialog(
            name.ifBlank { file.name },
            listOf(
                MenuEntry(getString(R.string.ml_notes_file_share), icon = R.drawable.ic_m_share) {
                    go(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
                },
                MenuEntry(getString(R.string.ml_notes_file_save), icon = R.drawable.ic_m_download) {
                    go(Intent(this, app.olauncher.ui.SaveToActivity::class.java).setAction(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri))
                },
                MenuEntry(getString(R.string.ml_notes_file_open_with), icon = R.drawable.ic_m_open) {
                    go(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
                },
            )
        )
    }

    companion object {
        private const val EXTRA_PATH = "path"
        private const val STATE_PATH = "path"

        fun intent(ctx: Context, note: File): Intent = Intent(ctx, NoteEditorActivity::class.java).putExtra(EXTRA_PATH, note.absolutePath)
    }
}
