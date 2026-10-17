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
import app.olauncher.helper.showConfirmDialog
import app.olauncher.helper.showMenuDialog
import app.olauncher.helper.showToast
import app.olauncher.ui.UiKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Photo editor: crop (drag the frame or its corners, rotate 90°), draw (brush with colours and
 * widths, undo). Saves as a copy next to the original (name + " (1)") or over it.
 */
class PhotoEditActivity : AppCompatActivity() {

    private lateinit var file: File
    private lateinit var editor: EditorView
    private lateinit var cropTools: LinearLayout
    private lateinit var drawTools: LinearLayout
    private lateinit var modeCrop: View
    private lateinit var modeDraw: View
    private lateinit var widthButton: LinearLayout
    private lateinit var widthDot: View
    private val swatches = mutableListOf<View>()

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

        // Modes: crop or draw
        val modes = UiKit.toolBar(this)
        modeCrop = UiKit.toolButton(this, R.drawable.ic_m_crop, getString(R.string.ml_edit_crop)) { setMode(crop = true) }
        modeDraw = UiKit.toolButton(this, R.drawable.ic_m_draw, getString(R.string.ml_edit_draw)) { setMode(crop = false) }
        modes.addView(modeCrop, toolParams())
        modes.addView(modeDraw, toolParams())
        bottom.addView(modes, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        updateColorMarks()
        updateWidthDot()
        setMode(crop = true)
        return root
    }

    private fun toolParams() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        val m = 2.dpToPx()
        setMargins(m, m, m, m)
    }

    private fun setMode(crop: Boolean) {
        editor.cropMode = crop
        cropTools.isVisible = crop
        drawTools.isVisible = !crop
        UiKit.setToolActive(modeCrop, crop)
        UiKit.setToolActive(modeDraw, !crop)
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
        val degrees = when (ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) bitmap
        else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
    } catch (e: Throwable) {
        null
    }

    // ---------------------------------------------------------------- editor view

    /** The picture with the crop frame or the drawing on top. Coordinates of the crop and strokes are in image pixels. */
    private inner class EditorView(context: Context) : View(context) {
        private var image: Bitmap? = null
        /** Image → view transform (fit, centered). */
        private val fit = Matrix()
        private val inverse = Matrix()
        private var crop = RectF()
        private var rotation = 0
        private val strokes = mutableListOf<Pair<Path, Paint>>()
        private var currentPath: Path? = null
        /** Crop mode shows the whole picture with the frame; draw mode shows only the cropped part, filling the view. */
        var cropMode = true
            set(value) {
                field = value
                computeFit()
                invalidate()
            }
        var brushColor = Color.RED
        var brushWidth = 8f * resources.displayMetrics.density
        val isModified get() = rotation != 0 || strokes.isNotEmpty() || image?.let { crop != RectF(0f, 0f, it.width.toFloat(), it.height.toFloat()) } == true

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

        /** The edited picture: strokes drawn on, then cropped. */
        fun result(): Bitmap? {
            val img = image ?: return null
            val drawn = img.copy(Bitmap.Config.ARGB_8888, true)
            val c = Canvas(drawn)
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
            strokes.forEach { (path, paint) -> canvas.drawPath(path, paint) }
            currentPath?.let { canvas.drawPath(it, brushPaint()) }
            canvas.restore()
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
        private val COLORS = listOf(Color.RED, Color.YELLOW, 0xFF00C853.toInt(), 0xFF2979FF.toInt(), Color.WHITE, Color.BLACK)

        fun open(context: Context, file: File) = context.startActivity(
            Intent(context, PhotoEditActivity::class.java).putExtra(EXTRA_PATH, file.absolutePath).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
