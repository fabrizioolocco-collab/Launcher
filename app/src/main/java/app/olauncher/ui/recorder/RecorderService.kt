package app.olauncher.ui.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import app.olauncher.R
import app.olauncher.helper.showToast
import java.io.File

/**
 * Records the microphone to a file as a foreground service (the "Recording" notification, with
 * pause and stop), so it goes on with the screen off or another app in front.
 */
class RecorderService : Service() {

    /** The recording going on: [elapsedBefore] is the time recorded before the last resume. */
    class State(val file: File, val paused: Boolean, private val elapsedBefore: Long, private val resumedAt: Long) {
        fun elapsed() = elapsedBefore + if (paused) 0 else SystemClock.elapsedRealtime() - resumedAt
    }

    private var recorder: MediaRecorder? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> intent.getStringExtra(EXTRA_PATH)?.let { begin(File(it)) }
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> endRecording()
        }
        if (recorder == null) stopSelf()
        return START_NOT_STICKY
    }

    private fun begin(file: File) {
        if (recorder != null) return
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            ServiceCompat.startForeground(this, NOTIFICATION, notification(paused = false, elapsed = 0), type)
        } catch (e: Exception) {
            e.printStackTrace()
            showToast(getString(R.string.ml_rec_failed))
            return
        }
        file.parentFile?.mkdirs()
        val started = create(file)
        if (started == null) {
            showToast(getString(R.string.ml_rec_failed))
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            return
        }
        recorder = started
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "minimalauncher:recorder").apply { acquire(MAX_RECORDING) }
        state.value = State(file, false, 0, SystemClock.elapsedRealtime())
    }

    /** Mono speech at 16 kHz (what the transcription works on); 48 kHz where the phone refuses it. */
    private fun create(file: File): MediaRecorder? {
        val ogg = file.extension.lowercase() == "ogg" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        for (rate in listOf(16000, 48000)) {
            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()
            try {
                r.setAudioSource(MediaRecorder.AudioSource.MIC)
                if (ogg && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
                    r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                } else {
                    r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                }
                r.setAudioChannels(1)
                r.setAudioSamplingRate(rate)
                r.setAudioEncodingBitRate(if (rate == 16000) 24000 else 32000)
                r.setOutputFile(file.absolutePath)
                r.prepare()
                r.start()
                return r
            } catch (e: Exception) {
                e.printStackTrace()
                try {
                    r.release()
                } catch (ignored: Exception) {
                }
                file.delete()
            }
        }
        return null
    }

    private fun pause() {
        val r = recorder ?: return
        val s = state.value ?: return
        if (s.paused) return
        try {
            r.pause()
        } catch (e: Exception) {
            return
        }
        state.value = State(s.file, true, s.elapsed(), 0)
        refresh(true, s.elapsed())
    }

    private fun resume() {
        val r = recorder ?: return
        val s = state.value ?: return
        if (!s.paused) return
        try {
            r.resume()
        } catch (e: Exception) {
            return
        }
        state.value = State(s.file, false, s.elapsed(), SystemClock.elapsedRealtime())
        refresh(false, s.elapsed())
    }

    private fun refresh(paused: Boolean, elapsed: Long) = try {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(paused, elapsed))
    } catch (e: Exception) {
        e.printStackTrace()
    }

    private fun endRecording() {
        val r = recorder ?: return
        recorder = null
        val file = state.value?.file
        try {
            r.stop()
        } catch (e: Exception) {
            // Nothing recorded yet
            e.printStackTrace()
        }
        try {
            r.release()
        } catch (ignored: Exception) {
        }
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (ignored: Exception) {
        }
        wakeLock = null
        state.value = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (file != null && file.length() == 0L) file.delete()
        RecorderJobs.changed()
        if (file != null && file.isFile && RecorderStore.autoTranscribe(this) && RecorderStore.apiKey(this).isNotEmpty())
            RecorderJobs.transcribe(this, file)
    }

    override fun onDestroy() {
        endRecording()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun notification(paused: Boolean, elapsed: Long): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.ml_rec_app), NotificationManager.IMPORTANCE_LOW)
        )
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        fun action(code: Int, action: String) =
            PendingIntent.getService(this, code, Intent(this, RecorderService::class.java).setAction(action), flags)
        val open = PendingIntent.getActivity(this, 0, Intent(this, RecorderActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_m_mic)
            .setContentTitle(getString(if (paused) R.string.ml_rec_paused else R.string.ml_rec_recording))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (paused) builder.setContentText(RecorderStore.clock(elapsed)).setShowWhen(false)
        else builder.setUsesChronometer(true).setShowWhen(true).setWhen(System.currentTimeMillis() - elapsed)
        if (paused) builder.addAction(R.drawable.ic_m_mic, getString(R.string.ml_rec_resume), action(1, ACTION_RESUME))
        else builder.addAction(R.drawable.ic_m_pause, getString(R.string.ml_rec_pause), action(2, ACTION_PAUSE))
        builder.addAction(R.drawable.ic_m_stop, getString(R.string.ml_rec_stop), action(3, ACTION_STOP))
        return builder.build()
    }

    companion object {
        private const val ACTION_START = "start"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_STOP = "stop"
        private const val EXTRA_PATH = "path"
        private const val CHANNEL = "recorder"
        private const val NOTIFICATION = 7301
        /** The wake lock gives up after this long, whatever happens. */
        private const val MAX_RECORDING = 12 * 60 * 60 * 1000L

        /** The recording going on, null when there is none. */
        val state = MutableLiveData<State?>(null)

        @Volatile
        private var instance: RecorderService? = null

        /** Loudness since the last call (0..32767), for the level meter. */
        fun amplitude(): Int = try {
            instance?.recorder?.maxAmplitude ?: 0
        } catch (e: Exception) {
            0
        }

        fun start(context: Context, file: File) = ContextCompat.startForegroundService(
            context, Intent(context, RecorderService::class.java).setAction(ACTION_START).putExtra(EXTRA_PATH, file.absolutePath)
        )

        /** Pause, resume or stop the recording going on. */
        fun send(context: Context, action: String) {
            if (state.value == null) return
            try {
                context.startService(Intent(context, RecorderService::class.java).setAction(action))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
