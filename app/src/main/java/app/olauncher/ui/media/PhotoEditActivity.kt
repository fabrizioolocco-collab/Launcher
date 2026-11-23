package app.olauncher.ui.media

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.media.MediaScannerConnection
import android.os.Bundle
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import app.olauncher.R
import app.olauncher.helper.MenuEntry
import app.olauncher.helper.dpToPx
import app.olauncher.helper.showColorPickerDialog
import app.olauncher.helper.showConfirmDialog
import app.olauncher.helper.showMenuDialog
import app.olauncher.helper.showToast
import app.olauncher.ui.UiKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Photo editor: crop (drag the frame or its corners, rotate 90°), draw (brush with colours and
 * widths, undo), add (other pictures and texts laid over the photo: each one is moved, pinched
 * bigger or smaller, turned, and a picture can be cropped and framed too; with a backdrop of one
 * colour the photo itself becomes one of them, for collages). Two fingers zoom while drawing.
 * Saves as a copy next to the original (name + " (1)") or over it.
 */
// The screen can be turned while editing: the manifest keeps the activity (and the work in it) alive, the views lay themselves out again
class PhotoEditActivity : AppCompatActivity() {

    private lateinit var file: File
    private lateinit var editor: EditorView
    private lateinit var cropTools: LinearLayout
    private lateinit var drawTools: LinearLayout
    private lateinit var modeCrop: View
    private lateinit var modeDraw: View
    private lateinit var modeAdd: View
    private lateinit var addTools: LinearLayout
    private lateinit var itemTools: LinearLayout
    private lateinit var widthButton: LinearLayout
    private lateinit var widthDot: View
    private val swatches = mutableListOf<View>()

