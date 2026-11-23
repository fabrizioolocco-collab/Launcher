package app.olauncher.ui.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.exifinterface.media.ExifInterface
import app.olauncher.R
import app.olauncher.helper.dpToPx
import app.olauncher.helper.showColorPickerDialog
import app.olauncher.helper.showTextInputDialog
import app.olauncher.ui.UiKit
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A picture ([bitmap]) or a text laid over a photo or a video: where its centre is, how big and how turned, in the pixels of what is under it. */
class Layer(val bitmap: Bitmap?, var text: String) {
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
    /** The colour of the frame around a picture, 0 for none. */
    var border = 0
    val isText get() = bitmap == null
}

/**
 * The pictures and texts laid over a photo or a video, with the touches that move, resize, turn
 * and cut them. The view that shows them gives the matrix from the pixels of what is under them to
 * its own; [changed] asks it to draw again.
 */
class LayerBoard(private val density: Float, private val changed: () -> Unit) {

    /** Bottom to top. */
    val items = mutableListOf<Layer>()
    var chosen: Layer? = null
        private set
    /** Told when another thing (or nothing) is chosen, to show its tools. */
    var onSelection: (() -> Unit)? = null
    /** While on, the corners of the chosen picture cut it instead of the one handle resizing it. */
    var itemCrop = false
        set(value) {
            field = value
            onSelection?.invoke()
            changed()
        }

    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2f * density
    }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xB3000000.toInt()
        strokeWidth = 1.5f * density
    }
    private val handleRadius = 10f * density
    private val touchRadius = 32f * density
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = 80 }
    private val platePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { textSize = TEXT_SIZE }

    fun select(item: Layer?) {
        if (chosen === item) return
        chosen = item
        itemCrop = false
    }

    fun clear() {
        items.clear()
        select(null)
    }

    /** Lays [bitmaps] in the middle of [area], each a little lower than the one before. */
    fun addImages(bitmaps: List<Bitmap>, area: RectF) {
        if (bitmaps.isEmpty()) return
        bitmaps.forEachIndexed { i, b ->
            val item = Layer(b, "")
            item.crop.set(0f, 0f, b.width.toFloat(), b.height.toFloat())
            item.scale = 0.6f * min(area.width() / b.width, area.height() / b.height)
            val step = 0.04f * i * min(area.width(), area.height())
            item.cx = area.centerX() + step
            item.cy = area.centerY() + step
            items += item
        }
        select(items.last())
        changed()
    }

    fun addText(text: String, area: RectF) {
        val item = Layer(null, text)
        val box = local(item)
        item.scale = min(0.7f * area.width() / box.width(), 0.12f * area.height() / box.height())
        item.cx = area.centerX()
        item.cy = area.centerY()
        items += item
        select(item)
        changed()
    }

    /** Puts [item] under all the others and chooses it. */
    fun addBottom(item: Layer) {
        items.add(0, item)
        select(item)
        changed()
    }

    fun deleteSelected() {
        val item = chosen ?: return
        items.remove(item)
        select(null)
        changed()
    }

    fun selectedToFront() {
        val item = chosen ?: return
        items.remove(item)
        items += item
        changed()
    }

    fun duplicateSelected(area: RectF) {
        val item = chosen ?: return
        val copy = Layer(item.bitmap, item.text)
        copy.crop.set(item.crop)
        copy.scale = item.scale
        copy.rot = item.rot
        copy.color = item.color
        copy.style = item.style
        copy.plate = item.plate
        copy.border = item.border
        val step = 0.05f * min(area.width(), area.height())
        copy.cx = item.cx + step
        copy.cy = item.cy + step
        items += copy
        select(copy)
        changed()
    }

    /** What is under the things was turned a quarter clockwise, by [m]: they turn with it. */
    fun quarterTurn(m: Matrix) {
        items.forEach {
            val c = floatArrayOf(it.cx, it.cy)
            m.mapPoints(c)
            it.cx = c[0]
            it.cy = c[1]
            it.rot += 90f
        }
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

    /** Own box → pixels of what is under. */
    private fun matrixOf(item: Layer) = Matrix().apply {
        postScale(item.scale, item.scale)
        postRotate(item.rot)
        postTranslate(item.cx, item.cy)
    }

    /** Draws everything on a canvas that is in the pixels of what is under; [preview] shows faintly what a cut takes away. */
    fun draw(canvas: Canvas, preview: Boolean) {
        items.forEach { drawItem(canvas, it, preview && itemCrop && it === chosen) }
    }

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
            if (item.border != 0) {
                // Inside the edge, so the picture keeps its size
                val w = BORDER * min(box.width(), box.height())
                borderPaint.color = item.border
                borderPaint.strokeWidth = w
                box.inset(w / 2f, w / 2f)
                canvas.drawRect(box, borderPaint)
            }
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
    private fun cornersOf(item: Layer, fit: Matrix): FloatArray {
        val r = local(item)
        val pts = floatArrayOf(r.left, r.top, r.right, r.top, r.right, r.bottom, r.left, r.bottom)
        matrixOf(item).mapPoints(pts)
        fit.mapPoints(pts)
        return pts
    }

    /** The frame of the chosen thing, in view pixels: one handle to resize and turn it, or four to cut it. */
    fun drawSelection(canvas: Canvas, fit: Matrix) {
        val item = chosen ?: return
        val pts = cornersOf(item, fit)
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

    private fun scaleOf(fit: Matrix): Float {
        val v = FloatArray(9)
        fit.getValues(v)
        return max(0.0001f, hypot(v[Matrix.MSCALE_X], v[Matrix.MSKEW_Y]))
    }

    /** The topmost thing under a point given in the pixels of what is under. */
    private fun itemAt(x: Float, y: Float, fitScale: Float): Layer? = items.lastOrNull { item ->
        val inv = Matrix()
        if (!matrixOf(item).invert(inv)) return@lastOrNull false
        val p = floatArrayOf(x, y)
        inv.mapPoints(p)
        // A small thing still has to be easy to take
        val box = local(item)
        val slack = max(0f, touchRadius / 2f / (fitScale * item.scale) - min(box.width(), box.height()) / 2f)
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
    /** Where the fingers were, in the pixels of what is under. */
    private var ax = 0f
    private var ay = 0f
    private var bx = 0f
    private var by = 0f
    /** The centre the handle turns around. */
    private var pivotX = 0f
    private var pivotY = 0f

    private fun point(e: MotionEvent, index: Int, inverse: Matrix): FloatArray {
        val p = floatArrayOf(e.getX(index), e.getY(index))
        inverse.mapPoints(p)
        return p
    }

    /** A touch on the view; [fit] goes from the pixels of what is under to the view's, [inverse] back. */
    fun onTouch(e: MotionEvent, fit: Matrix, inverse: Matrix): Boolean {
        val p = point(e, 0, inverse)
        val fitScale = scaleOf(fit)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gesture = G_NONE
                ax = p[0]
                ay = p[1]
                val current = chosen
                if (current != null) {
                    val pts = cornersOf(current, fit)
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
                    val hit = itemAt(p[0], p[1], fitScale)
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
                    val q = point(e, 1, inverse)
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
                            val q = point(e, 1, inverse)
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
                            val least = min(24f * density / (fitScale * item.scale), min(b.width, b.height) / 2f)
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
        changed()
        return true
    }

    companion object {
        const val MAX_PICK = 8
        /** A text is laid out at this size in its own box, then scaled like a picture. */
        private const val TEXT_SIZE = 100f
        private const val TEXT_PAD = 24f
        private const val MIN_SCALE = 0.01f
        private const val MAX_SCALE = 100f
        /** The frame of a picture, as a part of its shorter side. */
        private const val BORDER = 0.03f
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
        val STYLES get() = TYPEFACES.size

        /** A picture to lay over something, at up to ~4 MP, with EXIF rotation applied. */
        fun decode(context: Context, uri: Uri): Bitmap? = try {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while ((bounds.outWidth.toLong() * bounds.outHeight) / (sample.toLong() * sample) > 4_000_000L) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            val orientation = resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
            if (bitmap == null) null else upright(bitmap, orientation)
        } catch (e: Throwable) {
            null
        }

        fun upright(bitmap: Bitmap, orientation: Int): Bitmap {
            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            return if (degrees == 0f) bitmap
            else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
        }

        /** Asks for the words of a new text laid in [area], or of [item] when one is being changed. */
        fun askText(context: Context, board: LayerBoard, item: Layer?, area: () -> RectF) {
            context.showTextInputDialog(
                context.getString(R.string.ml_edit_text), item?.text.orEmpty(),
                context.getString(R.string.ml_edit_text_hint), context.getString(R.string.ml_save),
            ) { text ->
                if (item == null) board.addText(text, area())
                else {
                    item.text = text
                    board.changed()
                }
            }
        }

        /** Fills [bar] with the tools of the chosen picture or text; false (and an empty bar) when nothing is chosen. */
        fun fillTools(context: Context, bar: LinearLayout, board: LayerBoard, area: () -> RectF): Boolean {
            bar.removeAllViews()
            val item = board.chosen ?: return false
            fun tool(icon: Int, label: Int, active: Boolean = false, onClick: () -> Unit) {
                val button = UiKit.toolButton(context, icon, context.getString(label), onClick)
                if (active) UiKit.setToolActive(button, true)
                bar.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    val m = 2.dpToPx()
                    setMargins(m, m, m, m)
                })
            }
            if (item.isText) {
                tool(R.drawable.ic_m_edit, R.string.ml_edit_modify) { askText(context, board, item, area) }
                tool(R.drawable.ic_m_drop, R.string.ml_edit_color) {
                    context.showColorPickerDialog(context.getString(R.string.ml_edit_color), item.color, context.getString(R.string.ml_edit_white)) { picked ->
                        item.color = if (picked == 0) Color.WHITE else picked
                        board.changed()
                    }
                }
                tool(R.drawable.ic_m_text, R.string.ml_edit_style) {
                    item.style = (item.style + 1) % STYLES
                    board.changed()
                }
                tool(R.drawable.ic_m_tab, R.string.ml_edit_plate) {
                    item.plate = (item.plate + 1) % 3
                    board.changed()
                }
            } else {
                tool(R.drawable.ic_m_crop, R.string.ml_edit_crop, active = board.itemCrop) { board.itemCrop = !board.itemCrop }
                tool(R.drawable.ic_m_resize, R.string.ml_edit_border) {
                    context.showColorPickerDialog(context.getString(R.string.ml_edit_border), item.border, context.getString(R.string.ml_edit_border_none)) { picked ->
                        item.border = picked
                        board.changed()
                    }
                }
                tool(R.drawable.ic_m_copy, R.string.ml_edit_duplicate) { board.duplicateSelected(area()) }
            }
            tool(R.drawable.ic_m_up, R.string.ml_edit_front) { board.selectedToFront() }
            tool(R.drawable.ic_m_delete, R.string.ml_delete) { board.deleteSelected() }
            return true
        }
    }
}
