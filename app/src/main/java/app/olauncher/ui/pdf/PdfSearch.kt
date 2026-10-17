package app.olauncher.ui.pdf

import android.content.Context
import android.graphics.RectF
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.text.Normalizer

/**
 * Text search in a PDF: the text of each page with the box of every character (as fractions of
 * the page), read once with PdfBox; matches ignore case and accents.
 */
class PdfSearch private constructor(private val pages: List<PageText>) {

    /** A match: its page (original index) and its boxes, one per line it spans. */
    class Hit(val page: Int, val rects: List<RectF>)

    private class PageText(val text: String, val boxes: List<RectF?>)

    fun find(query: String): List<Hit> {
        val q = fold(query.trim().replace(Regex("\\s+"), " "))
        if (q.isEmpty()) return emptyList()
        val hits = mutableListOf<Hit>()
        pages.forEachIndexed { page, p ->
            var from = 0
            while (true) {
                val i = p.text.indexOf(q, from)
                if (i < 0) break
                lines(p.boxes.subList(i, i + q.length)).takeIf { it.isNotEmpty() }?.let { hits += Hit(page, it) }
                from = i + q.length
            }
        }
        return hits
    }

    /** The characters' boxes joined into one rectangle per line. */
    private fun lines(boxes: List<RectF?>): List<RectF> {
        val out = mutableListOf<RectF>()
        boxes.filterNotNull().forEach { b ->
            val last = out.lastOrNull()
            if (last != null && b.top < last.bottom && b.bottom > last.top) last.union(b) else out += RectF(b)
        }
        return out
    }

    /** Collects the characters of a page and their boxes; [width] x [height] is the page as rendered, in points. */
    private class Collector(private val width: Float, private val height: Float) : PDFTextStripper() {
        val text = StringBuilder()
        val boxes = ArrayList<RectF?>()

        override fun writeString(string: String?, textPositions: MutableList<TextPosition>?) {
            textPositions?.forEach { tp ->
                val unicode = tp.unicode ?: return@forEach
                val h = maxOf(tp.heightDir, tp.fontSizeInPt * 0.7f)
                val box = RectF(
                    tp.xDirAdj / width, (tp.yDirAdj - h) / height,
                    (tp.xDirAdj + tp.widthDirAdj) / width, (tp.yDirAdj + h * 0.25f) / height,
                )
                unicode.forEach { c ->
                    text.append(c)
                    boxes += box
                }
            }
        }

        override fun writeWordSeparator() = gap()
        override fun writeLineSeparator() = gap()

        private fun gap() {
            if (text.isNotEmpty() && text.last() != ' ') {
                text.append(' ')
                boxes += null
            }
        }
    }

    companion object {
        /** Reads the text of [file]; null if it can't be read (e.g. protected). Blocking. */
        fun load(context: Context, file: File): PdfSearch? = try {
            PDFBoxResourceLoader.init(context.applicationContext)
            PDDocument.load(file).use { doc ->
                (0 until doc.numberOfPages).map { i ->
                    val page = doc.getPage(i)
                    val turned = page.rotation % 180 != 0
                    val w = if (turned) page.cropBox.height else page.cropBox.width
                    val h = if (turned) page.cropBox.width else page.cropBox.height
                    val c = Collector(w, h).apply {
                        sortByPosition = true
                        startPage = i + 1
                        endPage = i + 1
                    }
                    c.getText(doc)
                    PageText(fold(c.text.toString()), c.boxes)
                }
            }.let { PdfSearch(it) }
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        }

        /** Lower case without accents, one character for one (so the indexes match the boxes). */
        private fun fold(s: String): String = buildString(s.length) {
            s.forEach { c ->
                val base = Normalizer.normalize(c.toString(), Normalizer.Form.NFD).firstOrNull() ?: c
                append(base.lowercaseChar().let { if (it.isWhitespace()) ' ' else it })
            }
        }
    }
}
