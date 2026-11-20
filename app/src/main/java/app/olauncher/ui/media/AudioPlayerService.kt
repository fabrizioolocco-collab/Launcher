package app.olauncher.ui.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import app.olauncher.R
import app.olauncher.helper.showToast
import java.io.File
import java.util.concurrent.Executors

/**
 * Plays music and audio files in the background: the audio files of a folder one after the
 * other, with a notification (and the lock screen, headset buttons, Bluetooth) to pause, skip
 * and close. [AudioPlayerActivity] is its screen.
 */
class AudioPlayerService : Service() {

    /** What is loaded. [cover]: the picture in the file, when it has one. */
    class State(val file: File, val title: String, val artist: String, val cover: Bitmap?, val playing: Boolean, val duration: Int)

    private var tracks: List<File> = emptyList()
    private var index = 0
    private var player: MediaPlayer? = null
    private var session: MediaSession? = null
    private var title = ""
    private var artist = ""
    private var cover: Bitmap? = null
    private var foreground = false
    private val main = Handler(Looper.getMainLooper())
    private val reader = Executors.newSingleThreadExecutor()

    /** Something else plays (a call, another app): this stops. */
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pausePlayback()
    }

    /** The headphones were unplugged: not out loud. */
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = pausePlayback()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        session = MediaSession(this, "MinimaAudio").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = resumePlayback()
                override fun onPause() = pausePlayback()
                override fun onSkipToNext() = next(byUser = true)
                override fun onSkipToPrevious() = previous()
                override fun onSeekTo(pos: Long) = seekTo(pos.toInt())
                override fun onStop() = stopPlayback()
            })
            isActive = true
        }
        ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val file = intent.getStringExtra(EXTRA_PATH)?.let { File(it) }
                // Started as a foreground service: it has to show its notification at once
                goForeground()
                if (file != null && file.isFile) open(file) else if (player == null) stopPlayback()
            }
            ACTION_TOGGLE -> toggle()
            ACTION_NEXT -> next(byUser = true)
            ACTION_PREVIOUS -> previous()
            ACTION_STOP -> stopPlayback()
            else -> if (player == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(noisy)
        } catch (ignored: Exception) {
        }
        release()
        session?.release()
        session = null
        reader.shutdownNow()
        @Suppress("DEPRECATION")
        (getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocus(focusListener)
        if (instance === this) instance = null
        state.value = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- playing

    private fun release() {
        try {
            player?.release()
        } catch (ignored: Exception) {
        }
        player = null
    }

    private fun open(file: File) {
        // The other audio files of the folder, by name: what previous and next go through
        val siblings = file.parentFile?.listFiles()?.filter { it.isFile && AudioPlayerActivity.isAudio(it.name) && !it.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() }.orEmpty()
        tracks = if (siblings.any { it.absolutePath == file.absolutePath }) siblings else listOf(file)
        load(tracks.indexOfFirst { it.absolutePath == file.absolutePath }.coerceAtLeast(0))
    }

    /** Loads the track at [position] of the folder and plays it. */
    private fun load(position: Int) {
        val file = tracks.getOrNull(position) ?: return
        index = position
        release()
        title = file.nameWithoutExtension
        artist = ""
        cover = null
        try {
            val created = MediaPlayer()
            created.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            created.setDataSource(file.absolutePath)
            // Goes on with the screen off
            created.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            created.prepare()
            created.setOnCompletionListener { next(byUser = false) }
            player = created
        } catch (e: Exception) {
            e.printStackTrace()
            player = null
            showToast(getString(R.string.ml_search_cannot_open))
            publish()
            return
        }
        readDetails(file)
        resumePlayback()
    }

    /** Title, artist and cover written in the file, read off the main thread. */
    private fun readDetails(file: File) {
        reader.execute {
            var foundTitle: String? = null
            var foundArtist = ""
            var picture: Bitmap? = null
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                foundTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                foundArtist = listOfNotNull(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                ).filter { it.isNotBlank() }.joinToString(" · ")
                picture = retriever.embeddedPicture?.let { bytes ->
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= 800) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            } finally {
                try {
                    retriever.release()
                } catch (ignored: Exception) {
                }
            }
            val shownTitle = foundTitle?.takeIf { it.isNotBlank() }
            val shownArtist = foundArtist
            val shownCover = picture
            main.post {
                if (tracks.getOrNull(index)?.absolutePath != file.absolutePath) return@post
                if (shownTitle != null) title = shownTitle
                artist = shownArtist
                cover = shownCover
                publish()
            }
        }
    }

    private fun isPlaying() = try {
        player?.isPlaying == true
    } catch (e: Exception) {
        false
    }

    private fun currentPosition() = try {
        player?.currentPosition ?: 0
    } catch (e: Exception) {
        0
    }

    private fun resumePlayback() {
        val p = player ?: return
        @Suppress("DEPRECATION")
        (getSystemService(Context.AUDIO_SERVICE) as AudioManager).requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        try {
            p.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        publish()
    }

    private fun pausePlayback() {
        try {
            player?.takeIf { it.isPlaying }?.pause()
        } catch (ignored: Exception) {
        }
        publish()
    }

    private fun toggle() {
        if (player == null) return load(index)
        if (isPlaying()) pausePlayback() else resumePlayback()
    }

    /** Back to the start of the track, or to the track before when it has just started. */
    private fun previous() {
        if (currentPosition() > 3000 || index == 0) seekTo(0) else load(index - 1)
    }

    /** The next track; at the end of the folder it stops (when a track ended) or stays where it is. */
    private fun next(byUser: Boolean) {
        if (index + 1 < tracks.size) return load(index + 1)
        if (byUser) return
        seekTo(0)
        publish()
    }

    private fun seekTo(position: Int) {
        try {
            player?.seekTo(position)
        } catch (ignored: Exception) {
        }
        publish()
    }

    private fun stopPlayback() {
        release()
        session?.isActive = false
        foreground = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        state.value = null
        stopSelf()
    }

    // ---------------------------------------------------------------- what the outside sees

    /** Tells the screen, the system (lock screen, headset, Bluetooth) and the notification how things are. */
    private fun publish() {
        val file = tracks.getOrNull(index) ?: return
        val playing = isPlaying()
        val duration = try {
            player?.duration?.coerceAtLeast(0) ?: 0
        } catch (e: Exception) {
            0
        }
        state.value = State(file, title, artist, cover, playing, duration)
        session?.let { s ->
            val metadata = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration.toLong())
            cover?.let { metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
            s.setMetadata(metadata.build())
            s.setPlaybackState(PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP)
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, currentPosition().toLong(), if (playing) 1f else 0f, SystemClock.elapsedRealtime())
                .build())
        }
        if (playing) goForeground()
        else {
            // Paused: the notification stays but can be swiped away (which closes the player)
            if (foreground) {
                foreground = false
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            }
            try {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(false))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun goForeground() {
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
            ServiceCompat.startForeground(this, NOTIFICATION, notification(isPlaying()), type)
            foreground = true
        } catch (e: Exception) {
            // Resumed from the background where the system doesn't allow it: it plays all the same
            e.printStackTrace()
        }
    }

    private fun notification(playing: Boolean): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.ml_audio_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        fun service(code: Int, action: String) = PendingIntent.getService(this, code, Intent(this, AudioPlayerService::class.java).setAction(action), flags)
        fun action(icon: Int, label: Int, intent: PendingIntent) = Notification.Action.Builder(Icon.createWithResource(this, icon), getString(label), intent).build()
        val open = PendingIntent.getActivity(this, 0, Intent(this, AudioPlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        builder.setSmallIcon(R.drawable.ic_m_audio)
            .setContentTitle(title)
            .setContentText(artist)
            .setLargeIcon(cover)
            .setContentIntent(open)
            .setDeleteIntent(service(4, ACTION_STOP))
            .setOngoing(playing)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(R.drawable.ic_m_left, R.string.ml_audio_previous, service(1, ACTION_PREVIOUS)))
            .addAction(
                if (playing) action(R.drawable.ic_m_pause, R.string.ml_audio_pause, service(2, ACTION_TOGGLE))
                else action(R.drawable.ic_m_play, R.string.ml_audio_play, service(2, ACTION_TOGGLE))
            )
            .addAction(action(R.drawable.ic_m_right, R.string.ml_audio_next, service(3, ACTION_NEXT)))
            .addAction(action(R.drawable.ic_m_close, R.string.close, service(4, ACTION_STOP)))
        val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
        session?.let { style.setMediaSession(it.sessionToken) }
        builder.setStyle(style)
        return builder.build()
    }

    companion object {
        private const val ACTION_PLAY = "play"
        private const val ACTION_TOGGLE = "toggle"
        private const val ACTION_NEXT = "next"
        private const val ACTION_PREVIOUS = "previous"
        private const val ACTION_STOP = "stop"
        private const val EXTRA_PATH = "path"
        private const val CHANNEL = "audio_player"
        private const val NOTIFICATION = 7401

        /** What is loaded, null when the player is closed. */
        val state = MutableLiveData<State?>(null)

        private var instance: AudioPlayerService? = null

        /** Plays [file], then the audio files after it in its folder. */
        fun play(context: Context, file: File) = ContextCompat.startForegroundService(
            context, Intent(context, AudioPlayerService::class.java).setAction(ACTION_PLAY).putExtra(EXTRA_PATH, file.absolutePath)
        )

        fun toggle() = instance?.toggle()
        fun next() = instance?.next(byUser = true)
        fun previous() = instance?.previous()
        fun seekTo(position: Int) = instance?.seekTo(position)
        fun stop() = instance?.stopPlayback()
        fun position(): Int = instance?.currentPosition() ?: 0
    }
}
