package app.olauncher.ui.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.models.selection.SelectionBoundary
import android.os.Build
import androidx.annotation.RequiresApi
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The text of a PDF, to search it and to select and copy it. Boxes are fractions of the page
 * (before any rotation made in "Pages").
 *
 * - Pages with real text: on Android 15+ the system's PDF engine (the one that draws the pages,
 *   so the boxes match exactly); before, PdfBox ([PdfSearch]), search only.
 * - Scanned pages (no text): text recognition (OCR) with ML Kit, on demand; the result is kept in
 *   the cache, so the next time the document opens it's ready.
 */
class PdfText(private val context: Context, private val file: File, private val renderer: PdfRenderer) {

    /** A recognised word, in reading order; [line] tells the lines apart. */
    class Word(val text: String, val box: RectF, val line: Int)

    /** Selected text and its boxes, one per line. */
    class Selection(val text: String, val rects: List<RectF>)

    private val hasText = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()
    private val ocr = java.util.concurrent.ConcurrentHashMap<Int, List<Word>>()
    private var pdfBox: PdfSearch? = null
    private var pdfBoxLoaded = false
    private val cacheFile = File(File(context.cacheDir, "ocr").apply { mkdirs() }, "${file.name}-${file.length()}-${file.lastModified()}.json")

    init {
        loadCache()
    }

    val pageCount get() = renderer.pageCount

    fun isRecognised(page: Int) = ocr.containsKey(page)

