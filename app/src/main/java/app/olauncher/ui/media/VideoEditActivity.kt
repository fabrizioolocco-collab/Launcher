package app.olauncher.ui.media

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import app.olauncher.R
import app.olauncher.helper.dpToPx
import app.olauncher.helper.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Video trimmer: two handles on a strip of frames pick the start and the end; the result is saved
 * as a copy without re-encoding (MediaExtractor → MediaMuxer), so it's fast and keeps the quality.
 * The cut lands on key frames, so the precision is about a second or two.
 */
class VideoEditActivity : AppCompatActivity() {

    private lateinit var file: File
    private lateinit var video: VideoView
    private lateinit var trimmer: TrimView
    private lateinit var rangeText: TextView
    private lateinit var playButton: TextView
    private var durationMs = 0L
    private var startMs = 0L
    private var endMs = 0L
    private val ticker = object : Runnable {
        override fun run() {
            if (video.isPlaying) {
                if (video.currentPosition >= endMs) {
                    video.pause()
                    video.seekTo(startMs.toInt())
                    playButton.text = "▶"
                } else {
                    trimmer.playhead = video.currentPosition.toLong()
                    trimmer.invalidate()
                    video.postDelayed(this, 100)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        file = File(intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return })
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        setContentView(buildLayout())
        lifecycleScope.launch {
            val (duration, frames) = withContext(Dispatchers.IO) { probe(file) }
            if (duration <= 0) {
                showToast(getString(R.string.ml_search_cannot_open))
                finish()
                return@launch
            }
            durationMs = duration
            endMs = duration
            trimmer.setup(duration, frames)
            updateRange()
            video.setVideoPath(file.absolutePath)
            video.seekTo(1)
        }
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dpToPx(), 8.dpToPx(), 8.dpToPx(), 8.dpToPx())
        }
        top.addView(button("←", 26f) { finish() })
        top.addView(TextView(this).apply {
            setText(R.string.ml_edit_trim)
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(button("✓", 26f) { save() })
        root.addView(top)

        val frame = FrameLayout(this)
        video = VideoView(this).apply {
            setOnPreparedListener { it.isLooping = false }
            setOnCompletionListener { playButton.text = "▶" }
        }
        frame.addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        playButton = TextView(this).apply {
            text = "▶"
            textSize = 40f
            setTextColor(Color.WHITE)
            setShadowLayer(12f, 0f, 0f, Color.BLACK)
            isClickable = true
            setOnClickListener { togglePlay() }
        }
        frame.addView(playButton, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        rangeText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 10.dpToPx(), 0, 6.dpToPx())
        }
        root.addView(rangeText)
        trimmer = TrimView(this)
        root.addView(trimmer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 72.dpToPx()).apply {
            setMargins(16.dpToPx(), 0, 16.dpToPx(), 20.dpToPx())
        })
        return root
    }

    private fun button(glyph: String, size: Float, onClick: () -> Unit) = TextView(this).apply {
        text = glyph
        textSize = size
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(10.dpToPx(), 4.dpToPx(), 10.dpToPx(), 4.dpToPx())
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun togglePlay() {
        if (video.isPlaying) {
            video.pause()
            playButton.text = "▶"
        } else {
            if (video.currentPosition < startMs || video.currentPosition >= endMs) video.seekTo(startMs.toInt())
            video.start()
            playButton.text = ""
            video.postDelayed(ticker, 100)
        }
    }

    private fun updateRange() {
        rangeText.text = getString(
            R.string.ml_edit_trim_range,
            MediaViewerActivity.formatDuration(startMs), MediaViewerActivity.formatDuration(endMs),
            MediaViewerActivity.formatDuration(endMs - startMs),
        )
    }

    override fun onPause() {
        super.onPause()
        video.pause()
        playButton.text = "▶"
    }

    // ---------------------------------------------------------------- probing

    /** Duration and a strip of small frames. */
    private fun probe(file: File): Pair<Long, List<Bitmap>> {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val frames = (0 until FRAMES).mapNotNull { i ->
                val t = duration * 1000L * i / FRAMES + duration * 500L / FRAMES
                try {
                    r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { f ->
                        val h = 72.dpToPx()
                        val w = max(1, f.width * h / max(1, f.height))
                        Bitmap.createScaledBitmap(f, w, h, true)
                    }
                } catch (e: Exception) {
                    null
                }
            }
            duration to frames
        } catch (e: Exception) {
            0L to emptyList()
        } finally {
            r.release()
        }
    }

    // ---------------------------------------------------------------- saving

    private fun save() {
        if (endMs - startMs < 500) {
            showToast(getString(R.string.ml_edit_trim_too_short))
            return
        }
        video.pause()
        playButton.text = "▶"
        showToast(getString(R.string.ml_edit_trim_saving))
        lifecycleScope.launch {
            val target = withContext(Dispatchers.IO) {
                val out = uniqueCopy(file)
                if (trim(file, out, startMs * 1000, endMs * 1000)) {
                    MediaScannerConnection.scanFile(applicationContext, arrayOf(out.absolutePath), null, null)
                    out
                } else {
                    out.delete()
                    null
                }
            }
            if (target == null) showToast(getString(R.string.ml_explorer_failed, getString(R.string.ml_edit_trim)))
            else {
                showToast(getString(R.string.ml_edit_saved, target.name))
                finish()
            }
        }
    }

