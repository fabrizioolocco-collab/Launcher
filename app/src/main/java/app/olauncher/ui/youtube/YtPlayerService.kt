package app.olauncher.ui.youtube

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Owns the player, so that a video goes on with the screen off or the app left: the session
 * gives the notification with pause and seek, and the controls of the lock screen and of the
 * headphones. The player screen attaches its picture to this player while it is open.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class YtPlayerService : MediaSessionService() {

    /** The video in the player, for a screen opened again from the notification. */
    class Now(
        val videoId: String, var title: String, var author: String, var channelId: String, var streams: YtStreams? = null,
        /** An episode of a podcast: where its sound is, and its cover. */
        val audio: String = "", val image: String = ""
    ) {
        val isEpisode get() = audio.isNotBlank()
        val picture get() = if (isEpisode) image else "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        fun asVideo() = YtVideo(videoId, title, author, channelId, "", "", audio, image)
    }

    private var session: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())

    /** The point of the video is written down every few seconds: the screen may be long gone. */
    private val ticker = object : Runnable {
        override fun run() {
            if (player?.isPlaying == true) savePosition()
            handler.postDelayed(this, SAVE_EVERY_MS)
        }
    }

    private fun writePosition() {
        val exo = player ?: return
        val video = now ?: return
        val duration = exo.duration
        if (exo.playbackState == Player.STATE_IDLE || duration == C.TIME_UNSET || duration <= 0 || exo.isCurrentMediaItemLive) return
        val position = exo.currentPosition
        // Near the end counts as finished: next time it starts over
        val finished = exo.playbackState == Player.STATE_ENDED || position > duration - 15_000
        if (finished) {
            YtStore.setPosition(this, video.videoId, 0, duration)
            // Watched to the end, and only then: it counts as watched, and is off the list of the videos to watch
            YtStore.setWatched(this, video.videoId, true)
            YtStore.dropLater(this, video.videoId)
        } else if (position > 5_000) YtStore.setPosition(this, video.videoId, position, duration)
    }

    override fun onCreate() {
        super.onCreate()
        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            // Starts as soon as one second is there (the default waits for two and a half)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(15000, 50000, 1000, 2000)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            )
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player = exo
        instance = this
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = savePosition()
        })
        handler.postDelayed(ticker, SAVE_EVERY_MS)
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, YtPlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, exo).setId("MinimaTube").setSessionActivity(open).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        writePosition()
        handler.removeCallbacks(ticker)
        instance = null
        session?.release()
        session = null
        player?.release()
        player = null
        now = null
        super.onDestroy()
    }

    companion object {
        /** The one player, alive as long as the service is. */
        var player: ExoPlayer? = null
            private set
        var now: Now? = null

        private var instance: YtPlayerService? = null
        private const val SAVE_EVERY_MS = 5_000L

        /** Writes down where the video is now (before another one takes its place). */
        fun savePosition() {
            instance?.writePosition()
        }
    }
}
