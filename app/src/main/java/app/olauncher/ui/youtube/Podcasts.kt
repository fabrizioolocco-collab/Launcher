package app.olauncher.ui.youtube

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.util.Xml
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Podcasts, in the shapes the videos already have, so that the lists, the player and what is
 * remembered (where an episode was left, the ones to listen to, the favourites) are the same code:
 * a podcast is a [YtChannel] whose id is the address of its feed, an episode a [YtVideo] whose id
 * starts with "p:" and that carries the address of its sound.
 */
object PodcastApi {

    private const val AGENT = "MinimaLauncher/1.0 (podcast player)"

    fun isPodcast(channelId: String) = channelId.startsWith("http")

    /** Opens an address following its redirects by hand: they often go from http to https, which Android won't do alone. */
    private fun open(address: String, range: String? = null): HttpURLConnection {
        var url = URL(address)
        repeat(10) {
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", AGENT)
            if (range != null) conn.setRequestProperty("Range", range)
            val code = conn.responseCode
            if (code in 300..399) {
                val next = conn.getHeaderField("Location")
                conn.disconnect()
                if (next.isNullOrBlank()) throw IOException("HTTP $code")
                url = URL(url, next)
            } else {
                if (code !in 200..299) {
                    conn.disconnect()
                    throw IOException("HTTP $code")
                }
                return conn
            }
        }
        throw IOException("Too many redirects")
    }

    /** Copies what is at an address into [out], telling [progress] the bytes so far and the total (-1 unknown). */
    fun copy(address: String, out: java.io.OutputStream, progress: (Long, Long) -> Unit) {
        val conn = open(address)
        try {
            val total = conn.contentLengthLong
            val buffer = ByteArray(64 * 1024)
            var done = 0L
            conn.inputStream.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    done += read
                    progress(done, total)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Where an episode's sound really is, past the counters its address goes through. */
    fun resolve(address: String): String {
        val conn = open(address, "bytes=0-0")
        try {
            return conn.url.toString()
        } finally {
            conn.disconnect()
        }
    }

    /** Podcasts by name, from the public catalogue of Apple Podcasts. */
    fun search(query: String): List<YtChannel> {
        val country = Locale.getDefault().country.ifBlank { "US" }
        val conn = open("https://itunes.apple.com/search?media=podcast&entity=podcast&limit=30&country=$country&term=" + URLEncoder.encode(query, "UTF-8"))
        try {
            val results = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optJSONArray("results") ?: return emptyList()
            return (0 until results.length()).mapNotNull { results.optJSONObject(it) }.mapNotNull { r ->
                val feed = r.optString("feedUrl")
                if (feed.isBlank()) null
                else YtChannel(feed, r.optString("collectionName"), r.optString("artworkUrl600").ifBlank { r.optString("artworkUrl100") }, r.optString("artistName"))
            }
        } finally {
            conn.disconnect()
        }
    }

    /** A podcast as its feed describes it now, and all its episodes, the newest first. */
    fun feed(address: String): YtChannelPage {
        val conn = open(address)
        try {
            var name = ""
            var cover = ""
            class Item {
                var title = ""
                var guid = ""
                var date = 0L
                var audio = ""
                var duration = ""
                var image = ""
            }
            val items = ArrayList<Item>()
            var item: Item? = null
            var inImage = false
            conn.inputStream.use { stream ->
                val xml = Xml.newPullParser()
                xml.setInput(stream, null)
                var event = xml.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG) {
                        val current = item
                        when (xml.name) {
                            "item" -> item = Item()
                            "image" -> inImage = true
                            "title" -> {
                                val text = text(xml)
                                if (current != null) current.title = text else if (!inImage && name.isEmpty()) name = text
                            }
                            "url" -> if (inImage && current == null && cover.isEmpty()) cover = text(xml)
                            "itunes:image" -> {
                                val href = xml.getAttributeValue(null, "href").orEmpty()
                                if (current != null) current.image = href else if (href.isNotBlank()) cover = href
                            }
                            "guid" -> if (current != null) current.guid = text(xml)
                            "pubDate" -> if (current != null) current.date = date(text(xml))
                            "itunes:duration" -> if (current != null) current.duration = duration(text(xml))
                            "enclosure" -> if (current != null && current.audio.isEmpty()) current.audio = xml.getAttributeValue(null, "url").orEmpty()
                        }
                    } else if (event == XmlPullParser.END_TAG) {
                        when (xml.name) {
                            "image" -> inImage = false
                            "item" -> {
                                item?.let { if (it.audio.isNotBlank()) items.add(it) }
                                item = null
                            }
                        }
                    }
                    event = xml.next()
                }
            }
            val day = DateFormat.getDateInstance(DateFormat.MEDIUM)
            val episodes = items.sortedByDescending { it.date }.map {
                YtVideo(
                    id = "p:" + hash(it.guid.ifBlank { it.audio }), title = it.title.trim(), channel = name.trim(), channelId = address,
                    info = if (it.date > 0) day.format(Date(it.date)) else "", duration = it.duration,
                    audio = it.audio, image = it.image.ifBlank { cover }
                )
            }
            return YtChannelPage(YtChannel(address, name.trim().ifBlank { address }, cover), episodes, null)
        } finally {
            conn.disconnect()
        }
    }

