package app.olauncher.ui.media

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
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
import java.util.Locale

/**
 * Plays a music or audio file from the explorer or the search: its cover (when the file has
 * one), title and artist, a bar to move along it, and previous / next among the audio files of
 * the same folder, which play one after the other. It goes on with the screen off, as long as
 * this screen isn't closed.
 */
class AudioPlayerActivity : AppCompatActivity() {

    private var tracks: List<File> = emptyList()
    private var index = 0
    private var player: MediaPlayer? = null
    private var seeking = false
    private val ticker = Handler(Looper.getMainLooper())

    private lateinit var bar: UiKit.AppBar
    private lateinit var cover: ImageView
    private lateinit var coverIcon: View
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var seek: SeekBar
    private lateinit var elapsed: TextView
    private lateinit var total: TextView
    private lateinit var playButton: ImageView

    /** Something else plays (a call, another app): this stops. */
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        open(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        open(intent)
    }

    private fun open(intent: Intent?) {
        val file = intent?.getStringExtra(EXTRA_PATH)?.let { File(it) }
        if (file == null || !file.isFile) {
            finish()
            return
        }
        // The other audio files of the folder, by name: what previous and next go through
        val siblings = file.parentFile?.listFiles()?.filter { it.isFile && isAudio(it.name) && !it.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() }.orEmpty()
        tracks = if (siblings.any { it.absolutePath == file.absolutePath }) siblings else listOf(file)
        index = tracks.indexOfFirst { it.absolutePath == file.absolutePath }.coerceAtLeast(0)
        bar.title.text = file.parentFile?.name.orEmpty()
        play(index, start = true)
    }

