package app.olauncher.ui.youtube

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import app.olauncher.R
import app.olauncher.helper.dpToPx
import app.olauncher.ui.UiKit
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The player: the video on top with its title under it. Leaving it while it plays shrinks it
 * to a floating window; with the screen off, or sent to the background, the sound goes on.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class YtPlayerActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout
    private lateinit var frame: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var spinner: ProgressBar
    private lateinit var info: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var channelView: TextView
    private lateinit var statusView: TextView
    private lateinit var qualityChip: TextView
    private lateinit var pipChip: TextView

    private var controller: ListenableFuture<MediaController>? = null
    private var player: ExoPlayer? = null
    private var loading: Job? = null
    private var retries = 0

    /** Leaving for the sound only: no floating window this time. */
    private var leavingForAudio = false

    private val canPip by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }
    private val inPip get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = updatePip()
        override fun onVideoSizeChanged(videoSize: VideoSize) = updatePip()

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) retries = 0
        }

        override fun onPlayerError(error: PlaybackException) {
            // The addresses of a video expire after some hours, and now and then one is refused: asked again
            if (retries < 2) {
                retries++
                load(player?.currentPosition ?: 0)
            } else showError(error.message)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        buildLayout()
        setContentView(root)
        applyLayout()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                        requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE ->
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    player?.isPlaying == true && enterPip() -> Unit
                    else -> finish()
                }
            }
        })

        val token = SessionToken(this, ComponentName(this, YtPlayerService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controller = future
        // Connected means the service is up, and its player with it
        future.addListener({
            if (isDestroyed) return@addListener
            val exo = YtPlayerService.player
            if (exo == null) {
                finish()
                return@addListener
            }
            player = exo
            exo.addListener(listener)
            playerView.player = exo
            showVideo(true)
            handleIntent(intent)
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (player != null) handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val exo = player ?: return
        val id = intent.getStringExtra(EXTRA_ID)
        val now = YtPlayerService.now
        if (id == null || (now != null && now.videoId == id && exo.playbackState != Player.STATE_IDLE)) {
            // Opened again from the notification, or on the video already playing
            if (now == null) finish() else showNow()
            return
        }
        YtPlayerService.now = YtPlayerService.Now(
            id,
            intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            intent.getStringExtra(EXTRA_CHANNEL).orEmpty(),
            intent.getStringExtra(EXTRA_CHANNEL_ID).orEmpty()
        )
        retries = 0
        showNow()
        load(0)
    }

    // ---- loading and playing ----

    private fun load(position: Long) {
        val now = YtPlayerService.now ?: return
        val exo = player ?: return
        loading?.cancel()
        statusView.isVisible = false
        spinner.isVisible = true
        exo.stop()
        loading = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { YouTubeApi.streams(now.videoId) } }
            if (YtPlayerService.now !== now) return@launch
            spinner.isVisible = false
            val streams = result.getOrNull()
            if (streams == null) {
                showError(result.exceptionOrNull()?.message)
                return@launch
            }
            now.streams = streams
            if (now.title.isBlank()) now.title = streams.title
            if (now.author.isBlank()) now.author = streams.author
            if (now.channelId.isBlank()) now.channelId = streams.channelId
            showNow()
            play(position)
        }
    }

    private fun play(position: Long) {
        val now = YtPlayerService.now ?: return
        val streams = now.streams ?: return
        val exo = player ?: return
        val http = DefaultHttpDataSource.Factory().setUserAgent(streams.userAgent).setAllowCrossProtocolRedirects(true)
        val meta = MediaMetadata.Builder()
            .setTitle(now.title)
            .setArtist(now.author)
            .setArtworkUri(Uri.parse("https://i.ytimg.com/vi/${now.videoId}/hqdefault.jpg"))
            .build()
        fun item(url: String) = MediaItem.Builder().setUri(url).setMediaId(now.videoId).setMediaMetadata(meta).build()
        fun progressive(url: String) = ProgressiveMediaSource.Factory(http).createMediaSource(item(url))

        val video = chosen(streams)
        val audio = streams.audioUrl
        val source: MediaSource = when {
            streams.live && streams.hlsUrl != null -> HlsMediaSource.Factory(http).createMediaSource(item(streams.hlsUrl))
            video != null && audio != null -> MergingMediaSource(progressive(video.second), progressive(audio))
            streams.muxedUrl != null -> progressive(streams.muxedUrl)
            streams.hlsUrl != null -> HlsMediaSource.Factory(http).createMediaSource(item(streams.hlsUrl))
            else -> {
                showError(null)
                return
            }
        }
        statusView.isVisible = false
        if (streams.live || position <= 0) exo.setMediaSource(source) else exo.setMediaSource(source, position)
        exo.prepare()
        exo.playWhenReady = true
    }

    /** The video stream for the height asked for: that one, or the nearest under it. */
    private fun chosen(streams: YtStreams): Pair<Int, String>? {
        val max = YtStore.maxHeight(this)
        return streams.videos.firstOrNull { it.first <= max } ?: streams.videos.lastOrNull()
    }

    private fun showNow() {
        val now = YtPlayerService.now ?: return
        titleView.text = now.title
        channelView.text = now.author
        channelView.isVisible = now.author.isNotBlank()
        val streams = now.streams
        val video = streams?.let { chosen(it) }
        qualityChip.isVisible = streams != null && !streams.live && streams.videos.size > 1 && video != null
        if (video != null) qualityChip.text = getString(R.string.ml_yt_quality, video.first)
    }

    private fun showError(message: String?) {
        spinner.isVisible = false
        statusView.text = listOfNotNull(getString(R.string.ml_yt_error), message?.takeIf { it.isNotBlank() }).joinToString("\n")
        statusView.isVisible = true
    }

    private fun chooseQuality() {
        val streams = YtPlayerService.now?.streams ?: return
        val heights = streams.videos.map { it.first }
        AlertDialog.Builder(this)
            .setItems(heights.map { "${it}p" }.toTypedArray()) { _, which ->
                YtStore.setMaxHeight(this, heights[which])
                showNow()
                play(player?.currentPosition ?: 0)
            }
            .show()
    }

    /** No picture is fetched while nobody can see it. */
    private fun showVideo(show: Boolean) {
        val exo = player ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !show).build()
    }

    // ---- floating window and background ----

    @RequiresApi(Build.VERSION_CODES.O)
    private fun pipParams(): PictureInPictureParams {
        val size = player?.videoSize
        val ratio = if (size != null && size.width > 0 && size.height > 0) size.width.toFloat() / size.height else 16f / 9
        // The system refuses anything outside these proportions
        val safe = ratio.coerceIn(0.42f, 2.38f)
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational((safe * 1000).toInt(), 1000))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(player?.isPlaying == true && !leavingForAudio)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    private fun updatePip() {
        if (!canPip || Build.VERSION.SDK_INT < Build.VERSION_CODES.O || isFinishing || isDestroyed) return
        runCatching { setPictureInPictureParams(pipParams()) }
    }

    private fun enterPip(): Boolean {
        if (!canPip || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return runCatching { enterPictureInPictureMode(pipParams()) }.getOrDefault(false)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // From Android 12 the system does it by itself (see pipParams), also with the home gesture
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && player?.isPlaying == true && !leavingForAudio) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        playerView.useController = !isInPictureInPictureMode
        applyLayout()
        // Out of the floating window without coming back to the screen: it was closed
        if (!isInPictureInPictureMode && lifecycle.currentState == Lifecycle.State.CREATED) {
            player?.pause()
            finish()
        }
    }

    private fun playInBackground() {
        leavingForAudio = true
        updatePip()
        moveTaskToBack(true)
    }

    override fun onStart() {
        super.onStart()
        leavingForAudio = false
        showVideo(true)
        updatePip()
    }

    override fun onStop() {
        super.onStop()
        if (!inPip) showVideo(false)
    }

    override fun onDestroy() {
        loading?.cancel()
        playerView.player = null
        player?.let { exo ->
            exo.removeListener(listener)
            // Closed while paused: nothing is left to listen to, so the notification goes too
            if (isFinishing && !exo.isPlaying) {
                exo.stop()
                exo.clearMediaItems()
                YtPlayerService.now = null
            }
        }
        player = null
        controller?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onDestroy()
    }

    // ---- layout ----

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLayout()
    }

    /** Upright: the video 16:9 on top and the title under it. On its side or floating: the video alone. */
    private fun applyLayout() {
        val full = inPip || resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        info.isVisible = !full
        frame.layoutParams = if (full) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, resources.displayMetrics.widthPixels * 9 / 16)
        val bars = WindowInsetsControllerCompat(window, root)
        if (full && !inPip) {
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
        } else bars.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun buildLayout() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        frame = FrameLayout(this)
        playerView = PlayerView(this).apply {
            setBackgroundColor(Color.BLACK)
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            setFullscreenButtonClickListener { fullScreen ->
                requestedOrientation = if (fullScreen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
        frame.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        spinner = ProgressBar(this).apply { isVisible = false }
        frame.addView(spinner, FrameLayout.LayoutParams(48.dpToPx(), 48.dpToPx(), Gravity.CENTER))
        root.addView(frame)

        info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dpToPx(), 14.dpToPx(), 16.dpToPx(), 16.dpToPx())
        }
        titleView = TextView(this).apply {
            textSize = 18f
            setTextColor(UiKit.primary(this@YtPlayerActivity))
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 4
        }
        channelView = TextView(this).apply {
            textSize = 14f
            setTextColor(UiKit.secondary(this@YtPlayerActivity))
            setPadding(0, 8.dpToPx(), 0, 8.dpToPx())
            maxLines = 1
            setOnClickListener {
                val now = YtPlayerService.now ?: return@setOnClickListener
                if (now.channelId.isBlank()) return@setOnClickListener
                startActivity(YouTubeActivity.channelIntent(this@YtPlayerActivity, YtChannel(now.channelId, now.author, "")))
                if (player?.isPlaying == true) enterPip()
            }
        }
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8.dpToPx(), 0, 0)
        }
        qualityChip = chip("") { chooseQuality() }.apply { isVisible = false }
        pipChip = chip(getString(R.string.ml_yt_pip)) { enterPip() }.apply { isVisible = canPip }
        chips.addView(qualityChip)
        chips.addView(pipChip)
        chips.addView(chip(getString(R.string.ml_yt_background)) { playInBackground() })
        statusView = TextView(this).apply {
            textSize = 14f
            setTextColor(UiKit.secondary(this@YtPlayerActivity))
            background = UiKit.border(this@YtPlayerActivity, 14)
            setPadding(14.dpToPx(), 12.dpToPx(), 14.dpToPx(), 12.dpToPx())
            isVisible = false
            setOnClickListener {
                retries = 0
                load(0)
            }
        }
        info.addView(titleView)
        info.addView(channelView)
        info.addView(chips)
        info.addView(statusView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 16.dpToPx()
        })
        root.addView(info, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun chip(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 13f
        setTextColor(UiKit.primary(this@YtPlayerActivity))
        gravity = Gravity.CENTER
        background = UiKit.border(this@YtPlayerActivity, 15)
        setPadding(14.dpToPx(), 7.dpToPx(), 14.dpToPx(), 7.dpToPx())
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = 8.dpToPx()
        }
    }

    companion object {
        private const val EXTRA_ID = "video_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_CHANNEL = "channel"
        private const val EXTRA_CHANNEL_ID = "channel_id"

        fun intent(ctx: Context, video: YtVideo): Intent = Intent(ctx, YtPlayerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_ID, video.id)
            .putExtra(EXTRA_TITLE, video.title)
            .putExtra(EXTRA_CHANNEL, video.channel)
            .putExtra(EXTRA_CHANNEL_ID, video.channelId)
    }
}
