package app.olauncher.ui

import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import app.olauncher.R
import app.olauncher.data.Prefs
import app.olauncher.helper.DetoxManager
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr

/**
 * Detox home (Settings): while home is in sight a bar on top empties in a few seconds and then the
 * phone is locked, unless an app is opened; touching the calendar widget fills it again. It waits
 * while something is open over home (search, menus, dialogs, the notification shade) and starts
 * full again after. When it runs out several times in a row a lockout starts, like detox's.
 * Not shown during a detox session, which has its own page and bar.
 */
class HomeDetoxBar(
    root: FrameLayout,
    private val prefs: Prefs,
    /** Something is open over home (the search page). */
    private val isCovered: () -> Boolean,
    /** Locks the phone: the bar ran out. */
    private val onLock: () -> Unit,
) {
    private val context = root.context
    private val handler = Handler(Looper.getMainLooper())
    private val fill = View(context).apply {
        pivotX = 0f
        background = GradientDrawable().apply { cornerRadius = 2.dpToPx().toFloat() }
    }
    private val bar = FrameLayout(context).apply {
        isVisible = false
        addView(fill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 4.dpToPx()))
    }
    private val missed = TextView(context, null, 0, R.style.TextSmall).apply {
        textSize = 12f
        setTextColor(context.getColorFromAttr(R.attr.secondaryTextColor))
    }
    private val box = FrameLayout(context).apply {
        setPadding(16.dpToPx(), 6.dpToPx(), 16.dpToPx(), 0)
        addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 4.dpToPx()))
        addView(missed, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END).apply {
            topMargin = 8.dpToPx()
        })
        isVisible = false
    }
    private var animator: ValueAnimator? = null
    private var resumed = false
    /** The bar locked the phone: leaving home now is not using it. */
    private var locking = false

    private val watch = object : Runnable {
        override fun run() {
            check()
            handler.postDelayed(this, 400)
        }
    }

    init {
        root.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        // Below the status bar
        ViewCompat.setOnApplyWindowInsetsListener(box) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, top + 6.dpToPx(), v.paddingRight, 0)
            insets
        }
    }

    fun onResume() {
        resumed = true
        locking = false
        handler.removeCallbacks(watch)
        handler.post(watch)
    }

    /** Home left: for an app or another screen, unless the bar itself locked the phone. */
    fun onPause() {
        resumed = false
        handler.removeCallbacks(watch)
        if (animator != null && !locking) DetoxManager.homeBarUsed(context)
        stop()
    }

    /** The calendar widget was touched: full again. */
    fun restart() {
        if (animator != null) start()
    }

    fun release() {
        handler.removeCallbacks(watch)
        stop()
    }

    private fun shouldRun() = resumed && prefs.homeDetox && !DetoxManager.isActive(context) &&
            box.hasWindowFocus() && !isCovered()

    private fun check() {
        val run = shouldRun()
        if (run && animator == null) start()
        else if (!run && animator != null) stop()
        box.isVisible = run
        updateMissed()
    }

    private fun updateMissed() {
        val after = prefs.homeDetoxLockoutAfter
        missed.isVisible = after > 0
        if (after > 0) missed.text = context.getString(
            R.string.ml_detox_missed_bars, DetoxManager.homeMissedBars(context, prefs.homeDetoxLockoutReset), after
        )
    }

    private fun start() {
        stop()
        val color = prefs.detoxLockColor.takeIf { it != 0 } ?: DEFAULT_COLOR
        (fill.background as GradientDrawable).setColor(color)
        fill.scaleX = 1f
        bar.isVisible = true
        animator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = prefs.homeDetoxSeconds * 1000L
            interpolator = LinearInterpolator()
            addUpdateListener { fill.scaleX = it.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (cancelled) return
                    animator = null
                    bar.isVisible = false
                    if (!shouldRun()) return
                    DetoxManager.homeBarMissed(
                        context, prefs.homeDetoxLockoutAfter, prefs.homeDetoxLockoutMinutes, prefs.homeDetoxLockoutReset
                    )
                    updateMissed()
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
        bar.isVisible = false
    }

    private companion object {
        const val DEFAULT_COLOR = 0xFFFF9800.toInt()
    }
}
