package app.olauncher.ui.media

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.MediaPlayer
import android.media.MediaScannerConnection
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import app.olauncher.R
import app.olauncher.helper.dpToPx
import app.olauncher.helper.showToast
import app.olauncher.ui.UiKit
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
 * The cut lands on key frames, so the precision is about a second or two. The rotation (90° steps)
 * only changes the video's orientation flag: instant and lossless, also together with a crop.
 */
class VideoEditActivity : AppCompatActivity() {

    private lateinit var file: File
    private lateinit var video: PreviewPlayer
    /** The preview frame, and inside it the picture (video + crop frame) that turns with the rotation. */
    private lateinit var frame: FrameLayout
    private lateinit var stage: FrameLayout
    private lateinit var rotateButton: ImageView
    /** Rotation chosen by the user, clockwise, on top of the video's own. */
    private var userRotation = 0
    private lateinit var trimmer: TrimView
    private lateinit var rangeText: TextView
    private lateinit var playButton: ImageView
    private var durationMs = 0L
    private var startMs = 0L
    private var endMs = 0L

    /** Crop frame over the preview; null = whole picture. In displayed (rotated) pixels. */
    private lateinit var cropView: CropOverlay
    private lateinit var cropButton: ImageView
    private var videoW = 0
    private var videoH = 0
    private var rotation = 0
    private var progressText: TextView? = null
    private val ticker = object : Runnable {
        override fun run() {
            if (video.isPlaying) {
                if (video.currentPosition >= endMs) {
                    video.pause()
                    video.seekTo(startMs.toInt())
                    showPlaying(false)
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
            withContext(Dispatchers.IO) {
                try {
                    val r = MediaMetadataRetriever()
                    r.setDataSource(file.absolutePath)
                    videoW = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    videoH = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    r.release()
                } catch (e: Exception) {
                    // no size info: crop stays off
                }
            }
            trimmer.setup(duration, frames)
            updateRange()
            layoutStage()
            video.setVideoPath(file.absolutePath)
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
        cropButton = UiKit.iconButton(this, R.drawable.ic_m_crop, getString(R.string.ml_edit_crop)) { toggleCrop() }
        rotateButton = UiKit.iconButton(this, R.drawable.ic_m_rotate, getString(R.string.ml_edit_rotate)) { rotate() }
        val bar = UiKit.appBar(this, getString(R.string.ml_edit_trim), onBack = { finish() }, actions = listOf(
            rotateButton,
            cropButton,
            UiKit.iconButton(this, R.drawable.ic_m_check, getString(R.string.ml_save)) { save() },
        ))
        UiKit.setSubtitle(bar, file.name)
        bar.view.setPadding(4.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
        root.addView(bar.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        frame = FrameLayout(this)
        frame.addOnLayoutChangeListener { _, l, t, rr, b, ol, ot, orr, ob -> if (rr - l != orr - ol || b - t != ob - ot) frame.post { layoutStage() } }
        stage = FrameLayout(this)
        val texture = TextureView(this)
        video = PreviewPlayer(texture).apply { onCompletion = { showPlaying(false) } }
        stage.addView(texture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(stage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        // Round dark play / pause button over the preview
        playButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_m_play)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = getString(R.string.ml_search_open)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x99000000.toInt())
                setStroke(1.dpToPx(), 0x40FFFFFF)
            }
            val p = 20.dpToPx()
            setPadding(p, p, p, p)
            isClickable = true
            isFocusable = true
            setOnClickListener { togglePlay() }
        }
        frame.addView(playButton, FrameLayout.LayoutParams(72.dpToPx(), 72.dpToPx(), Gravity.CENTER))
        cropView = CropOverlay(this).apply { isVisible = false }
        stage.addView(cropView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Trim range and the strip of frames, in a card
        val trimCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiKit.cardBackground(this@VideoEditActivity, 18)
            setPadding(12.dpToPx(), 0, 12.dpToPx(), 12.dpToPx())
        }
        rangeText = TextView(this).apply {
            setTextColor(UiKit.primary(this@VideoEditActivity))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 10.dpToPx(), 0, 8.dpToPx())
        }
        trimCard.addView(rangeText)
        trimmer = TrimView(this)
        trimCard.addView(trimmer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 72.dpToPx()))
        root.addView(trimCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(12.dpToPx(), 10.dpToPx(), 12.dpToPx(), 12.dpToPx())
        })
        return root
    }

