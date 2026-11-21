package app.olauncher.ui.notes

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
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
import app.olauncher.helper.showToast
import app.olauncher.ui.PickFiles
import app.olauncher.ui.UiKit
import org.json.JSONObject
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

    private val picker = registerForActivityResult(PickFiles()) { uris ->
        val source = uris.firstOrNull()?.path?.let { File(it) } ?: return@registerForActivityResult
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

        @JavascriptInterface
        fun rename(title: String): String {
            note = NotesStore.rename(root, note, title)
            return NotesStore.titleOf(note)
        }

        @JavascriptInterface
        fun pick(kind: String) = runOnUiThread {
            pickKind = kind
            picker.launch(if (kind == "image") PickFiles.Request(types = arrayOf("image/*")) else PickFiles.Request())
        }

        @JavascriptInterface
        fun saveData(name: String, base64: String): String {
            val dir = note.parentFile ?: return ""
            return NotesStore.attachBytes(dir, Base64.decode(base64, Base64.DEFAULT), name).orEmpty()
        }

        @JavascriptInterface
        fun open(href: String) = runOnUiThread { this@NoteEditorActivity.open(href) }
    }

    companion object {
        private const val EXTRA_PATH = "path"
        private const val STATE_PATH = "path"

        fun intent(ctx: Context, note: File): Intent = Intent(ctx, NoteEditorActivity::class.java).putExtra(EXTRA_PATH, note.absolutePath)
    }
}
