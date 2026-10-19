package app.olauncher.helper

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * The ringing of the todo alarms: vibration alone first, then the alarm sound joins in, from very
 * low up to full volume (the alarm volume of the phone). It goes on until every ringing alarm is
 * answered, at most [MAX_MS].
 */
object AlarmRinger {
    private const val VIBRATE_ONLY_MS = 10_000L
    private const val RAMP_MS = 30_000L
    private const val STEP_MS = 500L
    private const val START_VOLUME = 0.03f
    private const val MAX_MS = 15 * 60_000L

    private val handler = Handler(Looper.getMainLooper())
    private val ringing = mutableSetOf<Long>()
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var soundStart = 0L
    private var appContext: Context? = null
    private val soundStarter = Runnable { appContext?.let { startSound(it) } }
    private val timeout = Runnable { stopAll() }

    private val alarmAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun start(context: Context, id: Long) {
        val ctx = context.applicationContext
        handler.post {
            val wasRinging = ringing.isNotEmpty()
            ringing += id
            if (wasRinging) return@post
            appContext = ctx
            acquireWakeLock(ctx)
            vibrate(ctx)
            handler.postDelayed(soundStarter, VIBRATE_ONLY_MS)
            handler.postDelayed(timeout, MAX_MS)
        }
    }

    /** The alarm of [id] was answered: the ringing stops when no other alarm rings. */
    fun stop(id: Long) = handler.post {
        ringing -= id
        if (ringing.isEmpty()) stopAll()
    }

    private fun stopAll() {
        ringing.clear()
        handler.removeCallbacks(soundStarter)
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(ramp)
        try {
            player?.stop()
        } catch (e: Exception) {
            // Not started yet
        }
        player?.release()
        player = null
        vibrator?.cancel()
        vibrator = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun acquireWakeLock(ctx: Context) {
        wakeLock = try {
            (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MinimaLauncher:todoAlarm")
                .apply { acquire(MAX_MS + 5_000) }
        } catch (e: Exception) {
            null
        }
    }

    private fun vibrate(ctx: Context) {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else
            @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
        val pattern = longArrayOf(0, 800, 600)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                @Suppress("DEPRECATION") v.vibrate(VibrationEffect.createWaveform(pattern, 0), alarmAttributes)
            else
                @Suppress("DEPRECATION") v.vibrate(pattern, 0)
            vibrator = v
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startSound(ctx: Context) {
        if (ringing.isEmpty()) return
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return
        player = try {
            MediaPlayer().apply {
                setAudioAttributes(alarmAttributes)
                setDataSource(ctx, uri)
                isLooping = true
                setVolume(START_VOLUME, START_VOLUME)
                prepare()
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } ?: return
        soundStart = System.currentTimeMillis()
        handler.post(ramp)
    }

    /** Volume up from [START_VOLUME] to full in [RAMP_MS], on a curve so the start stays soft. */
    private val ramp = object : Runnable {
        override fun run() {
            val p = player ?: return
            val share = ((System.currentTimeMillis() - soundStart).toFloat() / RAMP_MS).coerceIn(0f, 1f)
            val volume = START_VOLUME + (1f - START_VOLUME) * share * share
            try {
                p.setVolume(volume, volume)
            } catch (e: Exception) {
                return
            }
            if (share < 1f) handler.postDelayed(this, STEP_MS)
        }
    }
}
