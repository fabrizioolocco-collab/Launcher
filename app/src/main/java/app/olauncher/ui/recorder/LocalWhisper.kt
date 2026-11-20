package app.olauncher.ui.recorder

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** whisper.cpp, built with the app (see src/main/cpp). */
object WhisperNative {

    /** False where the library isn't there (only 64-bit ARM phones have it). */
    val loaded: Boolean by lazy {
        try {
            System.loadLibrary("minima_whisper")
            true
        } catch (e: Throwable) {
            false
        }
    }

    external fun init(path: String): Long
    external fun free(pointer: Long)
    external fun setAbort(abort: Boolean)
    external fun progress(): Int
    external fun full(pointer: Long, threads: Int, language: String, prompt: String, samples: FloatArray): Int
    external fun segmentCount(pointer: Long): Int
    external fun segmentText(pointer: Long, index: Int): ByteArray?
    external fun segmentStart(pointer: Long, index: Int): Long
    external fun segmentEnd(pointer: Long, index: Int): Long
}

/**
 * Transcripts made on the phone, with nothing sent anywhere: a Whisper model downloaded once, then
 * the recording goes through it two minutes at a time. What is done is saved after each piece, so a
 * transcription that was stopped (by the user, the system, a reboot) goes on from there.
 */
object LocalWhisper {

    class Model(val id: String, val label: String, val file: String, val megabytes: Int)

    val MODELS = listOf(
        Model("large-v3-turbo", "large-v3-turbo", "ggml-large-v3-turbo-q5_0.bin", 574),
        Model("small", "small", "ggml-small-q5_1.bin", 190),
    )

    private const val SOURCE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"
    private const val RATE = 16000
    /** Samples given to the model at a time, and how far back from their end a quiet spot is looked for to cut at. */
    private const val WINDOW = 120 * RATE
    private const val CUT_SEARCH = 15 * RATE
    private const val FRAME = RATE * 4 / 10

    class Stopped : Exception()

    fun model(id: String) = MODELS.firstOrNull { it.id == id }

    fun file(ctx: Context, model: Model) = File(File(ctx.noBackupFilesDir, "whisper"), model.file)

    fun isDownloaded(ctx: Context, model: Model) = file(ctx, model).isFile

    /** 64-bit ARM with half floats and dot products (what the library is built for). */
    fun supported(): Boolean {
        if (Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a") return false
        val features = try {
            File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("Features") }.orEmpty().split(' ', '\t', ':')
        } catch (e: Exception) {
            return false
        }
        return "asimdhp" in features && "asimddp" in features && WhisperNative.loaded
    }