    /** The pictures chosen to be laid over the photo. */
    private val pickImages = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        lifecycleScope.launch {
            val bitmaps = withContext(Dispatchers.IO) { uris.take(LayerBoard.MAX_PICK).mapNotNull { LayerBoard.decode(applicationContext, it) } }
            if (bitmaps.isEmpty()) showToast(getString(R.string.ml_search_cannot_open))
            else editor.addImages(bitmaps)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        file = File(intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return })
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        setContentView(buildLayout())
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = askLeave()
        })
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { decode(file) }
            if (bitmap == null) {
                showToast(getString(R.string.ml_search_cannot_open))
                finish()
            } else {
                editor.setImage(bitmap)
                val prefs = getSharedPreferences("app.minimalauncher.hints", MODE_PRIVATE)
                if (!prefs.getBoolean("edit_hint", false)) {
                    prefs.edit().putBoolean("edit_hint", true).apply()
                    showToast(getString(R.string.ml_edit_hint), android.widget.Toast.LENGTH_LONG)
                }
            }
        }
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        // Top: back, file name, save
        val bar = UiKit.appBar(this, file.name, onBack = { askLeave() }, actions = listOf(
            UiKit.iconButton(this, R.drawable.ic_m_check, getString(R.string.ml_save)) { askSave() },
        ))
        bar.view.setPadding(4.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
        root.addView(bar.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        editor = EditorView(this)
        root.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dpToPx(), 8.dpToPx(), 12.dpToPx(), 10.dpToPx())
        }

        // Crop tools: rotate, square, reset
        cropTools = UiKit.toolBar(this)
        cropTools.addView(UiKit.toolButton(this, R.drawable.ic_m_rotate, getString(R.string.ml_edit_rotate)) { editor.rotate() }, toolParams())
        cropTools.addView(UiKit.toolButton(this, R.drawable.ic_m_resize, getString(R.string.ml_edit_square)) { editor.squareCrop() }, toolParams())
        cropTools.addView(UiKit.toolButton(this, R.drawable.ic_m_repeat, getString(R.string.ml_edit_reset)) { editor.resetCrop() }, toolParams())
        bottom.addView(cropTools, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 8.dpToPx() })

        // Draw tools: a palette card with round colour swatches, the brush width and undo
        drawTools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UiKit.cardBackground(this@PhotoEditActivity, 18)
            setPadding(14.dpToPx(), 6.dpToPx(), 4.dpToPx(), 6.dpToPx())
            isVisible = false
        }
        COLORS.forEach { color ->
            val swatch = View(this).apply {
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    editor.brushColor = color
                    updateColorMarks()
                }
                tag = color
            }
            swatches.add(swatch)
            drawTools.addView(swatch, LinearLayout.LayoutParams(28.dpToPx(), 28.dpToPx()).apply { marginEnd = 10.dpToPx() })
        }
        drawTools.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        widthDot = View(this)
        widthButton = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(8.dpToPx(), 4.dpToPx(), 8.dpToPx(), 4.dpToPx())
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.ml_edit_width)
            background = UiKit.ripple(this@PhotoEditActivity)
            addView(FrameLayout(this@PhotoEditActivity).apply {
                addView(widthDot, FrameLayout.LayoutParams(12.dpToPx(), 12.dpToPx(), Gravity.CENTER))
            }, LinearLayout.LayoutParams(26.dpToPx(), 26.dpToPx()))
            addView(TextView(this@PhotoEditActivity).apply {
                setText(R.string.ml_edit_width)
                textSize = 11.5f
                setTextColor(UiKit.primary(this@PhotoEditActivity))
                maxLines = 1
                gravity = Gravity.CENTER
                setPadding(0, 2.dpToPx(), 0, 0)
            })
            setOnClickListener { pickWidth() }
        }
        drawTools.addView(widthButton)
        drawTools.addView(UiKit.iconButton(this, R.drawable.ic_m_undo, getString(R.string.ml_edit_undo)) { editor.undo() })
        bottom.addView(drawTools, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 8.dpToPx() })

        // Add tools: another picture or a text; under them the tools of the chosen one
        addTools = UiKit.toolBar(this)
        addTools.addView(UiKit.toolButton(this, R.drawable.ic_m_image, getString(R.string.ml_edit_image)) {
            try {
                pickImages.launch("image/*")
            } catch (e: Exception) {
                showToast(getString(R.string.ml_search_cannot_open))
            }
        }, toolParams())
        addTools.addView(UiKit.toolButton(this, R.drawable.ic_m_text, getString(R.string.ml_edit_text)) { LayerBoard.askText(this, editor.board, null) { editor.area() } }, toolParams())
        addTools.addView(UiKit.toolButton(this, R.drawable.ic_m_tab, getString(R.string.ml_edit_plate)) { askBackdrop() }, toolParams())
        bottom.addView(addTools, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 8.dpToPx() })
        itemTools = UiKit.toolBar(this)
        bottom.addView(itemTools, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 8.dpToPx() })
        editor.board.onSelection = { updateItemTools() }

        // Modes: crop, draw or add
        val modes = UiKit.toolBar(this)
        modeCrop = UiKit.toolButton(this, R.drawable.ic_m_crop, getString(R.string.ml_edit_crop)) { setMode(MODE_CROP) }
        modeDraw = UiKit.toolButton(this, R.drawable.ic_m_draw, getString(R.string.ml_edit_draw)) { setMode(MODE_DRAW) }
        modeAdd = UiKit.toolButton(this, R.drawable.ic_m_add, getString(R.string.ml_edit_add)) { setMode(MODE_ADD) }
        modes.addView(modeCrop, toolParams())
        modes.addView(modeDraw, toolParams())
        modes.addView(modeAdd, toolParams())
        bottom.addView(modes, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        updateColorMarks()
        updateWidthDot()
        setMode(MODE_CROP)
        return root
    }

    private fun toolParams() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        val m = 2.dpToPx()
        setMargins(m, m, m, m)
    }

    private fun setMode(mode: Int) {
        editor.mode = mode
        cropTools.isVisible = mode == MODE_CROP
        drawTools.isVisible = mode == MODE_DRAW
        addTools.isVisible = mode == MODE_ADD
        UiKit.setToolActive(modeCrop, mode == MODE_CROP)
        UiKit.setToolActive(modeDraw, mode == MODE_DRAW)
        UiKit.setToolActive(modeAdd, mode == MODE_ADD)
        updateItemTools()
        if (mode == MODE_ADD) {
            val prefs = getSharedPreferences("app.minimalauncher.hints", MODE_PRIVATE)
            if (!prefs.getBoolean("edit_add_hint", false)) {
                prefs.edit().putBoolean("edit_add_hint", true).apply()
                showToast(getString(R.string.ml_edit_add_hint), android.widget.Toast.LENGTH_LONG)
            }
        }
    }

    /** The tools of the chosen picture or text; the row is hidden while nothing is chosen. */
    private fun updateItemTools() {
        itemTools.isVisible = LayerBoard.fillTools(this, itemTools, editor.board) { editor.area() } && editor.mode == MODE_ADD
    }

    /**
     * A backdrop of one colour under everything: the photo becomes a picture like the added ones,
     * to be made smaller and moved over it.
     */
    private fun askBackdrop() {
        showColorPickerDialog(getString(R.string.ml_edit_plate), editor.backdrop, getString(R.string.ml_edit_white)) { picked ->
            val first = editor.backdrop == 0
            if (!editor.setBackdrop(if (picked == 0) Color.WHITE else picked)) {
                showToast(getString(R.string.ml_explorer_failed, getString(R.string.ml_edit_plate)))
            } else if (first) showToast(getString(R.string.ml_edit_backdrop_hint), android.widget.Toast.LENGTH_LONG)
        }
    }

    private fun updateColorMarks() {
        swatches.forEach { v ->
            val color = v.tag as? Int ?: return@forEach
            val selected = color == editor.brushColor
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                if (selected) setStroke(3.dpToPx(), UiKit.primary(this@PhotoEditActivity))
                else setStroke(1.dpToPx(), UiKit.stroke(this@PhotoEditActivity))
            }
            v.scaleX = if (selected) 1.15f else 1f
            v.scaleY = v.scaleX
        }
    }

    /** Cycles the brush width: thin, medium, thick, extra; the dot shows the chosen one. */
    private fun pickWidth() {
        val widths = listOf(4f, 8f, 14f, 24f)
        val current = widths.indexOfFirst { abs(it.dpToPx() - editor.brushWidth) < 0.5f }
        val next = widths[(current + 1) % widths.size]
        editor.brushWidth = next.dpToPx()
        updateWidthDot()
    }

    /** The dot of the width button, as big as the brush (capped to the button). */
    private fun updateWidthDot() {
        val size = min(editor.brushWidth + 4f.dpToPx(), 24f.dpToPx()).toInt()
        widthDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(UiKit.primary(this@PhotoEditActivity))
        }
        widthDot.layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
    }

    private fun Float.dpToPx() = this * resources.displayMetrics.density

    private fun askLeave() {
        if (!editor.isModified) {
            finish()
            return
        }
        showConfirmDialog(getString(R.string.ml_edit_discard_title), getString(R.string.ml_edit_discard), getString(R.string.ml_edit_discard_action)) { finish() }
    }

    private fun askSave() {
        showMenuDialog(getString(R.string.ml_edit_save_title), listOf(
            MenuEntry(getString(R.string.ml_edit_save_copy)) { save(overwrite = false) },
            MenuEntry(getString(R.string.ml_edit_save_overwrite)) { save(overwrite = true) },
        ))
    }

    private fun save(overwrite: Boolean) {
        val result = editor.result() ?: return
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                try {
                    val target = if (overwrite) file else uniqueCopy(file)
                    val png = file.extension.lowercase() == "png"
                    target.outputStream().use { out ->
                        result.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, out)
                    }
                    MediaScannerConnection.scanFile(applicationContext, arrayOf(target.absolutePath), null, null)
                    target
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
            if (saved == null) showToast(getString(R.string.ml_explorer_failed, getString(R.string.ml_save)))
            else {
                showToast(getString(R.string.ml_edit_saved, saved.name))
                finish()
            }
        }
    }

    private fun uniqueCopy(src: File): File {
        val base = src.nameWithoutExtension
        val ext = src.extension.let { if (it.isEmpty()) "" else ".$it" }
        var i = 1
        var f: File
        do {
            f = File(src.parentFile, "$base (${i++})$ext")
        } while (f.exists())
        return f
    }

    /** Decodes at up to ~12 MP (enough for editing without running out of memory), with EXIF rotation applied. */
    private fun decode(file: File): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while ((bounds.outWidth.toLong() * bounds.outHeight) / (sample.toLong() * sample) > 12_000_000L) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inMutable = true
        }) ?: return null
        LayerBoard.upright(bitmap, ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
    } catch (e: Throwable) {
        null
    }

    // ---------------------------------------------------------------- editor view

    /**
     * The picture with the crop frame, the drawing or the added pictures and texts on top.
     * Coordinates of the crop, the strokes and the added things are in image pixels.
     */
    private inner class EditorView(context: Context) : View(context) {
        private var image: Bitmap? = null
        /** Image → view transform (fit, centered). */
        private val fit = Matrix()
        /** What two fingers add to the fit while drawing: bigger and moved, in view pixels. */
        private val zoom = Matrix()
        private var zooming = false
        private var zoomX = 0f
        private var zoomY = 0f
        private var zoomSpan = 0f
        /** The colour of the backdrop, 0 while the photo is still the bottom of everything. */
        var backdrop = 0
            private set
        private val inverse = Matrix()
        private var crop = RectF()
        private var rotation = 0
        private val strokes = mutableListOf<Pair<Path, Paint>>()
        private var currentPath: Path? = null
        /** The pictures and texts laid over the photo. */
        val board = LayerBoard(resources.displayMetrics.density) { invalidate() }
        /** Crop mode shows the whole picture with the frame; the others show only the cropped part, filling the view. */
        var mode = MODE_CROP
            set(value) {
                field = value
                if (value != MODE_ADD) board.select(null)
                if (value == MODE_CROP) zoom.reset()
                computeFit()
                invalidate()
            }
        private val cropMode get() = mode == MODE_CROP
        var brushColor = Color.RED
        var brushWidth = 8f * resources.displayMetrics.density
        val isModified get() = rotation != 0 || backdrop != 0 || strokes.isNotEmpty() || board.items.isNotEmpty() || image?.let { crop != RectF(0f, 0f, it.width.toFloat(), it.height.toFloat()) } == true

        private val dim = Paint().apply { color = 0x99000000.toInt() }
        private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = 2f * resources.displayMetrics.density
        }
        private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0x80FFFFFF.toInt()
            strokeWidth = 1f * resources.displayMetrics.density
        }
        private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val handleRadius = 10f * resources.displayMetrics.density
        private val touchRadius = 32f * resources.displayMetrics.density

        fun setImage(bitmap: Bitmap) {
            image = bitmap
            crop = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
            strokes.clear()
            board.clear()
            zoom.reset()
            backdrop = 0
            rotation = 0
            requestLayout()
            computeFit()
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            computeFit()
        }

        private fun computeFit() {
            val img = image ?: return
            if (width == 0 || height == 0) return
            val pad = 16f * resources.displayMetrics.density
            // What is shown: the whole picture while cropping, the cropped part while drawing
            val area = if (cropMode) RectF(0f, 0f, img.width.toFloat(), img.height.toFloat()) else RectF(crop)
            val s = min((width - 2 * pad) / area.width(), (height - 2 * pad) / area.height())
            fit.reset()
            fit.postTranslate(-area.left, -area.top)
            fit.postScale(s, s)
            fit.postTranslate((width - area.width() * s) / 2f, (height - area.height() * s) / 2f)
            if (!zoom.isIdentity) {
                fit.postConcat(zoom)
                // The zoomed picture is not let go off the view: it stays centred where it is smaller, and covers the view where it is bigger
                val shown = RectF(area).also { fit.mapRect(it) }
                val dx = when {
                    shown.width() <= width -> (width - shown.width()) / 2f - shown.left
                    shown.left > 0f -> -shown.left
                    shown.right < width -> width - shown.right
                    else -> 0f
                }
                val dy = when {
                    shown.height() <= height -> (height - shown.height()) / 2f - shown.top
                    shown.top > 0f -> -shown.top
                    shown.bottom < height -> height - shown.bottom
                    else -> 0f
                }
                zoom.postTranslate(dx, dy)
                fit.postTranslate(dx, dy)
            }
            fit.invert(inverse)
        }

        private fun zoomScale(): Float {
            val v = FloatArray(9)
            zoom.getValues(v)
            return v[Matrix.MSCALE_X]
        }

        /**
         * Two fingers zoom and move the picture while drawing (and while adding, when nothing is chosen:
         * on a chosen thing they resize it). True when the touch was theirs.
         */
        private fun zoomTouch(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2 && !(mode == MODE_ADD && board.chosen != null)) {
                    zooming = true
                    // The stroke begun by the first finger is dropped
                    currentPath = null
                    zoomX = (e.getX(0) + e.getX(1)) / 2f
                    zoomY = (e.getY(0) + e.getY(1)) / 2f
                    zoomSpan = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0))
                    invalidate()
                    return true
                }

                MotionEvent.ACTION_MOVE -> if (zooming) {
                    if (e.pointerCount >= 2) {
                        val x = (e.getX(0) + e.getX(1)) / 2f
                        val y = (e.getY(0) + e.getY(1)) / 2f
                        val span = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0))
                        if (span > 1f && zoomSpan > 1f) {
                            val now = zoomScale()
                            val target = (now * span / zoomSpan).coerceIn(1f, MAX_ZOOM)
                            zoom.postTranslate(x - zoomX, y - zoomY)
                            zoom.postScale(target / now, target / now, x, y)
                            if (target <= 1.001f) zoom.reset()
                            computeFit()
                            invalidate()
                        }
                        zoomX = x
                        zoomY = y
                        zoomSpan = span
                    }
                    return true
                }

                // With one finger lifted the other does not start drawing: both go up first
                MotionEvent.ACTION_POINTER_UP -> if (zooming) {
                    zoomSpan = 0f
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (zooming) {
                    zooming = false
                    return true
                }
            }
            return false
        }

        /** Puts a backdrop of [color] under everything; the first time the photo becomes a thing laid over it. False when memory is short. */
        fun setBackdrop(color: Int): Boolean {
            val img = image ?: return false
            try {
                if (backdrop != 0 && img.isMutable) img.eraseColor(color)
                else {
                    val base = Bitmap.createBitmap(img.width, img.height, Bitmap.Config.RGB_565)
                    base.eraseColor(color)
                    image = base
                    if (backdrop == 0) {
                        val item = Layer(img, "")
                        item.crop.set(0f, 0f, img.width.toFloat(), img.height.toFloat())
                        item.cx = img.width / 2f
                        item.cy = img.height / 2f
                        // A little smaller at once, so the backdrop shows and the photo is seen to be free
                        item.scale = 0.85f
                        board.addBottom(item)
                    }
                }
            } catch (e: Throwable) {
                return false
            }
            backdrop = color
            invalidate()
            return true
        }

        fun rotate() {
            val img = image ?: return
            val rotated = Bitmap.createBitmap(img, 0, 0, img.width, img.height, Matrix().apply { postRotate(90f) }, true)
            // Rotate the crop and the strokes along with the picture
            val m = Matrix().apply {
                postTranslate(-img.width / 2f, -img.height / 2f)
                postRotate(90f)
                postTranslate(rotated.width / 2f, rotated.height / 2f)
            }
            m.mapRect(crop)
            strokes.forEach { it.first.transform(m) }
            board.quarterTurn(m)
            image = rotated
            rotation = (rotation + 90) % 360
            computeFit()
            invalidate()
        }

        fun squareCrop() {
            val img = image ?: return
            val side = min(img.width, img.height).toFloat()
            crop = RectF((img.width - side) / 2f, (img.height - side) / 2f, (img.width + side) / 2f, (img.height + side) / 2f)
            invalidate()
        }

        fun resetCrop() {
            val img = image ?: return
            crop = RectF(0f, 0f, img.width.toFloat(), img.height.toFloat())
            invalidate()
        }

        fun undo() {
            if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex)
            invalidate()
        }

        /** The part of the photo that is kept: new things are laid in its middle. */
        fun area() = RectF(crop)

        fun addImages(bitmaps: List<Bitmap>) {
            if (image != null) board.addImages(bitmaps, crop)
        }

        /** The edited picture: the added things and the strokes drawn on, then cropped. */
        fun result(): Bitmap? {
            val img = image ?: return null
            val drawn = img.copy(Bitmap.Config.ARGB_8888, true)
            val c = Canvas(drawn)
            board.draw(c, preview = false)
            strokes.forEach { (path, paint) -> c.drawPath(path, paint) }
            val r = android.graphics.Rect(
                crop.left.toInt().coerceIn(0, img.width - 1), crop.top.toInt().coerceIn(0, img.height - 1),
                crop.right.toInt().coerceIn(1, img.width), crop.bottom.toInt().coerceIn(1, img.height),
            )
            return Bitmap.createBitmap(drawn, r.left, r.top, max(1, r.width()), max(1, r.height()))
        }

        override fun onDraw(canvas: Canvas) {
            val img = image ?: return
            canvas.save()
            canvas.concat(fit)
            // While drawing, nothing outside the crop is shown (that's what will be saved)
            if (!cropMode) canvas.clipRect(crop)
            canvas.drawBitmap(img, 0f, 0f, null)
            board.draw(canvas, preview = true)
            strokes.forEach { (path, paint) -> canvas.drawPath(path, paint) }
            currentPath?.let { canvas.drawPath(it, brushPaint()) }
            canvas.restore()
            if (mode == MODE_ADD) board.drawSelection(canvas, fit)
            if (!cropMode) return
            // Dim outside the crop, draw the frame, thirds and corner handles
            val v = RectF(crop).also { fit.mapRect(it) }
            canvas.drawRect(0f, 0f, width.toFloat(), v.top, dim)
            canvas.drawRect(0f, v.bottom, width.toFloat(), height.toFloat(), dim)
            canvas.drawRect(0f, v.top, v.left, v.bottom, dim)
            canvas.drawRect(v.right, v.top, width.toFloat(), v.bottom, dim)
            canvas.drawRect(v, frame)
            for (i in 1..2) {
                canvas.drawLine(v.left + v.width() * i / 3, v.top, v.left + v.width() * i / 3, v.bottom, grid)
                canvas.drawLine(v.left, v.top + v.height() * i / 3, v.right, v.top + v.height() * i / 3, grid)
            }
            corners(v).forEach { (x, y) -> canvas.drawCircle(x, y, handleRadius, handle) }
        }

        private fun corners(r: RectF) = listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom)

        private fun brushPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = brushColor
            // Stroke width in image pixels, so it looks the same on screen whatever the picture size
            strokeWidth = brushWidth / fitScale()
        }

        private fun fitScale(): Float {
            val v = FloatArray(9)
            fit.getValues(v)
            return v[Matrix.MSCALE_X]
        }

        // Crop drag state
        private var dragCorner = -1
        private var dragInside = false
        private var lastX = 0f
        private var lastY = 0f
        private val minSide get() = 48f / fitScale()

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val img = image ?: return false
            if (mode != MODE_CROP && zoomTouch(e)) return true
            if (mode == MODE_ADD) return board.onTouch(e, fit, inverse)
            val p = floatArrayOf(e.x, e.y)
            inverse.mapPoints(p)
            val ix = p[0]
            val iy = p[1]
            if (!cropMode) {
                // Strokes are kept inside the cropped part
                val cx = ix.coerceIn(crop.left, crop.right)
                val cy = iy.coerceIn(crop.top, crop.bottom)
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> currentPath = Path().apply { moveTo(cx, cy) }
                    MotionEvent.ACTION_MOVE -> currentPath?.lineTo(cx, cy)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        currentPath?.let {
                            it.lineTo(cx, cy)
                            strokes += it to brushPaint()
                        }
                        currentPath = null
                    }
                }
                invalidate()
                return true
            }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val v = RectF(crop).also { fit.mapRect(it) }
                    dragCorner = corners(v).indexOfFirst { (x, y) -> abs(e.x - x) < touchRadius && abs(e.y - y) < touchRadius }
                    dragInside = dragCorner < 0 && v.contains(e.x, e.y)
                    lastX = ix
                    lastY = iy
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = ix - lastX
                    val dy = iy - lastY
                    lastX = ix
                    lastY = iy
                    val w = img.width.toFloat()
                    val h = img.height.toFloat()
                    when {
                        dragCorner >= 0 -> {
                            if (dragCorner % 2 == 0) crop.left = (crop.left + dx).coerceIn(0f, crop.right - minSide)
                            else crop.right = (crop.right + dx).coerceIn(crop.left + minSide, w)
                            if (dragCorner < 2) crop.top = (crop.top + dy).coerceIn(0f, crop.bottom - minSide)
                            else crop.bottom = (crop.bottom + dy).coerceIn(crop.top + minSide, h)
                        }

                        dragInside -> {
                            val mx = dx.coerceIn(-crop.left, w - crop.right)
                            val my = dy.coerceIn(-crop.top, h - crop.bottom)
                            crop.offset(mx, my)
                        }
                    }
                    invalidate()
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragCorner = -1
                    dragInside = false
                }
            }
            return true
        }
    }

    companion object {
        const val EXTRA_PATH = "path"
        private const val MODE_CROP = 0
        private const val MODE_DRAW = 1
        private const val MODE_ADD = 2
        private const val MAX_ZOOM = 8f
        private val COLORS = listOf(Color.RED, Color.YELLOW, 0xFF00C853.toInt(), 0xFF2979FF.toInt(), Color.WHITE, Color.BLACK)

        fun open(context: Context, file: File) = context.startActivity(
            Intent(context, PhotoEditActivity::class.java).putExtra(EXTRA_PATH, file.absolutePath).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
