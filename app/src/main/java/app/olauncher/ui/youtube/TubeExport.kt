package app.olauncher.ui.youtube

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import app.olauncher.R
import app.olauncher.helper.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

/**
 * Saves a video or an episode as a file where the user chose: a video as MP4 (its picture and its
 * sound, which YouTube keeps apart, are fetched and put together without encoding anything again),
 * the sound of a video alone as M4A, an episode as the file its podcast publishes. It runs apart
 * from any screen and tells how far it is with a notification.
 */
object TubeExport {

    const val VIDEO = 0
    const val AUDIO = 1
    const val EPISODE = 2

    private const val CHANNEL = "tube_export"
    private const val CHUNK = 8L * 1024 * 1024
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private var count = 0

    /** The name and the type offered for the file: an episode keeps the format of its podcast. */
    fun fileFor(item: YtVideo, kind: Int): Pair<String, String> {
        val name = item.title.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), " ").trim().take(80).ifBlank { "tube" }
        return when (kind) {
            VIDEO -> "$name.mp4" to "video/mp4"
            AUDIO -> "$name.m4a" to "audio/mp4"
            else -> {
                val path = item.audio.substringBefore('?').lowercase()
                if (path.endsWith(".m4a") || path.endsWith(".mp4") || path.endsWith(".aac")) "$name.m4a" to "audio/mp4" else "$name.mp3" to "audio/mpeg"
            }
        }
    }

    fun save(context: Context, item: YtVideo, kind: Int, target: Uri) {
        val ctx = context.applicationContext
        val id = 7300 + (count++ % 50)
        scope.launch {
            val work = File(ctx.cacheDir, "tube_export/$id").apply {
                deleteRecursively()
                mkdirs()
            }
            try {
                notify(ctx, id, item.title, 0, true)
                if (kind == EPISODE) saveEpisode(ctx, item, target) { notify(ctx, id, item.title, it, true) }
                else saveVideo(ctx, item, kind == AUDIO, target, work) { notify(ctx, id, item.title, it, true) }
                notify(ctx, id, item.title, 100, false)
                main.post { ctx.showToast(ctx.getString(R.string.ml_yt_saved)) }
            } catch (e: Throwable) {
                e.printStackTrace()
                runCatching { android.provider.DocumentsContract.deleteDocument(ctx.contentResolver, target) }
                cancel(ctx, id)
                val why = e.message?.take(120).orEmpty()
                main.post { ctx.showToast(ctx.getString(R.string.ml_yt_save_failed) + if (why.isNotBlank()) "\n$why" else "") }
            } finally {
                work.deleteRecursively()
            }
        }
    }

    private fun saveEpisode(ctx: Context, item: YtVideo, target: Uri, progress: (Int) -> Unit) {
        val out = ctx.contentResolver.openOutputStream(target) ?: throw IOException("No file")
        out.use { stream ->
            val local = PodcastDownloads.local(ctx, item.id)
            if (local != null) local.inputStream().use { it.copyTo(stream) }
            else {
                var last = -1
                PodcastApi.copy(item.audio, stream) { done, total ->
                    val percent = if (total > 0) (done * 100 / total).toInt() else 0
                    if (percent != last) {
                        last = percent
                        progress(percent)
                    }
                }
            }
        }
    }

    private fun saveVideo(ctx: Context, item: YtVideo, audioOnly: Boolean, target: Uri, work: File, progress: (Int) -> Unit) {
        if (YouTubeApi.visitor == null) YouTubeApi.visitor = YtStore.visitor(ctx)
        val streams = YouTubeApi.streams(item.id)
        if (streams.live) throw IOException("Live")
        val max = YtStore.maxHeight(ctx)
        val picture = if (audioOnly) null else (streams.mp4Videos.firstOrNull { it.first <= max } ?: streams.mp4Videos.lastOrNull())?.second
        val sound = streams.audioUrl
        val result = File(work, "out")
        when {
            audioOnly && sound != null -> {
                val a = File(work, "a")
                fetch(sound, streams.userAgent, a) { progress(it * 9 / 10) }
                mux(listOf(a), result)
            }
            picture != null && sound != null -> {
                val v = File(work, "v")
                val a = File(work, "a")
                // The picture is nearly all of the bytes: nine tenths of the bar
                fetch(picture, streams.userAgent, v) { progress(it * 8 / 10) }
                fetch(sound, streams.userAgent, a) { progress(80 + it / 10) }
                mux(listOf(v, a), result)
            }
            // No separate streams from this client: the one small file that has both
            !audioOnly && streams.muxedUrl != null -> fetch(streams.muxedUrl, streams.userAgent, result) { progress(it * 9 / 10) }
            else -> throw IOException(streams.client)
        }
        progress(95)
        val out = ctx.contentResolver.openOutputStream(target) ?: throw IOException("No file")
        out.use { stream -> result.inputStream().use { it.copyTo(stream, 256 * 1024) } }
    }

    /** Fetches a stream of YouTube into a file, a piece at a time (in one go it is slowed down to a crawl). */
    private fun fetch(address: String, agent: String, file: File, progress: (Int) -> Unit) {
        val total = runCatching { Uri.parse(address).getQueryParameter("clen")?.toLong() }.getOrNull() ?: -1L
        file.outputStream().use { out ->
            var position = 0L
            var last = -1
            val buffer = ByteArray(128 * 1024)
            while (total < 0 || position < total) {
                val end = if (total > 0) minOf(position + CHUNK, total) - 1 else -1L
                var tries = 0
                while (true) {
                    val start = position
                    try {
                        val conn = URL(address).openConnection() as HttpURLConnection
                        try {
                            conn.connectTimeout = 15000
                            conn.readTimeout = 30000
                            conn.setRequestProperty("User-Agent", agent)
                            if (end >= 0) conn.setRequestProperty("Range", "bytes=$position-$end")
                            val code = conn.responseCode
                            if (code !in 200..299) throw IOException("HTTP $code")
                            conn.inputStream.use { input ->
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    out.write(buffer, 0, read)
                                    position += read
                                    val percent = if (total > 0) (position * 100 / total).toInt() else 0
                                    if (percent != last) {
                                        last = percent
                                        progress(percent)
                                    }
                                }
                            }
                        } finally {
                            conn.disconnect()
                        }
                        break
                    } catch (e: IOException) {
                        // A piece that broke half way is asked again from where it got to, a few times
                        if (++tries > 3 || (total < 0 && position > start)) throw e
                    }
                }
                if (total < 0) break
            }
        }
    }

    /** Puts the tracks of [inputs] (a picture and a sound, or a sound alone) in one MP4 file, as they are. */
    private fun mux(inputs: List<File>, out: File) {
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val extractors = inputs.map { MediaExtractor().apply { setDataSource(it.path) } }
        try {
            var size = 2 * 1024 * 1024
            val tracks = extractors.map { extractor ->
                if (extractor.trackCount < 1) throw IOException("No track")
                extractor.selectTrack(0)
                val format = extractor.getTrackFormat(0)
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) size = maxOf(size, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                muxer.addTrack(format)
            }
            muxer.start()
            val buffer = ByteBuffer.allocate(size.coerceAtMost(32 * 1024 * 1024))
            val info = MediaCodec.BufferInfo()
            val live = BooleanArray(extractors.size) { true }
            while (true) {
                // Always the track that is behind: the two end up side by side in the file, as players want
                var next = -1
                var time = Long.MAX_VALUE
                for (i in extractors.indices) {
                    if (!live[i]) continue
                    val t = extractors[i].sampleTime
                    if (t < 0) live[i] = false else if (t < time) {
                        time = t
                        next = i
                    }
                }
                if (next < 0) break
                val extractor = extractors[next]
                val read = extractor.readSampleData(buffer, 0)
                if (read < 0) {
                    live[next] = false
                    continue
                }
                val key = (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                info.set(0, read, time, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(tracks[next], buffer, info)
                extractor.advance()
            }
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
            extractors.forEach { runCatching { it.release() } }
        }
    }

    private fun notify(ctx: Context, id: Int, title: String, percent: Int, going: Boolean) {
        try {
            val manager = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) manager.createNotificationChannel(
                NotificationChannel(CHANNEL, ctx.getString(R.string.ml_yt_save_channel), NotificationManager.IMPORTANCE_LOW)
            )
            val builder = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_m_download)
                .setContentTitle(title)
                .setContentText(ctx.getString(if (going) R.string.ml_yt_saving else R.string.ml_yt_saved))
                .setOngoing(going)
                .setOnlyAlertOnce(true)
            if (going) builder.setProgress(100, percent, percent == 0)
            manager.notify(id, builder.build())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun cancel(ctx: Context, id: Int) {
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.cancel(id) }
    }
}
