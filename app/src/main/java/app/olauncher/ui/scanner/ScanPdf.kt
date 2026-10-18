package app.olauncher.ui.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceGray
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.DeflaterOutputStream
import kotlin.math.max
import kotlin.math.min

/** A scanned sheet: the photo cropped by the scanner and the turn given in the review. */
class ScanPage(val file: File, var rotation: Int = 0)

/**
 * Turns scanned pages into a PDF: each photo becomes a page, in colour (JPEG) or black and white
 * (1 bit, crisp and small), on an A4 sheet or at the photo's own proportions. With [Options.searchable]
 * the recognised text (ML Kit OCR) is written invisibly over the image, so the PDF can be searched
 * and its text selected and copied.
 */
object ScanPdf {

    class Options(val blackWhite: Boolean, val a4: Boolean, val searchable: Boolean)

    /** Long side of the page images: about 190 dpi on an A4 sheet. */
    private const val MAX_SIDE = 2200
    private const val A4_W = 595.28f
    private const val A4_H = 841.89f
    private val font = PDType1Font.HELVETICA

    /** Writes [pages] to [target]; [progress] gets the page being made (1-based) and the count. Blocking. */
    fun write(context: Context, pages: List<ScanPage>, target: File, options: Options, progress: (Int, Int) -> Unit) {
        PDFBoxResourceLoader.init(context.applicationContext)
        val recognizer = if (options.searchable) TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) else null
        try {
            PDDocument().use { doc ->
                pages.forEachIndexed { i, page ->
                    progress(i + 1, pages.size)
                    val bitmap = load(page.file, MAX_SIDE, page.rotation) ?: return@forEachIndexed
                    val text = recognizer?.let { r ->
                        try {
                            Tasks.await(r.process(InputImage.fromBitmap(bitmap, 0)))
                        } catch (e: Exception) {
                            e.printStackTrace()
                            null
                        }
                    }
                    addPage(doc, bitmap, text, options)
                    bitmap.recycle()
                }
                doc.save(target)
            }
        } finally {
            recognizer?.close()
        }
    }

    private fun addPage(doc: PDDocument, bitmap: Bitmap, text: Text?, options: Options) {
        val w = bitmap.width
        val h = bitmap.height
        val landscape = w > h
        // The page and where the image goes on it, in points
        val pageW: Float
        val pageH: Float
        val scale: Float
        if (options.a4) {
            pageW = if (landscape) A4_H else A4_W
            pageH = if (landscape) A4_W else A4_H
            scale = min(pageW / w, pageH / h)
        } else {
            scale = A4_H / max(w, h)
            pageW = w * scale
            pageH = h * scale
        }
        val iw = w * scale
        val ih = h * scale
        val ix = (pageW - iw) / 2f
        val iy = (pageH - ih) / 2f
        val page = PDPage(PDRectangle(pageW, pageH))
        doc.addPage(page)
        val image = if (options.blackWhite) bilevelImage(doc, bitmap) else {
            val bytes = ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
                out.toByteArray()
            }
            JPEGFactory.createFromByteArray(doc, bytes)
        }
        PDPageContentStream(doc, page).use { cs ->
            cs.drawImage(image, ix, iy, iw, ih)
            if (text != null) writeText(cs, text, ix, iy, ih, scale)
        }
    }

    /**
     * The recognised lines, invisible, each stretched over its box on the image: the page looks the
     * same, but search, selection and copy find the words where they are seen.
     */
    private fun writeText(cs: PDPageContentStream, text: Text, ix: Float, iy: Float, ih: Float, scale: Float) {
        text.textBlocks.forEach { block ->
            block.lines.forEach { line ->
                val box = line.boundingBox ?: return@forEach
                val s = encodable(line.text.trim())
                if (s.isEmpty() || box.width() <= 0 || box.height() <= 0) return@forEach
                val fontSize = box.height() * scale * 0.9f
                val width = font.getStringWidth(s) / 1000f * fontSize
                if (width <= 0f) return@forEach
                val x = ix + box.left * scale
                // PDF y goes up: the bottom of the box, raised by the font's descent to reach the baseline
                val baseline = iy + ih - box.bottom * scale + fontSize * 0.21f
                cs.beginText()
                cs.setRenderingMode(RenderingMode.NEITHER)
                cs.setFont(font, fontSize)
                cs.setHorizontalScaling(box.width() * scale / width * 100f)
                cs.newLineAtOffset(x, baseline)
                cs.showText(s)
                cs.endText()
            }
        }
    }

    /** The text with only the characters the standard font can write (accents and € included). */
    private fun encodable(s: String): String = try {
        font.encode(s)
        s
    } catch (e: Exception) {
        s.filter { c ->
            try {
                font.encode(c.toString())
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    /** Black and white as a 1-bit image (0 black, 1 white), rows packed in bytes and deflated. */
    private fun bilevelImage(doc: PDDocument, bitmap: Bitmap): PDImageXObject {
        val w = bitmap.width
        val h = bitmap.height
        val black = blackMask(bitmap)
        val rowBytes = (w + 7) / 8
        val packed = ByteArrayOutputStream()
        DeflaterOutputStream(packed).use { out ->
            val row = ByteArray(rowBytes)
            for (y in 0 until h) {
                row.fill(0)
                val base = y * w
                for (x in 0 until w) {
                    if (!black[base + x]) row[x shr 3] = (row[x shr 3].toInt() or (0x80 ushr (x and 7))).toByte()
                }
                out.write(row)
            }
        }
        return PDImageXObject(doc, ByteArrayInputStream(packed.toByteArray()), COSName.FLATE_DECODE, w, h, 1, PDDeviceGray.INSTANCE)
    }

    /**
     * Which pixels are ink. Each pixel is compared with the paper around it (the brightest values
     * in a grid of blocks, smoothed), so shadows and uneven light don't turn grey paper black,
     * and faint print still comes out; large dark areas (logos, stamps) stay black.
     */
    fun blackMask(bitmap: Bitmap): BooleanArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            gray[i] = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
        }
        // Paper brightness per block: the brightest pixel of the block
        val block = max(8, max(w, h) / 40)
        val bw = (w + block - 1) / block
        val bh = (h + block - 1) / block
        val paper = IntArray(bw * bh)
        for (y in 0 until h) {
            val by = y / block
            val base = y * w
            for (x in 0 until w) {
                val k = by * bw + x / block
                if (gray[base + x] > paper[k]) paper[k] = gray[base + x]
            }
        }
        // Smoothed with the neighbours, so one dark block (a logo) takes the paper around it
        val smooth = IntArray(bw * bh)
        for (by in 0 until bh) for (bx in 0 until bw) {
            var best = 0
            var sum = 0
            var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val yy = by + dy
                val xx = bx + dx
                if (yy !in 0 until bh || xx !in 0 until bw) continue
                val v = paper[yy * bw + xx]
                sum += v
                n++
                if (v > best) best = v
            }
            smooth[by * bw + bx] = (best + sum / n) / 2
        }
        val sorted = smooth.sorted()
        val globalPaper = sorted[sorted.size / 2].coerceAtLeast(1)
        val out = BooleanArray(w * h)
        for (y in 0 until h) {
            // Paper value interpolated between block centres
            val fy = ((y + 0.5f) / block - 0.5f).coerceIn(0f, (bh - 1).toFloat())
            val y0 = fy.toInt()
            val y1 = min(y0 + 1, bh - 1)
            val ty = fy - y0
            val base = y * w
            for (x in 0 until w) {
                val fx = ((x + 0.5f) / block - 0.5f).coerceIn(0f, (bw - 1).toFloat())
                val x0 = fx.toInt()
                val x1 = min(x0 + 1, bw - 1)
                val tx = fx - x0
                val top = smooth[y0 * bw + x0] * (1 - tx) + smooth[y0 * bw + x1] * tx
                val bottom = smooth[y1 * bw + x0] * (1 - tx) + smooth[y1 * bw + x1] * tx
                val local = max(1f, top * (1 - ty) + bottom * ty)
                val g = gray[base + x]
                out[base + x] = g < local * 0.80f || g < globalPaper * 0.35f
            }
        }
        return out
    }

    /** [bitmap] as it will look in black and white, for the thumbnails. */
    fun blackWhitePreview(bitmap: Bitmap): Bitmap {
        val mask = blackMask(bitmap)
        val pixels = IntArray(mask.size) { if (mask[it]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    }

    /** The photo at most [maxSide] px on its long side, turned by [rotation] degrees; null if unreadable. */
    fun load(file: File, maxSide: Int, rotation: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val long = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (long / (sample * 2) >= maxSide) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        if (decoded == null) null
        else {
            val s = min(1f, maxSide.toFloat() / max(decoded.width, decoded.height))
            if (s == 1f && rotation % 360 == 0) decoded
            else {
                val m = Matrix().apply {
                    setScale(s, s)
                    postRotate(rotation.toFloat())
                }
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true).also { if (it !== decoded) decoded.recycle() }
            }
        }
    } catch (e: Throwable) {
        e.printStackTrace()
        null
    }
}
