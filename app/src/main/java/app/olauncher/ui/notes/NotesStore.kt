package app.olauncher.ui.notes

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The notes on the disk, in the folder the user chose: each note a small page (`title.html`), each
 * folder of the app a real folder. What a note carries with it (images, drawings, attached files)
 * sits in an `_allegati` folder beside it, deleted notes go to `_cestino` at the top, and
 * `_note.json` there keeps which notes are pinned. Names that start with `_` or `.` are never shown.
 *
 * The same folder is opened on a computer by the `Note.html` copied into it: the page that is also
 * this app's editor (see NoteEditorActivity), so the two agree on every file by construction.
 */
object NotesStore {

    const val FILES = "_allegati"
    private const val TRASH = "_cestino"
    private const val INDEX = "_note.json"
    private const val PAGE = "Note.html"
    private const val START = "<!--note-->"
    private const val END = "<!--/note-->"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("notes", Context.MODE_PRIVATE)

    fun root(ctx: Context): File =
        prefs(ctx).getString("root", null)?.let { File(it) }
            ?: File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Note")

    fun setRoot(ctx: Context, dir: File) = prefs(ctx).edit().putString("root", dir.absolutePath).apply()

    /** The folders shut in the tree, by path: kept on this phone only. */
    fun shut(ctx: Context): MutableSet<String> = prefs(ctx).getStringSet("shut", emptySet()).orEmpty().toMutableSet()
    fun setShut(ctx: Context, set: Set<String>) = prefs(ctx).edit().putStringSet("shut", set).apply()

    private fun hidden(name: String) = name.startsWith("_") || name.startsWith(".")
    fun isNote(file: File) = file.isFile && file.extension.equals("html", true)
    fun titleOf(file: File) = file.nameWithoutExtension

    class Folder(val dir: File, val folders: List<Folder>, val notes: List<File>) {
        /** Notes in this folder and in the ones inside it. */
        val count: Int get() = notes.size + folders.sumOf { it.count }
        fun all(): List<File> = notes + folders.flatMap { it.all() }
    }

    fun scan(dir: File, top: Boolean = true): Folder {
        val entries = dir.listFiles()?.filter { !hidden(it.name) }.orEmpty()
        return Folder(
            dir,
            entries.filter { it.isDirectory }.sortedBy { it.name.lowercase() }.map { scan(it, false) },
            entries.filter { isNote(it) && !(top && it.name == PAGE) }.sortedByDescending { it.lastModified() },
        )
    }

    // ---------------------------------------------------------------- pinned notes

    private fun rel(root: File, file: File) = file.relativeTo(root).invariantSeparatorsPath

    fun pins(root: File): List<String> = try {
        val array = JSONObject(File(root, INDEX).readText()).optJSONArray("pinned") ?: JSONArray()
        (0 until array.length()).map { array.getString(it) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun savePins(root: File, pins: List<String>) = try {
        File(root, INDEX).writeText(JSONObject().put("pinned", JSONArray(pins.distinct())).toString(1))
    } catch (e: Exception) {
        e.printStackTrace()
    }

    fun isPinned(root: File, note: File) = rel(root, note) in pins(root)

    fun setPinned(root: File, note: File, on: Boolean) {
        val key = rel(root, note)
        savePins(root, if (on) pins(root) + key else pins(root) - key)
    }

    /** A note (or a whole folder) went from [from] to [to]: its pins follow it. */
    private fun movePins(root: File, from: File, to: File?) {
        val old = rel(root, from)
        val pins = pins(root)
        val moved = pins.mapNotNull { pin ->
            when {
                pin == old -> to?.let { rel(root, it) }
                pin.startsWith("$old/") -> to?.let { rel(root, it) + pin.substring(old.length) }
                else -> pin
            }
        }
        if (moved != pins) savePins(root, moved)
    }

    // ---------------------------------------------------------------- making, renaming, moving

    private fun clean(name: String) = name.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), " ").trim().take(120)

    /** A name not taken in [dir]: [wanted], or with a number after it. */
    fun free(dir: File, wanted: String): File {
        val stem = wanted.substringBeforeLast('.', wanted)
        val ext = if ('.' in wanted) "." + wanted.substringAfterLast('.') else ""
        var i = 1
        while (true) {
            val file = File(dir, if (i == 1) wanted else "$stem $i$ext")
            if (!file.exists()) return file
            i++
        }
    }

    /** An empty note in [dir]; the editor writes it as soon as something is typed. */
    fun newNote(dir: File, title: String): File {
        dir.mkdirs()
        return free(dir, clean(title).ifBlank { "Nota" } + ".html").apply { writeText("") }
    }

    /** A notebook to write in by hand: pages of squared paper, filled in by the editor. */
    fun newNotebook(dir: File, title: String): File {
        dir.mkdirs()
        return free(dir, clean(title).ifBlank { "Quaderno" } + ".html").apply {
            writeText("<!doctype html>\n<html><head><meta charset=\"utf-8\"></head>\n<body>\n$START\n<div class=\"notebook\" data-paper=\"grid\"></div>\n$END\n</body></html>\n")
        }
    }

    fun newFolder(dir: File, name: String): File? {
        val safe = clean(name)
        if (safe.isBlank() || hidden(safe)) return null
        return File(dir, safe).takeIf { it.isDirectory || it.mkdirs() }
    }

    /** The note under a new title; the same file when the title is not one a file can have. */
    fun rename(root: File, note: File, title: String): File {
        val safe = clean(title)
        if (safe.isBlank() || hidden(safe) || safe == titleOf(note)) return note
        val target = free(note.parentFile ?: return note, "$safe.html")
        if (!note.renameTo(target)) return note
        movePins(root, note, target)
        return target
    }

