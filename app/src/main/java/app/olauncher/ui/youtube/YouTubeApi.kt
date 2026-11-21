package app.olauncher.ui.youtube

import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** A video in a list: what the search and a channel's page give. */
data class YtVideo(val id: String, val title: String, val channel: String, val channelId: String, val info: String, val duration: String) {
    val thumbnail get() = "https://i.ytimg.com/vi/$id/mqdefault.jpg"
}

/** A channel: in the search results and in the grid of the subscriptions. */
data class YtChannel(val id: String, val name: String, val avatar: String, val info: String = "")

/** What the player needs to play a video. Either [videoUrl] + [audioUrl], or [muxedUrl], or [hlsUrl]. */
class YtStreams(
    val title: String,
    val author: String,
    val channelId: String,
    val userAgent: String,
    /** Which client gave them, and its place in the list: the next one is asked when these don't play. */
    val client: String,
    val clientIndex: Int,
    /** Video-only streams, one per height, best first. */
    val videos: List<Pair<Int, String>>,
    val audioUrl: String?,
    val muxedUrl: String?,
    val hlsUrl: String?,
    val live: Boolean
)

class YtException(message: String) : IOException(message)

/**
 * The little of YouTube this app needs, asked to the same endpoints its own apps use, with no
 * account: the search, the videos of a channel and the streams of a video.
 */
object YouTubeApi {

    private const val HOST = "https://www.youtube.com/youtubei/v1/"
    private const val WEB_VERSION = "2.20250312.04.00"
    private const val WEB_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"

    /** The clients asked for the streams, in order: their addresses play as they are. */
    private class Client(val id: String, val agent: String, val fields: JSONObject) {
        val version: String get() = fields.getString("clientVersion")
    }

    private val players = listOf(
        Client(
            "28", "com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            JSONObject().put("clientName", "ANDROID_VR").put("clientVersion", "1.62.27").put("deviceMake", "Oculus")
                .put("deviceModel", "Quest 3").put("androidSdkVersion", 32).put("osName", "Android").put("osVersion", "12L")
        ),
        Client(
            "5", "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            JSONObject().put("clientName", "IOS").put("clientVersion", "20.10.4").put("deviceMake", "Apple")
                .put("deviceModel", "iPhone16,2").put("osName", "iPhone").put("osVersion", "18.3.2.22D82")
        ),
        Client(
            "3", "com.google.android.youtube/20.10.38 (Linux; U; Android 14; en_US; Pixel 8 Build/AP2A.240805.005) gzip",
            JSONObject().put("clientName", "ANDROID").put("clientVersion", "20.10.38").put("androidSdkVersion", 34)
                .put("osName", "Android").put("osVersion", "14")
        )
    )

    val clientCount get() = players.size

    /** The visitor YouTube takes us for: streams asked without one are often refused when they are fetched. */
    @Volatile
    var visitor: String? = null

    private fun visitor(): String? {
        visitor?.let { return it }
        runCatching { web("visitor_id", JSONObject()) }
        return visitor
    }

    private fun locale(fields: JSONObject): JSONObject {
        val l = Locale.getDefault()
        return fields.put("hl", l.language.ifBlank { "en" }).put("gl", l.country.ifBlank { "US" })
    }

