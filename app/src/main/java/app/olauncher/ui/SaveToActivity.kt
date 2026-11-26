package app.olauncher.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import app.olauncher.R
import app.olauncher.helper.showToast
import java.io.File

/**
 * "Save to…" for any file another app opens or shares (an attachment, a download): the
 * launcher's explorer asks for the folder, the files are copied there and the folder opens.
 */
class SaveToActivity : Activity() {

    private val uris = mutableListOf<Uri>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        when (intent?.action) {
            Intent.ACTION_SEND -> (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)?.let { uris += it }
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris += it }
            else -> intent?.data?.let { uris += it }
        }
        if (uris.isEmpty()) {
            finish()
            return
        }
        // A photo or a video asked to be shown under a type that is not a picture's (some cameras do, from the
        // small preview of the shot): the viewer was not offered for it and this was the only answer. It is
        // shown, not saved. Asked as a picture, both were offered and "Save to" was the one chosen: it stays.
        if (intent?.action == Intent.ACTION_VIEW) {
            val declared = intent.type
            val real = try {
                contentResolver.getType(uris[0])
            } catch (e: Exception) {
                null
            }
            fun media(type: String?) = type != null && (type.startsWith("image/") || type.startsWith("video/"))
            if (!media(declared) && media(real)) {
                try {
                    startActivity(
                        Intent(this, app.olauncher.ui.media.ViewerEntryActivity::class.java).setAction(Intent.ACTION_VIEW)
                            .setDataAndType(uris[0], real).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    )
                    finish()
                    return
                } catch (e: Exception) {
                    // falls back to saving
                }
            }
        }
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
        if (!allowed) {
            // The explorer asks for the files access itself
            showToast(getString(R.string.ml_search_allow_files), Toast.LENGTH_LONG)
            FileExplorerActivity.openRoots(this)
            finish()
            return
        }
        if (savedInstanceState == null) {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(this, FolderPickerActivity::class.java).putExtra(FileExplorerActivity.EXTRA_LOCAL_PATH, downloads.absolutePath), REQUEST_FOLDER,
            )
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val folder = data?.getStringExtra(FileExplorerActivity.EXTRA_LOCAL_PATH)?.let { File(it) }
        if (requestCode != REQUEST_FOLDER || resultCode != RESULT_OK || folder == null) {
            finish()
            return
        }
        Thread {
            val failed = uris.count { !save(it, folder) }
            runOnUiThread {
                showToast(
                    if (failed == 0) getString(R.string.ml_save_to_done, folder.name.ifEmpty { "/" }) else getString(R.string.ml_save_to_failed, failed),
                    Toast.LENGTH_LONG,
                )
                if (failed < uris.size) FileExplorerActivity.openLocal(this, folder)
                finish()
            }
        }.start()
    }

    /** Copies what [uri] holds into [folder] under its own name (numbered when the name is taken). */
    private fun save(uri: Uri, folder: File): Boolean = try {
        var name = (if (uri.scheme == "file") uri.lastPathSegment else contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null })?.replace('/', '_')?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "file"
        if (!name.contains('.')) {
            val type = intent?.type?.takeIf { !it.contains('*') } ?: contentResolver.getType(uri)
            MimeTypeMap.getSingleton().getExtensionFromMimeType(type)?.let { name += ".$it" }
        }
        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
        var target = File(folder, name)
        var i = 1
        while (target.exists()) target = File(folder, "$base (${i++})$extension")
        val input = contentResolver.openInputStream(uri)
        if (input == null) false
        else {
            input.use { source -> target.outputStream().use { source.copyTo(it, 256 * 1024) } }
            true
        }
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }

    companion object {
        private const val REQUEST_FOLDER = 1
    }
}
