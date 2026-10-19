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
 * while something is open over home (menus, dialogs, the notification shade) and starts full again
 * after. On the search page, as set: it goes on from where it was, waits, or runs a countdown of
 * its own. When it runs out several times in a row a lockout starts, like detox's.
 * Shown by the [DetoxRingView]s (a home widget and the ring around the Detox button); not running
 * during a detox session, which has its own page and bar.
 */
class HomeDetoxBar(
    private val root: View,
    private val prefs: Prefs,
    /** The search page is open. */
    private val isSearching: () -> Boolean,
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
    /** The search page was open when the countdown was last checked. */
    private var searching = false

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

    /**
     * Home left: for an app or another screen the missed bars start again from zero; not when the
     * countdown itself locked the phone or the screen was turned off.
     */
    fun onPause() {
        resumed = false
        handler.removeCallbacks(watch)
        if (animator != null && !locking && screenOn()) DetoxManager.homeBarUsed(context)
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

    private fun screenOn() = try {
        (context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
    } catch (e: Exception) {
        true
    }

    private fun shouldRun() = resumed && enabled() && root.hasWindowFocus() &&
            !(isSearching() && prefs.homeDetoxSearch == Prefs.HOME_DETOX_SEARCH_UNLIMITED)

    private fun check() {
        val search = isSearching()
        // A countdown of its own on the search page, and home's full again after it
        val switched = search != searching && prefs.homeDetoxSearch == Prefs.HOME_DETOX_SEARCH_LIMITED
        searching = search
        val run = shouldRun()
        if (run && (animator == null || switched)) start()
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
            val limited = searching && prefs.homeDetoxSearch == Prefs.HOME_DETOX_SEARCH_LIMITED
            duration = (if (limited) prefs.homeDetoxSearchSeconds else prefs.homeDetoxSeconds) * 1000L
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
