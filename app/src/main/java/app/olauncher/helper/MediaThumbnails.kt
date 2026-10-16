package app.olauncher.helper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.webkit.MimeTypeMap
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Thumbnails of photos and videos, made off the main thread and kept in a shared memory cache. */
object MediaThumbnails {

    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun mimeOf(name: String): String? =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())

    fun isVideo(file: File) = mimeOf(file.name)?.startsWith("video/") == true

    /** Shows the thumbnail of [file] in [view] (which may be recycled for another file meanwhile). */
    fun load(scope: CoroutineScope, view: ImageView, file: File, size: Int) {
        val key = file.absolutePath + "@" + size
        view.tag = key
        val cached = cache.get(key)
        view.setImageBitmap(cached)
        if (cached != null) return
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { make(file, size) } ?: return@launch
            cache.put(key, bitmap)
            if (view.tag == key) view.setImageBitmap(bitmap)
        }
    }

    private fun make(file: File, size: Int): Bitmap? = try {
        val video = isVideo(file)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val s = Size(size, size)
            if (video) ThumbnailUtils.createVideoThumbnail(file, s, null) else ThumbnailUtils.createImageThumbnail(file, s, null)
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
    } catch (e: Exception) {
        null
    }
}
