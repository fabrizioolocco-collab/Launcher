package app.olauncher.ui.pdf

import android.content.Context
import android.graphics.Bitmap
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.util.Matrix
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/**
 * Writes the edited PDF: pages reordered, deleted or rotated, and the annotations drawn straight
 * into each page's content (so they can't be removed later), with the original text kept intact.
 * Annotation coordinates are fractions of the page as displayed (rotation applied).
 */
object PdfSaver {

    fun save(context: Context, source: File, target: File, layout: PageLayout, annotations: List<Annotation>) {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(source).use { doc ->
            // Draw annotations into their original pages first (indexes refer to the original order)
            annotations.groupBy { it.page }.forEach { (pageIndex, items) ->
                val page = doc.getPage(pageIndex)
                val rotation = ((page.rotation + (layout.rotation[pageIndex] ?: 0)) % 360 + 360) % 360
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    items.forEach { draw(doc, cs, page, rotation, it) }
                }
            }
            // Apply rotation
            layout.rotation.forEach { (index, extra) -> if (extra % 360 != 0) doc.getPage(index).rotation = doc.getPage(index).rotation + extra }
            // New document in the chosen order (import keeps the pages, so annotations come along)
            PDDocument().use { out ->
                layout.order.forEach { index -> out.importPage(doc.getPage(index)) }
                out.save(target)
            }
        }
    }

    /**
     * Maps a point given as fractions of the displayed page (x right, y down, rotation applied) to
     * PDF user space of the unrotated page (origin bottom-left).
     */
    private fun toPdf(page: PDPage, rotation: Int, fx: Float, fy: Float): Pair<Float, Float> {
        val box = page.mediaBox
        val w = box.width
        val h = box.height
        return when (rotation) {
            90 -> Pair(box.lowerLeftX + fy * w, box.lowerLeftY + fx * h)
            180 -> Pair(box.lowerLeftX + (1 - fx) * w, box.lowerLeftY + fy * h)
            270 -> Pair(box.lowerLeftX + (1 - fy) * w, box.lowerLeftY + (1 - fx) * h)
            else -> Pair(box.lowerLeftX + fx * w, box.lowerLeftY + (1 - fy) * h)
        }
    }

    /** Displayed page width in PDF points (after rotation), to turn size fractions into points. */
    private fun displayedWidth(page: PDPage, rotation: Int) = if (rotation % 180 == 0) page.mediaBox.width else page.mediaBox.height

    private fun draw(doc: PDDocument, cs: PDPageContentStream, page: PDPage, rotation: Int, a: Annotation) {
        when (a) {
            is Annotation.Stroke -> {
                if (a.points.size < 2) return
                cs.setStrokingColor(a.color.r(), a.color.g(), a.color.b())
                cs.setLineWidth(max(0.5f, a.widthFraction * displayedWidth(page, rotation)))
                cs.setLineCapStyle(1)
                cs.setLineJoinStyle(1)
                val first = toPdf(page, rotation, a.points[0].first, a.points[0].second)
                cs.moveTo(first.first, first.second)
                a.points.drop(1).forEach { (x, y) ->
                    val p = toPdf(page, rotation, x, y)
                    cs.lineTo(p.first, p.second)
                }
                cs.stroke()
            }

            is Annotation.Check -> {
                val size = a.sizeFraction * displayedWidth(page, rotation)
                cs.setStrokingColor(a.color.r(), a.color.g(), a.color.b())
                cs.setLineWidth(max(1f, size / 8f))
                cs.setLineCapStyle(1)
                // A check mark drawn in displayed space (fractions), mapped point by point
                val pts = listOf(-0.4f to 0.05f, -0.1f to 0.4f, 0.45f to -0.4f)
                val sx = a.sizeFraction
                val sy = a.sizeFraction * displayedWidth(page, rotation) / (if (rotation % 180 == 0) page.mediaBox.height else page.mediaBox.width)
                val mapped = pts.map { (dx, dy) -> toPdf(page, rotation, a.x + dx * sx, a.y + dy * sy) }
                cs.moveTo(mapped[0].first, mapped[0].second)
                cs.lineTo(mapped[1].first, mapped[1].second)
                cs.lineTo(mapped[2].first, mapped[2].second)
                cs.stroke()
            }

            is Annotation.Text -> {
                val fontSize = max(4f, a.sizeFraction * displayedWidth(page, rotation))
                val (x, y) = toPdf(page, rotation, a.x, a.y)
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, fontSize)
                cs.setNonStrokingColor(a.color.r(), a.color.g(), a.color.b())
                // Text runs along the displayed horizontal: rotate it with the page
                cs.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(rotation.toDouble()), x, y))
                cs.showText(a.text.filter { it.code in 32..255 })
                cs.endText()
            }

            is Annotation.Signature -> {
                val image = LosslessFactory.createFromImage(doc, a.bitmap.ensureArgb())
                val w = a.rect.width() * displayedWidth(page, rotation)
                val h = a.rect.height() * (if (rotation % 180 == 0) page.mediaBox.height else page.mediaBox.width)
                // Bottom-left corner of the displayed rectangle, then rotate the image with the page
                val (x, y) = toPdf(page, rotation, a.rect.left, a.rect.bottom)
                val m = Matrix.getRotateInstance(Math.toRadians(rotation.toDouble()), x, y)
                m.concatenate(Matrix.getScaleInstance(w, h))
                cs.drawImage(image, m)
            }
        }
    }

    private fun Int.r() = android.graphics.Color.red(this) / 255f
    private fun Int.g() = android.graphics.Color.green(this) / 255f
    private fun Int.b() = android.graphics.Color.blue(this) / 255f

    private fun Bitmap.ensureArgb(): Bitmap = if (config == Bitmap.Config.ARGB_8888) this else copy(Bitmap.Config.ARGB_8888, false)

    /** Page count, or -1 when the file can't be read (encrypted, damaged). */
    fun pageCount(context: Context, file: File): Int = try {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(file).use { it.numberOfPages }
    } catch (e: Throwable) {
        -1
    }

    @Suppress("unused")
    private fun Bitmap.toPng(): ByteArray = ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
}
