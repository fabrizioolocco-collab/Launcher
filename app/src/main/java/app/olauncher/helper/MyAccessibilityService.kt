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

    /** Detox: a blocked app was opened (one of its screens came to the front), send the user back home. */
    private fun checkDetox(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (!DetoxManager.isActive(this)) {
            DetoxManager.checkExpired(this)
            return
        }
        if (DetoxManager.isAllowed(this, pkg)) return
        if (!opensAsScreen(pkg, event.className?.toString())) return
        performGlobalAction(GLOBAL_ACTION_HOME)
        val now = SystemClock.elapsedRealtime()
        if (now - lastBlockToast > 3000) {
            lastBlockToast = now
            Toast.makeText(this, getString(R.string.ml_detox_blocked), Toast.LENGTH_SHORT).show()
        }
    }

    /** Answers of [opensAsScreen] by "package/class": an app's activities don't change while it runs. */
    private val screenCache = HashMap<String, Boolean>()

    /**
     * True when the window that came up is one of the app's screens, i.e. the app was opened. A
     * dialog, popup or panel it shows on its own (often while syncing in the background, for a
     * login or a permission) is not an activity, and a translucent or floating activity only
     * draws over what is there: closing those would break the app's work, so detox leaves them.
     */
    private fun opensAsScreen(pkg: String, className: String?): Boolean {
        if (className.isNullOrEmpty()) return false
        val key = "$pkg/$className"
        screenCache[key]?.let { return it }
        val result = try {
            val info = packageManager.getActivityInfo(android.content.ComponentName(pkg, className), 0)
            val theme = info.themeResource.takeIf { it != 0 } ?: info.applicationInfo.theme
            val appContext = createPackageContext(pkg, 0).apply { if (theme != 0) setTheme(theme) }
            val attrs = appContext.theme.obtainStyledAttributes(
                intArrayOf(android.R.attr.windowIsTranslucent, android.R.attr.windowIsFloating)
            )
            val overlay = attrs.getBoolean(0, false) || attrs.getBoolean(1, false)
            attrs.recycle()
            !overlay
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            // Not an activity of the app: a dialog, popup or other view
            false
        } catch (e: Exception) {
            // The theme can't be read: an activity all the same, treated as a screen
            true
        }
        screenCache[key] = result
        return result
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