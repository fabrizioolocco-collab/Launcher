package app.olauncher.ui.widgets

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import app.olauncher.R
import app.olauncher.data.Prefs
import app.olauncher.ui.UiKit

/**
 * The detox home countdown as a small ring that empties, with the bars left before the lockout
 * inside. On home as a widget (moved and resized like any other) and at the left of the search
 * bar, around the Detox button. While the countdown waits (search or a dialog open) the ring is
 * full and faded; with detox home off the widget shows only its faint track.
 */
@SuppressLint("ViewConstructor")
class DetoxRingView(context: Context, val widgetId: Int, private val inBar: Boolean = false) : View(context) {

    val touch = GridItemTouch(this)

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()
    private val listener: () -> Unit = { invalidate() }

    init {
        contentDescription = context.getString(R.string.ml_home_detox)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        State.listeners += listener
    }

    override fun onDetachedFromWindow() {
        State.listeners -= listener
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (inBar && !State.enabled) return
        val size = minOf(width, height).toFloat()
        if (size <= 0f) return
        val stroke = if (inBar) 3f * resources.displayMetrics.density else (size * 0.08f).coerceAtLeast(2f)
        val inset = stroke / 2f + if (inBar) 2f * resources.displayMetrics.density else size * 0.06f
        val cx = width / 2f
        val cy = height / 2f
        rect.set(cx - size / 2f + inset, cy - size / 2f + inset, cx + size / 2f - inset, cy + size / 2f - inset)
        val primary = UiKit.primary(context)
        trackPaint.strokeWidth = stroke
        trackPaint.color = ColorUtils.setAlphaComponent(primary, 0x30)
        canvas.drawOval(rect, trackPaint)
        if (!State.enabled) return
        val color = Prefs(context).detoxLockColor.takeIf { it != 0 } ?: DEFAULT_COLOR
        arcPaint.strokeWidth = stroke
        arcPaint.color = if (State.running) color else ColorUtils.setAlphaComponent(color, 0x60)
        val share = if (State.running) State.progress.coerceIn(0f, 1f) else 1f
        if (share > 0f) canvas.drawArc(rect, -90f, 360f * share, false, arcPaint)
        val left = State.barsLeft ?: return
        textPaint.color = primary
        textPaint.textSize = rect.height() * 0.5f
        val fm = textPaint.fontMetrics
        canvas.drawText(left.toString(), cx, cy - (fm.ascent + fm.descent) / 2f, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = if (inBar) false else touch.touch(event)

    /** What the rings show, set by the detox home countdown. */
    object State {
        var enabled = false
            private set
        var running = false
            private set
        var progress = 1f
            private set
        /** Bars that can still run out before the lockout; null when the lockout is off. */
        var barsLeft: Int? = null
            private set
        internal val listeners = mutableSetOf<() -> Unit>()

        fun set(enabled: Boolean, running: Boolean, progress: Float, barsLeft: Int?) {
            if (enabled == this.enabled && running == this.running && progress == this.progress && barsLeft == this.barsLeft) return
            this.enabled = enabled
            this.running = running
            this.progress = progress
            this.barsLeft = barsLeft
            listeners.toList().forEach { it() }
        }
    }

    companion object {
        const val KIND = "detox_ring"
        const val MIN_SIZE_DP = 22
        private const val DEFAULT_COLOR = 0xFFFF9800.toInt()
    }
}
