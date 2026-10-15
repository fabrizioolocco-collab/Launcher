package app.olauncher.helper

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.olauncher.R
import app.olauncher.data.Prefs

class MyAccessibilityService : AccessibilityService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onServiceConnected() {
        Prefs(applicationContext).lockModeOn = true
        super.onServiceConnected()
    }

    private var lastBlockToast = 0L

    /** Detox: a blocked app came to the front, send the user back home. */
    private fun checkDetox(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (!DetoxManager.isActive(this)) {
            DetoxManager.checkExpired(this)
            return
        }
        if (DetoxManager.isAllowed(this, pkg)) return
        performGlobalAction(GLOBAL_ACTION_HOME)
        val now = SystemClock.elapsedRealtime()
        if (now - lastBlockToast > 3000) {
            lastBlockToast = now
            Toast.makeText(this, getString(R.string.ml_detox_blocked), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        try {
            checkDetox(event)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // Double tap to lock: only clicks on the launcher's own placeholder view
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED || event.packageName != packageName) return
        try {
            val source: AccessibilityNodeInfo = event.source ?: return
            if (source.className != "android.widget.FrameLayout") return

            when (source.contentDescription) {
                getString(R.string.lock_layout_description) -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                        performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                }
                // Home button for recents feature disabled
                // getString(R.string.recents_layout_description) -> {
                //     performGlobalAction(GLOBAL_ACTION_RECENTS)
                // }
            }
        } catch (e: Exception) {
            return
        }
    }

    override fun onInterrupt() {

    }
}