package app.olauncher.ui.media

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import app.olauncher.R
import app.olauncher.helper.showToast
import java.io.File

/**
 * The built-in viewer as an app for photos and videos: opened by other apps (the camera's preview,
 * a file manager, "Open with"). A media file on the phone opens with its folder to swipe through;
 * anything else (another app's private file) is copied to the cache and opened alone. The viewer
 * stays in the caller's task, so back returns to it (e.g. the camera).
 */
class ViewerEntryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data ?: run { finish(); return }
        val type = intent.type ?: contentResolver.getType(uri)
        Thread {
            val file = localFile(uri) ?: copyToCache(uri, type)
            runOnUiThread {
                if (file == null) showToast(getString(R.string.ml_search_cannot_open))
                else startActivity(MediaViewerActivity.intent(this, file))
                finish()
                overridePendingTransition(0, 0)
            }
        }.start()
    }

    /** The file behind [uri] when it is on the phone storage and readable. */
    private fun localFile(uri: Uri): File? {
        val path = when (uri.scheme) {
            "file" -> uri.path
            "content" -> try {
                @Suppress("DEPRECATION")
                contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            } catch (e: Exception) {
                null
            }
            else -> null
        } ?: return null
        return File(path).takeIf { it.isFile && it.canRead() }
    }

    /** A copy in the cache (the folder emptied first, so the viewer shows only this one). */
    private fun copyToCache(uri: Uri, type: String?): File? = try {
        val dir = File(cacheDir, "viewer").apply { deleteRecursively(); mkdirs() }
        val ext = type?.let { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        var name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "media"
        if (ext != null && !name.contains('.')) name += ".$ext"
        val out = File(dir, name)
        contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } } ?: throw IllegalStateException()
        out
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
