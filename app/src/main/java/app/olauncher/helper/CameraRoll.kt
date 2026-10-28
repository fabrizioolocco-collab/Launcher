package app.olauncher.helper

import android.content.Context
import android.os.Environment
import androidx.core.content.edit
import app.olauncher.R
import java.io.File

/**
 * The folders of the Gallery ([app.olauncher.ui.gallery.GalleryActivity]), like "Recents" on the
 * iPhone: next to the camera's photos it shows those of other folders (screenshots, WhatsApp,
 * Telegram...), newest first. Each file stays in its own folder; only the view is merged, and only
 * photos and videos: the other files of these folders (a PDF in Download...) are seen only in the
 * folder itself. The folders shown are chosen in the gallery.
 */
object CameraRoll {

    private fun prefs(context: Context) = context.getSharedPreferences("app.minimalauncher.cameraroll", Context.MODE_PRIVATE)

    private val root: File get() = Environment.getExternalStorageDirectory()

    /** The camera's folder, the gallery's base: DCIM/Camera, or the DCIM folder the camera uses on this phone. */
    fun cameraDir(): File {
        val dcim = File(root, "DCIM")
        val camera = File(dcim, "Camera")
        if (camera.isDirectory) return camera
        return dcim.listFiles()?.firstOrNull { it.isDirectory && isCameraRoll(it) } ?: camera
    }

    /** The camera's folder (DCIM/Camera, or DCIM/100ANDRO and the like): opened from home it shows the gallery. */
    fun isCameraRoll(dir: File) = dir.parentFile?.absolutePath.equals(File(root, "DCIM").absolutePath, ignoreCase = true) &&
        (dir.name.equals("Camera", ignoreCase = true) || Regex("\\d{3}[A-Za-z_]{5}").matches(dir.name))

    /**
     * Where the apps save photos and videos, included unless turned off. WhatsApp's "Sent" and
     * "Private" subfolders stay out (only the files directly inside a folder are shown).
     */
    private val DEFAULTS = listOf(
        "DCIM/Screenshots",
        "Pictures/Screenshots",
        "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images",
        "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video",
        "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Animated Gifs",
        "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Images",
        "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Video",
        "WhatsApp/Media/WhatsApp Images",
        "WhatsApp/Media/WhatsApp Video",
        "Android/media/org.telegram.messenger/Telegram/Telegram Images",
        "Android/media/org.telegram.messenger/Telegram/Telegram Video",
        "Android/media/org.telegram.messenger.web/Telegram/Telegram Images",
        "Android/media/org.telegram.messenger.web/Telegram/Telegram Video",
        "Telegram/Telegram Images",
        "Telegram/Telegram Video",
        "Pictures/Telegram",
        "Movies/Telegram",
        "Download",
    )

    /** Other places offered (off until chosen): the albums of the other apps. */
    private val OFFERED_PARENTS = listOf("DCIM", "Pictures", "Movies")

    private fun defaults() = DEFAULTS.map { File(root, it) }.filter { it.isDirectory }

    /** The folders merged into the gallery of [camera] (the camera folder itself excluded). */
    fun included(context: Context, camera: File): List<File> {
        addDownload(context)
        val stored = prefs(context).getStringSet(KEY_SOURCES, null)
            ?: return defaults().filterNot { same(it, camera) }
        return options(context, camera).map { it.first }.filter { it.absolutePath in stored && it.isDirectory }
    }

    /** All the folders that can be merged, with the name shown: the included ones first. */
    fun options(context: Context, camera: File): List<Pair<File, String>> {
        val stored = prefs(context).getStringSet(KEY_SOURCES, null).orEmpty()
        val offered = OFFERED_PARENTS.flatMap { parent ->
            File(root, parent).listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.sortedBy { it.name.lowercase() }.orEmpty()
        }
        val all = (defaults() + stored.map { File(it) } + offered)
            .filter { it.isDirectory && !same(it, camera) }
            .distinctBy { it.absolutePath.lowercase() }
        // Same name in two places: the old WhatsApp/Telegram folder (from before Android/media) is
        // marked as such, otherwise the parent tells them apart (screenshots in DCIM and Pictures)
        val byName = all.groupBy { it.name.lowercase() }
        return all.map { dir ->
            val twins = byName[dir.name.lowercase()].orEmpty()
            dir to when {
                twins.size < 2 -> dir.name
                isAppMedia(dir) -> dir.name
                twins.any { isAppMedia(it) } -> "${dir.name} (${context.getString(R.string.ml_gallery_old_folder)})"
                else -> "${dir.name} (${dir.parentFile?.name})"
            }
        }
    }

    /** Turns [dir] on or off in the gallery of [camera]. */
    fun set(context: Context, camera: File, dir: File, on: Boolean) {
        val current = included(context, camera).map { it.absolutePath }.toMutableSet()
        if (on) current += dir.absolutePath else current -= dir.absolutePath
        prefs(context).edit { putStringSet(KEY_SOURCES, current) }
    }

    /** Download joined the defaults later: added once to a choice already made, then left to the user. */
    private fun addDownload(context: Context) {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_DOWNLOAD_ADDED, false)) return
        val stored = prefs.getStringSet(KEY_SOURCES, null)
        prefs.edit {
            if (stored != null) putStringSet(KEY_SOURCES, stored + File(root, Environment.DIRECTORY_DOWNLOADS).absolutePath)
            putBoolean(KEY_DOWNLOAD_ADDED, true)
        }
    }

    private fun isAppMedia(dir: File) = dir.absolutePath.contains("/Android/media/", ignoreCase = true)

    private fun same(a: File, b: File) = a.absolutePath.trimEnd('/').equals(b.absolutePath.trimEnd('/'), ignoreCase = true)

    private const val KEY_SOURCES = "SOURCES"
    private const val KEY_DOWNLOAD_ADDED = "DOWNLOAD_ADDED"
}
