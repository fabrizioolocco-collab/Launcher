package app.olauncher.helper

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The gallery's own recycle bin. A deleted photo or video is moved (not copied: same storage, so
 * instant) into a hidden ".MinimaTrash" folder at the root of its storage (phone or SD card), with
 * a ".nomedia" file so other galleries don't show it. An index in that folder remembers where each
 * file came from and when it was deleted; after [KEEP_DAYS] days it is deleted for good.
 *
 * Kept on the storage itself (not in the app's data), so clearing the launcher's data doesn't lose
 * the files in the bin. All functions do file work: call them off the main thread.
 */
object MediaTrash {

    const val KEEP_DAYS = 30
    private const val DIR = ".MinimaTrash"
    private const val INDEX = "index.json"

    /** A file in the bin: [file] is where it is now, [originalPath] where it goes back. */
    class Entry(val file: File, val originalPath: String, val deletedAt: Long) {
        val name: String get() = File(originalPath).name

        fun daysLeft(now: Long = System.currentTimeMillis()): Int =
            (KEEP_DAYS - TimeUnit.MILLISECONDS.toDays(now - deletedAt)).toInt().coerceAtLeast(0)
    }

    private val lock = Any()

    /** Root of the storage holding [file]: the phone's shared storage, or /storage/XXXX-XXXX for an SD card. */
    private fun volumeRoot(file: File): File {
        val primary = Environment.getExternalStorageDirectory()
        val path = file.absolutePath
        if (path.startsWith(primary.absolutePath + "/")) return primary
        val parts = path.split('/')
        // /storage/XXXX-XXXX/...
        if (parts.size > 3 && parts[1] == "storage") return File("/${parts[1]}/${parts[2]}")
        return primary
    }

    private fun trashDir(root: File) = File(root, DIR)

    /** The bins that may exist: the phone's storage and every mounted SD card. */
    private fun allTrashDirs(context: Context): List<File> {
        val roots = mutableListOf(Environment.getExternalStorageDirectory())
        try {
            context.getExternalFilesDirs(null).filterNotNull().forEach { roots += volumeRoot(it) }
        } catch (e: Exception) {
            // only the phone's storage
        }
        return roots.distinctBy { it.absolutePath }.map { trashDir(it) }.filter { it.isDirectory }
    }

    private fun ensureDir(root: File): File? {
        val dir = trashDir(root)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val noMedia = File(dir, ".nomedia")
        if (!noMedia.exists()) try {
            noMedia.createNewFile()
        } catch (e: Exception) {
            // not fatal: the files are still kept
        }
        return dir
    }

    private fun readIndex(dir: File): MutableList<Entry> = try {
        val array = JSONArray(File(dir, INDEX).takeIf { it.isFile }?.readText() ?: "[]")
        MutableList(array.length()) { i ->
            val o = array.getJSONObject(i)
            Entry(File(dir, o.getString("f")), o.getString("p"), o.getLong("t"))
        }
    } catch (e: Exception) {
        mutableListOf()
    }

    private fun writeIndex(dir: File, entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { array.put(JSONObject().put("f", it.file.name).put("p", it.originalPath).put("t", it.deletedAt)) }
        try {
            File(dir, INDEX).writeText(array.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun moveFile(src: File, dest: File): Boolean = try {
        src.renameTo(dest) || (src.copyTo(dest, overwrite = false).exists() && src.delete())
    } catch (e: Exception) {
        false
    }

    /**
     * Moves [files] into the bin. Returns the original paths actually moved (to tell the system's
     * media index they are gone); fewer than [files] when some couldn't be moved.
     */
    fun moveToTrash(files: List<File>): List<String> = synchronized(lock) {
        val moved = mutableListOf<String>()
        val now = System.currentTimeMillis()
        files.filter { it.isFile }.groupBy { volumeRoot(it).absolutePath }.forEach { (rootPath, group) ->
            val dir = ensureDir(File(rootPath)) ?: return@forEach
            val index = readIndex(dir)
            group.forEachIndexed { i, src ->
                var dest = File(dir, "${now}_${i}_${src.name}")
                var n = 1
                while (dest.exists()) dest = File(dir, "${now}_${i}_${n++}_${src.name}")
                if (moveFile(src, dest)) {
                    index += Entry(dest, src.absolutePath, now)
                    moved += src.absolutePath
                }
            }
            writeIndex(dir, index)
        }
        moved
    }

    /** Everything in the bin, newest first (files missing from disk are dropped from the index). */
    fun list(context: Context): List<Entry> = synchronized(lock) {
        allTrashDirs(context).flatMap { dir ->
            val index = readIndex(dir)
            val present = index.filter { it.file.isFile }
            if (present.size != index.size) writeIndex(dir, present)
            present
        }.sortedByDescending { it.deletedAt }
    }

    private fun uniqueTarget(target: File): File {
        if (!target.exists()) return target
        val parent = target.parentFile
        val name = target.name
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
        var i = 1
        var f: File
        do {
            f = File(parent, "$base (${i++})$ext")
        } while (f.exists())
        return f
    }

    /** Puts [entries] back where they were (the folder is made again if it's gone). Returns the restored paths. */
    fun restore(entries: List<Entry>): List<String> = synchronized(lock) {
        val restored = mutableListOf<String>()
        entries.groupBy { it.file.parentFile!!.absolutePath }.forEach { (dirPath, group) ->
            val dir = File(dirPath)
            val index = readIndex(dir)
            val done = mutableSetOf<String>()
            group.forEach { entry ->
                val target = uniqueTarget(File(entry.originalPath))
                target.parentFile?.mkdirs()
                if (moveFile(entry.file, target)) {
                    restored += target.absolutePath
                    done += entry.file.name
                }
            }
            writeIndex(dir, index.filterNot { it.file.name in done })
        }
        restored
    }

    /** Deletes [entries] for good. Returns false when some couldn't be deleted. */
    fun deleteForever(entries: List<Entry>): Boolean = synchronized(lock) {
        var ok = true
        entries.groupBy { it.file.parentFile!!.absolutePath }.forEach { (dirPath, group) ->
            val dir = File(dirPath)
            val index = readIndex(dir)
            val done = mutableSetOf<String>()
            group.forEach { entry ->
                if (entry.file.delete() || !entry.file.exists()) done += entry.file.name else ok = false
            }
            writeIndex(dir, index.filterNot { it.file.name in done })
        }
        ok
    }

    /** Deletes for good what has been in the bin longer than [KEEP_DAYS] days. */
    fun purgeExpired(context: Context) {
        val expired = list(context).filter { it.daysLeft() <= 0 }
        if (expired.isNotEmpty()) deleteForever(expired)
    }

    /** Tells the system's media index about changed paths (moved out of or back into the gallery). */
    fun scan(context: Context, paths: List<String>) {
        if (paths.isNotEmpty()) MediaScannerConnection.scanFile(context.applicationContext, paths.toTypedArray(), null, null)
    }
}
