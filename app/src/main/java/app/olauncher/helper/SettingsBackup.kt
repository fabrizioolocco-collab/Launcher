package app.olauncher.helper

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.system.exitProcess

/**
 * Backup of every setting of the launcher to a single file, to move it to another phone:
 * all its SharedPreferences (as typed JSON) and the files it keeps (fonts, custom icons,
 * bookmarks, to-dos, signature...), zipped. Restoring replaces them and restarts the app.
 * Left out: the OneDrive sign-in (tokens), logs and caches. Widgets of other apps can't move
 * (their ids belong to the old phone): they are dropped and have to be added again.
 */
object SettingsBackup {

    private const val PREFS_ENTRY = "prefs.json"
    private const val FILES_PREFIX = "files/"
    private const val FORMAT = 1

    /** Preferences that hold sign-ins, logs or device state that makes no sense elsewhere. */
    private val SKIPPED_PREFS = setOf(
        "app.minimalauncher.onedrive",
        "app.minimalauncher.widgetlog",
        "app.minimalauncher.test",
    )
    private val SKIPPED_FILES = setOf("scanner-crash.txt")

    private fun prefsDir(context: Context) = File(context.applicationInfo.dataDir, "shared_prefs")

    private fun prefsNames(context: Context): List<String> =
        prefsDir(context).listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".xml") }
            .map { it.name.removeSuffix(".xml") }
            .filter { it !in SKIPPED_PREFS && !it.startsWith("androidx.") && !it.startsWith("com.google.") && !it.startsWith("WebView") }

    fun suggestedName(): String =
        "MinimaLauncher-backup-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date()) + ".zip"

    /** Writes the backup to [uri]; returns false when it couldn't be written. */
    fun export(context: Context, uri: Uri): Boolean = try {
        val ctx = context.applicationContext
        val prefs = JSONObject()
        prefsNames(ctx).forEach { name ->
            val values = JSONObject()
            ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (key, value) ->
                val typed = when (value) {
                    is Boolean -> JSONObject().put("t", "b").put("v", value)
                    is Int -> JSONObject().put("t", "i").put("v", value)
                    is Long -> JSONObject().put("t", "l").put("v", value)
                    is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
                    is String -> JSONObject().put("t", "s").put("v", value)
                    is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(value.map { it.toString() }))
                    else -> null
                }
                if (typed != null) values.put(key, typed)
            }
            prefs.put(name, values)
        }
        val root = JSONObject().put("format", FORMAT).put("app", ctx.packageName).put("prefs", prefs)
        ctx.contentResolver.openOutputStream(uri, "wt")!!.use { out ->
            ZipOutputStream(out.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(PREFS_ENTRY))
                zip.write(root.toString().toByteArray())
                zip.closeEntry()
                val base = ctx.filesDir
                base.walkTopDown().filter { it.isFile && it.name !in SKIPPED_FILES }.forEach { file ->
                    zip.putNextEntry(ZipEntry(FILES_PREFIX + file.relativeTo(base).invariantSeparatorsPath))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }

    /**
     * Replaces the settings with the backup at [uri]. Returns false (changing nothing) when
     * the file isn't a backup of the launcher.
     */
    fun restore(context: Context, uri: Uri): Boolean {
        val ctx = context.applicationContext
        var prefsJson: JSONObject? = null
        val files = LinkedHashMap<String, ByteArray>()
        try {
            ctx.contentResolver.openInputStream(uri)!!.use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) continue
                        when {
                            entry.name == PREFS_ENTRY -> prefsJson = JSONObject(zip.readBytes().decodeToString())
                            entry.name.startsWith(FILES_PREFIX) -> files[entry.name.removePrefix(FILES_PREFIX)] = zip.readBytes()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        val prefs = prefsJson?.optJSONObject("prefs") ?: return false

        // Files first: written next to the live ones, then swapped in
        val base = ctx.filesDir
        files.forEach { (path, bytes) ->
            val target = File(base, path)
            // No paths escaping the app's folder
            if (!target.canonicalPath.startsWith(base.canonicalPath + File.separator)) return@forEach
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
        }

        prefs.keys().forEach { name ->
            if (name in SKIPPED_PREFS) return@forEach
            val values = prefs.optJSONObject(name) ?: return@forEach
            val editor = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            values.keys().forEach { key ->
                val o = values.optJSONObject(key) ?: return@forEach
                when (o.optString("t")) {
                    "b" -> editor.putBoolean(key, o.optBoolean("v"))
                    "i" -> editor.putInt(key, o.optInt("v"))
                    "l" -> editor.putLong(key, o.optLong("v"))
                    "f" -> editor.putFloat(key, o.optDouble("v").toFloat())
                    "s" -> editor.putString(key, if (name == WIDGETS_PREFS) withoutAppWidgets(o.optString("v")) else o.optString("v"))
                    "ss" -> {
                        val array = o.optJSONArray("v") ?: JSONArray()
                        editor.putStringSet(key, (0 until array.length()).map { array.optString(it) }.toSet())
                    }
                }
            }
            editor.commit()
        }
        return true
    }

    private const val WIDGETS_PREFS = "app.minimalauncher.widgets"

    /**
     * Keeps app icons and the launcher's own widgets of a saved home layout; widgets of other
     * apps are dropped, their ids were allocated on the old phone. Other values pass as they are.
     */
    private fun withoutAppWidgets(raw: String): String = try {
        val array = JSONArray(raw)
        val kept = JSONArray()
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            if (o.optString("p").isNotBlank() || o.optString("b").isNotBlank()) kept.put(o)
        }
        kept.toString()
    } catch (e: Exception) {
        raw
    }

    /** Starts the launcher again from scratch, so every screen reads the restored settings. */
    fun restartApp(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (intent != null) context.startActivity(intent)
        exitProcess(0)
    }
}