    fun renameFolder(root: File, dir: File, name: String): File {
        val safe = clean(name)
        if (safe.isBlank() || hidden(safe) || safe == dir.name) return dir
        val target = File(dir.parentFile ?: return dir, safe)
        if (target.exists() || !dir.renameTo(target)) return dir
        movePins(root, dir, target)
        return target
    }

    /** What a note links to in the `_allegati` beside it. */
    private fun attachments(note: File): List<File> {
        val dir = File(note.parentFile, FILES)
        val text = try {
            note.readText()
        } catch (e: Exception) {
            return emptyList()
        }
        return Regex("(?:src|href)=\"$FILES/([^\"]+)\"").findAll(text)
            .map { File(dir, java.net.URLDecoder.decode(it.groupValues[1].replace("+", "%2B"), "UTF-8")) }
            .filter { it.isFile }.distinct().toList()
    }

    /** Moves a note into [dest], with the files it carries; null when it could not be moved. */
    fun move(root: File, note: File, dest: File): File? {
        if (dest == note.parentFile) return note
        dest.mkdirs()
        val carried = attachments(note)
        val target = free(dest, note.name)
        if (!note.renameTo(target)) return null
        if (carried.isNotEmpty()) {
            val files = File(dest, FILES).apply { mkdirs() }
            // Same names on the other side, or the note's links would point nowhere
            carried.forEach { file -> if (!File(files, file.name).exists()) file.renameTo(File(files, file.name)) }
        }
        movePins(root, note, target)
        return target
    }

    /** Moves a whole folder into [dest]; null when it can't go there (into itself, or a name already taken). */
    fun moveFolder(root: File, dir: File, dest: File): File? {
        if (dest == dir.parentFile) return dir
        if (dest == dir || dest.absolutePath.startsWith(dir.absolutePath + File.separator)) return null
        val target = File(dest, dir.name)
        if (target.exists() || !dir.renameTo(target)) return null
        movePins(root, dir, target)
        return target
    }

    // ---------------------------------------------------------------- the bin

    private fun bin(root: File) = File(root, TRASH)

    fun trash(root: File, note: File): Boolean {
        val bin = bin(root).apply { mkdirs() }
        // Its images go with it, into the bin's own folder of files, so that it comes back whole
        val carried = attachments(note)
        if (!note.renameTo(free(bin, note.name))) return false
        if (carried.isNotEmpty()) {
            val files = File(bin, FILES).apply { mkdirs() }
            carried.forEach { file -> if (!File(files, file.name).exists()) file.renameTo(File(files, file.name)) }
        }
        movePins(root, note, null)
        return true
    }

    /** Deletes a folder: its notes go to the bin, the rest (their files, empty folders) with it. */
    fun trashFolder(root: File, dir: File) {
        scan(dir, false).all().forEach { trash(root, it) }
        dir.deleteRecursively()
        movePins(root, dir, null)
    }

    fun trashed(root: File): List<File> = bin(root).listFiles()?.filter { isNote(it) }?.sortedByDescending { it.lastModified() }.orEmpty()

    fun restore(root: File, note: File): File? = move(root, note, root)

    fun emptyTrash(root: File) {
        bin(root).deleteRecursively()
    }

    // ---------------------------------------------------------------- reading

    /** What is written in a note, as the editor keeps it. */
    fun body(note: File): String {
        val text = try {
            note.readText()
        } catch (e: Exception) {
            return ""
        }
        val a = text.indexOf(START)
        val b = text.lastIndexOf(END)
        if (a >= 0 && b > a) return text.substring(a + START.length, b).trim()
        // A page made elsewhere: what its body holds
        return Regex("<body[^>]*>([\\s\\S]*)</body>", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1).orEmpty()
    }

    /** The words of a note, without its markup: what a search looks in. */
    fun text(note: File): String = body(note)
        .replace(Regex("<(br|/p|/div|/li|/h[1-6]|/tr|/td)[^>]*>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace(Regex("\\s+"), " ").trim()

    class Hit(val note: File, val snippet: String)

    /** Notes whose title or text has every word of [query]. */
    fun search(root: File, query: String): List<Hit> {
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return scan(root).all().mapNotNull { note ->
            val text = text(note)
            val all = (titleOf(note) + " " + text).lowercase()
            if (!words.all { it in all }) return@mapNotNull null
            val at = text.lowercase().indexOf(words.first()).coerceAtLeast(0)
            val from = (at - 30).coerceAtLeast(0)
            Hit(note, (if (from > 0) "…" else "") + text.substring(from, (from + 110).coerceAtMost(text.length)))
        }
    }

    // ---------------------------------------------------------------- files beside a note

    /** Copies [source] beside the note in [dir]; the path to write in the note. */
    fun attach(dir: File, source: File, name: String = source.name): String? = try {
        val files = File(dir, FILES).apply { mkdirs() }
        val target = free(files, clean(name).ifBlank { "file" })
        source.copyTo(target)
        "$FILES/${target.name}"
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }

    fun attachBytes(dir: File, bytes: ByteArray, name: String): String? = try {
        val files = File(dir, FILES).apply { mkdirs() }
        val target = free(files, clean(name).ifBlank { "file" })
        target.writeBytes(bytes)
        "$FILES/${target.name}"
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }

    /**
     * Puts in the folder the page that opens the notes on a computer, when it is not there or is
     * an older one (the app brings a newer page with an update).
     */
    fun putPage(ctx: Context, root: File) {
        try {
            root.mkdirs()
            val page = ctx.assets.open("notes/$PAGE").use { it.readBytes() }
            val file = File(root, PAGE)
            if (!file.isFile || file.length() != page.size.toLong()) file.writeBytes(page)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
