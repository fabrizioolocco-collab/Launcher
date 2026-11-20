package app.olauncher.ui.media

import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * Pinch zoom on a video of [MediaViewerActivity], as in the iPhone Photos app: two fingers zoom in and out
 * (up to [MAX_SCALE]) around the point between them, one finger moves the zoomed picture. Only the view
 * is enlarged, the file stays as it is. [page] gets the touches (the video and the black bands around it).
 */
class VideoZoom(private val page: View, private val video: View) {

    private var scale = 1f
    private var tx = 0f
    private var ty = 0f
    private var lastX = 0f
    private var lastY = 0f

    private val scaler = ScaleGestureDetector(page.context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            page.parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val newScale = (scale * detector.scaleFactor).coerceIn(1f, MAX_SCALE)
            // The point between the fingers stays where it is
            val fx = detector.focusX - page.width / 2f
            val fy = detector.focusY - page.height / 2f
            tx = fx - (fx - tx) * (newScale / scale)
            ty = fy - (fy - ty) * (newScale / scale)
            scale = newScale
            apply()
            return true
        }
    })

    private val zoomed get() = scale > 1.01f

    /** Feeds a touch of [page]: pinch, and moving the picture while zoomed (not a swipe to the next file). */
    fun onTouch(ev: MotionEvent) {
        scaler.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = ev.x
                lastY = ev.y
                if (zoomed) page.parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> page.parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_MOVE -> {
                if (zoomed && !scaler.isInProgress && ev.pointerCount == 1) {
                    tx += ev.x - lastX
                    ty += ev.y - lastY
                    apply()
                }
                lastX = ev.x
                lastY = ev.y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Go on from the finger left on the screen, without a jump
                val other = if (ev.actionIndex == 0) 1 else 0
                lastX = ev.getX(other)
                lastY = ev.getY(other)
            }
        }
    }

    /** Back to the normal size (another file, the video closed or ended). */
    fun reset() {
        scale = 1f
        tx = 0f
        ty = 0f
        apply()
    }

    private fun apply() {
        if (!zoomed) {
            scale = 1f
            tx = 0f
            ty = 0f
        }
        // The picture cannot be moved past its edges
        val maxX = ((video.width * scale - page.width) / 2f).coerceAtLeast(0f)
        val maxY = ((video.height * scale - page.height) / 2f).coerceAtLeast(0f)
        tx = tx.coerceIn(-maxX, maxX)
        ty = ty.coerceIn(-maxY, maxY)
        video.scaleX = scale
        video.scaleY = scale
        video.translationX = tx
        video.translationY = ty
    }

    private companion object {
        const val MAX_SCALE = 4f
    }
}
