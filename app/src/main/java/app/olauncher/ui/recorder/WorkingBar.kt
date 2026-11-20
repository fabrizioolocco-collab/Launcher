package app.olauncher.ui.recorder

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import app.olauncher.helper.dpToPx
import app.olauncher.ui.UiKit

/**
 * The bar shown while a transcript or a write-up is being made: colours that flow along it, filled
 * up to [fraction] when how far the work is is known, whole (just flowing) when it isn't.
 */
class WorkingBar(context: Context) : View(context) {

    /** 0..1, or null when the work can't say how far it is. */
    var fraction: Float? = null
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val matrix = Matrix()
    private var gradient: LinearGradient? = null
    private var span = 1f
    private var shift = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1800
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            shift = it.animatedValue as Float
            invalidate()
        }
    }

    private fun run(on: Boolean) {
        if (on && !animator.isStarted) animator.start() else if (!on && animator.isStarted) animator.cancel()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        run(isShown)
    }

    override fun onDetachedFromWindow() {
        run(false)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        run(isShown && isAttachedToWindow)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // The colours repeat, the last being the first again, so the flow has no seam
        span = maxOf(w.toFloat(), 200.dpToPx().toFloat())
        gradient = LinearGradient(0f, 0f, span, 0f, COLORS, null, Shader.TileMode.REPEAT)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = height / 2f
        box.set(0f, 0f, width.toFloat(), height.toFloat())
        track.color = ColorUtils.setAlphaComponent(UiKit.primary(context), 0x26)
        canvas.drawRoundRect(box, radius, radius, track)
        val shader = gradient ?: return
        matrix.setTranslate(shift * span, 0f)
        shader.setLocalMatrix(matrix)
        paint.shader = shader
        val part = fraction
        // Never so short that it can't be seen
        box.right = if (part == null) width.toFloat() else maxOf(width * part.coerceIn(0f, 1f), height * 2f)
        canvas.drawRoundRect(box, radius, radius, paint)
    }

    companion object {
        private val COLORS = intArrayOf(0xFF4F8EF7.toInt(), 0xFF9B6CF2.toInt(), 0xFFE85D9E.toInt(), 0xFFF59E42.toInt(), 0xFF4F8EF7.toInt())

        /** How far a job is, from its message: "… 42%" or "… 3/8"; null when it doesn't say. */
        fun fractionOf(message: String?): Float? {
            if (message == null) return null
            Regex("(\\d+)%").find(message)?.let { return it.groupValues[1].toFloat() / 100f }
            Regex("(\\d+)/(\\d+)").find(message)?.let {
                val total = it.groupValues[2].toFloat()
                // The step shown is the one being done: the ones before it are finished
                if (total > 0) return (it.groupValues[1].toFloat() - 1f).coerceAtLeast(0f) / total
            }
            return null
        }
    }
}