    private fun uniqueCopy(src: File): File {
        val base = src.nameWithoutExtension
        var i = 1
        var f: File
        do {
            f = File(src.parentFile, "$base (${i++}).mp4")
        } while (f.exists())
        return f
    }

    /** Copies the samples between [startUs] and [endUs] of every track into a new MP4, no re-encoding. */
    private fun trim(src: File, dst: File, startUs: Long, endUs: Long): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(src.absolutePath)
            muxer = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackMap = HashMap<Int, Int>()
            var maxBuffer = 1 shl 20
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                extractor.selectTrack(i)
                trackMap[i] = muxer.addTrack(format)
                if (format.containsKey(android.media.MediaFormat.KEY_MAX_INPUT_SIZE))
                    maxBuffer = max(maxBuffer, format.getInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE))
            }
            if (trackMap.isEmpty()) return false
            // Rotation and orientation are kept
            try {
                val r = MediaMetadataRetriever()
                r.setDataSource(src.absolutePath)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()?.let { muxer.setOrientationHint(it) }
                r.release()
            } catch (e: Exception) {
                // no rotation info
            }
            muxer.start()
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val buffer = ByteBuffer.allocate(maxBuffer)
            val info = MediaCodec.BufferInfo()
            val base = extractor.sampleTime.coerceAtLeast(0)
            while (true) {
                info.offset = 0
                info.size = extractor.readSampleData(buffer, 0)
                if (info.size < 0) break
                val time = extractor.sampleTime
                if (time > endUs) break
                info.presentationTimeUs = time - base
                info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(trackMap.getValue(extractor.sampleTrackIndex), buffer, info)
                extractor.advance()
            }
            muxer.stop()
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            try {
                muxer?.release()
            } catch (e: Exception) {
                // already released
            }
            extractor.release()
        }
    }

    // ---------------------------------------------------------------- trim strip

    private inner class TrimView(context: Context) : View(context) {
        private var duration = 1L
        private var frames: List<Bitmap> = emptyList()
        var playhead = 0L
        private val dim = Paint().apply { color = 0xAA000000.toInt() }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 2f * resources.displayMetrics.density
        }
        private val handleW = 14f * resources.displayMetrics.density
        private var dragging = 0 // 0 none, 1 start, 2 end

        fun setup(durationMs: Long, strip: List<Bitmap>) {
            duration = max(1L, durationMs)
            frames = strip
            invalidate()
        }

        private fun xOf(ms: Long) = handleW + (width - 2 * handleW) * ms / duration
        private fun msOf(x: Float) = ((x - handleW) / (width - 2 * handleW) * duration).toLong().coerceIn(0, duration)

        override fun onDraw(canvas: Canvas) {
            if (frames.isNotEmpty()) {
                val inner = width - 2 * handleW
                val fw = inner / frames.size
                frames.forEachIndexed { i, f ->
                    val dst = RectF(handleW + fw * i, 0f, handleW + fw * (i + 1), height.toFloat())
                    canvas.drawBitmap(f, null, dst, null)
                }
            }
            val sx = xOf(startMs)
            val ex = xOf(endMs)
            canvas.drawRect(handleW, 0f, sx, height.toFloat(), dim)
            canvas.drawRect(ex, 0f, width - handleW, height.toFloat(), dim)
            canvas.drawRoundRect(sx - handleW, 0f, sx, height.toFloat(), 6f, 6f, edge)
            canvas.drawRoundRect(ex, 0f, ex + handleW, height.toFloat(), 6f, 6f, edge)
            canvas.drawLine(sx, 0f, ex, 0f, line)
            canvas.drawLine(sx, height.toFloat(), ex, height.toFloat(), line)
            if (playhead in startMs..endMs) {
                val px = xOf(playhead)
                canvas.drawLine(px, 0f, px, height.toFloat(), line)
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val sx = xOf(startMs)
                    val ex = xOf(endMs)
                    dragging = when {
                        abs(e.x - sx) < abs(e.x - ex) && abs(e.x - sx) < handleW * 3 -> 1
                        abs(e.x - ex) < handleW * 3 -> 2
                        else -> 0
                    }
                    parent?.requestDisallowInterceptTouchEvent(true)
                }

                MotionEvent.ACTION_MOVE -> {
                    val ms = msOf(e.x)
                    when (dragging) {
                        1 -> {
                            startMs = min(ms, endMs - 500)
                            video.seekTo(startMs.toInt())
                        }

                        2 -> {
                            endMs = max(ms, startMs + 500)
                            video.seekTo(endMs.toInt())
                        }

                        else -> return true
                    }
                    updateRange()
                    invalidate()
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = 0
            }
            return true
        }
    }

    companion object {
        const val EXTRA_PATH = "path"
        private const val FRAMES = 10

        fun open(context: Context, file: File) = context.startActivity(
            Intent(context, VideoEditActivity::class.java).putExtra(EXTRA_PATH, file.absolutePath).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
