package app.olauncher.helper

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import app.olauncher.R
import app.olauncher.data.Prefs

/**
 * WhatsApp with blinders: inside the real WhatsApp app, the Updates (status and channels) and
 * Communities tabs and the Meta AI button are covered by plain patches of the app's background,
 * so they can't be seen or tapped; chats, groups, files and calls keep working as they are.
 * If an Updates or Communities page comes up anyway (a swipe, a notification), WhatsApp is taken
 * back to the chats.
 *
 * Nothing in WhatsApp is changed: the service only reads what is on screen and draws over it.
 * Safety: a long press on a patch lifts the blinders for [PAUSE_MS]; if WhatsApp keeps coming back
 * to a blocked page the blinders switch themselves off; the switch in Settings turns it all off.
 */
class WhatsAppBlinders(private val service: AccessibilityService) {

    private val prefs = Prefs(service.applicationContext)
    private val handler = Handler(Looper.getMainLooper())
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val covers = ArrayList<View>()
    private var coveredRects: List<Rect> = emptyList()
    private var pausedUntil = 0L
    private val bounces = ArrayDeque<Long>()
    private var lastToast = 0L

    private var pending = false
    private val update = Runnable {
        pending = false
        try {
            refresh()
        } catch (e: Exception) {
            e.printStackTrace()
            clear()
        }
    }

    fun onEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString()
        if (pkg in PACKAGES) {
            if (!prefs.waBlinders) {
                clear()
                return
            }
            schedule()
        } else if (covers.isNotEmpty() && (
                event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                    event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED)
        ) {
            // Something else came to the front (home, another app, the notification shade)
            schedule()
        }
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        clear()
    }

    /** At most one look at the screen every [THROTTLE_MS], however many events WhatsApp sends. */
    private fun schedule() {
        if (pending) return
        pending = true
        handler.postDelayed(update, THROTTLE_MS)
    }

    private fun refresh() {
        val root = service.rootInActiveWindow
        if (!prefs.waBlinders || root == null || root.packageName?.toString() !in PACKAGES ||
            SystemClock.elapsedRealtime() < pausedUntil
        ) {
            clear()
            return
        }
        val screen = service.resources.displayMetrics
        val found = Found()
        scan(root, found, screen.widthPixels, screen.heightPixels, 0)

        if (found.blockedSelected) {
            backToChats(found.chatsTab)
            return
        }
        show(found.rects)
    }

    private class Found {
        val rects = ArrayList<Rect>()
        var blockedSelected = false
        var chatsTab: AccessibilityNodeInfo? = null
        var visited = 0
    }

    private fun scan(node: AccessibilityNodeInfo, found: Found, width: Int, height: Int, depth: Int) {
        if (depth > 40 || ++found.visited > MAX_NODES) return
        if (node.isVisibleToUser) {
            val label = (node.text ?: node.contentDescription)?.toString()?.trim()?.lowercase()
            if (!label.isNullOrEmpty()) check(node, label, found, width, height)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            scan(child, found, width, height, depth + 1)
        }
    }

    private fun check(node: AccessibilityNodeInfo, label: String, found: Found, width: Int, height: Int) {
        val blockedTab = BLOCKED_TABS.any { label.isTabLabel(it) }
        val chatsTab = CHAT_TABS.any { label.isTabLabel(it) }
        val metaAi = node.className?.toString()?.contains("EditText") != true &&
            node.contentDescription?.toString()?.lowercase()?.contains("meta ai") == true
        if (!blockedTab && !chatsTab && !metaAi) return

        // A tab or a floating button is a small item: chat rows, messages and the search bar
        // span the screen and are left alone even when their text matches
        // (the selected tab is often not clickable: then the label itself is the item)
        val bounds = Rect()
        val item = listOfNotNull(clickableAncestor(node), node).firstOrNull {
            it.getBoundsInScreen(bounds)
            !bounds.isEmpty && bounds.width() <= width * 0.45f && bounds.height() <= height * 0.15f
        } ?: return

        when {
            chatsTab -> if (found.chatsTab == null) found.chatsTab = item
            blockedTab -> {
                found.rects += bounds
                if (item.isSelected || node.isSelected || item.isChecked) found.blockedSelected = true
            }
            else -> if (bounds.width() < width * 0.3f) found.rects += bounds
        }
    }

    /** "aggiornamenti", "aggiornamenti, 3 nuovi", "updates 2": the label, maybe followed by a count. */
    private fun String.isTabLabel(name: String) =
        this == name || (startsWith(name) && length > name.length && this[name.length].let { it == ',' || it == ' ' })

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var steps = 0
        while (current != null && steps++ < 5) {
            if (current.isClickable) return current
            current = current.parent
        }
        return null
    }

    private fun backToChats(chatsTab: AccessibilityNodeInfo?) {
        clear()
        val now = SystemClock.elapsedRealtime()
        bounces.addLast(now)
        while (bounces.isNotEmpty() && now - bounces.first() > LOOP_WINDOW_MS) bounces.removeFirst()
        if (bounces.size > LOOP_MAX) {
            // WhatsApp keeps going back there: rather than fight it, step aside
            prefs.waBlinders = false
            bounces.clear()
            Toast.makeText(service, R.string.ml_wa_blinders_loop_off, Toast.LENGTH_LONG).show()
            return
        }
        val clicked = chatsTab?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        if (!clicked) service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        toast(R.string.ml_wa_blinders_blocked)
        schedule()
    }

    private fun show(found: List<Rect>) {
        // A tab is often found twice, by its description and by its label
        val rects = found.distinct()
        if (rects == coveredRects) return
        clear()
        coveredRects = rects.map { Rect(it) }
        rects.forEach { addCover(it) }
    }

    private fun addCover(rect: Rect) {
        val night = (service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val view = View(service).apply {
            setBackgroundColor(if (night) DARK_BACKGROUND else LIGHT_BACKGROUND)
            setOnClickListener { toast(R.string.ml_wa_blinders_covered) }
            setOnLongClickListener {
                pausedUntil = SystemClock.elapsedRealtime() + PAUSE_MS
                clear()
                Toast.makeText(service, R.string.ml_wa_blinders_paused, Toast.LENGTH_SHORT).show()
                handler.postDelayed({ schedule() }, PAUSE_MS + 100)
                true
            }
        }
        val params = WindowManager.LayoutParams(
            rect.width(), rect.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = rect.left
            y = rect.top
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try {
            windowManager.addView(view, params)
            covers += view
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun clear() {
        covers.forEach { runCatching { windowManager.removeView(it) } }
        covers.clear()
        coveredRects = emptyList()
    }

    private fun toast(message: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastToast < 2500) return
        lastToast = now
        Toast.makeText(service, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        val PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
        private val BLOCKED_TABS = listOf(
            "aggiornamenti", "updates", "community", "communities", "comunità", "stato", "status",
        )
        private val CHAT_TABS = listOf("chat", "chats")
        private const val MAX_NODES = 2500
        private const val THROTTLE_MS = 120L
        private const val PAUSE_MS = 5 * 60 * 1000L
        private const val LOOP_WINDOW_MS = 10_000L
        private const val LOOP_MAX = 5
        private const val DARK_BACKGROUND = 0xFF0B141A.toInt()
        private const val LIGHT_BACKGROUND = 0xFFFFFFFF.toInt()
    }
}