    /** Play icon when stopped; a faint pause icon while the video plays (tap to pause). */
    private fun showPlaying(playing: Boolean) {
        playButton.setImageResource(if (playing) R.drawable.ic_m_pause else R.drawable.ic_m_play)
        playButton.alpha = if (playing) 0.35f else 1f
    }

    /** A quarter turn clockwise more; the preview (with the crop frame) turns with it. */
    private fun rotate() {
        if (videoW == 0 || videoH == 0) return
        userRotation = (userRotation + 90) % 360
        layoutStage()
        UiKit.setToolActive(rotateButton, userRotation != 0)
    }

    /**
     * The picture sized to the video as shown (its own rotation applied), turned by [userRotation]
     * and fitted in the preview frame.
     */
    private fun layoutStage() {
        val fw = frame.width
        val fh = frame.height
        if (fw == 0 || fh == 0 || videoW == 0 || videoH == 0) return
        val dw = if (rotation % 180 == 0) videoW else videoH
        val dh = if (rotation % 180 == 0) videoH else videoW
        val turned = userRotation % 180 != 0
        val aw = if (turned) dh else dw
        val ah = if (turned) dw else dh
        val s = min(fw.toFloat() / aw, fh.toFloat() / ah)
        val bw = (aw * s).toInt()
        val bh = (ah * s).toInt()
        stage.layoutParams = FrameLayout.LayoutParams(if (turned) bh else bw, if (turned) bw else bh, Gravity.CENTER)
        stage.rotation = userRotation.toFloat()
    }

    private fun toggleCrop() {
        if (videoW == 0 || videoH == 0) {
            showToast(getString(R.string.ml_search_cannot_open))
            return
        }
        val on = !cropView.isVisible
        cropView.isVisible = on
        UiKit.setToolActive(cropButton, on)
        if (on) {
            // Displayed size: rotated when the video is recorded in portrait
            val dw = if (rotation % 180 == 0) videoW else videoH
            val dh = if (rotation % 180 == 0) videoH else videoW
            cropView.setVideoSize(dw, dh)
        }
    }

    /** The crop in source pixels (before the rotation flag), or null when it covers everything. */
    private fun sourceCrop(): VideoCropper.Crop? {
        if (!cropView.isVisible) return null
        val r = cropView.cropRect() ?: return null
        val dw = if (rotation % 180 == 0) videoW else videoH
        val dh = if (rotation % 180 == 0) videoH else videoW
        if (r.left <= 0 && r.top <= 0 && r.right >= dw && r.bottom >= dh) return null
        // Map the displayed rectangle back to the stored frame
        val (l, t, w, h) = when (rotation) {
            90 -> listOf(r.top, dw - r.right, r.height(), r.width())
            180 -> listOf(dw - r.right, dh - r.bottom, r.width(), r.height())
            270 -> listOf(dh - r.bottom, r.left, r.height(), r.width())
            else -> listOf(r.left, r.top, r.width(), r.height())
        }
        return VideoCropper.Crop(l.coerceAtLeast(0), t.coerceAtLeast(0), w.coerceAtLeast(16), h.coerceAtLeast(16))
    }

