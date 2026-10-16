package app.olauncher.ui.media

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Image view with gallery-style zoom: pinch to zoom around the fingers, double tap to zoom in on
 * the tapped point (and back), drag to pan with the image kept inside the screen, fling with
 * inertia, and a gentle bounce back when zoomed out below the fit size or past the edges.
 * While the image fits the screen, horizontal drags are left to the pager (swipe to the next one).
 */
class ZoomImageView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : AppCompatImageView(context, attrs) {

    /** Called with the single tap position (toggles the viewer's chrome). */
    var onSingleTap: (() -> Unit)? = null

    private val matrix = Matrix()
    private val values = FloatArray(9)
    private var fitScale = 1f
    private var animator: ValueAnimator? = null
    private var flinger: ValueAnimator? = null

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            stopAnimations()
            val current = scale()
            var factor = d.scaleFactor
            // Allow a little past the limits, bounce back on release
            val target = (current * factor).coerceIn(fitScale * 0.7f, maxScale() * 1.5f)
            factor = target / current
            matrix.postScale(factor, factor, d.focusX, d.focusY)
            imageMatrix = matrix
            return true
        }

        override fun onScaleEnd(d: ScaleGestureDetector) = settle()
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            stopAnimations()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onSingleTap?.invoke()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val target = if (scale() > fitScale * 1.1f) fitScale else doubleTapScale()
            animateScale(target, e.x, e.y)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaleDetector.isInProgress) return false
            if (!isZoomed()) return false
            matrix.postTranslate(-dx, -dy)
            constrain(allowOverscroll = true)
            imageMatrix = matrix
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (!isZoomed() || scaleDetector.isInProgress) return false
            fling(vx, vy)
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
        super.setImageDrawable(drawable)
        post { fit() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    /** Scales the image to fit the view, centered. */
    fun fit() {
        val d = drawable ?: return
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (dw <= 0 || dh <= 0 || width == 0 || height == 0) return
        fitScale = min(width / dw, height / dh)
        matrix.reset()
        matrix.postScale(fitScale, fitScale)
        matrix.postTranslate((width - dw * fitScale) / 2f, (height - dh * fitScale) / 2f)
        imageMatrix = matrix
    }

    fun isZoomed() = scale() > fitScale * 1.02f

    private fun scale(): Float {
        matrix.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    private fun maxScale(): Float {
        val d = drawable ?: return fitScale * 4f
        // Up to 1:1 pixels × 3, never less than 4× the fit size
        val oneToOne = 1f
        return max(fitScale * 4f, oneToOne * 3f)
    }

    private fun doubleTapScale(): Float {
        val d = drawable ?: return fitScale * 2.5f
        val oneToOne = 1f
        // First double tap: real pixels when that's a visible jump, otherwise 2.5×
        return if (oneToOne > fitScale * 1.5f) min(oneToOne, maxScale()) else fitScale * 2.5f
    }

    private fun imageRect(): RectF {
        val d = drawable ?: return RectF()
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        matrix.mapRect(r)
        return r
    }

    /** Keeps the image covering the view (or centered when smaller). With [allowOverscroll] a bit of rubber-band past the edges. */
    private fun constrain(allowOverscroll: Boolean = false) {
        val r = imageRect()
        var dx = 0f
        var dy = 0f
        val slack = if (allowOverscroll) 0f else 0f
        if (r.width() <= width) dx = (width - r.width()) / 2f - r.left
        else if (r.left > slack) dx = slack - r.left
        else if (r.right < width - slack) dx = width - slack - r.right
        if (r.height() <= height) dy = (height - r.height()) / 2f - r.top
        else if (r.top > slack) dy = slack - r.top
        else if (r.bottom < height - slack) dy = height - slack - r.bottom
        matrix.postTranslate(dx, dy)
    }

    /** After a pinch: back within the scale limits, then inside the edges, animated. */
    private fun settle() {
        val s = scale()
        val target = s.coerceIn(fitScale, maxScale())
        if (abs(target - s) > 0.001f) animateScale(target, width / 2f, height / 2f)
        else animateConstrain()
    }

    private fun animateScale(target: Float, px: Float, py: Float) {
        stopAnimations()
        val start = scale()
        val startMatrix = Matrix(matrix)
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                val s = start + (target - start) * t
                matrix.set(startMatrix)
                matrix.postScale(s / start, s / start, px, py)
                constrain()
                imageMatrix = matrix
            }
            start()
        }
    }

    private fun animateConstrain() {
        val before = Matrix(matrix)
        constrain()
        val after = Matrix(matrix)
        matrix.set(before)
        val from = FloatArray(9).also { before.getValues(it) }
        val to = FloatArray(9).also { after.getValues(it) }
        if (abs(from[Matrix.MTRANS_X] - to[Matrix.MTRANS_X]) < 0.5f && abs(from[Matrix.MTRANS_Y] - to[Matrix.MTRANS_Y]) < 0.5f) {
            matrix.set(after)
            imageMatrix = matrix
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                val v = FloatArray(9) { i -> from[i] + (to[i] - from[i]) * t }
                matrix.setValues(v)
                imageMatrix = matrix
            }
            start()
        }
    }

    private fun fling(vx: Float, vy: Float) {
        var lastT = 0f
        flinger = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                val dt = t - lastT
                lastT = t
                // Distance covered in this step: velocity × step, slowed by the interpolator
                matrix.postTranslate(vx * dt * 0.35f, vy * dt * 0.35f)
                constrain()
                imageMatrix = matrix
            }
            start()
        }
    }

    private fun stopAnimations() {
        animator?.cancel()
        flinger?.cancel()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (!scaleDetector.isInProgress && flinger?.isRunning != true) settle()
        }
        // Zoomed: keep the touches (the pager must not swipe); fitted: let the pager take horizontal drags
        parent?.requestDisallowInterceptTouchEvent(isZoomed() || event.pointerCount > 1)
        return true
    }
}
