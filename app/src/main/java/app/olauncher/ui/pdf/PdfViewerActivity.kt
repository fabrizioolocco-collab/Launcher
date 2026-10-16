package app.olauncher.ui.pdf

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
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
import app.olauncher.helper.showConfirmDialog
import app.olauncher.helper.showMenuDialog
import app.olauncher.helper.showTextInputDialog
import app.olauncher.helper.showToast
import app.olauncher.ui.media.ZoomImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * PDF viewer and editor. Pages scroll vertically (pinch to zoom, double tap). Tools: text, check
 * marks, free drawing, the saved signature; "Pages" reorders, rotates or deletes pages. "Save" writes
 * a flattened copy (annotations drawn into the pages, text kept) and offers to share it.
 */
class PdfViewerActivity : AppCompatActivity() {

    private lateinit var file: File
    private var renderer: PdfRenderer? = null
    private var total = 0
    private var layout = PageLayout(mutableListOf())
    private val annotations = mutableListOf<Annotation>()
    private val renders = HashMap<Int, Bitmap>()

    private lateinit var pager: RecyclerView
    private lateinit var title: TextView
    private lateinit var toolbar: LinearLayout
    private lateinit var hint: TextView
    private var tool: Tool = Tool.NONE
    private var pendingText = ""
    private var signature: Bitmap? = null

    private enum class Tool { NONE, TEXT, CHECK, DRAW, SIGN }

