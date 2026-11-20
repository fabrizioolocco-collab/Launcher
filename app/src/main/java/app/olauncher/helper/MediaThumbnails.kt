package app.olauncher.helper

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.webkit.MimeTypeMap
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.WeakHashMap
import java.util.concurrent.Executors

/**
 * Thumbnails of photos and videos with a shared memory cache. On Android 10+ they come from the
 * system's thumbnail store (the same the gallery uses, so usually already made); a few are made at
 * a time and the work for views recycled meanwhile is cancelled, so the visible ones come first.
 * Every thumbnail made is also kept on disk ([diskFile]): reopening a folder shows them at once
 * instead of decoding a frame of each video again (slow on long or 4K videos).
 */
object MediaThumbnails {

    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val workers = Executors.newFixedThreadPool(3).asCoroutineDispatcher()
    private val jobs = WeakHashMap<ImageView, Job>()

    fun mimeOf(name: String): String? =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())

    fun isVideo(file: File) = mimeOf(file.name)?.startsWith("video/") == true

    fun isMedia(mime: String?) = mime != null && (mime.startsWith("image/") || mime.startsWith("video/"))

    /** Photos and videos of [dir], newest first, when it holds nothing else, no subfolders either (an album); null otherwise. */
    fun albumMedia(dir: File): List<File>? {
        val entries = dir.listFiles()?.filter { !it.name.startsWith(".") } ?: return null
        // By name first, without reading the disk: anything not named as a photo or video (a subfolder
        // like WhatsApp Images' Sent, which must stay reachable, or a PDF) means a folder, not an album
        if (entries.isEmpty() || !entries.all { isMedia(mimeOf(it.name)) }) return null
        // One date read per file (sorting would read them again at each comparison: slow on big folders)
        return entries.map { it to it.lastModified() }.sortedByDescending { it.second }.map { it.first }
    }

    /** Photos and videos directly inside [dirs] (other files ignored), newest first. */
    fun mediaIn(dirs: List<File>): List<File> = dirs
        .flatMap { dir -> dir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") && isMedia(mimeOf(it.name)) }.orEmpty() }
        .distinctBy { it.absolutePath }
        // One date read per file: thousands of chat photos would otherwise be read again at each comparison
        .map { it to it.lastModified() }
        .sortedByDescending { it.second }
        .map { it.first }

    /** MediaStore addresses of the files directly inside [dir], by absolute path. */
    fun mediaUris(context: Context, dir: File): Map<String, Uri> {
        val base = MediaStore.Files.getContentUri("external")
        val map = HashMap<String, Uri>()
        try {
            @Suppress("DEPRECATION")
            context.contentResolver.query(
                base, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA),
                "${MediaStore.MediaColumns.DATA} LIKE ?", arrayOf(dir.absolutePath.trimEnd('/') + "/%"), null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val path = c.getString(1) ?: continue
                    map[path] = ContentUris.withAppendedId(base, c.getLong(0))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return map
    }

    /** Shows the thumbnail of [file] in [view] (which may be recycled for another file meanwhile). */
    fun load(scope: CoroutineScope, view: ImageView, file: File, size: Int, uri: Uri? = null) {
        val key = file.absolutePath + "@" + size
        jobs.remove(view)?.cancel()
        view.tag = key
        val cached = cache.get(key)
        view.setImageBitmap(cached)
        if (cached != null) return
        val context = view.context.applicationContext
        jobs[view] = scope.launch {
            val bitmap = withContext(workers) { fromDisk(context, file, size) ?: make(context, file, size, uri)?.also { toDisk(context, file, size, it) } } ?: return@launch
            cache.put(key, bitmap)
            if (view.tag == key) view.setImageBitmap(bitmap)
        }
    }

    @Volatile private var writes = 0

    /** Where the thumbnail of [file] at [size] is kept; a changed file (date, length) gets a new name. */
    private fun diskFile(context: Context, file: File, size: Int): File {
        val key = "${file.absolutePath}|${file.lastModified()}|${file.length()}|$size"
        val hash = java.security.MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(File(context.cacheDir, DISK_DIR), "$hash.jpg")
    }

    private fun fromDisk(context: Context, file: File, size: Int): Bitmap? = try {
        val f = diskFile(context, file, size)
        if (f.isFile) BitmapFactory.decodeFile(f.absolutePath) else null
    } catch (e: Throwable) {
        null
    }

    private fun toDisk(context: Context, file: File, size: Int, bitmap: Bitmap) {
        try {
            val f = diskFile(context, file, size)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            tmp.renameTo(f)
            if (++writes % 100 == 0) trimDisk(f.parentFile ?: return)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    /** Keeps the disk cache under [DISK_MAX_BYTES], dropping the oldest thumbnails first. */
    private fun trimDisk(dir: File) {
        val files = dir.listFiles()?.map { it to it.lastModified() }?.sortedByDescending { it.second } ?: return
        var total = 0L
        files.forEach { (f, _) ->
            total += f.length()
            if (total > DISK_MAX_BYTES) f.delete()
        }
    }

    private const val DISK_DIR = "media_thumbs"
    private const val DISK_MAX_BYTES = 200L * 1024 * 1024

    private fun make(context: Context, file: File, size: Int, uri: Uri?): Bitmap? = try {
        val video = isVideo(file)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val s = Size(size, size)
            uri?.let {
                try {
                    context.contentResolver.loadThumbnail(it, s, null)
                } catch (e: Exception) {
                    null
                }
            } ?: if (video) ThumbnailUtils.createVideoThumbnail(file, s, null) else ThumbnailUtils.createImageThumbnail(file, s, null)
        } else if (video) {
            @Suppress("DEPRECATION")
            ThumbnailUtils.createVideoThumbnail(file.absolutePath, MediaStore.Images.Thumbnails.MINI_KIND)
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (e: Throwable) {
        // Includes OutOfMemoryError on huge images: no thumbnail rather than a crash
        null
    }
}
