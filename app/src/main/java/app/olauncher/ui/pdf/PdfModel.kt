package app.olauncher.ui.pdf

import android.graphics.Bitmap
import android.graphics.RectF
import java.io.File

/**
 * What the user adds to a PDF before saving. Positions are fractions (0..1) of the displayed page
 * (width and height), so they don't depend on the zoom or the screen and map to PDF points on save.
 */
sealed class Annotation(val page: Int) {
    class Text(page: Int, var text: String, var x: Float, var y: Float, val sizeFraction: Float, val color: Int) : Annotation(page)
    class Check(page: Int, var x: Float, var y: Float, val sizeFraction: Float, val color: Int) : Annotation(page)
    class Stroke(page: Int, val points: List<Pair<Float, Float>>, val widthFraction: Float, val color: Int) : Annotation(page)
    class Signature(page: Int, val bitmap: Bitmap, val rect: RectF) : Annotation(page)

    /** Moves the annotation by fractions of the page. */
    fun offset(dx: Float, dy: Float) {
        when (this) {
            is Text -> { x += dx; y += dy }
            is Check -> { x += dx; y += dy }
            is Signature -> rect.offset(dx, dy)
            is Stroke -> Unit
        }
    }
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
