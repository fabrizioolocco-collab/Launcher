package app.olauncher.helper

import android.database.Cursor
import android.database.MatrixCursor
import android.os.Build
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import app.olauncher.R
import java.io.File
import java.io.FileNotFoundException

/**
 * The phone's storage as a place in the system's file picker ("Files of the launcher"), for the
 * apps that only use that picker. A document's id is its path, always inside the storage.
 */
class ExplorerDocumentsProvider : DocumentsProvider() {

    private val storage: File get() = Environment.getExternalStorageDirectory()

    private fun allowed() = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    override fun onCreate() = true

    /** The file of a document id, refused when it isn't inside the storage. */
    private fun fileOf(documentId: String): File {
        val file = File(documentId).canonicalFile
        val root = storage.canonicalPath
        if (!allowed() || (file.path != root && !file.path.startsWith("$root/"))) throw FileNotFoundException(documentId)
        return file
    }

    private fun mimeOf(file: File): String =
        if (file.isDirectory) Document.MIME_TYPE_DIR
        else MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"

    private fun addRow(cursor: MatrixCursor, file: File) {
        var flags = 0
        if (file.isDirectory) {
            if (file.canWrite()) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        } else if (file.canWrite()) flags = flags or Document.FLAG_SUPPORTS_WRITE
        if (file.parentFile?.canWrite() == true && file.path != storage.path) flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        if (mimeOf(file).startsWith("image/")) flags = flags or Document.FLAG_SUPPORTS_THUMBNAIL
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, file.path)
            .add(Document.COLUMN_DISPLAY_NAME, if (file.path == storage.path) context?.getString(R.string.ml_docs_root) else file.name)
            .add(Document.COLUMN_MIME_TYPE, mimeOf(file))
            .add(Document.COLUMN_SIZE, if (file.isDirectory) null else file.length())
            .add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            .add(Document.COLUMN_FLAGS, flags)
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_COLUMNS)
        // Without the files access there is nothing to offer
        if (!allowed()) return cursor
        cursor.newRow()
            .add(Root.COLUMN_ROOT_ID, "storage")
            .add(Root.COLUMN_DOCUMENT_ID, storage.path)
            .add(Root.COLUMN_TITLE, context?.getString(R.string.ml_docs_root))
            .add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD)
            .add(Root.COLUMN_AVAILABLE_BYTES, storage.freeSpace)
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).also { addRow(it, fileOf(documentId)) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        fileOf(parentDocumentId).listFiles().orEmpty().filter { !it.name.startsWith(".") }.forEach { addRow(cursor, it) }
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = try {
        fileOf(documentId).path.startsWith(fileOf(parentDocumentId).path + "/")
    } catch (e: Exception) {
        false
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileOf(documentId), ParcelFileDescriptor.parseMode(mode))

    override fun openDocumentThumbnail(documentId: String, sizeHint: android.graphics.Point?, signal: CancellationSignal?): android.content.res.AssetFileDescriptor =
        android.content.res.AssetFileDescriptor(ParcelFileDescriptor.open(fileOf(documentId), ParcelFileDescriptor.MODE_READ_ONLY), 0, android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH)

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = fileOf(parentDocumentId)
        val clean = displayName.replace('/', '_')
        // The ending the type asks for, when the name has none
        val wanted = if (mimeType == Document.MIME_TYPE_DIR || clean.contains('.')) null else MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        val name = if (wanted == null) clean else "$clean.$wanted"
        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
        var target = File(parent, name)
        var i = 1
        while (target.exists()) target = File(parent, "$base (${i++})$extension")
        val made = if (mimeType == Document.MIME_TYPE_DIR) target.mkdir() else target.createNewFile()
        if (!made) throw FileNotFoundException(target.path)
        return target.path
    }

    override fun deleteDocument(documentId: String) {
        val file = fileOf(documentId)
        if (file.path == storage.path || !file.deleteRecursively()) throw FileNotFoundException(documentId)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val file = fileOf(documentId)
        val target = File(file.parentFile, displayName.replace('/', '_'))
        if (file.path == storage.path || target.exists() || !file.renameTo(target)) throw FileNotFoundException(documentId)
        return target.path
    }

    companion object {
        private val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_ICON, Root.COLUMN_FLAGS, Root.COLUMN_AVAILABLE_BYTES,
        )
        private val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )
    }
}