    override fun onDestroy() {
        ticker.removeCallbacksAndMessages(null)
        release()
        @Suppress("DEPRECATION")
        (getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocus(focusListener)
        super.onDestroy()
    }

    private fun release() {
        try {
            player?.release()
        } catch (ignored: Exception) {
        }
        player = null
    }

    // ---------------------------------------------------------------- layout

    private fun buildLayout(): View {
        val primary = UiKit.primary(this)
        val secondary = UiKit.secondary(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8.dpToPx(), 0, 8.dpToPx(), 0)
        }
        ViewCompat.setOnApplyWindowInsetsListener(layout) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top + 4.dpToPx(), v.paddingRight, bars.bottom + 16.dpToPx())
            insets
        }
        bar = UiKit.appBar(this, "", onBack = { finish() }, actions = listOf(
            UiKit.iconButton(this, R.drawable.ic_m_open, getString(R.string.ml_explorer_open_with)) { openWith() },
        ))
        layout.addView(bar.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // The cover, or a music note where the file has none
        val art = FrameLayout(this).apply {
            background = UiKit.cardBackground(this@AudioPlayerActivity, 28)
            clipToOutline = true
        }
        coverIcon = UiKit.icon(this, R.drawable.ic_m_audio, secondary, 72)
        art.addView(coverIcon, FrameLayout.LayoutParams(72.dpToPx(), 72.dpToPx(), Gravity.CENTER))
        cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            isVisible = false
        }
        art.addView(cover, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val side = (resources.displayMetrics.widthPixels * 0.68f).toInt().coerceAtMost(320.dpToPx())
        val artHolder = FrameLayout(this)
        artHolder.addView(art, FrameLayout.LayoutParams(side, side, Gravity.CENTER))
        layout.addView(artHolder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        titleView = TextView(this).apply {
            textSize = 20f
            setTextColor(primary)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(16.dpToPx(), 0, 16.dpToPx(), 0)
        }
        layout.addView(titleView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        artistView = TextView(this).apply {
            textSize = 14.5f
            setTextColor(secondary)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(16.dpToPx(), 4.dpToPx(), 16.dpToPx(), 0)
        }
        layout.addView(artistView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        seek = SeekBar(this).apply {
            progressTintList = ColorStateList.valueOf(primary)
            thumbTintList = ColorStateList.valueOf(primary)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, position: Int, fromUser: Boolean) {
                    if (fromUser) elapsed.text = clock(position)
                }

                override fun onStartTrackingTouch(bar: SeekBar?) {
                    seeking = true
                }

                override fun onStopTrackingTouch(bar: SeekBar?) {
                    seeking = false
                    try {
                        player?.seekTo(progress)
                    } catch (ignored: Exception) {
                    }
                }
            })
        }
        layout.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 20.dpToPx()
        })
        val times = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16.dpToPx(), 0, 16.dpToPx(), 0)
        }
        elapsed = TextView(this).apply {
            textSize = 12.5f
            setTextColor(secondary)
        }
        total = TextView(this).apply {
            textSize = 12.5f
            setTextColor(secondary)
            gravity = Gravity.END
        }
        times.addView(elapsed, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        times.addView(total, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        layout.addView(times)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun control(icon: Int, label: Int, size: Int, action: () -> Unit) = ImageView(this).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(primary)
            contentDescription = getString(label)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = (size * 0.24f).toInt().dpToPx()
            setPadding(pad, pad, pad, pad)
            background = UiKit.roundRipple(this@AudioPlayerActivity)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            controls.addView(this, LinearLayout.LayoutParams(size.dpToPx(), size.dpToPx()).apply {
                marginStart = 10.dpToPx()
                marginEnd = 10.dpToPx()
            })
        }
        control(R.drawable.ic_m_left, R.string.ml_audio_previous, 56) { previous() }
        playButton = control(R.drawable.ic_m_play, R.string.ml_audio_play, 76) { toggle() }
        control(R.drawable.ic_m_right, R.string.ml_audio_next, 56) { next(byUser = true) }
        layout.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 12.dpToPx()
        })
        return layout
    }

    // ---------------------------------------------------------------- playing

    private fun clock(millis: Int): String {
        val s = millis / 1000
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    /** Loads the track at [position] of the folder; [start] plays it at once. */
    private fun play(position: Int, start: Boolean) {
        val file = tracks.getOrNull(position) ?: return
        index = position
        ticker.removeCallbacksAndMessages(null)
        release()
        titleView.text = file.nameWithoutExtension
        artistView.text = ""
        cover.isVisible = false
        coverIcon.isVisible = true
        seek.progress = 0
        elapsed.text = clock(0)
        total.text = ""
        showDetails(file)
        try {
            val created = MediaPlayer()
            created.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            created.setDataSource(file.absolutePath)
            // Goes on with the screen off
            created.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            created.prepare()
            created.setOnCompletionListener { next(byUser = false) }
            player = created
            seek.isEnabled = true
            seek.max = created.duration.coerceAtLeast(0)
            total.text = clock(created.duration.coerceAtLeast(0))
            if (start) resume() else playButton.setImageResource(R.drawable.ic_m_play)
        } catch (e: Exception) {
            e.printStackTrace()
            player = null
            seek.isEnabled = false
            playButton.setImageResource(R.drawable.ic_m_play)
            showToast(getString(R.string.ml_search_cannot_open))
        }
    }

    /** Title, artist and cover written in the file, read off the main thread. */
    private fun showDetails(file: File) {
        lifecycleScope.launch {
            var title: String? = null
            var artist: String? = null
            val picture: Bitmap? = withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    artist = listOfNotNull(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                    ).filter { it.isNotBlank() }.joinToString(" · ")
                    retriever.embeddedPicture?.let { bytes ->
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        var sample = 1
                        while (bounds.outWidth / (sample * 2) >= 800) sample *= 2
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    }
                } catch (e: Throwable) {
                    null
                } finally {
                    try {
                        retriever.release()
                    } catch (ignored: Exception) {
                    }
                }
            }
            if (tracks.getOrNull(index)?.absolutePath != file.absolutePath) return@launch
            title?.takeIf { it.isNotBlank() }?.let { titleView.text = it }
            artistView.text = artist.orEmpty()
            if (picture != null) {
                cover.setImageBitmap(picture)
                cover.isVisible = true
                coverIcon.isVisible = false
            }
        }
    }

    private fun resume() {
        val p = player ?: return
        @Suppress("DEPRECATION")
        (getSystemService(Context.AUDIO_SERVICE) as AudioManager).requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        try {
            p.start()
            playButton.setImageResource(R.drawable.ic_m_pause)
            playButton.contentDescription = getString(R.string.ml_audio_pause)
            tick()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun pause() {
        ticker.removeCallbacksAndMessages(null)
        try {
            player?.takeIf { it.isPlaying }?.pause()
        } catch (ignored: Exception) {
        }
        playButton.setImageResource(R.drawable.ic_m_play)
        playButton.contentDescription = getString(R.string.ml_audio_play)
    }

    private fun toggle() {
        val p = player ?: return play(index, start = true)
        val playing = try {
            p.isPlaying
        } catch (e: Exception) {
            false
        }
        if (playing) pause() else resume()
    }

    /** Back to the start of the track, or to the track before when it has just started. */
    private fun previous() {
        val position = try {
            player?.currentPosition ?: 0
        } catch (e: Exception) {
            0
        }
        if (position > 3000 || index == 0) {
            try {
                player?.seekTo(0)
            } catch (ignored: Exception) {
            }
            seek.progress = 0
            elapsed.text = clock(0)
        } else play(index - 1, start = true)
    }

    /** The next track; at the end of the folder it stops (when a track ended) or stays where it is. */
    private fun next(byUser: Boolean) {
        if (index + 1 < tracks.size) return play(index + 1, start = true)
        if (byUser) return
        ticker.removeCallbacksAndMessages(null)
        seek.progress = 0
        elapsed.text = clock(0)
        playButton.setImageResource(R.drawable.ic_m_play)
        playButton.contentDescription = getString(R.string.ml_audio_play)
    }

    private fun tick() {
        val p = player ?: return
        try {
            if (!p.isPlaying) return
            if (!seeking) {
                seek.progress = p.currentPosition
                elapsed.text = clock(p.currentPosition)
            }
        } catch (e: Exception) {
            return
        }
        ticker.postDelayed({ tick() }, 400)
    }

    private fun openWith() {
        val file = tracks.getOrNull(index) ?: return
        pause()
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "audio/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), file.name))
        } catch (e: Exception) {
            showToast(getString(R.string.ml_search_cannot_open))
        }
    }

    companion object {
        private const val EXTRA_PATH = "path"
        private val EXTENSIONS = setOf("mp3", "m4a", "ogg", "opus", "wav", "flac", "aac", "wma", "amr", "m4b", "mka", "mid", "midi")

        fun isAudio(name: String, mime: String? = null) =
            mime?.startsWith("audio/") == true || name.substringAfterLast('.', "").lowercase() in EXTENSIONS

        fun open(context: Context, file: File) = context.startActivity(
            Intent(context, AudioPlayerActivity::class.java).putExtra(EXTRA_PATH, file.absolutePath)
                .apply { if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        )
    }
}
