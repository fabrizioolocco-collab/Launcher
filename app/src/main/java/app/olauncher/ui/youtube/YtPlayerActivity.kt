package app.olauncher.ui.youtube

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.graphics.drawable.Icon
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
import android.graphics.drawable.GradientDrawable
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
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
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
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
import app.olauncher.ui.media.VideoControls
import app.olauncher.ui.media.VideoTransport
import app.olauncher.ui.media.VideoZoom
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

    /** How the video is being tried: 0 separate picture and sound, 1 the two in one file, 2 a playlist of pieces. */
    private var mode = 0
    private var usedMode = 0
    private var clientFrom = 0

    /** The video has played with these streams: an error now is an expired address, not a refused one. */
    private var played = false
    private val tried = ArrayList<String>()

    /** Started from the way that played last time instead of from the first: if it fails, the list is run from the top. */
    private var fromSaved = false

    // The same controls and gestures as the launcher's video viewer
    private lateinit var controls: VideoControls
    private lateinit var zoom: VideoZoom
    private lateinit var seekLabel: TextView
    private lateinit var fullButton: ImageView
    private var chromeVisible = false
    private val hideChrome = Runnable { if (chromeVisible && transport.isPlaying) setChrome(false) }

    private val transport = object : VideoTransport {
        // "Playing" also while it buffers: the button shows what a press will do
        override val isPlaying get() = player?.let { it.playWhenReady && it.playbackState != Player.STATE_IDLE && it.playbackState != Player.STATE_ENDED } == true
        override val duration get() = player?.duration?.takeIf { it != C.TIME_UNSET && it > 0 }?.toInt() ?: 0
        override val position get() = player?.currentPosition?.toInt() ?: 0
        override fun seekTo(ms: Int) {
            player?.seekTo(ms.toLong())
        }

        override fun start() {
            val exo = player ?: return
            if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0)
            exo.play()
        }

        override fun pause() {
            player?.pause()
        }

        override fun setSpeed(speed: Float) {
            player?.setPlaybackSpeed(speed)
        }

        override fun setVolume(volume: Float) {
            player?.volume = volume
        }
    }

    /** Leaving for the sound only: no floating window this time. */
    private var leavingForAudio = false

    private var rotation: OrientationEventListener? = null

    /** Left with back: the video stops, also if it was playing. */
    private var closing = false

    private val canPip by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }
    private val inPip get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePip()
            controls.refresh()
        }
        override fun onVideoSizeChanged(videoSize: VideoSize) = updatePip()

        override fun onPlaybackStateChanged(playbackState: Int) {
            // Over: the controls come back, as on a video of the gallery
            if (playbackState == Player.STATE_ENDED && !inPip) setChrome(true)
            if (playbackState == Player.STATE_READY && !played) {
                played = true
                val streams = YtPlayerService.now?.streams ?: return
                YtStore.setPath(this@YtPlayerActivity, streams.clientIndex * 10 + usedMode)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val position = player?.currentPosition ?: 0
            val streams = YtPlayerService.now?.streams
            if (played || streams == null) {
                // The addresses of a video expire after some hours: the same ones are asked again
                played = false
                load(position)
                return
            }
            // Refused before it ever played: the next way of these streams, then the next client
            tried.add(streams.client + "/" + usedMode + " " + reason(error))
            mode = usedMode + 1
            if (modes(streams).any { it >= mode }) play(position)
            else if (streams.clientIndex + 1 < YouTubeApi.clientCount) {
                clientFrom = streams.clientIndex + 1
                mode = 0
                load(position)
            } else if (fromSaved) {
                // The way remembered no longer works: all of them again, from the first
                YtStore.setPath(this@YtPlayerActivity, 0)
                fromSaved = false
                mode = 0
                clientFrom = 0
                load(position)
            } else showError(tried.joinToString("\n"))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Turning the phone turns the video, also with the rotation of the system locked
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        rotation = object : OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                // A side chosen with the button holds until the phone is held that way: then turning it counts again
                val upright = degrees <= 30 || degrees >= 330
                val sideways = degrees in 60..120 || degrees in 240..300
                when (requestedOrientation) {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE -> if (sideways) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> if (upright) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
                }
            }
        }
        buildLayout()
        setContentView(root)
        applyLayout()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                        requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE ->
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    else -> {
                        // Back leaves the video: it stops. The floating window is for home, or its button
                        closing = true
                        finish()
                    }
                }
            }
        })

        ContextCompat.registerReceiver(
            this, pipReceiver,
            IntentFilter().apply {
                addAction(ACTION_PIP_TOGGLE)
                addAction(ACTION_PIP_AUDIO)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

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
        restart()
        zoom.reset()
        setChrome(false)
        showNow()
        load(0)
    }

    private fun restart() {
        val saved = YtStore.path(this)
        mode = saved % 10
        clientFrom = saved / 10
        fromSaved = saved != 0
        played = false
        tried.clear()
    }

    /** What the player was refused, in the few words that tell one case from another. */
    private fun reason(error: Throwable): String {
        var cause: Throwable? = error
        var last = error
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) return "HTTP " + cause.responseCode
            last = cause
            cause = cause.cause
        }
        return last.javaClass.simpleName + (last.message?.let { ": " + it.take(80) } ?: "")
    }

    private fun modes(streams: YtStreams): List<Int> = listOfNotNull(
        0.takeIf { streams.videos.isNotEmpty() && streams.audioUrl != null && !streams.live },
        1.takeIf { streams.muxedUrl != null && !streams.live },
        2.takeIf { streams.hlsUrl != null }
    )

    // ---- loading and playing ----

    private fun load(position: Long) {
        val now = YtPlayerService.now ?: return
        val exo = player ?: return
        loading?.cancel()
        statusView.isVisible = false
        spinner.isVisible = true
        exo.stop()
        if (YouTubeApi.visitor == null) YouTubeApi.visitor = YtStore.visitor(this)
        loading = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { YouTubeApi.streams(now.videoId, clientFrom) } }
            YouTubeApi.visitor?.let { YtStore.setVisitor(this@YtPlayerActivity, it) }
            if (YtPlayerService.now !== now) return@launch
            spinner.isVisible = false
            val streams = result.getOrNull()
            if (streams == null) {
                showError((tried + listOfNotNull(result.exceptionOrNull()?.message?.takeIf { it.isNotBlank() })).joinToString("\n"))
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
        val plain = DefaultHttpDataSource.Factory().setUserAgent(streams.userAgent).setAllowCrossProtocolRedirects(true)
        // Whole files are asked a piece at a time: asked in one go they are slowed down or refused
        val http = DataSource.Factory { YtChunkedDataSource(plain.createDataSource()) }
        val meta = MediaMetadata.Builder()
            .setTitle(now.title)
            .setArtist(now.author)
            .setArtworkUri(Uri.parse("https://i.ytimg.com/vi/${now.videoId}/hqdefault.jpg"))
            .build()
        fun item(url: String) = MediaItem.Builder().setUri(url).setMediaId(now.videoId).setMediaMetadata(meta).build()
        fun progressive(url: String) = ProgressiveMediaSource.Factory(http).createMediaSource(item(url))

        val video = chosen(streams)
        val audio = streams.audioUrl
        val use = modes(streams).firstOrNull { it >= mode }
        val source: MediaSource = when {
            use == 0 && video != null && audio != null -> MergingMediaSource(progressive(video.second), progressive(audio))
            use == 1 && streams.muxedUrl != null -> progressive(streams.muxedUrl)
            use == 2 && streams.hlsUrl != null -> HlsMediaSource.Factory(plain).createMediaSource(item(streams.hlsUrl))
            else -> {
                showError(tried.joinToString("\n"))
                return
            }
        }
        usedMode = use ?: 0
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
                restart()
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
        // The buttons of the floating window: pause / play, and close it keeping the sound
        val playing = transport.isPlaying
        builder.setActions(
            listOf(
                pipAction(if (playing) R.drawable.ic_m_pause else R.drawable.ic_m_play, getString(R.string.ml_player_play_pause), ACTION_PIP_TOGGLE, 1),
                pipAction(R.drawable.ic_m_audio, getString(R.string.ml_yt_background), ACTION_PIP_AUDIO, 2)
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(player?.isPlaying == true && !leavingForAudio)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun pipAction(icon: Int, label: String, action: String, code: Int) = RemoteAction(
        Icon.createWithResource(this, icon), label, label,
        PendingIntent.getBroadcast(this, code, Intent(action).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE)
    )

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PIP_TOGGLE -> if (transport.isPlaying) transport.pause() else transport.start()
                ACTION_PIP_AUDIO -> {
                    // The window goes, the service keeps playing: the notification brings the video back
                    leavingForAudio = true
                    showVideo(false)
                    finish()
                }
            }
        }
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
        if (isInPictureInPictureMode) {
            chromeVisible = false
            controls.hideNow()
            fullButton.isVisible = false
        }
        zoom.reset()
        applyLayout()
        // Out of the floating window without coming back to the screen: it was closed
        if (!isInPictureInPictureMode && lifecycle.currentState == Lifecycle.State.CREATED && !leavingForAudio && !isFinishing) {
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
        rotation?.let { if (it.canDetectOrientation()) it.enable() }
        showVideo(true)
        updatePip()
    }

    override fun onStop() {
        super.onStop()
        rotation?.disable()
        if (!inPip) showVideo(false)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(pipReceiver) }
        loading?.cancel()
        playerView.player = null
        player?.let { exo ->
            exo.removeListener(listener)
            // Closed with back, or while paused: nothing is left to listen to, so the notification goes too
            if (isFinishing && (closing || !exo.isPlaying) && !leavingForAudio) {
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
        zoom.reset()
        applyLayout()
    }

    // ---- controls: a tap shows or hides them, and they hide by themselves while the video plays ----

    private fun setChrome(show: Boolean) {
        frame.removeCallbacks(hideChrome)
        chromeVisible = show
        controls.setShown(show)
        fullButton.isVisible = show
        if (show) scheduleHide()
    }

    private fun scheduleHide() {
        frame.removeCallbacks(hideChrome)
        frame.postDelayed(hideChrome, HIDE_DELAY_MS)
    }

    /** Shows [text] for a moment over the video, at the side given by [horizontal] (double tap, long press). */
    private fun flash(text: String, horizontal: Int) {
        seekLabel.text = text
        (seekLabel.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = Gravity.CENTER_VERTICAL or horizontal
            marginStart = 32.dpToPx()
            marginEnd = 32.dpToPx()
        }
        seekLabel.requestLayout()
        seekLabel.animate().cancel()
        seekLabel.alpha = 1f
        seekLabel.isVisible = true
        seekLabel.animate().alpha(0f).setStartDelay(500).setDuration(300)
            .withEndAction { seekLabel.isVisible = false }.start()
    }

    private fun toggleFullScreen() {
        requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
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
            useController = false
        }
        frame.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Feedback of a double-tap seek ("-10 s" / "+10 s") and of a long press
        seekLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            val p = 14.dpToPx()
            setPadding(p, p / 2, p, p / 2)
            background = GradientDrawable().apply {
                cornerRadius = 24.dpToPx().toFloat()
                setColor(0x99000000.toInt())
            }
            isVisible = false
        }
        frame.addView(seekLabel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        controls = VideoControls(this, transport, onActivity = { scheduleHide() }, onPlay = { setChrome(false) })
        frame.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fullButton = UiKit.iconButton(this, R.drawable.ic_m_resize, getString(R.string.ml_yt_fullscreen), Color.WHITE) {
            toggleFullScreen()
            scheduleHide()
        }.apply { isVisible = false }
        frame.addView(fullButton, FrameLayout.LayoutParams(44.dpToPx(), 44.dpToPx(), Gravity.TOP or Gravity.END))
        zoom = VideoZoom(frame, playerView)
        // Tap: show / hide the controls; double tap on the right / left half: forward / back 10 s;
        // long press: pause / play; two fingers: zoom
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (player != null && !inPip) setChrome(!chromeVisible)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val duration = transport.duration
                if (duration <= 0) return false
                val forward = e.x >= frame.width / 2f
                transport.seekTo((transport.position + if (forward) SEEK_STEP_MS else -SEEK_STEP_MS).coerceIn(0, duration))
                controls.refresh()
                flash(if (forward) "+10 s  \u00BB" else "\u00AB  -10 s", if (forward) Gravity.END else Gravity.START)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (transport.duration <= 0) return
                frame.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                val pausing = transport.isPlaying
                controls.togglePlay()
                flash(if (pausing) "\u275A\u275A" else "\u25B6", Gravity.CENTER_HORIZONTAL)
            }
        })
        frame.setOnTouchListener { _, ev ->
            zoom.onTouch(ev)
            gestures.onTouchEvent(ev)
            true
        }
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
                restart()
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
        private const val SEEK_STEP_MS = 10_000
        private const val ACTION_PIP_TOGGLE = "app.olauncher.tube.PIP_TOGGLE"
        private const val ACTION_PIP_AUDIO = "app.olauncher.tube.PIP_AUDIO"
        private const val HIDE_DELAY_MS = 3_000L
        private const val EXTRA_ID = "video_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_CHANNEL = "channel"
        private const val EXTRA_CHANNEL_ID = "channel_id"

        /** Back to the video the service is playing (the "now playing" bar of the list). */
        fun resumeIntent(ctx: Context): Intent = Intent(ctx, YtPlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun intent(ctx: Context, video: YtVideo): Intent = Intent(ctx, YtPlayerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_ID, video.id)
            .putExtra(EXTRA_TITLE, video.title)
            .putExtra(EXTRA_CHANNEL, video.channel)
            .putExtra(EXTRA_CHANNEL_ID, video.channelId)
    }
}