    /** The text of the tag the parser is on; empty when it holds tags instead of text. */
    private fun text(xml: XmlPullParser): String = try {
        xml.nextText().orEmpty()
    } catch (e: Exception) {
        ""
    }

    private val dates = listOf("EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm Z")

    private fun date(text: String): Long {
        for (pattern in dates) {
            try {
                return SimpleDateFormat(pattern, Locale.US).parse(text.trim())?.time ?: continue
            } catch (e: Exception) {
                continue
            }
        }
        return 0
    }

    /** "3725" or "1:02:05" as feeds write it, as the lists show it. */
    private fun duration(text: String): String {
        val clean = text.trim()
        val seconds = clean.toLongOrNull() ?: return clean
        return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)
    }

    fun hash(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).take(10).joinToString("") { "%02x".format(it) }

    /** The podcasts put in the first time the section is opened: looked up by name, taken when the name matches. */
    val starters = listOf(
        "Tg La7" to "tgla7",
        "Market Mover" to "marketmover",
        "The Bull Il tuo podcast di finanza personale" to "thebull",
        "Marco Casario" to "marcocasariofinanzaperson",
        "I profeti del codice" to "iprofetidelcodice",
        "Cronache dell'ignoto" to "cronachedellignoto",
        "Crime & Comedy" to "crimecomedy",
        "Non Aprite Quella Podcast" to "nonapritequellapodcast"
    )

    /** The podcast a starter names, or null when the catalogue has none whose name begins that way. */
    fun starter(term: String, begins: String): YtChannel? =
        search(term).firstOrNull { c -> c.name.lowercase().filter { it.isLetterOrDigit() }.startsWith(begins) }
}

/**
 * Episodes kept on the phone to be listened to without a network. The system's download manager
 * does the fetching (it goes on with the app closed, shows its progress, takes up again after a
 * drop); an episode is "downloaded" once its file is there and the manager is done with it.
 */
object PodcastDownloads {

    private const val PREFS = "youtube"
    private const val KEY_JOB = "dl_"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun dir(ctx: Context): File? = ctx.getExternalFilesDir("podcasts")?.apply { mkdirs() }

    fun file(ctx: Context, episodeId: String): File? = dir(ctx)?.let { File(it, PodcastApi.hash(episodeId) + ".audio") }

    fun isDownloading(ctx: Context, episodeId: String) = prefs(ctx).contains(KEY_JOB + episodeId)

    fun isDownloaded(ctx: Context, episodeId: String) = !isDownloading(ctx, episodeId) && file(ctx, episodeId)?.let { it.isFile && it.length() > 0 } == true

    /** The file to play instead of the network, when the episode is on the phone. */
    fun local(ctx: Context, episodeId: String): File? = if (isDownloaded(ctx, episodeId)) file(ctx, episodeId) else null

    /** Starts the download of an episode; call it off the main thread (it first finds where the file really is). */
    fun start(ctx: Context, episode: YtVideo): Boolean {
        if (isDownloading(ctx, episode.id) || isDownloaded(ctx, episode.id)) return true
        val target = file(ctx, episode.id) ?: return false
        return try {
            target.delete()
            val address = PodcastApi.resolve(episode.audio)
            val request = DownloadManager.Request(Uri.parse(address))
                .setTitle(episode.title)
                .setDescription(episode.channel)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationUri(Uri.fromFile(target))
            val job = (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            prefs(ctx).edit().putLong(KEY_JOB + episode.id, job).apply()
            YtStore.setDownload(ctx, episode, true)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /** Deletes a downloaded episode, or stops its download. */
    fun remove(ctx: Context, episode: YtVideo) {
        val key = KEY_JOB + episode.id
        if (prefs(ctx).contains(key)) {
            runCatching { (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(prefs(ctx).getLong(key, 0)) }
            prefs(ctx).edit().remove(key).apply()
        }
        file(ctx, episode.id)?.delete()
        YtStore.setDownload(ctx, episode, false)
    }

    /**
     * Asks the manager about the downloads under way; true when one has just ended (well or badly),
     * for the list to be drawn again.
     */
    fun poll(ctx: Context): Boolean {
        val pending = prefs(ctx).all.filterKeys { it.startsWith(KEY_JOB) }
        if (pending.isEmpty()) return false
        val manager = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        var changed = false
        for ((key, value) in pending) {
            val job = value as? Long ?: continue
            val episodeId = key.removePrefix(KEY_JOB)
            var status = DownloadManager.STATUS_FAILED
            runCatching {
                manager.query(DownloadManager.Query().setFilterById(job))?.use { c ->
                    if (c.moveToFirst()) status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                }
            }
            if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
                prefs(ctx).edit().remove(key).apply()
                if (status == DownloadManager.STATUS_FAILED) {
                    file(ctx, episodeId)?.delete()
                    YtStore.downloads(ctx).firstOrNull { it.id == episodeId }?.let { YtStore.setDownload(ctx, it, false) }
                }
                changed = true
            }
        }
        return changed
    }
}
