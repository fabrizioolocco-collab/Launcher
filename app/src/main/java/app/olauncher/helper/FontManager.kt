package app.olauncher.helper

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.util.TypedValue
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import app.olauncher.R
import java.io.File

/**
 * Fonts selectable for app names: system families, fonts bundled in res/font (Latin subsets of
 * Google Fonts, OFL) and font files imported by the user.
 *
 * A font is identified by a string id stored in Prefs; "" means "keep the default style".
 */
object FontManager {

    class FontOption(val id: String, val label: String)

    private val SYSTEM = listOf(
        "sys:sans-serif-light" to "Sistema light",
        "sys:sans-serif" to "Sistema",
        "sys:sans-serif-medium" to "Sistema medium",
        "sys:sans-serif-condensed" to "Sistema condensed",
        "sys:serif" to "Serif",
        "sys:monospace" to "Monospace",
    )

    private val BUNDLED = listOf(
        "res:ml_inter" to "Inter",
        "res:ml_roboto" to "Roboto",
        "res:ml_lato" to "Lato",
        "res:ml_montserrat" to "Montserrat",
        "res:ml_nunito" to "Nunito",
        "res:ml_poppins" to "Poppins",
        "res:ml_work_sans" to "Work Sans",
        "res:ml_playfair_display" to "Playfair Display",
        "res:ml_lora" to "Lora",
        "res:ml_jetbrains_mono" to "JetBrains Mono",
    )

    private val BUNDLED_RES = mapOf(
        "res:ml_inter" to R.font.ml_inter,
        "res:ml_roboto" to R.font.ml_roboto,
        "res:ml_lato" to R.font.ml_lato,
        "res:ml_montserrat" to R.font.ml_montserrat,
        "res:ml_nunito" to R.font.ml_nunito,
        "res:ml_poppins" to R.font.ml_poppins,
        "res:ml_work_sans" to R.font.ml_work_sans,
        "res:ml_playfair_display" to R.font.ml_playfair_display,
        "res:ml_lora" to R.font.ml_lora,
        "res:ml_jetbrains_mono" to R.font.ml_jetbrains_mono,
    )

    private const val FILE_PREFIX = "file:"
    private val cache = HashMap<String, Typeface?>()

    private fun fontsDir(context: Context) = File(context.filesDir, "fonts").apply { mkdirs() }

    /** All choices, imported fonts last. The default entry ("") is added by the caller. */
    fun options(context: Context): List<FontOption> {
        val imported = fontsDir(context).listFiles()?.sortedBy { it.name.lowercase() }.orEmpty()
            .map { FontOption(FILE_PREFIX + it.absolutePath, it.nameWithoutExtension) }
        return (SYSTEM + BUNDLED).map { FontOption(it.first, it.second) } + imported
    }

    fun label(context: Context, id: String): String {
        if (id.isBlank()) return context.getString(R.string.ml_default)
        return options(context).firstOrNull { it.id == id }?.label ?: context.getString(R.string.ml_default)
    }

    /** Returns null for the default style or when the font can't be loaded. */
    fun typeface(context: Context, id: String): Typeface? {
        if (id.isBlank()) return null
        synchronized(cache) { if (cache.containsKey(id)) return cache[id] }
        val tf = try {
            when {
                id.startsWith("sys:") -> Typeface.create(id.removePrefix("sys:"), Typeface.NORMAL)
                id.startsWith("res:") -> BUNDLED_RES[id]?.let { ResourcesCompat.getFont(context, it) }
                id.startsWith(FILE_PREFIX) -> File(id.removePrefix(FILE_PREFIX))
                    .takeIf { it.exists() }?.let { Typeface.createFromFile(it) }

                else -> null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
        synchronized(cache) { cache[id] = tf }
        return tf
    }

    /** Applies font and size (sp, 0 = keep) to [view]. */
    fun apply(view: TextView, fontId: String, sizeSp: Int) {
        typeface(view.context, fontId)?.let { view.typeface = it }
        if (sizeSp > 0) view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp.toFloat())
    }

    /** [text] rendered in the given font, used to preview fonts in menus. */
    fun preview(context: Context, id: String, text: String): CharSequence {
        val tf = typeface(context, id) ?: return text
        return SpannableString(text).apply {
            setSpan(TypefaceSpanCompat(tf), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** Copies a .ttf/.otf picked by the user into app storage. Returns the new font id, or null. Blocking. */
    fun importFont(context: Context, uri: Uri): String? {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: "font_${System.currentTimeMillis()}.ttf"
        val safeName = name.replace(Regex("[^A-Za-z0-9._ -]"), "_")
        val file = File(fontsDir(context), safeName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { input.copyTo(it) }
        } ?: return null
        return try {
            Typeface.createFromFile(file)
            val id = FILE_PREFIX + file.absolutePath
            synchronized(cache) { cache.remove(id) }
            id
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    private class TypefaceSpanCompat(private val tf: Typeface) : MetricAffectingSpan() {
        override fun updateDrawState(tp: TextPaint) = apply(tp)
        override fun updateMeasureState(tp: TextPaint) = apply(tp)
        private fun apply(paint: Paint) {
            paint.typeface = tf
        }
    }
}