    private fun post(endpoint: String, client: JSONObject, clientId: String, agent: String, body: JSONObject): JSONObject {
        val known = visitor
        if (known != null) client.put("visitorData", known)
        body.put("context", JSONObject().put("client", locale(client)))
        val conn = URL("$HOST$endpoint?prettyPrint=false").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", agent)
            conn.setRequestProperty("X-YouTube-Client-Name", clientId)
            conn.setRequestProperty("X-YouTube-Client-Version", client.getString("clientVersion"))
            conn.setRequestProperty("Origin", "https://www.youtube.com")
            conn.setRequestProperty("Cookie", "SOCS=CAI")
            if (known != null) conn.setRequestProperty("X-Goog-Visitor-Id", known)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) throw YtException("HTTP $code")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            if (visitor == null) json.optJSONObject("responseContext")?.optString("visitorData")?.takeIf { it.isNotBlank() }?.let { visitor = it }
            return json
        } finally {
            conn.disconnect()
        }
    }

    private fun web(endpoint: String, body: JSONObject) =
        post(endpoint, JSONObject().put("clientName", "WEB").put("clientVersion", WEB_VERSION), "1", WEB_AGENT, body)

    /** Videos and channels for [query], in the order YouTube gives them. */
    fun search(query: String): List<Any> {
        val out = ArrayList<Any>()
        collect(web("search", JSONObject().put("query", query)), out, "", "")
        return out
    }

    /** The channel as YouTube names it now, and its latest videos. */
    fun channel(id: String): Pair<YtChannel?, List<YtVideo>> {
        var info: YtChannel? = null
        val out = ArrayList<Any>()
        try {
            val json = web("browse", JSONObject().put("browseId", id).put("params", "EgZ2aWRlb3PyBgQKAjoA"))
            val meta = json.optJSONObject("metadata")?.optJSONObject("channelMetadataRenderer")
            val name = meta?.optString("title").orEmpty()
            if (meta != null && name.isNotBlank()) info = YtChannel(id, name, thumb(meta.optJSONObject("avatar")))
            collect(json.optJSONObject("contents") ?: JSONObject(), out, name, id)
        } catch (e: Exception) {
            if (e is InterruptedException) throw e
        }
        val videos = out.filterIsInstance<YtVideo>()
        return info to videos.ifEmpty { feed(id) }
    }

    /** The public feed of a channel: its last fifteen videos, for when the page can't be read. */
    private fun feed(id: String): List<YtVideo> {
        val conn = URL("https://www.youtube.com/feeds/videos.xml?channel_id=$id").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", WEB_AGENT)
            if (conn.responseCode !in 200..299) throw YtException("HTTP ${conn.responseCode}")
            val out = ArrayList<YtVideo>()
            conn.inputStream.use { stream ->
                val xml = Xml.newPullParser()
                xml.setInput(stream, null)
                var author = ""
                var videoId = ""
                var title = ""
                var published = ""
                var inEntry = false
                var event = xml.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG) {
                        when (xml.name) {
                            "entry" -> { inEntry = true; videoId = ""; title = ""; published = "" }
                            "yt:videoId", "videoId" -> videoId = xml.nextText()
                            "title" -> if (inEntry) title = xml.nextText()
                            "published" -> if (inEntry) published = xml.nextText().take(10)
                            "name" -> if (author.isEmpty()) author = xml.nextText()
                        }
                    } else if (event == XmlPullParser.END_TAG && xml.name == "entry") {
                        inEntry = false
                        if (videoId.isNotBlank()) out.add(YtVideo(videoId, title, author, id, published, ""))
                    }
                    event = xml.next()
                }
            }
            return out
        } finally {
            conn.disconnect()
        }
    }

    /** The streams of a video, from the first client (from the [from]th on) that gives playable ones. */
    fun streams(videoId: String, from: Int = 0): YtStreams {
        var error: String? = null
        visitor()
        for (index in from until players.size) {
            val client = players[index]
            try {
                val json = post(
                    "player", JSONObject(client.fields.toString()), client.id, client.agent,
                    JSONObject().put("videoId", videoId).put("contentCheckOk", true).put("racyCheckOk", true)
                )
                val status = json.optJSONObject("playabilityStatus")
                if (status?.optString("status") != "OK") {
                    if (error == null) error = status?.optString("reason")?.takeIf { it.isNotBlank() }?.let { client.fields.optString("clientName") + ": " + it }
                    continue
                }
                val data = json.optJSONObject("streamingData") ?: continue
                val details = json.optJSONObject("videoDetails")
                val adaptive = data.optJSONArray("adaptiveFormats") ?: JSONArray()

                // One video-only stream per height: H.264 when there is one (every phone decodes it), else what there is
                val byHeight = HashMap<Int, JSONObject>()
                var audio: JSONObject? = null
                for (i in 0 until adaptive.length()) {
                    val f = adaptive.optJSONObject(i) ?: continue
                    val url = f.optString("url")
                    if (url.isBlank()) continue
                    val mime = f.optString("mimeType")
                    if (mime.startsWith("video/")) {
                        val h = f.optInt("height")
                        if (h <= 0 || h > 1080 || mime.contains("av01")) continue
                        val old = byHeight[h]
                        if (old == null || rank(f) > rank(old)) byHeight[h] = f
                    } else if (mime.startsWith("audio/mp4")) {
                        val track = f.optJSONObject("audioTrack")
                        // Dubbed videos carry a track per language: the original one is kept
                        if (track != null && !track.optBoolean("audioIsDefault", true)) continue
                        if (audio == null || f.optInt("bitrate") > audio.optInt("bitrate")) audio = f
                    }
                }
                val muxed = data.optJSONArray("formats")?.let { formats ->
                    (0 until formats.length()).mapNotNull { formats.optJSONObject(it) }
                        .filter { it.optString("url").isNotBlank() }
                        .maxByOrNull { it.optInt("height") }?.optString("url")
                }
                val hls = data.optString("hlsManifestUrl").takeIf { it.isNotBlank() }
                val videos = if (audio != null) byHeight.entries.sortedByDescending { it.key }.map { it.key to it.value.optString("url") } else emptyList()
                if (videos.isEmpty() && muxed == null && hls == null) continue
                return YtStreams(
                    title = details?.optString("title").orEmpty(),
                    author = details?.optString("author").orEmpty(),
                    channelId = details?.optString("channelId").orEmpty(),
                    userAgent = client.agent,
                    client = client.fields.optString("clientName"),
                    clientIndex = index,
                    videos = videos,
                    audioUrl = audio?.optString("url"),
                    muxedUrl = muxed,
                    hlsUrl = hls,
                    live = details?.optBoolean("isLive") == true || details?.optBoolean("isLiveContent") == true && videos.isEmpty()
                )
            } catch (e: Exception) {
                if (e is InterruptedException) throw e
                if (error == null) error = e.message
            }
        }
        throw YtException(error ?: "")
    }

    private fun rank(format: JSONObject): Int {
        val mime = format.optString("mimeType")
        val codec = if (mime.contains("avc1")) 2 else 1
        // 60 fps over 30 within the same codec
        return codec * 1000 + format.optInt("fps")
    }

    // ---- reading the lists ----

    /** Walks the answer and keeps every video and channel it meets, wherever YouTube has put them this month. */
    private fun collect(node: Any?, out: MutableList<Any>, channelName: String, channelId: String) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = node.opt(key)
                    when {
                        key == "videoRenderer" && value is JSONObject -> video(value, channelName, channelId)?.let { out.add(it) }
                        key == "channelRenderer" && value is JSONObject -> channelItem(value)?.let { out.add(it) }
                        key == "lockupViewModel" && value is JSONObject -> lockup(value, channelName, channelId)?.let { out.add(it) }
                        else -> collect(value, out, channelName, channelId)
                    }
                }
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), out, channelName, channelId)
        }
    }

    private fun video(v: JSONObject, channelName: String, channelId: String): YtVideo? {
        val id = v.optString("videoId")
        if (id.isBlank() || membersOnly(v)) return null
        val owner = v.optJSONObject("ownerText") ?: v.optJSONObject("longBylineText") ?: v.optJSONObject("shortBylineText")
        val ownerRun = owner?.optJSONArray("runs")?.optJSONObject(0)
        val ownerId = ownerRun?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId").orEmpty()
        val info = listOf(text(v.optJSONObject("shortViewCountText") ?: v.optJSONObject("viewCountText")), text(v.optJSONObject("publishedTimeText")))
            .filter { it.isNotBlank() }.joinToString(" · ")
        return YtVideo(
            id, text(v.optJSONObject("title")),
            text(owner).ifBlank { channelName }, ownerId.ifBlank { channelId },
            info, text(v.optJSONObject("lengthText"))
        )
    }

    private fun channelItem(c: JSONObject): YtChannel? {
        val id = c.optString("channelId")
        if (id.isBlank()) return null
        // Where the subscribers are depends on whether the channel has a @handle
        val counts = listOf(text(c.optJSONObject("videoCountText")), text(c.optJSONObject("subscriberCountText")))
        return YtChannel(id, text(c.optJSONObject("title")), thumb(c.optJSONObject("thumbnail")), counts.firstOrNull { it.isNotBlank() && !it.startsWith("@") }.orEmpty())
    }

    /** The newer shape of a video in a list. */
    private fun lockup(l: JSONObject, channelName: String, channelId: String): YtVideo? {
        if (l.optString("contentType") != "LOCKUP_CONTENT_TYPE_VIDEO") return null
        val id = l.optString("contentId")
        if (id.isBlank() || membersOnly(l)) return null
        val meta = l.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
        val parts = ArrayList<String>()
        val rows = meta?.optJSONObject("metadata")?.optJSONObject("contentMetadataViewModel")?.optJSONArray("metadataRows")
        if (rows != null) for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i)?.optJSONArray("metadataParts") ?: continue
            for (j in 0 until row.length()) {
                val t = row.optJSONObject(j)?.optJSONObject("text")?.optString("content").orEmpty()
                if (t.isNotBlank()) parts.add(t)
            }
        }
        // In a search the first part is the channel; on a channel's page every part is about the video
        val owner = if (channelName.isBlank() && parts.size > 1) parts.removeAt(0) else channelName
        return YtVideo(
            id, meta?.optJSONObject("title")?.optString("content").orEmpty(), owner, channelId,
            parts.joinToString(" · "), find(l.optJSONObject("contentImage"), "thumbnailBadgeViewModel")?.optString("text").orEmpty()
        )
    }

    /** A video only the paying members of its channel can watch: it carries a badge that says so. */
    private fun membersOnly(item: JSONObject): Boolean {
        val json = item.toString()
        return json.contains("MEMBERS_ONLY") || json.contains("\"Members only\"") || json.contains("\"Solo per gli abbonati\"")
    }

    private fun find(node: Any?, name: String): JSONObject? {
        when (node) {
            is JSONObject -> {
                node.optJSONObject(name)?.let { return it }
                val keys = node.keys()
                while (keys.hasNext()) find(node.opt(keys.next()), name)?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) find(node.opt(i), name)?.let { return it }
        }
        return null
    }

    private fun text(o: JSONObject?): String {
        if (o == null) return ""
        val simple = o.optString("simpleText")
        if (simple.isNotBlank()) return simple
        val runs = o.optJSONArray("runs") ?: return ""
        return (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
    }

    /** The largest of a list of thumbnails, as a full address. */
    private fun thumb(o: JSONObject?): String {
        val list = o?.optJSONArray("thumbnails") ?: return ""
        val url = list.optJSONObject(list.length() - 1)?.optString("url").orEmpty()
        return if (url.startsWith("//")) "https:$url" else url
    }
}
