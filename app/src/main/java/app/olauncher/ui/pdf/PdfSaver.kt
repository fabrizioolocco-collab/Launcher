package app.olauncher.ui.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Writes the edited PDF: pages reordered, deleted or rotated, and the annotations drawn straight
 * into each page's content (so they can't be removed later), with the original text kept intact.
 *
 * Every annotation is drawn in "displayed page" space: a coordinate system where the page as the
 * user saw it (rotation applied) has its origin at the top-left, x to the right and y down, in
 * PDF points. One matrix maps that space onto the real page, so moves, rotations and resizes done
 * in the editor land exactly where they were seen.
 */
object PdfSaver {

    fun save(context: Context, source: File, target: File, layout: PageLayout, annotations: List<Annotation>) {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(source).use { doc ->
            annotations.groupBy { it.page }.forEach { (pageIndex, items) ->
                val page = doc.getPage(pageIndex)
                val rotation = ((page.rotation + (layout.rotation[pageIndex] ?: 0)) % 360 + 360) % 360
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.saveGraphicsState()
                    cs.transform(displayedToPage(page, rotation))
                    val dw = displayedWidth(page, rotation)
                    val dh = displayedHeight(page, rotation)
                    items.forEach { draw(doc, cs, dw, dh, it) }
                    cs.restoreGraphicsState()
                }
            }
            layout.rotation.forEach { (index, extra) -> if (extra % 360 != 0) doc.getPage(index).rotation = doc.getPage(index).rotation + extra }
            PDDocument().use { out ->
                layout.order.forEach { index -> out.importPage(doc.getPage(index)) }
                out.save(target)
            }
        }
    }

    private fun displayedWidth(page: PDPage, rotation: Int) = if (rotation % 180 == 0) page.mediaBox.width else page.mediaBox.height
    private fun displayedHeight(page: PDPage, rotation: Int) = if (rotation % 180 == 0) page.mediaBox.height else page.mediaBox.width

    /**
     * Matrix from displayed space (origin top-left, y down, rotation applied) to the page's user
     * space (origin bottom-left, y up, unrotated). Built from the point mapping used by the viewer.
     */
    private fun displayedToPage(page: PDPage, rotation: Int): Matrix {
        val box = page.mediaBox
        val w = box.width
        val h = box.height
        // Displayed (dx, dy) in points → page (px, py)
        return when (rotation) {
            90 -> Matrix(0f, 1f, 1f, 0f, box.lowerLeftX, box.lowerLeftY)                     // px = dy, py = dx
            180 -> Matrix(-1f, 0f, 0f, 1f, box.lowerLeftX + w, box.lowerLeftY)               // px = w - dx, py = dy
            270 -> Matrix(0f, -1f, -1f, 0f, box.lowerLeftX + w, box.lowerLeftY + h)          // px = w - dy, py = h - dx
            else -> Matrix(1f, 0f, 0f, -1f, box.lowerLeftX, box.lowerLeftY + h)              // px = dx, py = h - dy
        }
    }

    /** Rotation of [a] around its box centre, in displayed space (y down: angles flip sign). */
    private fun rotate(cs: PDPageContentStream, a: Annotation, dw: Float, dh: Float) {
        if (a.rotation == 0f) return
        val cx = a.box.centerX() * dw
        val cy = a.box.centerY() * dh
        val rad = Math.toRadians(a.rotation.toDouble())
        cs.transform(Matrix(cos(rad).toFloat(), sin(rad).toFloat(), -sin(rad).toFloat(), cos(rad).toFloat(),
            (cx - cx * cos(rad) + cy * sin(rad)).toFloat(), (cy - cx * sin(rad) - cy * cos(rad)).toFloat()))
    }

    private fun draw(doc: PDDocument, cs: PDPageContentStream, dw: Float, dh: Float, a: Annotation) {
        cs.saveGraphicsState()
        rotate(cs, a, dw, dh)
        when (a) {
            is Annotation.Stroke -> {
                val pts = a.currentPoints()
                if (pts.size >= 2) {
                    if (a.highlighter) {
                        val gs = PDExtendedGraphicsState().apply { strokingAlphaConstant = 0.35f }
                        cs.setGraphicsStateParameters(gs)
                    }
                    cs.setStrokingColor(a.color.r(), a.color.g(), a.color.b())
                    cs.setLineWidth(max(0.5f, a.widthFraction * dw))
                    cs.setLineCapStyle(if (a.highlighter) 0 else 1)
                    cs.setLineJoinStyle(1)
                    cs.moveTo(pts[0].first * dw, pts[0].second * dh)
                    pts.drop(1).forEach { (x, y) -> cs.lineTo(x * dw, y * dh) }
                    cs.stroke()
                }
            }

            is Annotation.Check -> {
                val b = a.box
                val w = b.width() * dw
                val h = b.height() * dh
                cs.setStrokingColor(a.color.r(), a.color.g(), a.color.b())
                cs.setLineWidth(max(1f, minOf(w, h) / 8f))
                cs.setLineCapStyle(1)
                if (a.cross) {
                    cs.moveTo(b.left * dw + w * 0.15f, b.top * dh + h * 0.15f)
                    cs.lineTo(b.left * dw + w * 0.85f, b.top * dh + h * 0.85f)
                    cs.stroke()
                    cs.moveTo(b.left * dw + w * 0.85f, b.top * dh + h * 0.15f)
                    cs.lineTo(b.left * dw + w * 0.15f, b.top * dh + h * 0.85f)
                    cs.stroke()
                } else {
                    cs.moveTo(b.left * dw + w * 0.1f, b.top * dh + h * 0.55f)
                    cs.lineTo(b.left * dw + w * 0.4f, b.top * dh + h * 0.9f)
                    cs.lineTo(b.left * dw + w * 0.95f, b.top * dh + h * 0.1f)
                    cs.stroke()
                }
            }

            is Annotation.Text -> {
                val fontSize = max(4f, a.box.height() * dh * 0.8f)
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, fontSize)
                cs.setNonStrokingColor(a.color.r(), a.color.g(), a.color.b())
                // Text is drawn with y up: flip it back inside our y-down space, at the box's baseline
                cs.setTextMatrix(Matrix(1f, 0f, 0f, -1f, a.box.left * dw, a.box.top * dh + fontSize))
                cs.showText(a.text.filter { it.code in 32..255 })
                cs.endText()
            }

            is Annotation.Signature -> {
                val image = LosslessFactory.createFromImage(doc, a.bitmap.ensureArgb())
                val w = a.box.width() * dw
                val h = a.box.height() * dh
                // Images are drawn with their bottom-left at the origin, y up: flip inside y-down space
                cs.drawImage(image, Matrix(w, 0f, 0f, -h, a.box.left * dw, a.box.top * dh + h))
            }
        }
        cs.restoreGraphicsState()
    }

    private fun Int.r() = Color.red(this) / 255f
    private fun Int.g() = Color.green(this) / 255f
    private fun Int.b() = Color.blue(this) / 255f

    private fun Bitmap.ensureArgb(): Bitmap = if (config == Bitmap.Config.ARGB_8888) this else copy(Bitmap.Config.ARGB_8888, false)

    /** Page count, or -1 when the file can't be read (encrypted, damaged). */
    fun pageCount(context: Context, file: File): Int = try {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(file).use { it.numberOfPages }
    } catch (e: Throwable) {
        -1
    }
}