    private val pagesLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data ?: return@registerForActivityResult
        val order = data.getIntArrayExtra(PdfPagesActivity.EXTRA_ORDER) ?: return@registerForActivityResult
        val keys = data.getIntArrayExtra(PdfPagesActivity.EXTRA_ROTATION_KEYS) ?: IntArray(0)
        val values = data.getIntArrayExtra(PdfPagesActivity.EXTRA_ROTATION_VALUES) ?: IntArray(0)
        layout = PageLayout(order.toMutableList(), keys.indices.associate { keys[it] to values[it] }.toMutableMap())
        renders.clear()
        pager.adapter?.notifyDataSetChanged()
        updateTitle()
    }

    private val signatureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        signature = SavedSignature.load(this)
        if (signature != null) setTool(Tool.SIGN)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val opened = resolveFile(intent) ?: run {
            showToast(getString(R.string.ml_pdf_cannot_open))
            finish()
            return
        }
        file = opened
        try {
            renderer = PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
            total = renderer!!.pageCount
        } catch (e: Exception) {
            showToast(getString(R.string.ml_pdf_cannot_open))
            finish()
            return
        }
        layout = PageLayout.identity(total)
        signature = SavedSignature.load(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        setContentView(buildLayout())
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (tool != Tool.NONE) setTool(Tool.NONE)
                else if (annotations.isNotEmpty() || !layout.isIdentity(total)) showConfirmDialog(
                    getString(R.string.ml_edit_discard_title), getString(R.string.ml_pdf_discard), getString(R.string.ml_edit_discard_action),
                ) { finish() }
                else finish()
            }
        })
    }

    /** The file to open: a path from our explorer, or a content/file URI from another app (copied to the cache). */
    private fun resolveFile(intent: Intent): File? {
        intent.getStringExtra(EXTRA_PATH)?.let { return File(it).takeIf { f -> f.exists() } }
        val uri = intent.data ?: return null
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
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(12.dpToPx(), 6.dpToPx(), 8.dpToPx(), 6.dpToPx())
        }
        top.addView(button("←", 26f) { onBackPressedDispatcher.onBackPressed() })
        title = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        top.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 8.dpToPx() })
        top.addView(button(getString(R.string.ml_pdf_pages), 14f) { openPages() })
        top.addView(button("⤴", 22f) { share(file) })
        top.addView(button("✓", 26f) { save() })
        root.addView(top)

        hint = TextView(this).apply {
            setTextColor(Color.BLACK)
            setBackgroundColor(0xFFFFE082.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(12.dpToPx(), 6.dpToPx(), 12.dpToPx(), 6.dpToPx())
            isVisible = false
        }
        root.addView(hint)

        pager = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@PdfViewerActivity)
            adapter = PagesAdapter()
            overScrollMode = View.OVER_SCROLL_NEVER
            setItemViewCacheSize(2)
        }
        pager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = updateTitle()
        })
        root.addView(pager, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(4.dpToPx(), 2.dpToPx(), 4.dpToPx(), 2.dpToPx())
        }
        listOf(
            Tool.TEXT to R.string.ml_pdf_text, Tool.CHECK to R.string.ml_pdf_check,
            Tool.DRAW to R.string.ml_pdf_draw, Tool.SIGN to R.string.ml_pdf_sign,
        ).forEach { (t, label) ->
            toolbar.addView(TextView(this).apply {
                setText(label)
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(2.dpToPx(), 12.dpToPx(), 2.dpToPx(), 12.dpToPx())
                isClickable = true
                tag = t
                setOnClickListener { onToolTapped(t) }
                setOnLongClickListener {
                    if (t == Tool.SIGN) signatureMenu()
                    t == Tool.SIGN
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        toolbar.addView(TextView(this).apply {
            setText(R.string.ml_pdf_undo)
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(2.dpToPx(), 12.dpToPx(), 2.dpToPx(), 12.dpToPx())
            isClickable = true
            setOnClickListener { undo() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(toolbar)
        updateTitle()
        return root
    }

    private fun button(label: String, size: Float, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = size
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(10.dpToPx(), 4.dpToPx(), 10.dpToPx(), 4.dpToPx())
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun updateTitle() {
        val lm = pager.layoutManager as? LinearLayoutManager
        val pos = lm?.findFirstVisibleItemPosition()?.takeIf { it >= 0 } ?: 0
        title.text = file.name + "  ·  " + getString(R.string.ml_pdf_page_of, pos + 1, layout.order.size)
    }

    // ---------------------------------------------------------------- tools

    private fun onToolTapped(t: Tool) {
        if (tool == t) return setTool(Tool.NONE)
        when (t) {
            Tool.TEXT -> showTextInputDialog(getString(R.string.ml_pdf_text), pendingText, getString(R.string.ml_pdf_text), getString(R.string.ml_done)) { text ->
                pendingText = text
                setTool(Tool.TEXT)
            }

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
        tool = t
        for (i in 0 until toolbar.childCount) {
            val v = toolbar.getChildAt(i) as TextView
            val active = v.tag == t
            v.alpha = if (t == Tool.NONE || active) 1f else 0.5f
            v.setTypeface(null, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        hint.isVisible = t != Tool.NONE
        hint.text = when (t) {
            Tool.TEXT -> getString(R.string.ml_pdf_text_hint) + "  «$pendingText»"
            Tool.CHECK -> getString(R.string.ml_pdf_check_hint)
            Tool.SIGN -> getString(R.string.ml_pdf_sign_hint)
            else -> ""
        }
        for (i in 0 until pager.childCount) (pager.getChildAt(i) as? PageView)?.invalidate()
    }

    private fun undo() {
        if (annotations.isEmpty()) return
        val last = annotations.removeAt(annotations.lastIndex)
        pageViewOf(last.page)?.invalidate()
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

    private fun uniqueCopy(src: File): File {
        val base = src.nameWithoutExtension
        var i = 1
        var f: File
        do {
            f = File(src.parentFile, "$base (${i++}).pdf")
        } while (f.exists())
        return f
    }

    private fun save() {
        if (annotations.isEmpty() && layout.isIdentity(total)) {
            showToast(getString(R.string.ml_pdf_nothing))
            return
        }
        setTool(Tool.NONE)
        showToast(getString(R.string.ml_pdf_saving))
        lifecycleScope.launch {
            val target = withContext(Dispatchers.IO) {
                // Files opened from other apps live in the cache: save into Download instead
                val dir = if (file.absolutePath.startsWith(cacheDir.absolutePath))
                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).also { it.mkdirs() }
                else file.parentFile!!
                val out = uniqueCopy(File(dir, file.name))
                try {
                    PdfSaver.save(this@PdfViewerActivity, file, out, layout, annotations)
                    MediaScannerConnection.scanFile(applicationContext, arrayOf(out.absolutePath), null, null)
                    out
                } catch (e: Throwable) {
                    e.printStackTrace()
                    out.delete()
                    null
                }
            }
            if (target == null) {
                showToast(getString(R.string.ml_explorer_failed, getString(R.string.ml_save)))
                return@launch
            }
            showMenuDialog(getString(R.string.ml_pdf_saved, target.name), listOf(
                MenuEntry(getString(R.string.ml_pdf_share_copy)) { share(target) },
                MenuEntry(getString(R.string.ml_search_open)) { open(this@PdfViewerActivity, target); finish() },
            ))
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

    /** One page: the rendered bitmap with the annotations of that page drawn on top; handles the tools' touches. */
    private inner class PageView(context: Context) : FrameLayout(context) {
        var original = -1
            private set
        private val image = ZoomImageView(context).apply {
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            onSingleTap = {}
        }
        private val overlay = Overlay(context)

        init {
            setPadding(8.dpToPx(), 6.dpToPx(), 8.dpToPx(), 6.dpToPx())
            addView(image, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(overlay, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        fun bind(original: Int) {
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

        /** Page rectangle in this view's coordinates (the image fills the width). */
        private fun pageRect(): RectF {
            val d = image.drawable ?: return RectF()
            val w = (width - paddingLeft - paddingRight).toFloat()
            val h = w * d.intrinsicHeight / d.intrinsicWidth
            return RectF(paddingLeft.toFloat(), paddingTop.toFloat(), paddingLeft + w, paddingTop + h)
        }

        private inner class Overlay(context: Context) : View(context) {
            private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF1A1A99.toInt()
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A1A99.toInt() }
            private var current: MutableList<Pair<Float, Float>>? = null
            private var placing: Annotation.Signature? = null
            private var resizing = false
            private var dragging = false
            private var lastX = 0f
            private var lastY = 0f

            override fun onDraw(canvas: Canvas) {
                val r = pageRect()
                if (r.isEmpty) return
                annotations.filter { it.page == original }.forEach { draw(canvas, r, it) }
                current?.let { pts -> drawStroke(canvas, r, pts, 0.004f) }
                placing?.let { sig ->
                    draw(canvas, r, sig)
                    val v = toView(r, sig.rect)
                    val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
                    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = 0xFF1A1A99.toInt()
                        style = Paint.Style.STROKE
                        strokeWidth = 2f
                    }
                    canvas.drawRect(v, border)
                    canvas.drawCircle(v.right, v.bottom, 10f * resources.displayMetrics.density, handle)
                    canvas.drawCircle(v.right, v.bottom, 10f * resources.displayMetrics.density, border)
                }
            }

            private fun toView(r: RectF, f: RectF) = RectF(r.left + f.left * r.width(), r.top + f.top * r.height(), r.left + f.right * r.width(), r.top + f.bottom * r.height())

            private fun drawStroke(canvas: Canvas, r: RectF, pts: List<Pair<Float, Float>>, widthFraction: Float) {
                if (pts.size < 2) return
                ink.strokeWidth = max(1f, widthFraction * r.width())
                val path = Path()
                path.moveTo(r.left + pts[0].first * r.width(), r.top + pts[0].second * r.height())
                pts.drop(1).forEach { (x, y) -> path.lineTo(r.left + x * r.width(), r.top + y * r.height()) }
                canvas.drawPath(path, ink)
            }

            private fun draw(canvas: Canvas, r: RectF, a: Annotation) {
                when (a) {
                    is Annotation.Stroke -> drawStroke(canvas, r, a.points, a.widthFraction)
                    is Annotation.Check -> {
                        val s = a.sizeFraction * r.width()
                        val cx = r.left + a.x * r.width()
                        val cy = r.top + a.y * r.height()
                        ink.strokeWidth = max(2f, s / 8f)
                        val p = Path()
                        p.moveTo(cx - 0.4f * s, cy + 0.05f * s)
                        p.lineTo(cx - 0.1f * s, cy + 0.4f * s)
                        p.lineTo(cx + 0.45f * s, cy - 0.4f * s)
                        canvas.drawPath(p, ink)
                    }

                    is Annotation.Text -> {
                        textPaint.textSize = a.sizeFraction * r.width()
                        canvas.drawText(a.text, r.left + a.x * r.width(), r.top + a.y * r.height(), textPaint)
                    }

                    is Annotation.Signature -> canvas.drawBitmap(a.bitmap, null, toView(r, a.rect), null)
                }
            }

            override fun onTouchEvent(e: MotionEvent): Boolean {
                if (tool == Tool.NONE) return false
                val r = pageRect()
                if (r.isEmpty) return false
                val fx = ((e.x - r.left) / r.width()).coerceIn(0f, 1f)
                val fy = ((e.y - r.top) / r.height()).coerceIn(0f, 1f)
                parent?.requestDisallowInterceptTouchEvent(true)
                when (tool) {
                    Tool.DRAW -> when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> current = mutableListOf(fx to fy)
                        MotionEvent.ACTION_MOVE -> current?.add(fx to fy)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            current?.let { if (it.size > 1) annotations += Annotation.Stroke(original, it.toList(), 0.004f) }
                            current = null
                        }
                    }

                    Tool.CHECK -> if (e.actionMasked == MotionEvent.ACTION_UP) annotations += Annotation.Check(original, fx, fy, 0.035f)

                    Tool.TEXT -> if (e.actionMasked == MotionEvent.ACTION_UP && pendingText.isNotBlank())
                        annotations += Annotation.Text(original, pendingText, fx, fy, 0.025f)

                    Tool.SIGN -> handleSign(e, r, fx, fy)
                    Tool.NONE -> Unit
                }
                invalidate()
                return true
            }

            /** First tap places the signature; drag inside moves it, the bottom-right handle resizes; a tap elsewhere confirms. */
            private fun handleSign(e: MotionEvent, r: RectF, fx: Float, fy: Float) {
                val sig = signature ?: return
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val p = placing
                        if (p == null) {
                            val w = 0.3f
                            val h = w * sig.height / sig.width * r.width() / r.height()
                            placing = Annotation.Signature(original, sig, RectF(fx - w / 2, fy - h / 2, fx + w / 2, fy + h / 2))
                            dragging = true
                        } else {
                            val v = toView(r, p.rect)
                            val handleR = 24f * resources.displayMetrics.density
                            resizing = abs(e.x - v.right) < handleR && abs(e.y - v.bottom) < handleR
                            dragging = !resizing && v.contains(e.x, e.y)
                            if (!resizing && !dragging) {
                                // Confirmed: becomes an annotation
                                annotations += p
                                placing = null
                                setTool(Tool.NONE)
                                return
                            }
                        }
                        lastX = fx
                        lastY = fy
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val p = placing ?: return
                        val dx = fx - lastX
                        val dy = fy - lastY
                        lastX = fx
                        lastY = fy
                        if (resizing) {
                            val newW = max(0.05f, p.rect.width() + dx)
                            val newH = newW * sig.height / sig.width * r.width() / r.height()
                            p.rect.right = p.rect.left + newW
                            p.rect.bottom = p.rect.top + newH
                        } else if (dragging) p.rect.offset(dx, dy)
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        resizing = false
                        dragging = false
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_PATH = "path"

        fun open(context: Context, file: File) = context.startActivity(
            Intent(context, PdfViewerActivity::class.java).putExtra(EXTRA_PATH, file.absolutePath).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