    /** Downloads the model; a download cut short goes on from where it stopped. [onProgress]: 0..1. */
    fun download(ctx: Context, model: Model, onProgress: (Float) -> Unit, cancelled: () -> Boolean) {
        val target = file(ctx, model)
        if (target.isFile) return
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, model.file + ".part")
        var have = if (part.isFile) part.length() else 0L
        val conn = (URL(SOURCE + model.file).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            val code = conn.responseCode
            if (code == 416) {
                // The part is already whole
                part.renameTo(target)
                return
            }
            if (code != 200 && code != 206) throw java.io.IOException("HTTP $code")
            if (code == 200) have = 0
            val total = have + conn.contentLengthLong.coerceAtLeast(0)
            conn.inputStream.use { input ->
                java.io.FileOutputStream(part, have > 0).use { out ->
                    val buffer = ByteArray(256 * 1024)
                    var last = 0L
                    while (true) {
                        if (cancelled()) throw Stopped()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        have += n
                        val now = System.currentTimeMillis()
                        if (now - last > 1000 && total > 0) {
                            last = now
                            onProgress(have.toFloat() / total)
                        }
                    }
                }
            }
            if (total > 0 && have < total) throw java.io.IOException("incomplete")
            if (!part.renameTo(target)) throw java.io.IOException("rename")
        } finally {
            conn.disconnect()
        }
    }

    // ---------------------------------------------------------------- transcript

    private fun hash(text: String) = MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    private fun workDir(ctx: Context) = File(ctx.cacheDir, "recorder-local").apply { mkdirs() }

    /**
     * The speech of [audio] as timed segments. [language]: ISO code, "" to detect it.
     * [onProgress]: 0..1, every couple of seconds. Throws [Stopped] when [cancelled] says so.
     */
    fun transcribe(
        ctx: Context, audio: File, model: Model, language: String,
        onProgress: (Float) -> Unit, cancelled: () -> Boolean,
    ): List<Groq.Segment> {
        if (!supported()) throw Exception("unsupported")
        val identity = audio.absolutePath + "|" + audio.length() + "|" + audio.lastModified()
        val pcm = File(workDir(ctx), hash(identity) + ".pcm")
        val state = File(workDir(ctx), hash(identity + "|" + model.id + "|" + language) + ".json")
        // Leftovers of other recordings (each is as big as the recording played out) go
        workDir(ctx).listFiles().orEmpty().filter { it != pcm && it != state && System.currentTimeMillis() - it.lastModified() > 7 * 24 * 3600_000L }.forEach { it.delete() }
        if (!pcm.isFile) {
            val tmp = File(pcm.path + ".tmp")
            decode(audio, tmp, cancelled)
            if (!tmp.renameTo(pcm)) throw java.io.IOException("rename")
        }
        val total = pcm.length() / 2
        val segments = mutableListOf<Groq.Segment>()
        var next = 0L
        try {
            if (state.isFile) {
                val saved = JSONObject(state.readText())
                val array = saved.getJSONArray("segments")
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    segments += Groq.Segment(o.getDouble("s"), o.getDouble("e"), o.getString("t"))
                }
                next = saved.getLong("next").coerceIn(0L, total)
            }
        } catch (e: Exception) {
            segments.clear()
            next = 0
        }
        if (next >= total && segments.isNotEmpty()) {
            pcm.delete()
            state.delete()
            return segments
        }
        WhisperNative.setAbort(false)
        val pointer = WhisperNative.init(file(ctx, model).absolutePath)
        if (pointer == 0L) throw Exception("model")
        // The big cores only: the small ones slow everything down
        val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 4)
        try {
            RandomAccessFile(pcm, "r").use { raf ->
                while (next < total) {
                    if (cancelled()) throw Stopped()
                    val samples = window(raf, next, total)
                    val begin = next
                    // While the model works: the progress, and the order to stop
                    val working = java.util.concurrent.atomic.AtomicBoolean(true)
                    val watcher = Thread {
                        while (working.get()) {
                            try {
                                Thread.sleep(1500)
                            } catch (e: InterruptedException) {
                                break
                            }
                            if (!working.get()) break
                            if (cancelled()) WhisperNative.setAbort(true)
                            onProgress(((begin + samples.size * (WhisperNative.progress().coerceIn(0, 100) / 100f)) / total).coerceIn(0f, 1f))
                        }
                    }.apply {
                        isDaemon = true
                        start()
                    }
                    val result = try {
                        WhisperNative.full(pointer, threads, language.ifEmpty { "auto" }, segments.lastOrNull()?.text.orEmpty().takeLast(200), samples)
                    } finally {
                        working.set(false)
                        watcher.interrupt()
                    }
                    if (cancelled()) throw Stopped()
                    if (result != 0) throw Exception("whisper $result")
                    val offset = begin.toDouble() / RATE
                    for (i in 0 until WhisperNative.segmentCount(pointer)) {
                        val text = WhisperNative.segmentText(pointer, i)?.toString(Charsets.UTF_8).orEmpty().trim()
                        // What the model writes for silence and noises
                        if (text.isEmpty() || text.startsWith("[") && text.endsWith("]") || text.startsWith("(") && text.endsWith(")")) continue
                        segments += Groq.Segment(offset + WhisperNative.segmentStart(pointer, i) / 100.0, offset + WhisperNative.segmentEnd(pointer, i) / 100.0, text)
                    }
                    next = begin + samples.size
                    val array = JSONArray()
                    segments.forEach { array.put(JSONObject().put("s", it.start).put("e", it.end).put("t", it.text)) }
                    state.writeText(JSONObject().put("next", next).put("segments", array).toString())
                    onProgress((next.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        } finally {
            WhisperNative.free(pointer)
        }
        pcm.delete()
        state.delete()
        return segments
    }

    /** The next piece from [from]: two minutes, ending in the quietest spot of its last seconds so no word is cut. */
    private fun window(raf: RandomAccessFile, from: Long, total: Long): FloatArray {
        var count = minOf(WINDOW.toLong(), total - from).toInt()
        val bytes = ByteArray(count * 2)
        raf.seek(from * 2)
        raf.readFully(bytes)
        val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        if (from + count < total && count > CUT_SEARCH + FRAME) {
            var best = count
            var quietest = Double.MAX_VALUE
            var at = count - CUT_SEARCH
            while (at + FRAME <= count) {
                var energy = 0.0
                for (i in at until at + FRAME) {
                    val v = shorts.get(i).toDouble()
                    energy += v * v
                }
                if (energy < quietest) {
                    quietest = energy
                    best = at + FRAME / 2
                }
                at += FRAME / 2
            }
            count = best
        }
        return FloatArray(count) { shorts.get(it) / 32768f }
    }

    /** Decodes [audio] into 16 kHz mono 16-bit samples in [out]. */
    private fun decode(audio: File, out: File, cancelled: () -> Boolean) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(audio.absolutePath)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/") }
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME).orEmpty())
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floats = false
            BufferedOutputStream(out.outputStream(), 256 * 1024).use { stream ->
                var resampler = Resampler(rate, stream)
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                while (!outputDone) {
                    if (cancelled()) throw Stopped()
                    if (!inputDone) {
                        val index = decoder.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = decoder.getInputBuffer(index)
                            val size = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val index = decoder.dequeueOutputBuffer(info, 10_000)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = decoder.outputFormat
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        val newRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (newRate != rate) {
                            rate = newRate
                            resampler = Resampler(rate, stream)
                        }
                        floats = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    } else if (index >= 0) {
                        val buffer = decoder.getOutputBuffer(index)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.order(ByteOrder.LITTLE_ENDIAN)
                            val ch = channels.coerceAtLeast(1)
                            if (floats) {
                                val data = buffer.asFloatBuffer()
                                var i = 0
                                while (i + ch <= data.remaining()) {
                                    var sum = 0f
                                    for (c in 0 until ch) sum += data.get(i + c)
                                    resampler.push(sum / ch)
                                    i += ch
                                }
                            } else {
                                val data = buffer.asShortBuffer()
                                var i = 0
                                while (i + ch <= data.remaining()) {
                                    var sum = 0f
                                    for (c in 0 until ch) sum += data.get(i + c) / 32768f
                                    resampler.push(sum / ch)
                                    i += ch
                                }
                            }
                        }
                        decoder.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } catch (e: Stopped) {
            out.delete()
            throw e
        } catch (e: Exception) {
            out.delete()
            throw Exception("decode: " + (e.message ?: e.javaClass.simpleName))
        } finally {
            try {
                codec?.stop()
            } catch (ignored: Exception) {
            }
            try {
                codec?.release()
            } catch (ignored: Exception) {
            }
            extractor.release()
        }
    }

    /**
     * Mono samples at [rate] in, 16 kHz 16-bit samples out: averaged over the samples that make one
     * output sample (so higher rates don't alias), then interpolated.
     */
    private class Resampler(rate: Int, private val out: java.io.OutputStream) {
        private val step = rate.toDouble() / RATE
        private val width = Math.round(step).toInt().coerceAtLeast(1)
        private val recent = FloatArray(width)
        private var filled = 0
        private var sum = 0f
        private var count = 0L
        private var previous = 0f
        private var nextOut = 0.0

        fun push(sample: Float) {
            // Moving average of the last [width] samples
            val slot = (count % width).toInt()
            if (filled == width) sum -= recent[slot] else filled++
            recent[slot] = sample
            sum += sample
            val smooth = sum / filled
            if (count == 0L) previous = smooth
            while (nextOut <= count) {
                val fraction = (nextOut - (count - 1)).toFloat().coerceIn(0f, 1f)
                val value = previous + (smooth - previous) * fraction
                val s = (value * 32767f).toInt().coerceIn(-32768, 32767)
                out.write(s and 0xFF)
                out.write((s shr 8) and 0xFF)
                nextOut += step
            }
            previous = smooth
            count++
        }
    }
}
