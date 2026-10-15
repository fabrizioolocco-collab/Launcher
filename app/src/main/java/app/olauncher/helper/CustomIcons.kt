package app.olauncher.helper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.UserHandle
import androidx.core.content.edit
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Per-app icon overrides chosen by the user, shown everywhere the app appears.
 * Values are "pack:<pack package>:<drawable name>" or "file:<absolute path>".
 */
object CustomIcons {

    const val PACK_PREFIX = "pack:"
    const val FILE_PREFIX = "file:"
    private const val PREFS_FILE = "app.minimalauncher.custom_icons"
    private const val IMAGE_SIZE = 256

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private fun key(packageName: String, activityClassName: String?, user: UserHandle) =
        "$packageName/${activityClassName.orEmpty()}|$user"

    fun get(context: Context, packageName: String, activityClassName: String?, user: UserHandle): String? =
        prefs(context).getString(key(packageName, activityClassName, user), null)

    fun hasCustom(context: Context, packageName: String, activityClassName: String?, user: UserHandle): Boolean =
        get(context, packageName, activityClassName, user) != null

    fun setFromPack(context: Context, packageName: String, activityClassName: String?, user: UserHandle, pack: String, drawable: String) {
        reset(context, packageName, activityClassName, user)
        prefs(context).edit { putString(key(packageName, activityClassName, user), "$PACK_PREFIX$pack:$drawable") }
        IconPackManager.clearCache()
    }

    /** Copies the picked image (center-cropped to a square) into app storage. Blocking. */
    fun setFromImage(context: Context, packageName: String, activityClassName: String?, user: UserHandle, uri: Uri): Boolean {
        val bitmap = decodeSquare(context, uri) ?: return false
        val dir = File(context.filesDir, "custom_icons").apply { mkdirs() }
        val file = File(dir, "${key(packageName, activityClassName, user).hashCode()}_${System.currentTimeMillis()}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        reset(context, packageName, activityClassName, user)
        prefs(context).edit { putString(key(packageName, activityClassName, user), "$FILE_PREFIX${file.absolutePath}") }
        IconPackManager.clearCache()
        return true
    }

    fun reset(context: Context, packageName: String, activityClassName: String?, user: UserHandle) {
        val k = key(packageName, activityClassName, user)
        prefs(context).getString(k, null)?.takeIf { it.startsWith(FILE_PREFIX) }?.let {
            File(it.removePrefix(FILE_PREFIX)).delete()
        }
        prefs(context).edit { remove(k) }
        IconPackManager.clearCache()
    }

    private fun decodeSquare(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // Downsample big photos while decoding to keep memory low
        var sample = 1
        while (min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= IMAGE_SIZE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null

        val side = min(decoded.width, decoded.height)
        val square = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
        val target = min(IMAGE_SIZE, max(side, 1))
        return Bitmap.createScaledBitmap(square, target, target, true)
    }
}
