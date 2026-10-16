package app.olauncher.helper

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import app.olauncher.BuildConfig

/**
 * Detox sessions: a timer during which only whitelisted apps can be used.
 *
 * - Home, drawer and News show only allowed apps; widgets of other apps are hidden.
 * - The accessibility service sends the user home when a blocked app comes to the front.
 * - Notifications of blocked apps are snoozed until the end and counted as "paused".
 * - Strict sessions: no changes to timer or whitelist, Android Settings blocked (so permissions
 *   can't be revoked), and leaving early requires asking and then waiting [EXIT_WAIT_MS].
 *
 * State is in SharedPreferences (wall-clock times) so it survives restarts and reboots.
 */
object DetoxManager {

    const val EXIT_WAIT_MS = 10 * 60 * 1000L
    private const val PREFS = "app.minimalauncher.detox"
    private const val KEY_WHITELIST = "WHITELIST"
    private const val KEY_END = "END_TIME"
    private const val KEY_STRICT = "STRICT"
    private const val KEY_EXIT_REQUESTED = "EXIT_REQUESTED_AT"
    private const val KEY_LAST_MINUTES = "LAST_MINUTES"

    /** Always usable: calls and emergency calls, system UI, the launcher itself. */
    private val ALWAYS_ALLOWED = setOf(
        BuildConfig.APPLICATION_ID,
        "android",
        "com.android.systemui",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.emergency",
        "com.google.android.apps.safetyhub",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
    )

    /** Blocked during strict sessions even if whitelisted: settings and uninstall screens. */
    private val STRICT_BLOCKED = setOf(
        "com.android.settings",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
    )

    private val _state = MutableLiveData(Unit)

    /** Emits whenever the session or the whitelist changes. */
    val state: LiveData<Unit> = _state

    private val _paused = MutableLiveData(0)

    /** Number of notifications currently paused by the session. */
    val pausedCount: LiveData<Int> = _paused

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun changed() = _state.postValue(Unit)

    internal fun publishPaused(count: Int) = _paused.postValue(count)

    // ---------------------------------------------------------------- whitelist

    fun whitelist(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_WHITELIST, emptySet()).orEmpty().toSet()

    /** Returns false when the change isn't allowed (strict session running). */
    fun setWhitelist(context: Context, packages: Set<String>): Boolean {
        if (isActive(context) && isStrict(context)) return false
        prefs(context).edit { putStringSet(KEY_WHITELIST, packages) }
        changed()
        NotificationListener.instance?.applyDetox()
        return true
    }

    // ---------------------------------------------------------------- session

    fun endTime(context: Context) = prefs(context).getLong(KEY_END, 0L)

    fun isActive(context: Context) = endTime(context) > System.currentTimeMillis()

    fun isStrict(context: Context) = prefs(context).getBoolean(KEY_STRICT, false)

    fun remainingMs(context: Context) = (endTime(context) - System.currentTimeMillis()).coerceAtLeast(0)

    fun lastMinutes(context: Context) = prefs(context).getInt(KEY_LAST_MINUTES, 30)

    fun start(context: Context, minutes: Int, strict: Boolean) {
        prefs(context).edit {
            putLong(KEY_END, System.currentTimeMillis() + minutes * 60_000L)
            putBoolean(KEY_STRICT, strict)
            putLong(KEY_EXIT_REQUESTED, 0L)
            putInt(KEY_LAST_MINUTES, minutes)
        }
        changed()
        NotificationListener.instance?.applyDetox()
    }

    /** Ends the session now and brings paused notifications back. */
    fun end(context: Context) {
        prefs(context).edit {
            putLong(KEY_END, 0L)
            putLong(KEY_EXIT_REQUESTED, 0L)
        }
        changed()
        NotificationListener.instance?.restoreSnoozed()
    }

    /** Called periodically by the UI: closes a session whose time is over. */
    fun checkExpired(context: Context) {
        val end = endTime(context)
        if (end != 0L && end <= System.currentTimeMillis()) end(context)
    }

    fun exitRequestedAt(context: Context) = prefs(context).getLong(KEY_EXIT_REQUESTED, 0L)

    /** Strict sessions: time left before an early exit is allowed, or null if not requested yet. */
    fun exitWaitLeftMs(context: Context): Long? {
        val requested = exitRequestedAt(context)
        if (requested == 0L) return null
        return (requested + EXIT_WAIT_MS - System.currentTimeMillis()).coerceAtLeast(0)
    }

    fun requestExit(context: Context) {
        if (exitRequestedAt(context) == 0L) {
            prefs(context).edit { putLong(KEY_EXIT_REQUESTED, System.currentTimeMillis()) }
            changed()
        }
    }

    fun cancelExitRequest(context: Context) {
        prefs(context).edit { putLong(KEY_EXIT_REQUESTED, 0L) }
        changed()
    }

    // ---------------------------------------------------------------- what is allowed

    /** Can [packageName] be used right now? Always true outside a session. */
    fun isAllowed(context: Context, packageName: String): Boolean {
        if (!isActive(context)) return true
        if (packageName.isBlank()) return true
        // Folder shortcuts on home are not apps
        if (FolderShortcuts.isFolder(packageName) || ParkingShortcut.isParking(packageName)) return true
        if (isStrict(context) && packageName in STRICT_BLOCKED) return false
        if (packageName in ALWAYS_ALLOWED) return true
        if (packageName == TestNotifications.FAKE_PACKAGE) return packageName in whitelist(context)
        if (packageName == defaultDialer(context)) return true
        if (packageName in inputMethods(context)) return true
        if (!isStrict(context) && packageName == "com.android.settings") return true
        return packageName in whitelist(context)
    }

    private fun defaultDialer(context: Context): String? = try {
        (context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).defaultDialerPackage
    } catch (e: Exception) {
        null
    }

    private var imeCache: Pair<Long, Set<String>>? = null

    /** Keyboards must keep working inside allowed apps. Cached for a minute. */
    private fun inputMethods(context: Context): Set<String> {
        val now = SystemClock.elapsedRealtime()
        imeCache?.let { if (now - it.first < 60_000) return it.second }
        val set = try {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .enabledInputMethodList.map { it.packageName }.toSet() +
                    listOfNotNull(Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                        ?.substringBefore("/"))
        } catch (e: Exception) {
            emptySet()
        }
        imeCache = now to set
        return set
    }

    fun formatDuration(ms: Long): String {
        val totalSec = (ms + 999) / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}
