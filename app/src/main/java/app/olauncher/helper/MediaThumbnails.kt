package app.olauncher.helper

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.webkit.MimeTypeMap
import android.widget.ImageView
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Thumbnails of photos and videos with a shared memory cache. On Android 10+ they come from the
 * system's thumbnail store (the same the gallery uses, so usually already made); a few are made at
 * a time and the work for views recycled meanwhile is cancelled, so the visible ones come first.
 * Every thumbnail made is also kept on disk ([diskFile]), no larger than asked: reopening a folder
 * shows them at once instead of decoding a frame of each video again (slow on long or 4K videos).
 *
 * Three things keep a grid quick: the thumbnails already on disk are read on threads of their own,
 * never queued behind the ones being made; a photo being made shows meanwhile the small picture the
 * camera put inside the file; and [preload] gets ready the ones the scroll is about to reach.
 */
object MediaThumbnails {

    // A quarter of the app's memory, between 48 and 128 MB: some screens of a grid, both ways
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 4).coerceIn(48L * 1024 * 1024, 128L * 1024 * 1024).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val cores = Runtime.getRuntime().availableProcessors()

    /** Reads the thumbnails already on disk (a few milliseconds each). */
    private val readers = Executors.newFixedThreadPool(4).asCoroutineDispatcher()

    /** Makes the missing ones (a photo decoded, a frame of a video found: tens to hundreds of milliseconds). */
    private val workers = Executors.newFixedThreadPool((cores - 2).coerceIn(3, 6)).asCoroutineDispatcher()

    /** Gets ready the ones not on screen yet, without taking threads from the visible ones. */
    private val preloaders = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
    private var preloading: Job? = null

    /** The threads the thumbnails are actually made on, as many as needed: see [produce]. */
    private val makers = Executors.newCachedThreadPool()
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
            var bitmap = withContext(readers) { fromDisk(context, file, size) }
            if (bitmap == null) {
                // A grid cell of a photo: the camera's own small picture right away, the sharp one after
                if (size <= PREVIEW_UP_TO && !isVideo(file) && !withContext(readers) { isBad(context, file, size) }) {
                    val quick = withContext(readers) { preview(file) }
                    if (quick != null && view.tag == key) view.setImageBitmap(quick)
                }
                bitmap = withContext(workers) { produce(context, file, size, uri) }
            }
            if (bitmap == null) return@launch
            cache.put(key, bitmap)
            if (view.tag == key) view.setImageBitmap(bitmap)
        }
    }

    /**
     * Gets the thumbnails of [files] ready in memory, in that order (the nearest to the screen first):
     * the cells a scroll is about to show. A new call replaces the one before.
     */
    fun preload(scope: CoroutineScope, context: Context, files: List<File>, size: Int, uris: Map<String, Uri> = emptyMap()) {
        preloading?.cancel()
        if (files.isEmpty()) return
        val app = context.applicationContext
        preloading = scope.launch(preloaders) {
            for (file in files) {
                if (!isActive) return@launch
                val key = file.absolutePath + "@" + size
                if (cache.get(key) != null) continue
                val bitmap = fromDisk(app, file, size) ?: produce(app, file, size, uris[file.absolutePath]) ?: continue
                cache.put(key, bitmap)
            }
        }
    }

    /** Makes a thumbnail, no larger than it will be shown, and keeps it on disk. */
    private fun produce(context: Context, file: File, size: Int, uri: Uri?): Bitmap? {
        if (isBad(context, file, size)) return null
        // On a thread that can be given up: a file the system chokes on (a broken video, a huge photo)
        // would otherwise hold one of the few workers for good, and after some of those nothing loads
        val task = makers.submit<Bitmap?> { make(context, file, size, uri) }
        val made = try {
            task.get(MAKE_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: Throwable) {
            task.cancel(true)
            null
        }
        if (made == null) {
            markBad(context, file, size)
            return null
        }
        return fit(made, size).also { toDisk(context, file, size, it) }
    }

    private fun badFile(context: Context, file: File, size: Int) = File(diskFile(context, file, size).path + ".bad")

    /** A file whose thumbnail couldn't be made is left alone for some days instead of being tried at every look. */
    private fun isBad(context: Context, file: File, size: Int): Boolean = try {
        val mark = badFile(context, file, size)
        mark.isFile && System.currentTimeMillis() - mark.lastModified() < BAD_FOR_MS
    } catch (e: Throwable) {
        false
    }

    private fun markBad(context: Context, file: File, size: Int) {
        try {
            val mark = badFile(context, file, size)
            mark.parentFile?.mkdirs()
            mark.writeBytes(ByteArray(0))
            mark.setLastModified(System.currentTimeMillis())
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    /**
     * Shrinks a thumbnail that came larger than asked (the system gives the nearest size it has) until
     * its short side is [size]: a cell crops to its centre, so nothing shown is lost, and it takes a
     * fraction of the memory and of the time to read back.
     */
    private fun fit(bitmap: Bitmap, size: Int): Bitmap = try {
        val short = minOf(bitmap.width, bitmap.height)
        if (short <= size * 1.15f) bitmap
        else {
            val scale = size.toFloat() / short
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        }
    } catch (e: Throwable) {
        bitmap
    }

    /** The small picture a camera stores inside a photo, turned upright; null when there is none. */
    private fun preview(file: File): Bitmap? = try {
        val exif = ExifInterface(file.absolutePath)
        val thumb = exif.thumbnailBitmap
        val degrees = exif.rotationDegrees
        if (thumb == null || degrees == 0) thumb
        else Bitmap.createBitmap(thumb, 0, 0, thumb.width, thumb.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
    } catch (e: Throwable) {
        null
    }

    @Volatile private var writes = 0

    /** Where the thumbnail of [file] at [size] is kept; a changed file (date, length) gets a new name. */
    private fun diskFile(context: Context, file: File, size: Int): File {
        val key = "${file.absolutePath}|${file.lastModified()}|${file.length()}|$size"
        val hash = java.security.MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(diskDir(context), "$hash.jpg")
    }

    @Volatile private var dir: File? = null

    /**
     * Where the thumbnails are kept: with the app's own files, not in its cache, which the system
     * empties when the phone runs short of space (a phone full of photos does), so that they had
     * to be made again and again. The ones already in the cache are moved over, once.
     */
    private fun diskDir(context: Context): File {
        dir?.let { return it }
        synchronized(this) {
            dir?.let { return it }
            val kept = File(context.noBackupFilesDir, DISK_DIR)
            val old = File(context.cacheDir, DISK_DIR)
            try {
                if (!kept.exists() && old.isDirectory) old.renameTo(kept)
                kept.mkdirs()
                if (old.exists()) old.deleteRecursively()
            } catch (e: Throwable) {
                e.printStackTrace()
            }
            dir = kept
            return kept
        }
    }

    private fun fromDisk(context: Context, file: File, size: Int): Bitmap? = try {
        val f = diskFile(context, file, size)
        // Thumbnails have no transparency: two bytes a pixel instead of four
        if (f.isFile) BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }) else null
    } catch (e: Throwable) {
        null
    }

    private fun toDisk(context: Context, file: File, size: Int, bitmap: Bitmap) {
        try {
            val f = diskFile(context, file, size)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
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
    private const val DISK_MAX_BYTES = 300L * 1024 * 1024
    private const val MAKE_TIMEOUT_S = 12L
    private const val BAD_FOR_MS = 7L * 24 * 3600 * 1000

    /** Up to this size a photo shows its embedded picture while the thumbnail is made (a full-screen poster doesn't). */
    private const val PREVIEW_UP_TO = 512

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
