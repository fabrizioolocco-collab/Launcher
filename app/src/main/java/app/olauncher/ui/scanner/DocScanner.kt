package app.olauncher.ui.scanner

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import app.olauncher.R
import app.olauncher.helper.showToast
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File

/**
 * Google's document scanner (ML Kit, run by Play services): it finds the sheet, shoots by itself,
 * crops and straightens it, and lets the crop be fixed by hand. It needs no camera permission.
 * The pages come back as JPEG files, copied into our own folder.
 */
object DocScanner {

    /** Opens the scanner; [pageLimit] 0 means as many pages as wanted. [onUnavailable] when it can't start. */
    fun launch(activity: Activity, launcher: ActivityResultLauncher<IntentSenderRequest>, pageLimit: Int = 0, onUnavailable: () -> Unit = {}) {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .apply { if (pageLimit > 0) setPageLimit(pageLimit) }
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
            .addOnSuccessListener { sender -> launcher.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener { e ->
                e.printStackTrace()
                activity.showToast(activity.getString(R.string.ml_scan_unavailable), Toast.LENGTH_LONG)
                onUnavailable()
            }
    }

    /** The scanned pages of a finished scan; empty when it was cancelled. */
    fun pages(result: ActivityResult): List<Uri> {
        if (result.resultCode != Activity.RESULT_OK) return emptyList()
        return GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pages?.map { it.imageUri } ?: emptyList()
    }

    /** Copies the pages into [dir] (the scanner's own files may be cleared). Blocking. */
    fun copyInto(context: Context, uris: List<Uri>, dir: File): List<File> {
        dir.mkdirs()
        val stamp = System.currentTimeMillis()
        return uris.mapIndexedNotNull { i, uri ->
            try {
                val target = File(dir, "page-$stamp-$i.jpg")
                context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } } ?: return@mapIndexedNotNull null
                target
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    /** The scanner's work folders in the cache: removed when older than a day (left by a closed app). */
    fun cleanup(context: Context) {
        val dayAgo = System.currentTimeMillis() - 24 * 3600_000L
        listOf(File(context.cacheDir, "scan"), File(context.cacheDir, "pdf")).forEach { dir ->
            dir.listFiles()?.forEach { f ->
                val ours = dir.name == "scan" || f.name.startsWith("work-")
                if (ours && f.lastModified() < dayAgo) f.deleteRecursively()
            }
        }
    }
}
