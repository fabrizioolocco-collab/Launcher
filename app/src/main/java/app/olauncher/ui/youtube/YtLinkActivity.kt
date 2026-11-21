package app.olauncher.ui.youtube

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * A link of YouTube opened from a browser, a chat or another app (or shared to Tube): the video in
 * Tube's player, from the moment the link names; a channel or anything else in Tube's search.
 * Shows nothing of its own.
 */
class YtLinkActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text -> Regex("https?://\\S+").find(text)?.value }
            else -> intent?.dataString
        }
        val uri = link?.let { runCatching { Uri.parse(it.trim()) }.getOrNull() }
        when (val target = uri?.let { read(it) }) {
            is Target.Video -> startActivity(YtPlayerActivity.linkIntent(this, target.id, target.startMs))
            is Target.Channel -> startActivity(YouTubeActivity.channelIntent(this, YtChannel(target.id, "", "")))
            is Target.Search -> startActivity(YouTubeActivity.searchIntent(this, target.query, podcast = false))
            // youtube.com itself, a page Tube has nothing like (feeds, playlists, settings): Tube's own first page
            null -> startActivity(Intent(this, YouTubeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }

    private sealed class Target {
        class Video(val id: String, val startMs: Long) : Target()
        class Channel(val id: String) : Target()
        class Search(val query: String) : Target()
    }

    private val videoId = Regex("[A-Za-z0-9_-]{11}")

    private fun read(uri: Uri): Target? {
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.")?.removePrefix("music.") ?: return null
        val parts = uri.pathSegments.orEmpty()
        val start = time(uri.getQueryParameter("t") ?: uri.getQueryParameter("start") ?: uri.fragment?.removePrefix("t="))
        val id: String? = when {
            host == "youtu.be" -> parts.firstOrNull()
            host == "youtube.com" || host == "youtube-nocookie.com" -> when (parts.firstOrNull()) {
                "watch" -> uri.getQueryParameter("v")
                "shorts", "live", "embed", "v" -> parts.getOrNull(1)
                "channel" -> return parts.getOrNull(1)?.let { Target.Channel(it) }
                // The old addresses of a channel by its name
                "c", "user" -> return parts.getOrNull(1)?.let { Target.Search(it) }
                // A channel by its name, a playlist, a search: looked for in Tube
                "results" -> return uri.getQueryParameter("search_query")?.let { Target.Search(it) }
                else -> {
                    val first = parts.firstOrNull() ?: return null
                    if (first.startsWith("@")) return Target.Search(first)
                    uri.getQueryParameter("v")
                }
            }
            else -> null
        }
        return id?.takeIf { videoId.matches(it) }?.let { Target.Video(it, start) }
    }

    /** "90", "90s", "1m30s", "1h2m3s" as links write a moment, in milliseconds; -1 when there is none. */
    private fun time(text: String?): Long {
        if (text.isNullOrBlank()) return -1
        text.toLongOrNull()?.let { return it * 1000 }
        val match = Regex("(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?").matchEntire(text) ?: return -1
        val (h, m, s) = match.destructured
        val total = (h.toLongOrNull() ?: 0) * 3600 + (m.toLongOrNull() ?: 0) * 60 + (s.toLongOrNull() ?: 0)
        return if (total > 0) total * 1000 else -1
    }
}