    /** Whether [page] has text of its own (else it's a scan to recognise). Blocking. */
    fun pageHasText(page: Int): Boolean = hasText.getOrPut(page) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) withPage(page) { p -> NativePdfText.hasText(p) } ?: false
        else pdfBox()?.hasText(page) ?: false
    }

    private fun pdfBox(): PdfSearch? {
        if (!pdfBoxLoaded) {
            pdfBoxLoaded = true
            pdfBox = PdfSearch.load(context, file)
        }
        return pdfBox
    }

    private fun <T> withPage(page: Int, block: (PdfRenderer.Page) -> T): T? = try {
        synchronized(renderer) { renderer.openPage(page).use { block(it) } }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }

    // ---------------------------------------------------------------- search

    /** Matches of [query] on [page]. Blocking. */
    fun search(page: Int, query: String): List<PdfSearch.Hit> {
        ocr[page]?.let { return searchWords(page, it, query) }
        if (!pageHasText(page)) return emptyList()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) withPage(page) { p -> NativePdfText.search(page, p, query) } ?: emptyList()
        else pdfBox()?.find(query, page) ?: emptyList()
    }

    private fun searchWords(page: Int, words: List<Word>, query: String): List<PdfSearch.Hit> {
        val q = fold(query.trim().replace(Regex("\\s+"), " "))
        if (q.isEmpty() || words.isEmpty()) return emptyList()
        // The words joined by spaces, remembering which word each character belongs to
        val text = StringBuilder()
        val owner = ArrayList<Int>()
        words.forEachIndexed { i, word ->
            if (i > 0) {
                text.append(' ')
                owner += -1
            }
            fold(word.text).forEach { c ->
                text.append(c)
                owner += i
            }
        }
        val hits = mutableListOf<PdfSearch.Hit>()
        var from = 0
        while (true) {
            val at = text.indexOf(q, from)
            if (at < 0) break
            val covered = (at until at + q.length).map { owner[it] }.filter { it >= 0 }.distinct()
            if (covered.isNotEmpty()) hits += PdfSearch.Hit(page, mergeLines(covered.map { words[it].box }))
            from = at + q.length
        }
        return hits
    }

    // ---------------------------------------------------------------- selection

    /** The text between the points [a] and [b] (fractions of the page) in reading order. Blocking. */
    fun select(page: Int, a: PointF, b: PointF): Selection? {
        ocr[page]?.let { return selectWords(it, a, b) }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM || !pageHasText(page)) return null
        return withPage(page) { p -> NativePdfText.select(p, a, b) }
    }

    private fun selectWords(words: List<Word>, a: PointF, b: PointF): Selection? {
        if (words.isEmpty()) return null
        var i = nearest(words, a)
        var j = nearest(words, b)
        if (i > j) i = j.also { j = i }
        val chosen = words.subList(i, j + 1)
        val text = buildString {
            chosen.forEachIndexed { k, word ->
                if (k > 0) append(if (word.line != chosen[k - 1].line) '\n' else ' ')
                append(word.text)
            }
        }
        return Selection(text, mergeLines(chosen.map { it.box }))
    }

    /** The word under the point, else the closest one. */
    private fun nearest(words: List<Word>, p: PointF): Int {
        words.indexOfFirst { it.box.contains(p.x, p.y) }.takeIf { it >= 0 }?.let { return it }
        return words.indices.minByOrNull { i ->
            val r = words[i].box
            val dx = maxOf(r.left - p.x, 0f, p.x - r.right)
            // Lines count more than columns: the point belongs to the line it's on
            val dy = maxOf(r.top - p.y, 0f, p.y - r.bottom) * 3f
            dx * dx + dy * dy
        } ?: 0
    }

    // ---------------------------------------------------------------- OCR

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Recognises the text of a scanned [page] (once; kept in the cache). False if it failed. */
    suspend fun recognise(page: Int): Boolean {
        if (ocr.containsKey(page)) return true
        val bitmap = renderForOcr(page) ?: return false
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val result = try {
            suspendCancellableCoroutine<Text> { c ->
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { c.resume(it) }
                    .addOnFailureListener { c.resumeWithException(it) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            bitmap.recycle()
        }
        val words = mutableListOf<Word>()
        var line = 0
        result.textBlocks.forEach { block ->
            block.lines.forEach { l ->
                l.elements.forEach { e ->
                    val r = e.boundingBox ?: return@forEach
                    words += Word(e.text, RectF(r.left / w, r.top / h, r.right / w, r.bottom / h), line)
                }
                line++
            }
        }
        synchronized(this) {
            ocr[page] = words
            saveCache()
        }
        return true
    }

    /** The page on white, about 2000 px on its long side (enough for small print). */
    private fun renderForOcr(page: Int): Bitmap? = withPage(page) { p ->
        val scale = 2000f / maxOf(p.width, p.height)
        val b = Bitmap.createBitmap((p.width * scale).toInt().coerceAtLeast(1), (p.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        b.eraseColor(Color.WHITE)
        p.render(b, null, Matrix().apply { setScale(scale, scale) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        b
    }

    private fun loadCache() {
        try {
            if (!cacheFile.exists()) return
            val o = JSONObject(cacheFile.readText())
            o.keys().forEach { key ->
                val a = o.getJSONArray(key)
                ocr[key.toInt()] = (0 until a.length()).map { i ->
                    val w = a.getJSONArray(i)
                    Word(w.getString(0), RectF(w.getDouble(1).toFloat(), w.getDouble(2).toFloat(), w.getDouble(3).toFloat(), w.getDouble(4).toFloat()), w.getInt(5))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveCache() {
        try {
            val o = JSONObject()
            ocr.forEach { (page, words) ->
                o.put(page.toString(), JSONArray().also { a ->
                    words.forEach { w -> a.put(JSONArray().put(w.text).put(w.box.left.toDouble()).put(w.box.top.toDouble()).put(w.box.right.toDouble()).put(w.box.bottom.toDouble()).put(w.line)) }
                })
            }
            cacheFile.writeText(o.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    companion object {
        /** Boxes on the same line joined into one. */
        fun mergeLines(boxes: List<RectF>): List<RectF> {
            val out = mutableListOf<RectF>()
            boxes.forEach { b ->
                val last = out.lastOrNull()
                if (last != null && b.top < last.bottom && b.bottom > last.top) last.union(b) else out += RectF(b)
            }
            return out
        }

        /** Lower case without accents, one character for one. */
        fun fold(s: String): String = buildString(s.length) {
            s.forEach { c ->
                val base = Normalizer.normalize(c.toString(), Normalizer.Form.NFD).firstOrNull() ?: c
                append(base.lowercaseChar().let { if (it.isWhitespace()) ' ' else it })
            }
        }
    }
}

/** The system's PDF text (Android 15+): same engine as the drawing, so the boxes match the page. */
@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
private object NativePdfText {

    fun hasText(p: PdfRenderer.Page): Boolean = p.textContents.any { it.text.isNotBlank() }

    fun search(page: Int, p: PdfRenderer.Page, query: String): List<PdfSearch.Hit> {
        val w = p.width.toFloat()
        val h = p.height.toFloat()
        return p.searchText(query.trim()).map { m ->
            PdfSearch.Hit(page, PdfText.mergeLines(m.bounds.map { RectF(it.left / w, it.top / h, it.right / w, it.bottom / h) }))
        }.filter { it.rects.isNotEmpty() }
    }

    fun select(p: PdfRenderer.Page, a: PointF, b: PointF): PdfText.Selection? {
        val w = p.width.toFloat()
        val h = p.height.toFloat()
        val start = SelectionBoundary(Point((a.x * w).toInt(), (a.y * h).toInt()))
        val stop = SelectionBoundary(Point((b.x * w).toInt(), (b.y * h).toInt()))
        val selection = p.selectContent(start, stop) ?: return null
        val contents = selection.selectedTextContents
        val text = contents.joinToString("\n") { it.text }.trim()
        if (text.isEmpty()) return null
        val rects = contents.flatMap { c -> c.bounds.map { RectF(it.left / w, it.top / h, it.right / w, it.bottom / h) } }
        return PdfText.Selection(text, PdfText.mergeLines(rects))
    }
}
