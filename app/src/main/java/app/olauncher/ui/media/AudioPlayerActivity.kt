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
 * The screen of the audio player ([AudioPlayerService], which plays in the background): the
 * cover (when the file has one), title and artist, a bar to move along the track, and previous /
 * next among the audio files of the same folder, which play one after the other.
 */
class AudioPlayerActivity : AppCompatActivity() {

    private var seeking = false
    private val ticker = Handler(Looper.getMainLooper())
    /** The track the screen shows, to tell a new one from news of the same. */
    private var shownPath: String? = null

    private lateinit var bar: UiKit.AppBar
    private lateinit var cover: ImageView
    private lateinit var coverIcon: View
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var seek: SeekBar
    private lateinit var elapsed: TextView
    private lateinit var total: TextView
    private lateinit var playButton: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        // The notification with the controls
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        val asked = open(intent)
        var seen = false
        AudioPlayerService.state.observe(this) { state ->
            if (state != null) {
                seen = true
                show(state)
            } else if (seen || !asked) finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        open(intent)
    }

    /** Plays the file the screen was opened for; false when it was opened for what is already playing. */
    private fun open(intent: Intent?): Boolean {
        val file = intent?.getStringExtra(EXTRA_PATH)?.let { File(it) }?.takeIf { it.isFile } ?: return false
        // Once: coming back to the screen must not start the file again
        intent?.removeExtra(EXTRA_PATH)
        if (AudioPlayerService.state.value?.file?.absolutePath != file.absolutePath) AudioPlayerService.play(this, file)
        return true
    }

    override fun onStart() {
        super.onStart()
        tick()
    }

    override fun onStop() {
        ticker.removeCallbacksAndMessages(null)
        super.onStop()
    }

    override fun onDestroy() {
        ticker.removeCallbacksAndMessages(null)
        // Closed while paused: nothing left to keep in the background
        if (isFinishing && AudioPlayerService.state.value?.playing == false) AudioPlayerService.stop()
        super.onDestroy()
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
                    AudioPlayerService.seekTo(progress)
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
        control(R.drawable.ic_m_left, R.string.ml_audio_previous, 56) { AudioPlayerService.previous() }
        playButton = control(R.drawable.ic_m_play, R.string.ml_audio_play, 76) { AudioPlayerService.toggle() }
        control(R.drawable.ic_m_right, R.string.ml_audio_next, 56) { AudioPlayerService.next() }
        layout.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 12.dpToPx()
        })
        return layout
    }

    // ---------------------------------------------------------------- showing

    private fun clock(millis: Int): String {
        val s = millis / 1000
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    private fun show(state: AudioPlayerService.State) {
        bar.title.text = state.file.parentFile?.name.orEmpty()
        titleView.text = state.title
        artistView.text = state.artist
        cover.setImageBitmap(state.cover)
        cover.isVisible = state.cover != null
        coverIcon.isVisible = state.cover == null
        seek.max = state.duration
        seek.isEnabled = state.duration > 0
        total.text = clock(state.duration)
        playButton.setImageResource(if (state.playing) R.drawable.ic_m_pause else R.drawable.ic_m_play)
        playButton.contentDescription = getString(if (state.playing) R.string.ml_audio_pause else R.string.ml_audio_play)
        if (shownPath != state.file.absolutePath) {
            shownPath = state.file.absolutePath
            seek.progress = 0
            elapsed.text = clock(0)
        }
        showPosition()
    }

    private fun showPosition() {
        if (seeking || AudioPlayerService.state.value == null) return
        val position = AudioPlayerService.position()
        seek.progress = position
        elapsed.text = clock(position)
    }

    private fun tick() {
        ticker.removeCallbacksAndMessages(null)
        showPosition()
        ticker.postDelayed({ tick() }, 400)
    }

    private fun openWith() {
        val file = AudioPlayerService.state.value?.file ?: return
        if (AudioPlayerService.state.value?.playing == true) AudioPlayerService.toggle()
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
