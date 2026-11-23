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
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaScannerConnection
import android.net.Uri
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
import app.olauncher.helper.showTextInputDialog
import app.olauncher.helper.showToast
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
import kotlin.math.sin

/**
 * Photo editor: crop (drag the frame or its corners, rotate 90°), draw (brush with colours and
 * widths, undo), add (other pictures and texts laid over the photo: each one is moved, pinched
 * bigger or smaller, turned, and a picture can be cropped too; good for small collages).
 * Saves as a copy next to the original (name + " (1)") or over it.
 */
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
            val bitmaps = withContext(Dispatchers.IO) { uris.take(MAX_PICK).mapNotNull { decodeUri(it) } }
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
        addTools.addView(UiKit.toolButton(this, R.drawable.ic_m_text, getString(R.string.ml_edit_text)) { askText(null) }, toolParams())
        bottom.addView(addTools, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 8.dpToPx() })
        itemTools = UiKit.toolBar(this)
        bottom.addView(itemTools, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 8.dpToPx() })
        editor.onSelection = { updateItemTools() }

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
        val item = editor.chosen
        itemTools.removeAllViews()
        itemTools.isVisible = editor.mode == MODE_ADD && item != null
        if (item == null) return
        fun tool(icon: Int, label: Int, active: Boolean = false, onClick: () -> Unit) {
            val button = UiKit.toolButton(this, icon, getString(label), onClick)
            if (active) UiKit.setToolActive(button, true)
            itemTools.addView(button, toolParams())
        }
        if (item.isText) {
            tool(R.drawable.ic_m_edit, R.string.ml_edit_modify) { askText(item) }
            tool(R.drawable.ic_m_drop, R.string.ml_edit_color) {
                showColorPickerDialog(getString(R.string.ml_edit_color), item.color, getString(R.string.ml_edit_white)) { picked ->
                    item.color = if (picked == 0) Color.WHITE else picked
                    editor.invalidate()
                }
            }
            tool(R.drawable.ic_m_text, R.string.ml_edit_style) {
                item.style = (item.style + 1) % TYPEFACES.size
                editor.invalidate()
            }
            tool(R.drawable.ic_m_tab, R.string.ml_edit_plate) {
                item.plate = (item.plate + 1) % 3
                editor.invalidate()
            }
        } else {
            tool(R.drawable.ic_m_crop, R.string.ml_edit_crop, active = editor.itemCrop) { editor.itemCrop = !editor.itemCrop }
            tool(R.drawable.ic_m_copy, R.string.ml_edit_duplicate) { editor.duplicateSelected() }
        }
        tool(R.drawable.ic_m_up, R.string.ml_edit_front) { editor.selectedToFront() }
        tool(R.drawable.ic_m_delete, R.string.ml_delete) { editor.deleteSelected() }
    }

    /** Asks for the words of a new text, or of [item] when one is being changed. */
    private fun askText(item: Layer?) {
        showTextInputDialog(getString(R.string.ml_edit_text), item?.text.orEmpty(), getString(R.string.ml_edit_text_hint), getString(R.string.ml_save)) { text ->
            if (item == null) editor.addText(text)
            else {
                item.text = text
                editor.invalidate()
            }
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
        upright(bitmap, ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
    } catch (e: Throwable) {
        null
    }

    /** A picture to lay over the photo, at up to ~4 MP, with EXIF rotation applied. */
    private fun decodeUri(uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while ((bounds.outWidth.toLong() * bounds.outHeight) / (sample.toLong() * sample) > 4_000_000L) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        val orientation = contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        if (bitmap == null) null else upright(bitmap, orientation)
    } catch (e: Throwable) {
        null
    }

    private fun upright(bitmap: Bitmap, orientation: Int): Bitmap {
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        return if (degrees == 0f) bitmap
        else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
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
        private val inverse = Matrix()
        private var crop = RectF()
        private var rotation = 0
        private val strokes = mutableListOf<Pair<Path, Paint>>()
        private var currentPath: Path? = null
        /** The pictures and texts laid over the photo, bottom to top. */
        private val items = mutableListOf<Layer>()
        var chosen: Layer? = null
            private set
        /** Told when another thing (or nothing) is chosen, to show its tools. */
        var onSelection: (() -> Unit)? = null
        /** While on, the corners of the chosen picture cut it instead of the one handle resizing it. */
        var itemCrop = false
            set(value) {
                field = value
                onSelection?.invoke()
                invalidate()
            }
        /** Crop mode shows the whole picture with the frame; the others show only the cropped part, filling the view. */
        var mode = MODE_CROP
            set(value) {
                field = value
                if (value != MODE_ADD) select(null)
                computeFit()
                invalidate()
            }
        private val cropMode get() = mode == MODE_CROP
        var brushColor = Color.RED
        var brushWidth = 8f * resources.displayMetrics.density
        val isModified get() = rotation != 0 || strokes.isNotEmpty() || items.isNotEmpty() || image?.let { crop != RectF(0f, 0f, it.width.toFloat(), it.height.toFloat()) } == true

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
        private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xB3000000.toInt()
            strokeWidth = 1.5f * resources.displayMetrics.density
        }
        private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = 80 }
        private val platePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { textSize = TEXT_SIZE }
        private val handleRadius = 10f * resources.displayMetrics.density
        private val touchRadius = 32f * resources.displayMetrics.density

        fun setImage(bitmap: Bitmap) {
            image = bitmap
            crop = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
            strokes.clear()
            items.clear()
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
            fit.invert(inverse)
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
            items.forEach {
                val c = floatArrayOf(it.cx, it.cy)
                m.mapPoints(c)
                it.cx = c[0]
                it.cy = c[1]
                it.rot += 90f
            }
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

        // ---- added pictures and texts

        private fun select(item: Layer?) {
            if (chosen === item) return
            chosen = item
            itemCrop = false
        }

        /** Lays [bitmaps] in the middle of the visible part, each a little lower than the one before. */
        fun addImages(bitmaps: List<Bitmap>) {
            if (image == null) return
            bitmaps.forEachIndexed { i, b ->
                val item = Layer(b, "")
                item.crop.set(0f, 0f, b.width.toFloat(), b.height.toFloat())
                item.scale = 0.6f * min(crop.width() / b.width, crop.height() / b.height)
                val step = 0.04f * i * min(crop.width(), crop.height())
                item.cx = crop.centerX() + step
                item.cy = crop.centerY() + step
                items += item
            }
            select(items.last())
            invalidate()
        }

        fun addText(text: String) {
            if (image == null) return
            val item = Layer(null, text)
            val box = local(item)
            item.scale = min(0.7f * crop.width() / box.width(), 0.12f * crop.height() / box.height())
            item.cx = crop.centerX()
            item.cy = crop.centerY()
            items += item
            select(item)
            invalidate()
        }

        fun deleteSelected() {
            val item = chosen ?: return
            items.remove(item)
            select(null)
            invalidate()
        }

        fun selectedToFront() {
            val item = chosen ?: return
            items.remove(item)
            items += item
            invalidate()
        }

        fun duplicateSelected() {
            val item = chosen ?: return
            val copy = Layer(item.bitmap, item.text)
            copy.crop.set(item.crop)
            copy.scale = item.scale
            copy.rot = item.rot
            copy.color = item.color
            copy.style = item.style
            copy.plate = item.plate
            val step = 0.05f * min(crop.width(), crop.height())
            copy.cx = item.cx + step
            copy.cy = item.cy + step
            items += copy
            select(copy)
            invalidate()
        }

        private fun prepareText(item: Layer) {
            textPaint.typeface = TYPEFACES[item.style % TYPEFACES.size]
            textPaint.color = item.color
        }

        /** The box of [item] around its own centre, before it is scaled and turned: the kept part of a picture, or the text with its margin. */
        private fun local(item: Layer): RectF {
            val b = item.bitmap
            if (b != null) return RectF(item.crop).apply { offset(-b.width / 2f, -b.height / 2f) }
            prepareText(item)
            val fm = textPaint.fontMetrics
            val w = textPaint.measureText(item.text) + 2 * TEXT_PAD
            val h = fm.descent - fm.ascent + 2 * TEXT_PAD
            return RectF(-w / 2f, -h / 2f, w / 2f, h / 2f)
        }

        /** Own box → image pixels. */
        private fun matrixOf(item: Layer) = Matrix().apply {
            postScale(item.scale, item.scale)
            postRotate(item.rot)
            postTranslate(item.cx, item.cy)
        }

        /** Draws [item] on a canvas that is in image pixels. */
        private fun drawItem(canvas: Canvas, item: Layer, ghost: Boolean) {
            val box = local(item)
            canvas.save()
            canvas.concat(matrixOf(item))
            val b = item.bitmap
            if (b != null) {
                // While it is being cut, what falls outside the cut stays faintly visible
                if (ghost) canvas.drawBitmap(b, -b.width / 2f, -b.height / 2f, ghostPaint)
                canvas.clipRect(box)
                canvas.drawBitmap(b, -b.width / 2f, -b.height / 2f, bitmapPaint)
            } else {
                if (item.plate != 0) {
                    platePaint.color = if (item.plate == 1) 0x99000000.toInt() else 0xD9FFFFFF.toInt()
                    canvas.drawRoundRect(box, TEXT_PAD, TEXT_PAD, platePaint)
                }
                prepareText(item)
                canvas.drawText(item.text, box.left + TEXT_PAD, box.top + TEXT_PAD - textPaint.fontMetrics.ascent, textPaint)
            }
            canvas.restore()
        }

        /** The corners of [item] in view pixels: top left, top right, bottom right, bottom left. */
        private fun cornersOf(item: Layer): FloatArray {
            val r = local(item)
            val pts = floatArrayOf(r.left, r.top, r.right, r.top, r.right, r.bottom, r.left, r.bottom)
            matrixOf(item).mapPoints(pts)
            fit.mapPoints(pts)
            return pts
        }

        /** The topmost thing under a point given in image pixels. */
        private fun itemAt(x: Float, y: Float): Layer? = items.lastOrNull { item ->
            val inv = Matrix()
            if (!matrixOf(item).invert(inv)) return@lastOrNull false
            val p = floatArrayOf(x, y)
            inv.mapPoints(p)
            // A small thing still has to be easy to take
            val box = local(item)
            val slack = max(0f, touchRadius / 2f / (fitScale() * item.scale) - min(box.width(), box.height()) / 2f)
            box.inset(-slack, -slack)
            box.contains(p[0], p[1])
        }

        /** Scales [item] by [ratio] and turns it by [degrees] around the point that goes from ([fromX], [fromY]) to ([toX], [toY]). */
        private fun transformAbout(item: Layer, fromX: Float, fromY: Float, toX: Float, toY: Float, ratio: Float, degrees: Float) {
            val scale = (item.scale * ratio).coerceIn(MIN_SCALE, MAX_SCALE)
            val r = scale / item.scale
            val rad = Math.toRadians(degrees.toDouble())
            val c = cos(rad).toFloat()
            val s = sin(rad).toFloat()
            val dx = (item.cx - fromX) * r
            val dy = (item.cy - fromY) * r
            item.cx = toX + dx * c - dy * s
            item.cy = toY + dx * s + dy * c
            item.scale = scale
            item.rot += degrees
        }

        /** The turn from one direction to another, in degrees, the short way round. */
        private fun turn(fromX: Float, fromY: Float, toX: Float, toY: Float): Float {
            var d = Math.toDegrees((atan2(toY, toX) - atan2(fromY, fromX)).toDouble()).toFloat()
            while (d > 180f) d -= 360f
            while (d < -180f) d += 360f
            return d
        }

        private var gesture = G_NONE
        private var cutCorner = -1
        /** Where the fingers were, in image pixels. */
        private var ax = 0f
        private var ay = 0f
        private var bx = 0f
        private var by = 0f
        /** The centre the handle turns around, in image pixels. */
        private var pivotX = 0f
        private var pivotY = 0f

        private fun imagePoint(e: MotionEvent, index: Int): FloatArray {
            val p = floatArrayOf(e.getX(index), e.getY(index))
            inverse.mapPoints(p)
            return p
        }

        private fun onAddTouch(e: MotionEvent): Boolean {
            val p = imagePoint(e, 0)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gesture = G_NONE
                    ax = p[0]
                    ay = p[1]
                    val current = chosen
                    if (current != null) {
                        val pts = cornersOf(current)
                        if (itemCrop) {
                            cutCorner = (0..3).firstOrNull { abs(e.x - pts[it * 2]) < touchRadius && abs(e.y - pts[it * 2 + 1]) < touchRadius } ?: -1
                            if (cutCorner >= 0) gesture = G_CUT
                        } else if (abs(e.x - pts[4]) < touchRadius && abs(e.y - pts[5]) < touchRadius) {
                            val box = local(current)
                            val centre = floatArrayOf(box.centerX(), box.centerY())
                            matrixOf(current).mapPoints(centre)
                            pivotX = centre[0]
                            pivotY = centre[1]
                            gesture = G_HANDLE
                        }
                    }
                    if (gesture == G_NONE) {
                        val hit = itemAt(p[0], p[1])
                        // While a picture is being cut, a touch on it does not move it; a touch elsewhere ends the cut
                        if (hit == null) select(null)
                        else if (!(itemCrop && hit === current)) {
                            select(hit)
                            gesture = G_MOVE
                        }
                    }
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (chosen != null && e.pointerCount == 2 && !itemCrop) {
                        val q = imagePoint(e, 1)
                        ax = p[0]
                        ay = p[1]
                        bx = q[0]
                        by = q[1]
                        gesture = G_PINCH
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    val item = chosen
                    if (item != null) when (gesture) {
                        G_MOVE -> {
                            item.cx += p[0] - ax
                            item.cy += p[1] - ay
                            ax = p[0]
                            ay = p[1]
                        }

                        G_HANDLE -> {
                            val before = hypot(ax - pivotX, ay - pivotY)
                            val after = hypot(p[0] - pivotX, p[1] - pivotY)
                            if (before > 1f && after > 1f) {
                                transformAbout(
                                    item, pivotX, pivotY, pivotX, pivotY, after / before,
                                    turn(ax - pivotX, ay - pivotY, p[0] - pivotX, p[1] - pivotY),
                                )
                                ax = p[0]
                                ay = p[1]
                            }
                        }

                        G_PINCH -> {
                            if (e.pointerCount >= 2) {
                                val q = imagePoint(e, 1)
                                val before = hypot(bx - ax, by - ay)
                                val after = hypot(q[0] - p[0], q[1] - p[1])
                                if (before > 1f && after > 1f) {
                                    transformAbout(
                                        item, (ax + bx) / 2f, (ay + by) / 2f, (p[0] + q[0]) / 2f, (p[1] + q[1]) / 2f, after / before,
                                        turn(bx - ax, by - ay, q[0] - p[0], q[1] - p[1]),
                                    )
                                    ax = p[0]
                                    ay = p[1]
                                    bx = q[0]
                                    by = q[1]
                                }
                            }
                        }

                        G_CUT -> {
                            val b = item.bitmap
                            val inv = Matrix()
                            if (b != null && matrixOf(item).invert(inv)) {
                                inv.mapPoints(p)
                                val x = p[0] + b.width / 2f
                                val y = p[1] + b.height / 2f
                                val least = min(48f / (fitScale() * item.scale), min(b.width, b.height) / 2f)
                                val c = item.crop
                                if (cutCorner == 0 || cutCorner == 3) c.left = max(0f, min(x, c.right - least))
                                else c.right = min(b.width.toFloat(), max(x, c.left + least))
                                if (cutCorner < 2) c.top = max(0f, min(y, c.bottom - least))
                                else c.bottom = min(b.height.toFloat(), max(y, c.top + least))
                            }
                        }

                        else -> Unit
                    }
                }

                // Lifting one of two fingers ends the gesture: the one left does not drag the thing along
                MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    gesture = G_NONE
                    cutCorner = -1
                }
            }
            invalidate()
            return true
        }

        /** The frame of the chosen thing: one handle to resize and turn it, or four to cut it. */
        private fun drawSelection(canvas: Canvas) {
            val item = chosen ?: return
            val pts = cornersOf(item)
            val outline = Path().apply {
                moveTo(pts[0], pts[1])
                for (i in 1..3) lineTo(pts[i * 2], pts[i * 2 + 1])
                close()
            }
            canvas.drawPath(outline, frame)
            val handles = if (itemCrop) 0..3 else 2..2
            for (i in handles) {
                canvas.drawCircle(pts[i * 2], pts[i * 2 + 1], handleRadius, handle)
                canvas.drawCircle(pts[i * 2], pts[i * 2 + 1], handleRadius, handleRing)
            }
        }

        /** The edited picture: the added things and the strokes drawn on, then cropped. */
        fun result(): Bitmap? {
            val img = image ?: return null
            val drawn = img.copy(Bitmap.Config.ARGB_8888, true)
            val c = Canvas(drawn)
            items.forEach { drawItem(c, it, ghost = false) }
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
            canvas.drawBitmap(img, 0f, 0f, bitmapPaint)
            items.forEach { drawItem(canvas, it, ghost = itemCrop && it === chosen) }
            strokes.forEach { (path, paint) -> canvas.drawPath(path, paint) }
            currentPath?.let { canvas.drawPath(it, brushPaint()) }
            canvas.restore()
            if (mode == MODE_ADD) drawSelection(canvas)
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
            if (mode == MODE_ADD) return onAddTouch(e)
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

    /** A picture ([bitmap]) or a text laid over the photo: where its centre is, how big and how turned, in image pixels. */
    private class Layer(val bitmap: Bitmap?, var text: String) {
        var cx = 0f
        var cy = 0f
        var scale = 1f
        var rot = 0f
        /** The part of the picture that is kept, in its own pixels. */
        val crop = RectF()
        var color = Color.WHITE
        var style = 0
        /** Behind a text: nothing, a dark plate or a light one. */
        var plate = 0
        val isText get() = bitmap == null
    }

    companion object {
        const val EXTRA_PATH = "path"
        private const val MODE_CROP = 0
        private const val MODE_DRAW = 1
        private const val MODE_ADD = 2
        private const val MAX_PICK = 8
        /** A text is laid out at this size in its own box, then scaled like a picture. */
        private const val TEXT_SIZE = 100f
        private const val TEXT_PAD = 24f
        private const val MIN_SCALE = 0.01f
        private const val MAX_SCALE = 100f
        private const val G_NONE = 0
        private const val G_MOVE = 1
        private const val G_HANDLE = 2
        private const val G_PINCH = 3
        private const val G_CUT = 4
        private val TYPEFACES = listOf(
            Typeface.DEFAULT_BOLD,
            Typeface.SANS_SERIF,
            Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC),
            Typeface.SERIF,
            Typeface.create(Typeface.SERIF, Typeface.BOLD),
            Typeface.MONOSPACE,
        )
        private val COLORS = listOf(Color.RED, Color.YELLOW, 0xFF00C853.toInt(), 0xFF2979FF.toInt(), Color.WHITE, Color.BLACK)

        fun open(context: Context, file: File) = context.startActivity(
            Intent(context, PhotoEditActivity::class.java).putExtra(EXTRA_PATH, file.absolutePath).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
