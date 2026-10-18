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

    /** Photos and videos of [dir], newest first, when it holds nothing else (an album); null otherwise. */
    fun albumMedia(dir: File): List<File>? {
        val files = dir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") } ?: return null
        if (files.isEmpty() || !files.all { isMedia(mimeOf(it.name)) }) return null
        return files.sortedByDescending { it.lastModified() }
    }

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
            val bitmap = withContext(workers) { make(context, file, size, uri) } ?: return@launch
            cache.put(key, bitmap)
            if (view.tag == key) view.setImageBitmap(bitmap)
        }
    }

    /** Shows a thumbnail whose image comes from [fetch] (e.g. a OneDrive thumbnail), cached under [key]. */
    fun loadBytes(scope: CoroutineScope, view: ImageView, key: String, size: Int, fetch: () -> ByteArray?) {
        val cacheKey = key + "@" + size
        jobs.remove(view)?.cancel()
        view.tag = cacheKey
        val cached = cache.get(cacheKey)
        view.setImageBitmap(cached)
        if (cached != null) return
        jobs[view] = scope.launch {
            val bitmap = withContext(workers) {
                try {
                    val bytes = fetch() ?: return@withContext null
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                } catch (e: Throwable) {
                    null
                }
            } ?: return@launch
            cache.put(cacheKey, bitmap)
            if (view.tag == cacheKey) view.setImageBitmap(bitmap)
        }
    }

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
