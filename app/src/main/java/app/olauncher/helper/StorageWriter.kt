package app.olauncher.helper

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import java.io.File

/**
 * Writes files (copy, new folder, rename, delete) on the phone and on USB drives / SD cards.
 *
 * Many phones don't let apps write on a USB drive directly, even with the access to all files:
 * there the user grants the drive once in the system picker ([pickIntent]), and the writes go
 * through the system documents provider with that permission (kept across restarts).
 */
class StorageWriter(private val ctx: Context, private val volumes: () -> List<StorageVolumes.Volume>) {

    /** Volumes (by path) where writing with plain files works. */
    private val direct = HashMap<String, Boolean>()

    private fun volumeOf(f: File) = StorageVolumes.of(volumes(), f)

    private fun directWritable(v: StorageVolumes.Volume): Boolean = direct.getOrPut(v.dir.absolutePath) {
        if (v.primary) return@getOrPut true
        try {
            val probe = File(v.dir, ".minima_write_test_${System.nanoTime()}")
            probe.createNewFile() && probe.delete()
        } catch (e: Exception) {
            false
        }
    }

    /** The drive's write permission granted in the picker, if any. */
    private fun tree(v: StorageVolumes.Volume): Uri? {
        val id = v.uuid ?: return null
        return ctx.contentResolver.persistedUriPermissions.firstOrNull { p ->
            p.isWritePermission && p.uri.authority == AUTHORITY &&
                    try { DocumentsContract.getTreeDocumentId(p.uri) == "$id:" } catch (e: Exception) { false }
        }?.uri
    }

    /** True when [v] can't be written yet: the user must grant it in the picker. */
    fun needsGrant(v: StorageVolumes.Volume): Boolean =
        !v.primary && !v.readOnly && v.uuid != null && !directWritable(v) && tree(v) == null

    /** Picker opened at the top of the drive, to grant it. */
    fun pickIntent(v: StorageVolumes.Volume): Intent {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && v.system != null) v.system.createOpenDocumentTreeIntent()
        else Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && v.uuid != null)
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(AUTHORITY, "${v.uuid}:"))
        return intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }

    /** Keeps the permission picked for [v]; false when another folder than the top of the drive was chosen. */
    fun keepGrant(v: StorageVolumes.Volume, uri: Uri): Boolean {
        val ok = try { DocumentsContract.getTreeDocumentId(uri) == "${v.uuid}:" } catch (e: Exception) { false }
        if (!ok) return false
        return try {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /** Where writing [f] has to go through the granted permission (null = plain files). */
    private fun safTree(f: File): Pair<StorageVolumes.Volume, Uri>? {
        val v = volumeOf(f) ?: return null
        if (v.primary || directWritable(v)) return null
        return tree(v)?.let { v to it }
    }

    private fun docUri(v: StorageVolumes.Volume, tree: Uri, f: File): Uri {
        val rel = f.absolutePath.removePrefix(v.dir.absolutePath).trimStart('/')
        return DocumentsContract.buildDocumentUriUsingTree(tree, "${v.uuid}:$rel")
    }

    fun mkdir(dir: File): Boolean {
        val (v, tree) = safTree(dir) ?: return dir.mkdirs()
        val parent = dir.parentFile ?: return false
        if (!parent.exists() && !mkdir(parent)) return false
        return DocumentsContract.createDocument(ctx.contentResolver, docUri(v, tree, parent), DocumentsContract.Document.MIME_TYPE_DIR, dir.name) != null
    }

    fun delete(f: File): Boolean {
        val (v, tree) = safTree(f) ?: return f.deleteRecursively()
        return DocumentsContract.deleteDocument(ctx.contentResolver, docUri(v, tree, f))
    }

    fun rename(f: File, name: String): Boolean {
        val (v, tree) = safTree(f) ?: return f.renameTo(File(f.parentFile, name))
        return DocumentsContract.renameDocument(ctx.contentResolver, docUri(v, tree, f), name) != null
    }

    /** Copies [src] (a file or a whole folder) to [dest], which doesn't exist yet. */
    fun copy(src: File, dest: File): Boolean {
        val (v, tree) = safTree(dest) ?: return src.copyRecursively(dest)
        return safCopy(v, tree, src, dest)
    }

    private fun safCopy(v: StorageVolumes.Volume, tree: Uri, src: File, dest: File): Boolean {
        val parent = docUri(v, tree, dest.parentFile ?: return false)
        val cr = ctx.contentResolver
        if (src.isDirectory) {
            DocumentsContract.createDocument(cr, parent, DocumentsContract.Document.MIME_TYPE_DIR, dest.name) ?: return false
            return src.listFiles()?.all { safCopy(v, tree, it, File(dest, it.name)) } ?: false
        }
        // The type from the extension: the provider would add an extension that doesn't match it
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(dest.extension.lowercase()) ?: "application/octet-stream"
        val uri = DocumentsContract.createDocument(cr, parent, mime, dest.name) ?: return false
        val out = cr.openOutputStream(uri) ?: return false
        out.use { o -> src.inputStream().use { it.copyTo(o) } }
        return true
    }

    companion object {
        private const val AUTHORITY = "com.android.externalstorage.documents"
    }
}
