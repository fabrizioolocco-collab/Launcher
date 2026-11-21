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
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** The subscriptions: kept on the phone only, there is no account. */
object YtStore {

    private const val PREFS = "youtube"
    private const val KEY_SUBS = "subscriptions"
    private const val KEY_HEIGHT = "max_height"
    private const val KEY_PATH = "path"
    private const val KEY_SEEN = "seen_"
    private const val KEY_WATCHED = "watched"
    private const val KEY_POSITIONS = "positions"
    private const val KEY_HIDE_WATCHED = "hide_watched"
    private const val KEY_FAVORITES = "favorites"
    private const val KEY_LATER = "later"
    private const val KEY_DOWNLOADS = "downloads"
    private const val KEY_PODCASTS = "podcasts"
    private const val KEY_PODCAST_MODE = "podcast_mode"
    private const val KEY_STARTERS = "podcast_starters"
    private const val KEY_NEW = "new_"
    private const val KEY_CHECKED = "checked_"
    private const val KEY_VISITOR = "visitor"
    private const val KEY_VISITOR_TIME = "visitor_time"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun subscriptions(ctx: Context): List<YtChannel> = channels(ctx, KEY_SUBS)

    /** The podcasts followed: kept like the channels, in a list of their own. */
    fun podcasts(ctx: Context): List<YtChannel> = channels(ctx, KEY_PODCASTS)

    /** Videos or podcasts: which of the two the app was showing the last time. */
    fun podcastMode(ctx: Context) = prefs(ctx).getBoolean(KEY_PODCAST_MODE, false)
    fun setPodcastMode(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY_PODCAST_MODE, on).apply()

    /** Whether the podcasts given with the app have been put in already. */
    fun startersDone(ctx: Context) = prefs(ctx).getBoolean(KEY_STARTERS, false)
    fun setStartersDone(ctx: Context) = prefs(ctx).edit().putBoolean(KEY_STARTERS, true).apply()

    private fun keyOf(channelId: String) = if (PodcastApi.isPodcast(channelId)) KEY_PODCASTS else KEY_SUBS

