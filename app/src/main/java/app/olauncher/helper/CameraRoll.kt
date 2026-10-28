package app.olauncher.helper

import android.content.Context
import android.os.Environment
import androidx.core.content.edit
import java.io.File

/**
 * The camera folder opened from home as a gallery, like "Recents" on the iPhone: next to the camera's
 * photos it shows those of other folders (screenshots, WhatsApp, Telegram...), newest first. Each file
 * stays in its own folder; only the view is merged. The folders shown are chosen in the album.
 */
object CameraRoll {

    private fun prefs(context: Context) = context.getSharedPreferences("app.minimalauncher.cameraroll", Context.MODE_PRIVATE)

    private val root: File get() = Environment.getExternalStorageDirectory()

    /** The camera's folder (DCIM/Camera, or DCIM/100ANDRO and the like): opened from home it becomes the gallery. */
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
    )

    /** Other places offered (off until chosen): the download folder and the albums of the other apps. */
    private val OFFERED_PARENTS = listOf("DCIM", "Pictures", "Movies")

    private fun defaults() = DEFAULTS.map { File(root, it) }.filter { it.isDirectory }

    /** The folders merged into the gallery of [camera] (the camera folder itself excluded). */
    fun included(context: Context, camera: File): List<File> {
        val stored = prefs(context).getStringSet(KEY_SOURCES, null)
            ?: return defaults().filterNot { same(it, camera) }
        return options(context, camera).map { it.first }.filter { it.absolutePath in stored && it.isDirectory }
    }

    /** All the folders that can be merged, with the name shown: the included ones first. */
    fun options(context: Context, camera: File): List<Pair<File, String>> {
        val stored = prefs(context).getStringSet(KEY_SOURCES, null).orEmpty()
        val offered = OFFERED_PARENTS.flatMap { parent ->
            File(root, parent).listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.sortedBy { it.name.lowercase() }.orEmpty()
        } + File(root, Environment.DIRECTORY_DOWNLOADS)
        val all = (defaults() + stored.map { File(it) } + offered)
            .filter { it.isDirectory && !same(it, camera) }
            .distinctBy { it.absolutePath.lowercase() }
        // Same name in two places (screenshots in DCIM and Pictures): the parent tells them apart
        val names = all.groupingBy { it.name.lowercase() }.eachCount()
        return all.map { dir ->
            dir to if ((names[dir.name.lowercase()] ?: 0) > 1) "${dir.name} (${dir.parentFile?.name})" else dir.name
        }
    }

    /** Turns [dir] on or off in the gallery of [camera]. */
    fun set(context: Context, camera: File, dir: File, on: Boolean) {
        val current = included(context, camera).map { it.absolutePath }.toMutableSet()
        if (on) current += dir.absolutePath else current -= dir.absolutePath
        prefs(context).edit { putStringSet(KEY_SOURCES, current) }
    }

    private fun same(a: File, b: File) = a.absolutePath.trimEnd('/').equals(b.absolutePath.trimEnd('/'), ignoreCase = true)

    private const val KEY_SOURCES = "SOURCES"
}
