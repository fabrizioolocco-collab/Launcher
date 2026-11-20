package app.olauncher.ui.media

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import androidx.core.view.isVisible
import app.olauncher.R
import app.olauncher.helper.dpToPx
import java.text.DecimalFormat

/**
 * Controls drawn over a video of [MediaViewerActivity]: one card in the middle of the screen with the
 * elapsed and total time, the bar, then speed, back 10 s, play-pause, forward 10 s and audio. Touches outside the buttons and the bar go through to the video (tap, double tap).
 * [onActivity] is told of every use, to restart the auto-hide countdown; [onPlay] of play pressed (the
 * controls then get out of the way at once).
 */
class VideoControls(
    context: Context,
    private val video: VideoView,
    private val onActivity: () -> Unit,
    private val onPlay: () -> Unit,
) : FrameLayout(context) {

    /** The player of the open video (set when prepared): speed and volume. */
    var player: MediaPlayer? = null
        set(value) {
            field = value
            applyVolume()
            // VideoView starts it right after the prepared listener: then the speed can be set
            if (speed != 1f) video.postDelayed({ if (video.isPlaying) applySpeed() }, 100)
        }

    private var speed = 1f
    private var muted = false
    private var dragging = false

    private val playIcon = ImageView(context).apply { setColorFilter(0xFF12161C.toInt()) }
    private val seek = SeekBar(context)
    private val elapsed = timeText()
    private val total = timeText().apply { gravity = Gravity.END }
    private val speedChip = TextView(context)
    private val muteIcon = ImageView(context)
    private val card = LinearLayout(context)

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            if (isAttachedToWindow && isVisible) postDelayed(this, 250)
        }
    }

    init {
        setBackgroundColor(0x33000000)
        isVisible = false

        // One dark card in the middle: the times, the bar, then speed, -10 s, play, +10 s, audio
        card.orientation = LinearLayout.VERTICAL
        card.background = GradientDrawable().apply {
            cornerRadius = 28.dpToPx().toFloat()
            setColor(0xC7121620.toInt())
            setStroke(1.dpToPx(), 0x1FFFFFFF)
        }
        card.setPadding(20.dpToPx(), 18.dpToPx(), 20.dpToPx(), 12.dpToPx())

        val times = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        times.addView(elapsed, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        times.addView(total, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(times, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = 4.dpToPx()
            marginEnd = 4.dpToPx()
        })

        seek.progressTintList = ColorStateList.valueOf(Color.WHITE)
        seek.progressBackgroundTintList = ColorStateList.valueOf(0x80FFFFFF.toInt())
        seek.thumbTintList = ColorStateList.valueOf(Color.WHITE)
        seek.splitTrack = false
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                video.seekTo(progress)
                showTimes(progress, bar.max)
            }
            override fun onStartTrackingTouch(bar: SeekBar) {
                dragging = true
                onActivity()
            }
            override fun onStopTrackingTouch(bar: SeekBar) {
                dragging = false
                onActivity()
            }
        })
        // Dragging the bar is the bar's, not a swipe to the next photo
        seek.setOnTouchListener { v, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
            false
        }
        card.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 36.dpToPx()).apply { topMargin = 4.dpToPx() })

        speedChip.apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = circle(0x00000000)
            contentDescription = context.getString(R.string.ml_player_speed)
            setOnClickListener { nextSpeed() }
        }
        val play = FrameLayout(context).apply {
            background = circle(Color.WHITE)
            contentDescription = context.getString(R.string.ml_player_play_pause)
            isClickable = true
            isFocusable = true
            setOnClickListener { togglePlay() }
            addView(playIcon, LayoutParams(30.dpToPx(), 30.dpToPx(), Gravity.CENTER))
        }
        val mute = FrameLayout(context).apply {
            contentDescription = context.getString(R.string.ml_viewer_mute)
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleMute() }
            addView(muteIcon, LayoutParams(22.dpToPx(), 22.dpToPx(), Gravity.CENTER))
        }
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf(
            speedChip to 48,
            skipButton(R.drawable.ic_mp_replay, R.string.ml_player_back) { jump(-SKIP_MS) } to 52,
            play to 68,
            skipButton(R.drawable.ic_mp_forward, R.string.ml_player_forward) { jump(SKIP_MS) } to 52,
            mute to 48,
        ).forEach { (v, size) ->
            buttons.addView(FrameLayout(context).apply {
                addView(v, LayoutParams(size.dpToPx(), size.dpToPx(), Gravity.CENTER))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        card.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 6.dpToPx() })
        addView(card, LayoutParams(360.dpToPx(), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        speedChip.text = speedLabel()
        muteIcon.setImageResource(R.drawable.ic_m_volume)
        refresh()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // As wide as the screen allows, up to 400 dp
        val width = (w - 48.dpToPx()).coerceIn(0, 400.dpToPx())
        if (card.layoutParams.width != width) post {
            card.layoutParams = card.layoutParams.apply { this.width = width }
        }
    }

    /** Shows or hides the controls, fading. */
    fun setShown(show: Boolean) {
        animate().cancel()
        if (show) {
            if (!isVisible) alpha = 0f
            isVisible = true
            animate().alpha(1f).setDuration(150).start()
            removeCallbacks(ticker)
            ticker.run()
        } else if (isVisible) {
            animate().alpha(0f).setDuration(150).withEndAction { isVisible = false }.start()
        }
    }

    /** Hidden at once, no fading (the video was closed). */
    fun hideNow() {
        animate().cancel()
        isVisible = false
    }

    /** Speed chosen again once playback is running (setting it while paused would start the video). */
    fun onStarted() {
        if (speed != 1f) applySpeed()
        refresh()
    }

    fun refresh() {
        playIcon.setImageResource(if (video.isPlaying) R.drawable.ic_mp_pause else R.drawable.ic_m_play)
        val duration = video.duration
        if (duration <= 0) {
            showTimes(0, 0)
            return
        }
        seek.max = duration
        if (!dragging) {
            seek.progress = video.currentPosition
            showTimes(video.currentPosition, duration)
        }
    }

    /** Pauses or starts the video, as the play button. */
    fun togglePlay() {
        if (video.isPlaying) {
            video.pause()
            refresh()
            onActivity()
        } else {
            video.start()
            onStarted()
            onPlay()
        }
    }

    private fun jump(ms: Int) {
        val duration = video.duration
        if (duration <= 0) return
        video.seekTo((video.currentPosition + ms).coerceIn(0, duration))
        refresh()
        onActivity()
    }

    private fun nextSpeed() {
        speed = SPEEDS[(SPEEDS.indexOf(speed) + 1).mod(SPEEDS.size)]
        speedChip.text = speedLabel()
        if (video.isPlaying) applySpeed()
        onActivity()
    }

    private fun applySpeed() {
        val p = player ?: return
        try {
            p.playbackParams = p.playbackParams.setSpeed(speed)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun toggleMute() {
        muted = !muted
        muteIcon.setImageResource(if (muted) R.drawable.ic_m_volume_off else R.drawable.ic_m_volume)
        applyVolume()
        onActivity()
    }

    private fun applyVolume() {
        try {
            val v = if (muted) 0f else 1f
            player?.setVolume(v, v)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showTimes(position: Int, duration: Int) {
        elapsed.text = MediaViewerActivity.formatDuration(position.toLong())
        total.text = if (duration > 0) MediaViewerActivity.formatDuration(duration.toLong()) else ""
    }

    private fun speedLabel() = DecimalFormat("0.##").format(speed) + "×"

    private fun timeText() = TextView(context).apply {
        setTextColor(0xF2FFFFFF.toInt())
        textSize = 13f
        setShadowLayer(4f, 0f, 1f, 0x99000000.toInt())
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun skipButton(icon: Int, description: Int, onClick: () -> Unit) = FrameLayout(context).apply {
        contentDescription = context.getString(description)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        addView(ImageView(context).apply {
            setImageResource(icon)
            setColorFilter(Color.WHITE)
        }, LayoutParams(34.dpToPx(), 34.dpToPx(), Gravity.CENTER))
        // The number in the middle of the circle of the arrow, a bit low
        addView(TextView(context).apply {
            text = "${SKIP_MS / 1000}"
            setTextColor(Color.WHITE)
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
        }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            topMargin = 3.dpToPx()
        })
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(ticker)
    }

    private companion object {
        const val SKIP_MS = 10_000
        val SPEEDS = listOf(1f, 1.25f, 1.5f, 2f, 0.5f, 0.75f)
    }
}
