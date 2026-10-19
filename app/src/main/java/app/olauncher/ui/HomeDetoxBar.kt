package app.olauncher.ui

import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import app.olauncher.data.Prefs
import app.olauncher.helper.DetoxManager
import app.olauncher.ui.widgets.DetoxRingView

/**
 * Detox home (Settings): while home is in sight a countdown runs for a few seconds and then the
 * phone is locked, unless an app is opened; touching the calendar widget starts it again. It waits
 * while something is open over home (search, menus, dialogs, the notification shade) and starts
 * full again after. When it runs out several times in a row a lockout starts, like detox's.
 * Shown by the [DetoxRingView]s (a home widget and the ring around the Detox button); not running
 * during a detox session, which has its own page and bar.
 */
class HomeDetoxBar(
    private val root: View,
    private val prefs: Prefs,
    /** Something is open over home (the search page). */
    private val isCovered: () -> Boolean,
    /** Locks the phone: the countdown ran out. */
    private val onLock: () -> Unit,
) {
    private val context = root.context
    private val handler = Handler(Looper.getMainLooper())
    private var animator: ValueAnimator? = null
    private var progress = 1f
    private var resumed = false
    /** The countdown locked the phone: leaving home now is not using it. */
    private var locking = false

    private val watch = object : Runnable {
        override fun run() {
            check()
            handler.postDelayed(this, 400)
        }
    }

    fun onResume() {
        resumed = true
        locking = false
        handler.removeCallbacks(watch)
        handler.post(watch)
    }

    /** Home left: for an app or another screen, unless the countdown itself locked the phone. */
    fun onPause() {
        resumed = false
        handler.removeCallbacks(watch)
        if (animator != null && !locking) DetoxManager.homeBarUsed(context)
        stop()
        publish()
    }

    /** The calendar widget was touched: full again. */
    fun restart() {
        if (animator != null) start()
    }

    fun release() {
        handler.removeCallbacks(watch)
        stop()
    }

    private fun enabled() = prefs.homeDetox && !DetoxManager.isActive(context)

    private fun shouldRun() = resumed && enabled() && root.hasWindowFocus() && !isCovered()

    private fun check() {
        val run = shouldRun()
        if (run && animator == null) start()
        else if (!run && animator != null) stop()
        publish()
    }

    private fun publish() {
        val after = prefs.homeDetoxLockoutAfter
        val left = if (after > 0) (after - DetoxManager.homeMissedBars(context, prefs.homeDetoxLockoutReset)).coerceAtLeast(0) else null
        DetoxRingView.State.set(enabled(), animator != null, progress, left)
    }

    private fun start() {
        stop()
        progress = 1f
        animator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = prefs.homeDetoxSeconds * 1000L
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                publish()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (cancelled) return
                    animator = null
                    progress = 1f
                    if (!shouldRun()) return
                    DetoxManager.homeBarMissed(
                        context, prefs.homeDetoxLockoutAfter, prefs.homeDetoxLockoutMinutes, prefs.homeDetoxLockoutReset
                    )
                    publish()
                    locking = true
                    onLock()
                }
            })
            start()
        }
    }

    private fun stop() {
        animator?.cancel()
        animator = null
        progress = 1f
    }
}
