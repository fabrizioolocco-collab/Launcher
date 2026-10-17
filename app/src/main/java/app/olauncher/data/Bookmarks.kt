package app.olauncher.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A category of bookmarks; [parent] 0 = top level. */
data class BookmarkFolder(val id: Long, var name: String, var parent: Long = 0L)

/** A saved page; [folder] 0 = no category. [browser]: package to open it with, empty = the default browser. */
data class Bookmark(val id: Long, var title: String, var url: String, var folder: Long = 0L, var browser: String = "")

/**
 * The launcher's own bookmarks, in categories nested at will. Kept in one JSON file; the order of
 * the lists is the order shown.
 */
object BookmarkStore {
    private const val FILE = "bookmarks.json"
    private const val PREFS = "app.minimalauncher.bookmarks"
    private const val MAX_RECENT = 3

    private val folders = mutableListOf<BookmarkFolder>()
    private val bookmarks = mutableListOf<Bookmark>()
    private var nextId = 1L
    private var loaded = false

    @Synchronized
    private fun ensure(context: Context) {
        if (loaded) return
        loaded = true
        val file = File(context.applicationContext.filesDir, FILE)
        if (!file.exists()) return
        try {
            val o = JSONObject(file.readText())
            nextId = o.optLong("next", 1L)
            o.optJSONArray("folders")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let { folders += BookmarkFolder(it.getLong("id"), it.optString("name"), it.optLong("parent")) }
            }
            o.optJSONArray("items")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let {
                    bookmarks += Bookmark(it.getLong("id"), it.optString("title"), it.optString("url"), it.optLong("folder"), it.optString("browser"))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    private fun write(context: Context) {
        val o = JSONObject()
            .put("next", nextId)
            .put("folders", JSONArray().also { a -> folders.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("parent", it.parent)) } })
            .put("items", JSONArray().also { a ->
                bookmarks.forEach { a.put(JSONObject().put("id", it.id).put("title", it.title).put("url", it.url).put("folder", it.folder).put("browser", it.browser)) }
            })
        try {
            val file = File(context.applicationContext.filesDir, FILE)
            val tmp = File(file.parentFile, "$FILE.tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(file)) file.writeText(o.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun folders(context: Context): List<BookmarkFolder> {
        ensure(context)
        return folders.map { it.copy() }
    }

    @Synchronized
    fun bookmarks(context: Context): List<Bookmark> {
        ensure(context)
        return bookmarks.map { it.copy() }
    }

    fun childFolders(context: Context, parent: Long) = folders(context).filter { it.parent == parent }

    fun bookmarksIn(context: Context, folder: Long) = bookmarks(context).filter { it.folder == folder }

    /** "Work › Clients" for a folder; empty for the top level. */
    fun path(context: Context, folder: Long): String {
        val all = folders(context).associateBy { it.id }
        val names = mutableListOf<String>()
        var id = folder
        var guard = 0
        while (id != 0L && guard++ < 50) {
            val f = all[id] ?: break
            names.add(0, f.name)
            id = f.parent
        }
        return names.joinToString(" › ")
    }

    /** Every folder with its path, in tree order (for pickers). */
    fun folderPaths(context: Context): List<Pair<BookmarkFolder, String>> {
        val all = folders(context)
        val out = mutableListOf<Pair<BookmarkFolder, String>>()
        fun walk(parent: Long, prefix: String, depth: Int) {
            if (depth > 50) return
            all.filter { it.parent == parent }.forEach { f ->
                val p = if (prefix.isEmpty()) f.name else "$prefix › ${f.name}"
                out += f to p
                walk(f.id, p, depth + 1)
            }
        }
        walk(0L, "", 0)
        return out
    }

    /** [folder] and everything under it. */
    fun subtree(context: Context, folder: Long): Set<Long> {
        val all = folders(context)
        val out = mutableSetOf(folder)
        var grew = true
        while (grew) {
            grew = all.filter { it.parent in out && it.id !in out }.map { out.add(it.id) }.isNotEmpty()
        }
        return out
    }

    @Synchronized
    fun saveBookmark(context: Context, bookmark: Bookmark): Bookmark {
        ensure(context)
        val b = if (bookmark.id == 0L) bookmark.copy(id = nextId++) else bookmark
        val i = bookmarks.indexOfFirst { it.id == b.id }
        if (i >= 0) bookmarks[i] = b else bookmarks += b
        write(context)
        if (b.folder != 0L) useFolder(context, b.folder)
        return b
    }

    @Synchronized
    fun deleteBookmark(context: Context, id: Long) {
        ensure(context)
        bookmarks.removeAll { it.id == id }
        write(context)
    }

    @Synchronized
    fun saveFolder(context: Context, folder: BookmarkFolder): BookmarkFolder {
        ensure(context)
        val f = if (folder.id == 0L) folder.copy(id = nextId++) else folder
        val i = folders.indexOfFirst { it.id == f.id }
        if (i >= 0) folders[i] = f else folders += f
        write(context)
        return f
    }

    /** A deleted category's bookmarks and subcategories go up one level. */
    @Synchronized
    fun deleteFolder(context: Context, id: Long) {
        ensure(context)
        val f = folders.firstOrNull { it.id == id } ?: return
        folders.filter { it.parent == id }.forEach { it.parent = f.parent }
        bookmarks.filter { it.folder == id }.forEach { it.folder = f.parent }
        folders.remove(f)
        write(context)
    }

    /** Moves a bookmark one place up or down among those of its category. */
    @Synchronized
    fun moveBookmark(context: Context, id: Long, delta: Int) {
        ensure(context)
        val b = bookmarks.firstOrNull { it.id == id } ?: return
        val siblings = bookmarks.filter { it.folder == b.folder }
        val target = siblings.getOrNull(siblings.indexOf(b) + delta) ?: return
        val i = bookmarks.indexOf(b)
        val j = bookmarks.indexOf(target)
        bookmarks[i] = target
        bookmarks[j] = b
        write(context)
    }

    @Synchronized
    fun moveFolder(context: Context, id: Long, delta: Int) {
        ensure(context)
        val f = folders.firstOrNull { it.id == id } ?: return
        val siblings = folders.filter { it.parent == f.parent }
        val target = siblings.getOrNull(siblings.indexOf(f) + delta) ?: return
        val i = folders.indexOf(f)
        val j = folders.indexOf(target)
        folders[i] = target
        folders[j] = f
        write(context)
    }

    // ---------------------------------------------------------------- recent categories and open state

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The categories used last, newest first (the form offers them as pills). */
    fun recentFolders(context: Context): List<Long> {
        val existing = folders(context).map { it.id }.toSet()
        return prefs(context).getString("recent", "").orEmpty().split(",").mapNotNull { it.toLongOrNull() }.filter { it in existing }
    }

    private fun useFolder(context: Context, id: Long) {
        val list = (listOf(id) + recentFolders(context).filter { it != id }).take(MAX_RECENT)
        prefs(context).edit { putString("recent", list.joinToString(",")) }
    }

    fun openFolders(context: Context): Set<Long> =
        prefs(context).getStringSet("open", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()

    fun setFolderOpen(context: Context, id: Long, open: Boolean) {
        val set = openFolders(context).toMutableSet()
        if (open) set += id else set -= id
        prefs(context).edit { putStringSet("open", set.map { it.toString() }.toSet()) }
    }

    // ---------------------------------------------------------------- shared text

    private val urlRegex = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    /** The first web address in [text] (what a browser shares), or null. */
    fun findUrl(text: String?): String? = text?.let { urlRegex.find(it)?.value?.trimEnd('.', ',', ')', ']') }

    /** "www.site.com/..." → "site.com". */
    fun domain(url: String): String = try {
        android.net.Uri.parse(url).host.orEmpty().removePrefix("www.").ifEmpty { url }
    } catch (e: Exception) {
        url
    }
}
