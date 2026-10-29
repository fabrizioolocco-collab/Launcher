package app.olauncher.ui.widgets

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Activity
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.lifecycle.Observer
import app.olauncher.R
import app.olauncher.data.CalendarText
import app.olauncher.helper.MenuEntry
import app.olauncher.helper.NotificationListener
import app.olauncher.helper.NotificationRepository
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.showDropdown
import java.text.DecimalFormat

/**
 * What is playing on the phone, at the bottom of the calendar widget: cover (tap opens the app,
 * long press moves to another app playing), title, a seek bar, jumps back / forward of a set number
 * of seconds, play / pause, next and the playback speed (when the app supports it).
 *
 * It reads the other apps' media sessions through the notification access the launcher already
 * has; without it, or with nothing playing, it stays hidden. It goes away when the app playing
 * closes its session (the app closed), and a paused one also when its media notification is gone
 * (Spotify keeps its session after being closed). The long press on the cover can also hide it.
 */
@SuppressLint("ViewConstructor")
class MediaPlayerBar(
    private val activity: Activity,
    private val style: (TextView, CalendarText) -> Unit,
) : LinearLayout(activity) {

    /** Seconds of the jumps back and forward (from the widget's settings). */
    var skipBack = 10
        set(value) {
            field = value
            backLabel.text = value.toString()
        }
    var skipForward = 30
        set(value) {
            field = value
            forwardLabel.text = value.toString()
        }

    /** Off from the widget's settings: no sessions read at all. */
    var active = true
        set(value) {
            field = value
            if (isAttachedToWindow) if (value) start() else stop()
        }

    private val primary = activity.getColorFromAttr(R.attr.primaryColor)
    private val inverse = activity.getColorFromAttr(R.attr.primaryInverseColor)
    private val manager = activity.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val listenerComponent = ComponentName(activity, NotificationListener::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var controllers: List<MediaController> = emptyList()
    private var current: MediaController? = null
    /** The app chosen with a long press on the cover, or the last one that started playing. */
    private var preferred: String? = null
    private var lastSpeed = 1f
    private var seeking = false
    /** Apps that were playing at the last refresh: one starting to play becomes the one shown. */
    private var wasPlaying = emptySet<String>()
    private var listening = false
    /** Apps hidden from the long press on the cover: back as soon as they start playing again. */
    private val hidden = mutableSetOf<String>()

    /** A media notification gone (app closed, notification swiped) can hide a paused session. */
    private val notificationsObserver = Observer<Any> { if (listening) refresh() }
    private var observing = false

    private val divider = View(activity)
    private val art = ImageView(activity).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = GradientDrawable().apply {
            cornerRadius = 9.dpToPx().toFloat()
            setColor(ColorUtils.setAlphaComponent(primary, 0x18))
        }
        clipToOutline = true
        contentDescription = activity.getString(R.string.ml_player_open)
        setOnClickListener { openApp() }
        setOnLongClickListener { switchApp(); true }
    }
    /** The app playing, always in sight (with "1/2" when more are playing: long press on the cover picks another). */
    private val appText = text(CalendarText.TIME).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val title = text(CalendarText.TITLE).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val subtitle = text(CalendarText.TIME).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val elapsed = text(CalendarText.TIME)
    private val total = text(CalendarText.TIME)
    private val seekBar = SeekBar(activity).apply {
        progressTintList = ColorStateList.valueOf(primary)
        progressBackgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(primary, 0x40))
        thumbTintList = ColorStateList.valueOf(primary)
        splitTrack = false
        setPadding(10.dpToPx(), 0, 10.dpToPx(), 0)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) elapsed.text = time(progress.toLong())
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                seeking = true
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                seeking = false
                current?.transportControls?.seekTo(bar.progress.toLong())
            }
        })
    }
    private val seekRow = LinearLayout(activity).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val speedChip = text(CalendarText.TIME).apply {
        gravity = Gravity.CENTER
        setPadding(9.dpToPx(), 4.dpToPx(), 9.dpToPx(), 4.dpToPx())
        background = GradientDrawable().apply {
            cornerRadius = 50.dpToPx().toFloat()
            setStroke(1.dpToPx(), ColorUtils.setAlphaComponent(primary, 0x60))
        }
        contentDescription = activity.getString(R.string.ml_player_speed)
        setOnClickListener { nextSpeed() }
        setOnLongClickListener { pickSpeed(); true }
    }
    private val backLabel = TextView(activity)
    private val forwardLabel = TextView(activity)
    private val backButton = skipButton(R.drawable.ic_mp_replay, backLabel, R.string.ml_player_back) { jump(-skipBack) }
    private val forwardButton = skipButton(R.drawable.ic_mp_forward, forwardLabel, R.string.ml_player_forward) { jump(skipForward) }
    private val playIcon = ImageView(activity).apply { setColorFilter(inverse, PorterDuff.Mode.SRC_IN) }
    private val playButton = FrameLayout(activity).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(primary)
        }
        contentDescription = activity.getString(R.string.ml_player_play_pause)
        isClickable = true
        isFocusable = true
        setOnClickListener { togglePlay() }
        addView(playIcon, FrameLayout.LayoutParams(24.dpToPx(), 24.dpToPx(), Gravity.CENTER))
    }
    private val prevButton = trackButton(R.drawable.ic_mp_prev, R.string.ml_player_prev) { current?.transportControls?.skipToPrevious() }
    private val nextButton = trackButton(R.drawable.ic_mp_next, R.string.ml_player_next) { current?.transportControls?.skipToNext() }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> setSessions(list.orEmpty()) }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
        override fun onSessionDestroyed() = refreshSessions()
    }

    private val ticker = object : Runnable {
        override fun run() {
            showPosition()
            if (isPlaying(current) && isShown) handler.postDelayed(this, 500)
        }
    }

    init {
        orientation = VERTICAL
        isVisible = false
        addView(divider, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1.dpToPx()).apply { bottomMargin = 10.dpToPx() })

        val top = LinearLayout(activity).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(art, LayoutParams(42.dpToPx(), 42.dpToPx()).apply { marginEnd = 12.dpToPx() })
        val meta = LinearLayout(activity).apply { orientation = VERTICAL }
        meta.addView(appText, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 3.dpToPx() })
        meta.addView(title)
        meta.addView(subtitle, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 3.dpToPx() })
        top.addView(meta, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(top, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        seekRow.addView(elapsed)
        seekRow.addView(seekBar, LayoutParams(0, 28.dpToPx(), 1f))
        seekRow.addView(total)
        addView(seekRow, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 6.dpToPx() })

        val controls = LinearLayout(activity).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf(speedChip, prevButton, backButton, playButton, forwardButton, nextButton).forEach { v ->
            val size = when (v) {
                playButton -> 46.dpToPx()
                prevButton, nextButton -> 40.dpToPx()
                else -> ViewGroup.LayoutParams.WRAP_CONTENT
            }
            controls.addView(FrameLayout(activity).apply {
                addView(v, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
            }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        addView(controls, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 2.dpToPx() })

        // A long press on the cover or the speed, or dragging the bar, is theirs: not the widget's (move / day swipe)
        listOf(art, speedChip, seekBar).forEach { claimTouch(it) }
        backLabel.text = skipBack.toString()
        forwardLabel.text = skipForward.toString()
        applyStyle()
    }

    /** The widget's text styles changed. */
    fun applyStyle() {
        divider.setBackgroundColor(ColorUtils.setAlphaComponent(primary, 0x50))
        style(title, CalendarText.TITLE)
        style(subtitle, CalendarText.TIME)
        subtitle.alpha = 0.6f
        style(appText, CalendarText.TIME)
        appText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        appText.typeface = Typeface.create(appText.typeface, Typeface.BOLD)
        appText.letterSpacing = 0.06f
        appText.alpha = 0.55f
        listOf(elapsed, total).forEach {
            style(it, CalendarText.TIME)
            it.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            it.fontFeatureSettings = "tnum"
            it.alpha = 0.8f
        }
        style(speedChip, CalendarText.TIME)
        speedChip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        listOf(backLabel, forwardLabel).forEach {
            it.setTextColor(primary)
            it.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f)
            it.typeface = Typeface.DEFAULT_BOLD
            it.includeFontPadding = false
        }
    }

    // ---------------------------------------------------------------- sessions

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (active) start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    /** Back on home: the notification access may have been granted meanwhile. */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE && isAttachedToWindow && active) start()
    }

    private fun start() {
        if (!observing) {
            NotificationRepository.state.observeForever(notificationsObserver)
            observing = true
        }
        if (!listening) {
            listening = try {
                manager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, handler)
                true
            } catch (e: Exception) {
                // No notification access: nothing to show
                false
            }
        }
        refreshSessions()
    }

    private fun stop() {
        if (observing) {
            NotificationRepository.state.removeObserver(notificationsObserver)
            observing = false
        }
        if (listening) {
            try {
                manager.removeOnActiveSessionsChangedListener(sessionsListener)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            listening = false
        }
        setSessions(emptyList())
    }

    private fun refreshSessions() {
        val list = try {
            manager.getActiveSessions(listenerComponent)
        } catch (e: Exception) {
            emptyList()
        }
        setSessions(list)
    }

    private fun setSessions(list: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(callback) }
        controllers = if (active) list else emptyList()
        controllers.forEach { it.registerCallback(callback, handler) }
        refresh()
    }

    /** Sessions with something loaded (playing, paused, buffering): not stopped or idle. */
    private fun loaded() = controllers.filter {
        when (it.playbackState?.state) {
            null, PlaybackState.STATE_NONE, PlaybackState.STATE_STOPPED, PlaybackState.STATE_ERROR -> false
            else -> it.metadata != null
        }
    }

    /**
     * The sessions shown: playing ones always (unless hidden), paused ones only while their app still
     * has its media notification. Apps like Spotify keep a paused session alive after being closed
     * (or after their notification is swiped away), which would leave the song there for good.
     */
    private fun candidates(): List<MediaController> {
        val media = mediaNotificationPackages()
        return loaded().filter {
            it.packageName !in hidden && (isPlaying(it) || media == null || it.packageName in media)
        }
    }

    /** Apps with a media notification up; null when the notifications can't be read (then no filter). */
    private fun mediaNotificationPackages(): Set<String>? {
        val listener = NotificationListener.instance ?: return null
        val all = try {
            listener.activeNotifications
        } catch (e: Exception) {
            null
        } ?: return null
        return all.filter { sbn ->
            val extras = sbn.notification?.extras
            extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true ||
                    extras?.getString(Notification.EXTRA_TEMPLATE)?.contains("MediaStyle") == true
        }.map { it.packageName }.toSet()
    }

    private fun isPlaying(c: MediaController?) = c?.playbackState?.state.let {
        it == PlaybackState.STATE_PLAYING || it == PlaybackState.STATE_BUFFERING ||
                it == PlaybackState.STATE_FAST_FORWARDING || it == PlaybackState.STATE_REWINDING
    }

    private fun refresh() {
        // An app that starts playing becomes the one shown (and is back if it was hidden)
        val playing = loaded().filter { isPlaying(it) }.map { it.packageName }.toSet()
        val started = playing - wasPlaying
        hidden -= started
        started.firstOrNull()?.let { preferred = it }
        wasPlaying = playing
        val list = candidates()
        val chosen = list.firstOrNull { it.packageName == preferred }
            ?: list.firstOrNull { isPlaying(it) }
            ?: list.firstOrNull()
        current = chosen
        handler.removeCallbacks(ticker)
        isVisible = chosen != null
        if (chosen == null) return
        show(chosen)
        if (isPlaying(chosen)) handler.postDelayed(ticker, 500)
    }

    private fun show(c: MediaController) {
        val meta = c.metadata
        val appName = appLabel(c.packageName)
        val name = meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val list = candidates()
        val index = list.indexOfFirst { it.packageName == c.packageName }
        appText.text = appName.uppercase() + if (list.size > 1 && index >= 0) "  ${index + 1}/${list.size}" else ""
        title.text = name ?: appName
        subtitle.text = (meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM)).orEmpty().trim()
        subtitle.isVisible = subtitle.text.isNotEmpty()

        val cover = meta?.cover()
        if (cover != null) {
            art.scaleType = ImageView.ScaleType.CENTER_CROP
            art.setImageBitmap(cover)
        } else {
            art.scaleType = ImageView.ScaleType.FIT_CENTER
            art.setImageDrawable(try {
                activity.packageManager.getApplicationIcon(c.packageName)
            } catch (e: Exception) {
                null
            })
        }

        val state = c.playbackState
        val actions = state?.actions ?: 0L
        val playing = isPlaying(c)
        playIcon.setImageResource(if (playing) R.drawable.ic_mp_pause else R.drawable.ic_mp_play)
        prevButton.isVisible = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L
        nextButton.isVisible = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L

        val duration = duration(c)
        seekRow.isVisible = duration > 0
        backButton.isVisible = duration > 0
        forwardButton.isVisible = duration > 0
        seekBar.isEnabled = actions and PlaybackState.ACTION_SEEK_TO != 0L
        if (duration > 0) {
            seekBar.max = duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            total.text = time(duration)
        }

        val speed = state?.playbackSpeed ?: 0f
        if (playing && speed > 0f) lastSpeed = speed
        speedChip.isVisible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                actions and PlaybackState.ACTION_SET_PLAYBACK_SPEED != 0L
        speedChip.text = speedLabel(lastSpeed)
        showPosition()
    }

    private fun MediaMetadata.cover(): Bitmap? =
        getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

    private fun duration(c: MediaController) = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L

    /** Where the playback is now: the last position reported plus the time gone since, at its speed. */
    private fun position(c: MediaController): Long {
        val state = c.playbackState ?: return 0L
        var pos = state.position
        if (state.state == PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime > 0)
            pos += ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
        val duration = duration(c)
        return if (duration > 0) pos.coerceIn(0, duration) else pos.coerceAtLeast(0)
    }

    private fun showPosition() {
        val c = current ?: return
        if (seeking || duration(c) <= 0) return
        val pos = position(c)
        seekBar.progress = pos.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        elapsed.text = time(pos)
    }

    // ---------------------------------------------------------------- actions

    private fun togglePlay() {
        val c = current ?: return
        if (isPlaying(c)) c.transportControls.pause() else c.transportControls.play()
    }

    private fun jump(seconds: Int) {
        val c = current ?: return
        val duration = duration(c)
        var target = position(c) + seconds * 1000L
        target = if (duration > 0) target.coerceIn(0, duration) else target.coerceAtLeast(0)
        c.transportControls.seekTo(target)
        seekBar.progress = target.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        elapsed.text = time(target)
    }

    /** Tap on the speed: the next of the usual ones. */
    private fun nextSpeed() {
        val next = CYCLE_SPEEDS.firstOrNull { it > lastSpeed + 0.01f } ?: CYCLE_SPEEDS.first()
        setSpeed(next)
    }

    /** Long press on the speed: all of them. */
    private fun pickSpeed() {
        speedChip.showDropdown(ALL_SPEEDS.map { s ->
            MenuEntry(speedLabel(s), icon = if (kotlin.math.abs(s - lastSpeed) < 0.01f) R.drawable.ic_m_check else 0) { setSpeed(s) }
        })
    }

    private fun setSpeed(speed: Float) {
        val c = current ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            c.transportControls.setPlaybackSpeed(speed)
            lastSpeed = speed
            speedChip.text = speedLabel(speed)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Tap on the cover: the app playing, on what it plays when it says where (its session activity). */
    private fun openApp() {
        val c = current ?: return
        val pi = c.sessionActivity
        if (pi != null) {
            try {
                // Since Android 14 the launcher must allow the PendingIntent to start the activity
                val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    @Suppress("DEPRECATION")
                    ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                        .toBundle()
                } else null
                pi.send(activity, 0, null, null, null, null, options)
                return
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        activity.packageManager.getLaunchIntentForPackage(c.packageName)?.let {
            try {
                activity.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Long press on the cover: a drop-down of the apps with something playing or paused, to pick the
     * one shown, and "Hide" to take the one shown away until it plays again.
     */
    private fun switchApp() {
        val list = candidates()
        if (list.size < 2) {
            art.showDropdown(listOf(MenuEntry(activity.getString(R.string.ml_player_hide), icon = R.drawable.ic_m_hide) { hideCurrent() }))
            return
        }
        val secondary = activity.getColorFromAttr(R.attr.secondaryTextColor)
        val card = LinearLayout(activity).apply {
            orientation = VERTICAL
            setPadding(6.dpToPx(), 6.dpToPx(), 6.dpToPx(), 6.dpToPx())
            background = GradientDrawable().apply {
                cornerRadius = 16.dpToPx().toFloat()
                setColor(activity.getColorFromAttr(R.attr.cardColor))
                setStroke(1.dpToPx(), activity.getColorFromAttr(R.attr.cardStrokeColor))
            }
        }
        val popup = android.widget.PopupWindow(card, 270.dpToPx(), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            elevation = 8.dpToPx().toFloat()
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            isOutsideTouchable = true
        }
        val ripple = TypedValue().let { tv ->
            if (activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) tv.resourceId else 0
        }
        list.forEach { c ->
            val name = c.metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                ?: c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            val state = activity.getString(if (isPlaying(c)) R.string.ml_player_playing else R.string.ml_player_paused)
            card.addView(LinearLayout(activity).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = 54.dpToPx()
                setPadding(10.dpToPx(), 6.dpToPx(), 10.dpToPx(), 6.dpToPx())
                if (ripple != 0) setBackgroundResource(ripple)
                isClickable = true
                addView(ImageView(activity).apply {
                    setImageDrawable(try {
                        activity.packageManager.getApplicationIcon(c.packageName)
                    } catch (e: Exception) {
                        null
                    })
                }, LayoutParams(30.dpToPx(), 30.dpToPx()).apply { marginEnd = 12.dpToPx() })
                addView(LinearLayout(activity).apply {
                    orientation = VERTICAL
                    addView(TextView(activity).apply {
                        text = appLabel(c.packageName)
                        textSize = 15f
                        setTextColor(primary)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    addView(TextView(activity).apply {
                        text = listOfNotNull(state, name).joinToString(" · ")
                        textSize = 12.5f
                        setTextColor(secondary)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    })
                }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (c.packageName == current?.packageName) addView(ImageView(activity).apply {
                    setImageResource(R.drawable.ic_m_check)
                    imageTintList = ColorStateList.valueOf(primary)
                }, LayoutParams(18.dpToPx(), 18.dpToPx()).apply { marginStart = 8.dpToPx() })
                setOnClickListener {
                    popup.dismiss()
                    preferred = c.packageName
                    refresh()
                }
            }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        card.addView(LinearLayout(activity).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 46.dpToPx()
            setPadding(10.dpToPx(), 6.dpToPx(), 10.dpToPx(), 6.dpToPx())
            if (ripple != 0) setBackgroundResource(ripple)
            isClickable = true
            addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_m_hide)
                imageTintList = ColorStateList.valueOf(primary)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, LayoutParams(30.dpToPx(), 20.dpToPx()).apply { marginEnd = 12.dpToPx() })
            addView(TextView(activity).apply {
                text = activity.getString(R.string.ml_player_hide)
                textSize = 15f
                setTextColor(primary)
            })
            setOnClickListener {
                popup.dismiss()
                hideCurrent()
            }
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        popup.showAsDropDown(art, 0, 4.dpToPx())
    }

    /** Takes the app shown off the widget until it starts playing again. */
    private fun hideCurrent() {
        val c = current ?: return
        hidden += c.packageName
        if (preferred == c.packageName) preferred = null
        refresh()
    }

    // ---------------------------------------------------------------- helpers

    @SuppressLint("ClickableViewAccessibility")
    private fun claimTouch(view: View) = view.setOnTouchListener { v, ev ->
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
        false
    }

    private fun trackButton(icon: Int, description: Int, onClick: () -> Unit) = ImageView(activity).apply {
        setImageResource(icon)
        setColorFilter(primary, PorterDuff.Mode.SRC_IN)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        contentDescription = activity.getString(description)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun skipButton(icon: Int, label: TextView, description: Int, onClick: () -> Unit) = FrameLayout(activity).apply {
        contentDescription = activity.getString(description)
        isClickable = true
        isFocusable = true
        setPadding(6.dpToPx(), 6.dpToPx(), 6.dpToPx(), 6.dpToPx())
        setOnClickListener { onClick() }
        addView(ImageView(activity).apply {
            setImageResource(icon)
            setColorFilter(primary, PorterDuff.Mode.SRC_IN)
        }, FrameLayout.LayoutParams(30.dpToPx(), 30.dpToPx(), Gravity.CENTER))
        // The number sits in the middle of the circle of the arrow, a bit low
        addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            topMargin = 3.dpToPx()
        })
    }

    private fun text(t: CalendarText) = TextView(activity, null, 0, R.style.TextSmall).also { style(it, t) }

    private fun appLabel(packageName: String) = try {
        activity.packageManager.getApplicationLabel(activity.packageManager.getApplicationInfo(packageName, 0)).toString()
    } catch (e: Exception) {
        packageName
    }

    private fun speedLabel(speed: Float) = DecimalFormat("0.##").format(speed) + "×"

    /** "3:07", "1:02:45". */
    private fun time(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = s % 3600 / 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s % 60) else "%d:%02d".format(m, s % 60)
    }

    companion object {
        private val CYCLE_SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
        private val ALL_SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
        val SKIP_SECONDS = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120)
    }
}
