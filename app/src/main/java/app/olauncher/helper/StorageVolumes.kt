package app.olauncher.helper

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.File

/**
 * The storages mounted on the phone: its own memory first, then SD cards and USB drives
 * (pen drives plugged in with an OTG cable or a USB-C adapter).
 */
object StorageVolumes {

    class Volume(
        val dir: File,
        val label: String,
        /** The phone's own memory. */
        val primary: Boolean,
        /** Most likely a USB drive (not an SD card). */
        val usb: Boolean,
        val readOnly: Boolean,
    )

    fun list(ctx: Context): List<Volume> {
        val primaryDir = Environment.getExternalStorageDirectory()
        val found = mutableListOf<Volume>()
        try {
            val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            for (v in sm.storageVolumes) {
                val state = v.state
                if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) continue
                val dir = dirOf(v) ?: continue
                if (!dir.exists()) continue
                val isPrimary = v.isPrimary || dir.absolutePath == primaryDir.absolutePath
                val label = v.getDescription(ctx)?.takeIf { it.isNotBlank() } ?: dir.name
                found += Volume(dir, label, isPrimary, isUsb(v, label, dir), state == Environment.MEDIA_MOUNTED_READ_ONLY)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (found.none { it.primary }) found.add(0, Volume(primaryDir, "", primary = true, usb = false, readOnly = false))
        return found.sortedBy { !it.primary }.distinctBy { it.dir.absolutePath }
    }

    /** The storage holding [file] (the deepest root containing it), or null. */
    fun of(volumes: List<Volume>, file: File): Volume? {
        val path = file.absolutePath
        return volumes.filter { path == it.dir.absolutePath || path.startsWith(it.dir.absolutePath + "/") }
            .maxByOrNull { it.dir.absolutePath.length }
    }

    private fun dirOf(v: StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return v.directory
        // Before Android 11 the path is only reachable through the hidden getPath()
        return try {
            (StorageVolume::class.java.getMethod("getPath").invoke(v) as? String)?.let { File(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun isUsb(v: StorageVolume, label: String, dir: File): Boolean {
        if (!v.isRemovable) return false
        if (label.contains("usb", ignoreCase = true)) return true
        // The system names SD cards "SD card" / "Scheda SD"; a removable storage that isn't one is a USB drive
        if (label.contains("sd", ignoreCase = true)) return false
        return dir.absolutePath.contains("usb", ignoreCase = true) || label.isNotBlank()
    }
}
