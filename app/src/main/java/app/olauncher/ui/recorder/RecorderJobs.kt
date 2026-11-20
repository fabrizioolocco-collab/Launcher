package app.olauncher.ui.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import app.olauncher.R
import app.olauncher.helper.showToast
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.Executors

/** The transcripts and AI write-ups being made, for the screens that show them. */
object RecorderJobs {

    /** Recording (audio path) -> what is being done to it right now. */
    val running = MutableLiveData<Map<String, String>>(emptyMap())

    /** Goes up whenever the files of a recording changed (a job ended, a recording was saved). */
    val changes = MutableLiveData(0)

    fun changed() = changes.postValue((changes.value ?: 0) + 1)

    internal fun status(audio: File, message: String?) {
        synchronized(this) {
            val map = current.toMutableMap()
            if (message == null) map.remove(audio.absolutePath) else map[audio.absolutePath] = message
            current = map
            running.postValue(map)
        }
    }

    /** As [running], but up to date off the main thread too. */
    @Volatile
    private var current: Map<String, String> = emptyMap()

    fun isBusy(audio: File) = current.containsKey(audio.absolutePath)

    fun transcribe(context: Context, audio: File) = start(context, audio) {}

    /** [rewrite]: the result is as long as the transcript (a clean copy), not a digest of it. */
    fun elaborate(context: Context, audio: File, title: String, instruction: String, rewrite: Boolean) = start(context, audio) {
        putExtra(RecorderJobService.EXTRA_TITLE, title)
        putExtra(RecorderJobService.EXTRA_INSTRUCTION, instruction)
        putExtra(RecorderJobService.EXTRA_REWRITE, rewrite)
    }

    private fun start(context: Context, audio: File, extras: Intent.() -> Unit) {
        if (isBusy(audio)) return
        status(audio, context.getString(R.string.ml_rec_queued))
        try {
            ContextCompat.startForegroundService(
                context, Intent(context, RecorderJobService::class.java).putExtra(RecorderJobService.EXTRA_AUDIO, audio.absolutePath).apply(extras)
            )
        } catch (e: Exception) {
            e.printStackTrace()
            status(audio, null)
        }
    }

