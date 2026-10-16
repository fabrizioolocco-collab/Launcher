package app.olauncher.ui.pdf

import android.graphics.Bitmap
import android.graphics.RectF
import java.io.File

/**
 * What the user adds to a PDF before saving. Every annotation has a box in fractions (0..1) of the
 * displayed page (x, y, width, height relative to the page width/height) and a rotation in degrees
 * around the box centre, so the editor's selection frame (move, rotate, resize) and the saver work
 * on the same numbers, whatever the zoom or the screen.
 */
sealed class Annotation(val page: Int) {
    /** Box in page fractions. */
    abstract val box: RectF
    var rotation = 0f

    class Text(page: Int, var text: String, override val box: RectF, var color: Int) : Annotation(page)

    /** A check mark, or a cross when [cross] is set. */
    class Check(page: Int, override val box: RectF, var color: Int, var cross: Boolean = false) : Annotation(page)

    /** Free drawing. [points] are fractions of the page; [highlighter] draws wide and translucent. */
    class Stroke(page: Int, val points: List<Pair<Float, Float>>, var widthFraction: Float, var color: Int, val highlighter: Boolean) : Annotation(page) {
        override val box: RectF = RectF(
            points.minOf { it.first }, points.minOf { it.second }, points.maxOf { it.first }, points.maxOf { it.second },
        )
        /** Points relative to the box (0..1 inside it), so moving or scaling the box moves the stroke. */
        val relative: List<Pair<Float, Float>> = points.map { (x, y) ->
            Pair(if (box.width() > 0f) (x - box.left) / box.width() else 0.5f, if (box.height() > 0f) (y - box.top) / box.height() else 0.5f)
        }

        /** Current points, after the box was moved or resized. */
        fun currentPoints(): List<Pair<Float, Float>> = relative.map { (rx, ry) -> Pair(box.left + rx * box.width(), box.top + ry * box.height()) }
    }

    class Signature(page: Int, val bitmap: Bitmap, override val box: RectF) : Annotation(page)
}

/** Page order and rotation changes: [order] lists original page indexes (0-based) in the new order; deleted pages are absent. */
class PageLayout(var order: MutableList<Int>, val rotation: MutableMap<Int, Int> = mutableMapOf()) {
    fun isIdentity(total: Int) = order.size == total && order.withIndex().all { it.index == it.value } && rotation.values.all { it % 360 == 0 }

    companion object {
        fun identity(total: Int) = PageLayout(MutableList(total) { it })
    }
}

/** The saved signature as a PNG with transparent background. */
object SavedSignature {
    fun file(context: android.content.Context) = File(context.filesDir, "signature.png")
    fun exists(context: android.content.Context) = file(context).exists()
    fun load(context: android.content.Context): Bitmap? = try {
        android.graphics.BitmapFactory.decodeFile(file(context).absolutePath)
    } catch (e: Exception) {
        null
    }

    fun save(context: android.content.Context, bitmap: Bitmap) {
        file(context).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    fun delete(context: android.content.Context) = file(context).delete()
}