    private fun togglePlay() {
        if (video.isPlaying) {
            video.pause()
            showPlaying(false)
        } else {
            if (video.currentPosition < startMs || video.currentPosition >= endMs) video.seekTo(startMs.toInt())
            video.start()
            showPlaying(true)
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
        showPlaying(false)
    }

    override fun onDestroy() {
        video.release()
        super.onDestroy()
    }

    /** A MediaPlayer on a TextureView: unlike a VideoView it turns with its view (the rotation preview). */
    private class PreviewPlayer(private val view: TextureView) : TextureView.SurfaceTextureListener {
        private var player: MediaPlayer? = null
        private var path: String? = null
        private var prepared = false
        private var pendingSeek = 1
        var onCompletion: (() -> Unit)? = null

        init {
            view.surfaceTextureListener = this
        }

        val isPlaying get() = prepared && player?.isPlaying == true
        val currentPosition get() = if (prepared) player?.currentPosition ?: 0 else 0

        fun setVideoPath(p: String) {
            path = p
            view.surfaceTexture?.let { open(it) }
        }

        fun seekTo(ms: Int) {
            if (prepared) player?.seekTo(ms) else pendingSeek = ms
        }

        fun start() {
            if (prepared) player?.start()
        }

        fun pause() {
            if (isPlaying) player?.pause()
        }

        fun postDelayed(action: Runnable, ms: Long) = view.postDelayed(action, ms)

        fun release() {
            player?.release()
            player = null
            prepared = false
        }

        private fun open(texture: SurfaceTexture) {
            val p = path ?: return
            if (player != null) return
            player = try {
                MediaPlayer().apply {
                    setSurface(Surface(texture))
                    setDataSource(p)
                    setOnPreparedListener { mp ->
                        prepared = true
                        mp.seekTo(pendingSeek)
                    }
                    setOnCompletionListener { onCompletion?.invoke() }
                    prepareAsync()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) = open(texture)
        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            release()
            return true
        }
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
        showPlaying(false)
        val crop = sourceCrop()
        val trimmed = startMs > 0 || endMs < durationMs
        val turn = userRotation
        if (crop == null && !trimmed && turn == 0) {
            showToast(getString(R.string.ml_edit_nothing))
            return
        }
        showToast(getString(when {
            crop != null -> R.string.ml_edit_crop_saving
            trimmed -> R.string.ml_edit_trim_saving
            else -> R.string.ml_edit_rotate_saving
        }))
        val progress = progressText ?: TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = 16.dpToPx().toFloat()
                setColor(0xCC000000.toInt())
            }
            setPadding(24.dpToPx(), 16.dpToPx(), 24.dpToPx(), 16.dpToPx())
            frame.addView(this, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            progressText = this
        }
        progress.isVisible = crop != null
        progress.text = "0%"
        lifecycleScope.launch {
            val target = withContext(Dispatchers.IO) {
                val out = uniqueCopy(file)
                val ok = if (crop != null) VideoCropper(file, out).also { c ->
                    c.onProgress = { p -> runOnUiThread { progress.text = "${(p * 100).toInt()}%" } }
                    c.extraRotation = turn
                }.run(crop, startMs * 1000, endMs * 1000)
                else trim(file, out, startMs * 1000, endMs * 1000, turn)
                if (ok) {
                    MediaScannerConnection.scanFile(applicationContext, arrayOf(out.absolutePath), null, null)
                    out
                } else {
                    out.delete()
                    null
                }
            }
            progress.isVisible = false
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
    /** Copy of [startUs]..[endUs] without re-encoding, turned by [extraRotation] more (orientation flag). */
    private fun trim(src: File, dst: File, startUs: Long, endUs: Long, extraRotation: Int = 0): Boolean {
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
            // Rotation and orientation are kept, plus the user's
            var own = 0
            try {
                val r = MediaMetadataRetriever()
                r.setDataSource(src.absolutePath)
                own = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                r.release()
            } catch (e: Exception) {
                // no rotation info
            }
            muxer.setOrientationHint(((own + extraRotation) % 360 + 360) % 360)
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

    /** Draggable crop frame over the video preview (handles on the corners, drag inside to move). */
    private inner class CropOverlay(context: Context) : View(context) {
        private var vw = 0
        private var vh = 0
        /** Video display rectangle inside the view (letterboxed). */
        private val shown = RectF()
        /** Crop in video pixels. */
        private val crop = RectF()
        private val dim = Paint().apply { color = 0x99000000.toInt() }
        private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = 2f * resources.displayMetrics.density
        }
        private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val handleRadius = 10f * resources.displayMetrics.density
        private val touchRadius = 32f * resources.displayMetrics.density
        private var dragCorner = -1
        private var dragInside = false
        private var lastX = 0f
        private var lastY = 0f

        fun setVideoSize(w: Int, h: Int) {
            vw = w
            vh = h
            crop.set(0f, 0f, w.toFloat(), h.toFloat())
            layoutShown()
            invalidate()
        }

        fun cropRect(): android.graphics.Rect? = if (vw == 0) null else android.graphics.Rect(
            crop.left.toInt(), crop.top.toInt(), crop.right.toInt(), crop.bottom.toInt()
        )

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            layoutShown()
        }

        private fun layoutShown() {
            if (vw == 0 || vh == 0 || width == 0 || height == 0) return
            val s = min(width.toFloat() / vw, height.toFloat() / vh)
            val sw = vw * s
            val sh = vh * s
            shown.set((width - sw) / 2f, (height - sh) / 2f, (width + sw) / 2f, (height + sh) / 2f)
        }

        private fun scale() = if (vw == 0) 1f else shown.width() / vw
        private fun toView(r: RectF) = RectF(shown.left + r.left * scale(), shown.top + r.top * scale(), shown.left + r.right * scale(), shown.top + r.bottom * scale())
        private fun corners(r: RectF) = listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom)

        override fun onDraw(canvas: Canvas) {
            if (vw == 0) return
            val v = toView(crop)
            canvas.drawRect(shown.left, shown.top, shown.right, v.top, dim)
            canvas.drawRect(shown.left, v.bottom, shown.right, shown.bottom, dim)
            canvas.drawRect(shown.left, v.top, v.left, v.bottom, dim)
            canvas.drawRect(v.right, v.top, shown.right, v.bottom, dim)
            canvas.drawRect(v, frame)
            corners(v).forEach { (x, y) -> canvas.drawCircle(x, y, handleRadius, handle) }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (vw == 0) return false
            val ix = (e.x - shown.left) / scale()
            val iy = (e.y - shown.top) / scale()
            val minSide = 64f
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val v = toView(crop)
                    dragCorner = corners(v).indexOfFirst { (x, y) -> abs(e.x - x) < touchRadius && abs(e.y - y) < touchRadius }
                    dragInside = dragCorner < 0 && v.contains(e.x, e.y)
                    if (dragCorner < 0 && !dragInside) return false
                    lastX = ix
                    lastY = iy
                    parent?.requestDisallowInterceptTouchEvent(true)
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = ix - lastX
                    val dy = iy - lastY
                    lastX = ix
                    lastY = iy
                    when {
                        dragCorner >= 0 -> {
                            if (dragCorner % 2 == 0) crop.left = (crop.left + dx).coerceIn(0f, crop.right - minSide)
                            else crop.right = (crop.right + dx).coerceIn(crop.left + minSide, vw.toFloat())
                            if (dragCorner < 2) crop.top = (crop.top + dy).coerceIn(0f, crop.bottom - minSide)
                            else crop.bottom = (crop.bottom + dy).coerceIn(crop.top + minSide, vh.toFloat())
                        }

                        dragInside -> crop.offset(dx.coerceIn(-crop.left, vw - crop.right), dy.coerceIn(-crop.top, vh - crop.bottom))
                    }
                    invalidate()
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragCorner = -1
                    dragInside = false
                }
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
