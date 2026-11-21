package app.olauncher.ui.youtube

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
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
    class Now(val videoId: String, var title: String, var author: String, var channelId: String, var streams: YtStreams? = null)

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player = exo
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, YtPlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, exo).setId("MinimaTube").setSessionActivity(open).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
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
    }
}