    private fun channels(ctx: Context, key: String): List<YtChannel> {
        val array = try {
            JSONArray(prefs(ctx).getString(key, "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            .map { YtChannel(it.optString("id"), it.optString("name"), it.optString("avatar")) }
            .filter { it.id.isNotBlank() }
            .sortedBy { it.name.lowercase() }
    }

    private fun save(ctx: Context, key: String, list: List<YtChannel>) {
        val array = JSONArray()
        list.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("avatar", it.avatar)) }
        prefs(ctx).edit().putString(key, array.toString()).apply()
    }

    fun isSubscribed(ctx: Context, id: String) = channels(ctx, keyOf(id)).any { it.id == id }

    /** Adds the channel (or the podcast), or brings its name and picture up to date. */
    fun subscribe(ctx: Context, channel: YtChannel) {
        val key = keyOf(channel.id)
        save(ctx, key, channels(ctx, key).filter { it.id != channel.id } + channel)
    }

    /** Updates a channel already there (a picture found later), and only that. */
    fun refresh(ctx: Context, channel: YtChannel) {
        val key = keyOf(channel.id)
        val list = channels(ctx, key)
        val old = list.firstOrNull { it.id == channel.id } ?: return
        val avatar = channel.avatar.ifBlank { old.avatar }
        // A podcast keeps the name it was followed under: feeds like to add slogans to theirs
        val name = if (PodcastApi.isPodcast(channel.id)) old.name else channel.name
        if (old.name == name && old.avatar == avatar) return
        save(ctx, key, list.map { if (it.id == channel.id) YtChannel(it.id, name, avatar) else it })
    }

    fun unsubscribe(ctx: Context, id: String) {
        val key = keyOf(id)
        save(ctx, key, channels(ctx, key).filter { it.id != id })
        prefs(ctx).edit().remove(KEY_SEEN + id).remove(KEY_NEW + id).remove(KEY_CHECKED + id).apply()
    }

    // ---- watched videos and favourites ----

    private var watchedCache: LinkedHashSet<String>? = null

    private fun watched(ctx: Context): LinkedHashSet<String> =
        watchedCache ?: LinkedHashSet(ids(ctx, KEY_WATCHED).orEmpty()).also { watchedCache = it }

    fun isWatched(ctx: Context, videoId: String) = videoId in watched(ctx)

    /** A video played to the end, or marked by hand as watched or not. */
    fun setWatched(ctx: Context, videoId: String, on: Boolean) {
        val set = watched(ctx)
        if (on == (videoId in set)) return
        if (on) set.add(videoId) else set.remove(videoId)
        putIds(ctx, KEY_WATCHED, set.toList().takeLast(5000))
    }

    fun hideWatched(ctx: Context) = prefs(ctx).getBoolean(KEY_HIDE_WATCHED, false)
    fun setHideWatched(ctx: Context, hide: Boolean) = prefs(ctx).edit().putBoolean(KEY_HIDE_WATCHED, hide).apply()

    private fun videos(ctx: Context, key: String): List<YtVideo> {
        val array = runCatching { JSONArray(prefs(ctx).getString(key, "[]")) }.getOrDefault(JSONArray())
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            .map {
                YtVideo(
                    it.optString("id"), it.optString("title"), it.optString("channel"), it.optString("channelId"),
                    it.optString("info"), it.optString("duration"), it.optString("audio"), it.optString("image")
                )
            }
            .filter { it.id.isNotBlank() }
    }

    /** Puts a video on top of a list, or takes it out. */
    private fun setIn(ctx: Context, key: String, video: YtVideo, on: Boolean) {
        val old = videos(ctx, key)
        if (!on && old.none { it.id == video.id }) return
        val rest = old.filter { it.id != video.id }
        val array = JSONArray()
        (if (on) listOf(video) + rest else rest).forEach {
            array.put(
                JSONObject().put("id", it.id).put("title", it.title).put("channel", it.channel)
                    .put("channelId", it.channelId).put("info", it.info).put("duration", it.duration)
                    .put("audio", it.audio).put("image", it.image)
            )
        }
        prefs(ctx).edit().putString(key, array.toString()).apply()
    }

    /** The videos kept as favourites, the last added first. */
    fun favorites(ctx: Context) = videos(ctx, KEY_FAVORITES)
    fun isFavorite(ctx: Context, videoId: String) = favorites(ctx).any { it.id == videoId }
    fun setFavorite(ctx: Context, video: YtVideo, on: Boolean) = setIn(ctx, KEY_FAVORITES, video, on)

    /** The videos put aside to watch, the last added first: one played to the end leaves the list. */
    fun later(ctx: Context) = videos(ctx, KEY_LATER)
    fun isLater(ctx: Context, videoId: String) = later(ctx).any { it.id == videoId }
    fun setLater(ctx: Context, video: YtVideo, on: Boolean) = setIn(ctx, KEY_LATER, video, on)
    /** The episodes downloaded or being downloaded (see [PodcastDownloads]), the last asked first. */
    fun downloads(ctx: Context) = videos(ctx, KEY_DOWNLOADS)
    fun setDownload(ctx: Context, episode: YtVideo, on: Boolean) = setIn(ctx, KEY_DOWNLOADS, episode, on)

    fun dropLater(ctx: Context, videoId: String) = setIn(ctx, KEY_LATER, YtVideo(videoId, "", "", "", "", ""), false)

    // ---- where each video was left ----

    private var positionCache: LinkedHashMap<String, LongArray>? = null

    private fun positions(ctx: Context): LinkedHashMap<String, LongArray> {
        positionCache?.let { return it }
        val map = LinkedHashMap<String, LongArray>()
        runCatching {
            val json = JSONObject(prefs(ctx).getString(KEY_POSITIONS, "{}") ?: "{}")
            for (id in json.keys()) {
                val pair = json.optJSONArray(id) ?: continue
                map[id] = longArrayOf(pair.optLong(0), pair.optLong(1))
            }
        }
        positionCache = map
        return map
    }

    private fun savePositions(ctx: Context, map: LinkedHashMap<String, LongArray>) {
        val json = JSONObject()
        map.forEach { (id, pair) -> json.put(id, JSONArray().put(pair[0]).put(pair[1])) }
        prefs(ctx).edit().putString(KEY_POSITIONS, json.toString()).apply()
    }

    /** Where a video was left, in milliseconds (0: from the start). */
    fun position(ctx: Context, videoId: String) = positions(ctx)[videoId]?.get(0) ?: 0L

    /** How much of a video was played, 0 to 1 (0: never left half way). */
    fun progress(ctx: Context, videoId: String): Float {
        val pair = positions(ctx)[videoId] ?: return 0f
        return if (pair[1] > 0) (pair[0].toFloat() / pair[1]).coerceIn(0f, 1f) else 0f
    }

    /** Remembers the point of the last few hundred videos; a video played to the end starts over. */
    fun setPosition(ctx: Context, videoId: String, position: Long, duration: Long) {
        val map = positions(ctx)
        map.remove(videoId)
        if (position > 0) map[videoId] = longArrayOf(position, duration)
        while (map.size > 300) map.remove(map.keys.first())
        savePositions(ctx, map)
    }

    // ---- new videos ----

    private fun ids(ctx: Context, key: String): List<String>? {
        val text = prefs(ctx).getString(key, null) ?: return null
        return runCatching { JSONArray(text).let { a -> (0 until a.length()).map { a.optString(it) } } }.getOrNull()
    }

    private fun putIds(ctx: Context, key: String, ids: Collection<String>) =
        prefs(ctx).edit().putString(key, JSONArray(ids).toString()).apply()

    /** The videos of a channel that came out after it was first looked at and haven't been opened yet. */
    fun newVideos(ctx: Context, channelId: String): List<String> = ids(ctx, KEY_NEW + channelId).orEmpty()

    /** Whether a channel's videos were read too long ago to trust the count. */
    fun due(ctx: Context, channelId: String) =
        System.currentTimeMillis() - prefs(ctx).getLong(KEY_CHECKED + channelId, 0) > 10 * 60_000L

    /**
     * The latest videos of a channel, just read. The first time they are all taken as known, so
     * that the count starts from that day; from then on, the ones never met before are new.
     */
    fun update(ctx: Context, channelId: String, latest: List<String>) {
        if (latest.isEmpty()) return
        val seen = ids(ctx, KEY_SEEN + channelId)
        if (seen == null) {
            putIds(ctx, KEY_SEEN + channelId, latest)
            putIds(ctx, KEY_NEW + channelId, emptyList())
        } else {
            val known = seen.toHashSet()
            putIds(ctx, KEY_NEW + channelId, latest.filter { it !in known })
        }
        prefs(ctx).edit().putLong(KEY_CHECKED + channelId, System.currentTimeMillis()).apply()
    }

    /** Videos opened, or dismissed all together: no longer counted. */
    fun markSeen(ctx: Context, channelId: String, videos: Collection<String>) {
        if (channelId.isBlank() || videos.isEmpty()) return
        val seen = ids(ctx, KEY_SEEN + channelId) ?: return
        putIds(ctx, KEY_SEEN + channelId, (seen + videos).distinct().takeLast(400))
        putIds(ctx, KEY_NEW + channelId, newVideos(ctx, channelId).filter { it !in videos })
    }

    /** The client and the way that played last time (client * 10 + way): the next video starts from there. */
    fun path(ctx: Context) = prefs(ctx).getInt(KEY_PATH, 0)
    fun setPath(ctx: Context, path: Int) {
        if (path != path(ctx)) prefs(ctx).edit().putInt(KEY_PATH, path).apply()
    }

    /** The visitor id YouTube gave, good for half a day: one request less before every first video. */
    fun visitor(ctx: Context): String? =
        prefs(ctx).getString(KEY_VISITOR, null)?.takeIf { System.currentTimeMillis() - prefs(ctx).getLong(KEY_VISITOR_TIME, 0) < 12 * 3600_000L }

    fun setVisitor(ctx: Context, visitor: String) {
        if (visitor == visitor(ctx)) return
        prefs(ctx).edit().putString(KEY_VISITOR, visitor).putLong(KEY_VISITOR_TIME, System.currentTimeMillis()).apply()
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
        // Asked again for the picture it already shows (a list drawn again): nothing to do, and no blink
        if (view.tag == url && view.drawable != null) return
        val same = view.tag == url
        view.tag = url
        if (url.isBlank()) {
            view.setImageDrawable(null)
            return
        }
        memory.get(url)?.let {
            view.setImageBitmap(it)
            return
        }
        if (!same) view.setImageDrawable(null)
        val ctx = view.context.applicationContext
        pool.execute {
            val bitmap = runCatching { fetch(ctx, url) }.getOrNull() ?: return@execute
            memory.put(url, bitmap)
            main.post { if (view.tag == url) view.setImageBitmap(bitmap) }
        }
    }

    /**
     * A picture no larger than the lists show it: covers of podcasts come at 600 or 3000 pixels a
     * side, and a few of those filled the memory, so that the others had to be loaded again and again.
     */
    private fun small(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 320) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    }

    private fun fetch(ctx: Context, url: String): Bitmap? {
        val dir = File(ctx.cacheDir, "youtube").apply { mkdirs() }
        val file = File(dir, url.hashCode().toString(16) + "_" + url.length)
        if (file.length() > 0) small(file.readBytes())?.let { return it }
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            if (conn.responseCode !in 200..299) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            val bitmap = small(bytes) ?: return null
            file.writeBytes(bytes)
            return bitmap
        } finally {
            conn.disconnect()
        }
    }
}