    fun cancel(context: Context) {
        try {
            context.startService(Intent(context, RecorderJobService::class.java).setAction(RecorderJobService.ACTION_CANCEL))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

/**
 * Makes transcripts and write-ups one at a time, as a foreground service with its progress in a
 * notification: they go on when the screen is off or the recorder is closed.
 */
class RecorderJobService : Service() {

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var pending = 0
    @Volatile
    private var cancelled = false
    private var wakeLock: PowerManager.WakeLock? = null

    private class Cancelled : Exception()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelled = pending > 0
            if (pending == 0) stopSelf()
            return START_NOT_STICKY
        }
        val audio = intent?.getStringExtra(EXTRA_AUDIO)?.let { File(it) }
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            ServiceCompat.startForeground(this, NOTIFICATION, notification(getString(R.string.ml_rec_working)), type)
        } catch (e: Exception) {
            // Started from the background (a recording stopped from its notification): it runs all the same
            e.printStackTrace()
        }
        if (audio == null) {
            if (pending == 0) stopSelf()
            return START_NOT_STICKY
        }
        val title = intent?.getStringExtra(EXTRA_TITLE)
        val instruction = intent?.getStringExtra(EXTRA_INSTRUCTION)
        val rewrite = intent?.getBooleanExtra(EXTRA_REWRITE, false) == true
        pending++
        if (wakeLock == null) wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "minimalauncher:recorder-jobs").apply { acquire(MAX_WORK) }
        executor.execute {
            var error: String? = null
            try {
                if (cancelled) throw Cancelled()
                if (title != null && instruction != null) elaborate(audio, title, instruction, rewrite) else transcribe(audio)
            } catch (e: Cancelled) {
            } catch (e: Groq.GroqException) {
                error = if (e.code == 401) getString(R.string.ml_rec_key_wrong) else e.message
            } catch (e: java.io.IOException) {
                error = getString(R.string.ml_rec_no_network)
            } catch (e: Exception) {
                e.printStackTrace()
                error = e.message ?: e.javaClass.simpleName
            }
            RecorderJobs.status(audio, null)
            RecorderJobs.changed()
            val failure = error
            main.post {
                if (failure != null && !cancelled) showToast(getString(R.string.ml_rec_error, audio.nameWithoutExtension, failure), android.widget.Toast.LENGTH_LONG)
                if (--pending == 0) {
                    cancelled = false
                    try {
                        wakeLock?.takeIf { it.isHeld }?.release()
                    } catch (ignored: Exception) {
                    }
                    wakeLock = null
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    /** What is being done, in the notification and on the recording's row. */
    private fun progress(audio: File, message: String) {
        RecorderJobs.status(audio, message)
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(audio.nameWithoutExtension + ": " + message))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun waiting(audio: File) = { seconds: Long -> progress(audio, getString(R.string.ml_rec_waiting, seconds.toInt())) }

    private fun key(): String = RecorderStore.apiKey(this).ifEmpty { throw Exception(getString(R.string.ml_rec_key_missing)) }

    // ---------------------------------------------------------------- transcript

    private fun transcribe(audio: File) {
        val key = key()
        val label = getString(R.string.ml_rec_transcribing)
        progress(audio, label)
        val parts = split(audio)
        val segments = mutableListOf<Groq.Segment>()
        try {
            parts.forEachIndexed { i, (file, offset) ->
                if (cancelled) throw Cancelled()
                val step = if (parts.size > 1) "$label ${i + 1}/${parts.size}" else label
                progress(audio, step)
                val found = Groq.transcribe(key, file, RecorderStore.whisperModel(this), RecorderStore.language(this), waiting(audio)) { cancelled }
                progress(audio, step)
                segments += found.map { Groq.Segment(it.start + offset, it.end + offset, it.text) }
            }
        } finally {
            parts.forEach { (file, _) -> if (file != audio) file.delete() }
        }
        if (cancelled) throw Cancelled()
        if (segments.isEmpty()) throw Exception(getString(R.string.ml_rec_no_speech))
        RecorderStore.transcript(audio).writeText(format(segments, RecorderStore.timestamps(this)))
    }

    /** Paragraphs: a new one at a pause, or after a full stop once it's long enough. */
    private fun format(segments: List<Groq.Segment>, times: Boolean): String {
        val paragraphs = mutableListOf<Pair<Double, StringBuilder>>()
        var lastEnd = 0.0
        for (s in segments) {
            val current = paragraphs.lastOrNull()
            val ended = current != null && current.second.trimEnd().lastOrNull()?.let { it in ".?!…" } == true
            if (current == null || s.start - lastEnd > 2.0 && ended || current.second.length > 500 && ended) {
                paragraphs += s.start to StringBuilder(s.text)
            } else current.second.append(' ').append(s.text)
            lastEnd = s.end
        }
        return paragraphs.joinToString("\n\n") { (start, text) ->
            if (!times) text.toString()
            else {
                val t = start.toLong()
                String.format(Locale.US, "[%02d:%02d:%02d] ", t / 3600, t / 60 % 60, t % 60) + text
            }
        } + "\n"
    }

    /**
     * Groq takes 25 MB at a time: a longer recording is cut (without re-encoding) into pieces, each
     * with the second it starts at. A recording that fits is returned as it is.
     */
    private fun split(audio: File): List<Pair<File, Double>> {
        if (audio.length() <= MAX_UPLOAD) return listOf(audio to 0.0)
        val dir = File(cacheDir, "recorder-parts").apply {
            deleteRecursively()
            mkdirs()
        }
        val parts = mutableListOf<Pair<File, Double>>()
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(audio.absolutePath)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/") }
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            val (container, ext) = when {
                mime == MediaFormat.MIMETYPE_AUDIO_OPUS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG to "ogg"
                mime == MediaFormat.MIMETYPE_AUDIO_AAC -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4 to "m4a"
                else -> throw Exception(mime)
            }
            val duration = format.getLong(MediaFormat.KEY_DURATION)
            val count = (audio.length() / PART_SIZE + 1).toInt()
            val partUs = duration / count + 1
            extractor.selectTrack(track)
            val size = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            val buffer = ByteBuffer.allocate(maxOf(size, 256 * 1024))
            val info = MediaCodec.BufferInfo()
            var part = -1
            var partStart = 0L
            var outTrack = 0
            while (true) {
                if (cancelled) throw Cancelled()
                val read = extractor.readSampleData(buffer, 0)
                if (read < 0) break
                val time = extractor.sampleTime
                val index = (time / partUs).toInt().coerceIn(0, count - 1)
                if (index != part) {
                    muxer?.let {
                        it.stop()
                        it.release()
                    }
                    part = index
                    partStart = time
                    val file = File(dir, "part-$index.$ext")
                    parts += file to time / 1_000_000.0
                    muxer = MediaMuxer(file.absolutePath, container).also {
                        outTrack = it.addTrack(format)
                        it.start()
                    }
                }
                info.set(0, read, time - partStart, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                muxer?.writeSampleData(outTrack, buffer, info)
                extractor.advance()
            }
            muxer?.let {
                it.stop()
                it.release()
            }
            muxer = null
            if (parts.isEmpty()) throw Exception("empty")
            return parts
        } catch (e: Cancelled) {
            dir.deleteRecursively()
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                muxer?.release()
            } catch (ignored: Exception) {
            }
            dir.deleteRecursively()
            throw Exception(getString(R.string.ml_rec_too_long))
        } finally {
            extractor.release()
        }
    }

    // ---------------------------------------------------------------- write-ups

    /**
     * The free plan takes a few thousand words a minute, so a long transcript goes piece by piece:
     * notes of each piece first, then the write-up from the notes (or, for a [rewrite], the pieces
     * rewritten one after the other).
     */
    private fun elaborate(audio: File, title: String, instruction: String, rewrite: Boolean) {
        val key = key()
        val model = RecorderStore.chatModel(this)
        val source = RecorderStore.transcript(audio)
        if (!source.isFile) throw Exception(getString(R.string.ml_rec_no_transcript))
        val text = source.readText().trim()
        val system = getString(R.string.ml_rec_prompt_system)
        var done = 0
        var total = 1
        fun ask(user: String): String {
            if (cancelled) throw Cancelled()
            val step = if (total > 1) "$title ${done + 1}/$total" else title
            progress(audio, step)
            val answer = Groq.chat(key, model, system, user, waiting(audio)) { cancelled }
            done++
            return answer
        }
        val pieces = chunks(text.split(Regex("\n\\s*\n")), PIECE)
        val result = when {
            pieces.size <= 1 -> ask(instruction + "\n\n" + getString(R.string.ml_rec_prompt_transcript) + "\n" + text)
            rewrite -> {
                total = pieces.size
                pieces.mapIndexed { i, piece ->
                    ask(instruction + "\n\n" + getString(R.string.ml_rec_prompt_part, i + 1, pieces.size) + "\n\n" + piece)
                }.joinToString("\n\n")
            }
            else -> {
                total = pieces.size + 1
                var notes = pieces.mapIndexed { i, piece -> ask(getString(R.string.ml_rec_prompt_notes, i + 1, pieces.size) + "\n\n" + piece) }
                // Too many notes for one request: they are condensed first
                var rounds = 0
                while (notes.sumOf { it.length } > PIECE && notes.size > 1 && rounds++ < 4) {
                    val groups = chunks(notes, PIECE)
                    if (groups.size >= notes.size) break
                    total += groups.size
                    notes = groups.map { ask(getString(R.string.ml_rec_prompt_condense) + "\n\n" + it) }
                }
                ask(instruction + "\n\n" + getString(R.string.ml_rec_prompt_from_notes) + "\n" + notes.joinToString("\n\n"))
            }
        }
        if (result.isBlank()) throw Exception(getString(R.string.ml_rec_empty_answer))
        RecorderStore.elaboration(audio, title).writeText(result.trim() + "\n")
    }

    /** [paragraphs] put together into pieces of at most [limit] characters (a longer paragraph is cut at a space). */
    private fun chunks(paragraphs: List<String>, limit: Int): List<String> {
        val out = mutableListOf<StringBuilder>()
        fun add(text: String) {
            val last = out.lastOrNull()
            if (last != null && last.length + text.length + 2 <= limit) last.append("\n\n").append(text)
            else out += StringBuilder(text)
        }
        for (p in paragraphs) {
            var rest = p.trim()
            while (rest.length > limit) {
                val cut = rest.lastIndexOf(' ', limit).takeIf { it > limit / 2 } ?: limit
                add(rest.substring(0, cut))
                rest = rest.substring(cut).trim()
            }
            if (rest.isNotEmpty()) add(rest)
        }
        return out.map { it.toString() }
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.ml_rec_channel_jobs), NotificationManager.IMPORTANCE_LOW)
        )
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 0, Intent(this, RecorderActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
        val cancel = PendingIntent.getService(this, 1, Intent(this, RecorderJobService::class.java).setAction(ACTION_CANCEL), flags)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_m_mic)
            .setContentTitle(getString(R.string.ml_rec_app))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .addAction(R.drawable.ic_m_close, getString(R.string.ml_todo_cancel), cancel)
            .build()
    }

    companion object {
        const val ACTION_CANCEL = "cancel"
        const val EXTRA_AUDIO = "audio"
        const val EXTRA_TITLE = "title"
        const val EXTRA_INSTRUCTION = "instruction"
        const val EXTRA_REWRITE = "rewrite"
        private const val CHANNEL = "recorder_jobs"
        private const val NOTIFICATION = 7302
        private const val MAX_WORK = 3 * 60 * 60 * 1000L
        private const val MAX_UPLOAD = 24L * 1024 * 1024
        private const val PART_SIZE = 18L * 1024 * 1024
        /** Characters of transcript in one request (about 4,000 tokens). */
        private const val PIECE = 12_000
    }
}
