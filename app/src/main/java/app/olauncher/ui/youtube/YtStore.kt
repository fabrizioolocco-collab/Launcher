package app.olauncher.ui.youtube

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** The subscriptions: kept on the phone only, there is no account. */
object YtStore {

    private const val PREFS = "youtube"
    private const val KEY_SUBS = "subscriptions"
    private const val KEY_HEIGHT = "max_height"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun subscriptions(ctx: Context): List<YtChannel> {
        val array = try {
            JSONArray(prefs(ctx).getString(KEY_SUBS, "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            .map { YtChannel(it.optString("id"), it.optString("name"), it.optString("avatar")) }
            .filter { it.id.isNotBlank() }
            .sortedBy { it.name.lowercase() }
    }

    private fun save(ctx: Context, list: List<YtChannel>) {
        val array = JSONArray()
        list.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("avatar", it.avatar)) }
        prefs(ctx).edit().putString(KEY_SUBS, array.toString()).apply()
    }

    fun isSubscribed(ctx: Context, id: String) = subscriptions(ctx).any { it.id == id }

    /** Adds the channel, or brings its name and picture up to date. */
    fun subscribe(ctx: Context, channel: YtChannel) {
        val list = subscriptions(ctx).filter { it.id != channel.id }
        save(ctx, list + channel)
    }

    /** Updates a channel already there (a picture found later), and only that. */
    fun refresh(ctx: Context, channel: YtChannel) {
        val list = subscriptions(ctx)
        val old = list.firstOrNull { it.id == channel.id } ?: return
        if (old.name == channel.name && old.avatar == channel.avatar) return
        save(ctx, list.map { if (it.id == channel.id) channel.copy(avatar = channel.avatar.ifBlank { old.avatar }) else it })
    }

    fun unsubscribe(ctx: Context, id: String) = save(ctx, subscriptions(ctx).filter { it.id != id })

    /**
     * The subscriptions file of Google Takeout (subscriptions.csv: channel id, address, title).
     * Returns how many channels were new.
     */
    fun importCsv(ctx: Context, input: InputStream): Int {
        val list = subscriptions(ctx).toMutableList()
        val known = list.map { it.id }.toHashSet()
        var added = 0
        input.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val cells = cells(line)
                val id = cells.firstOrNull()?.trim().orEmpty()
                if (!Regex("UC[\\w-]{22}").matches(id) || !known.add(id)) return@forEach
                list.add(YtChannel(id, cells.getOrNull(2)?.trim().orEmpty().ifBlank { id }, ""))
                added++
            }
        }
        if (added > 0) save(ctx, list)
        return added
    }

    private fun cells(line: String): List<String> {
        val out = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out.add(cell.toString()); cell.setLength(0) }
                else -> cell.append(c)
            }
            i++
        }
        out.add(cell.toString())
        return out
    }

    /** The tallest video asked for: 1080p costs data that a phone screen rarely shows. */
    fun maxHeight(ctx: Context) = prefs(ctx).getInt(KEY_HEIGHT, 720)
    fun setMaxHeight(ctx: Context, height: Int) = prefs(ctx).edit().putInt(KEY_HEIGHT, height).apply()
}

/** Thumbnails and channel pictures: loaded off the main thread, kept in memory and in the cache folder. */
object YtImages {

    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())
    private val memory = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun load(view: ImageView, url: String) {
        view.tag = url
        if (url.isBlank()) {
            view.setImageDrawable(null)
            return
        }
        memory.get(url)?.let {
            view.setImageBitmap(it)
            return
        }
        view.setImageDrawable(null)
        val ctx = view.context.applicationContext
        pool.execute {
            val bitmap = runCatching { fetch(ctx, url) }.getOrNull() ?: return@execute
            memory.put(url, bitmap)
            main.post { if (view.tag == url) view.setImageBitmap(bitmap) }
        }
    }

    private fun fetch(ctx: Context, url: String): Bitmap? {
        val dir = File(ctx.cacheDir, "youtube").apply { mkdirs() }
        val file = File(dir, url.hashCode().toString(16) + "_" + url.length)
        if (file.length() > 0) BitmapFactory.decodeFile(file.path)?.let { return it }
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            if (conn.responseCode !in 200..299) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            file.writeBytes(bytes)
            return bitmap
        } finally {
            conn.disconnect()
        }
    }
}
