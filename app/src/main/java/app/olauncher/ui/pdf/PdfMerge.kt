package app.olauncher.ui.pdf

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File

/** Joins PDFs: the pages of the first, then those of the others in their order, saved as one file. */
object PdfMerge {

    /** False (and no [out]) when one of [files] cannot be read, e.g. it asks for a password. */
    fun merge(context: Context, files: List<File>, out: File): Boolean {
        if (files.isEmpty()) return false
        // The pages taken from the other files belong to them until the save: all stay open till then
        val opened = mutableListOf<PDDocument>()
        return try {
            PDFBoxResourceLoader.init(context.applicationContext)
            val doc = PDDocument.load(files.first())
            opened += doc
            doc.isAllSecurityToBeRemoved = true
            files.drop(1).forEach { file ->
                val other = PDDocument.load(file)
                opened += other
                other.pages.forEach { doc.importPage(it) }
            }
            doc.save(out)
            true
        } catch (e: Throwable) {
            e.printStackTrace()
            out.delete()
            false
        } finally {
            opened.forEach { runCatching { it.close() } }
        }
    }
}
