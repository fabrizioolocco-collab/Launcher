package app.olauncher.helper

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import app.olauncher.BuildConfig

/** Notification summary for one app (package + user). */
data class AppNotifications(val count: Int, val lastPostTime: Long)

/**
 * Latest notification counts, keyed by "package|UserHandle.toString()".
 * Filled by [NotificationListener], observed by the UI (same process).
 */
object NotificationRepository {
    private val _state = MutableLiveData<Map<String, AppNotifications>>(emptyMap())
    val state: LiveData<Map<String, AppNotifications>> = _state

    fun key(packageName: String, user: String) = "$packageName|$user"

    fun current(): Map<String, AppNotifications> = _state.value.orEmpty()

    internal fun publish(map: Map<String, AppNotifications>) = _state.postValue(map)

    fun isAccessGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** Opens the system page where notification access is granted (our entry directly, when possible). */
    fun openAccessSettings(context: Context) {
        val component = ComponentName(context, NotificationListener::class.java)
        val direct = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
        val generic = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        for (intent in listOfNotNull(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) direct else null, generic)) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

/**
 * Counts notifications per app the way Android's badge dots do: only channels allowed to show a
 * badge, skipping ongoing/foreground notifications. Messaging notifications (WhatsApp, Telegram,
 * anything using MessagingStyle) count their individual messages instead of 1 per notification.
 */
class NotificationListener : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private val recompute = Runnable { publishCounts() }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        scheduleRecompute()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance === this) instance = null
        NotificationRepository.publish(emptyMap())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            requestRebind(ComponentName(this, NotificationListener::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = scheduleRecompute()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = scheduleRecompute()

    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) = scheduleRecompute()

    private fun scheduleRecompute() {
        // Notifications often arrive in bursts (summary + children): coalesce them
        handler.removeCallbacks(recompute)
        handler.postDelayed(recompute, 250)
    }

    /** Visible notifications of one app (launcher package name, so the fake test app works too). */
    fun activeFor(packageName: String, user: String): List<StatusBarNotification> {
        val all = try {
            activeNotifications
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        val mine = all.filter { ownerOf(it) == packageName && it.user.toString() == user }
        // Summaries only repeat their children: keep them only when alone
        val children = mine.filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
        return (children.ifEmpty { mine }).sortedByDescending { it.postTime }
    }

    fun dismiss(key: String) = try {
        cancelNotification(key)
    } catch (e: Exception) {
        e.printStackTrace()
    }

    /** Re-applies the detox rules now (session started, whitelist changed). */
    fun applyDetox() = handler.post { publishCounts() }

    /** Package a notification belongs to for the launcher (test notifications map to the fake app). */
    private fun ownerOf(sbn: StatusBarNotification): String? {
        val n = sbn.notification ?: return null
        if (sbn.packageName != BuildConfig.APPLICATION_ID) return sbn.packageName
        return if (TestNotifications.isTestNotification(n)) TestNotifications.FAKE_PACKAGE else null
    }

    /**
     * During a detox session, snoozes the notifications of blocked apps until the session ends.
     * Returns the notifications that stay visible.
     */
    private fun snoozeBlocked(active: Array<StatusBarNotification>): List<StatusBarNotification> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !DetoxManager.isActive(this)) return active.toList()
        val remaining = DetoxManager.remainingMs(this)
        val snoozed = snoozedKeys().toMutableSet()
        val visible = mutableListOf<StatusBarNotification>()
        for (sbn in active) {
            val owner = ownerOf(sbn)
            val n = sbn.notification
            val ongoing = n != null && n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0
            if (owner != null && sbn.isClearable && !ongoing && !DetoxManager.isAllowed(this, owner)) {
                try {
                    snoozeNotification(sbn.key, remaining)
                    snoozed += sbn.key
                    continue
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            visible += sbn
        }
        saveSnoozedKeys(snoozed)
        return visible
    }

    /** Notifications we paused that are still snoozed. */
    private fun publishPausedCount() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ours = snoozedKeys()
        val stillSnoozed = try {
            snoozedNotifications?.map { it.key }?.filter { it in ours }.orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
        if (stillSnoozed.size != ours.size) saveSnoozedKeys(stillSnoozed.toSet())
        DetoxManager.publishPaused(if (DetoxManager.isActive(this)) stillSnoozed.size else 0)
    }

    /** End of a session: brings back the notifications we paused (a 1 ms re-snooze reposts them). */
    fun restoreSnoozed() = handler.post {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ours = snoozedKeys()
            try {
                snoozedNotifications?.filter { it.key in ours }?.forEach { snoozeNotification(it.key, 1L) }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        saveSnoozedKeys(emptySet())
        DetoxManager.publishPaused(0)
        scheduleRecompute()
    }

    private fun snoozedKeys(): Set<String> =
        getSharedPreferences(SNOOZE_PREFS, MODE_PRIVATE).getStringSet(SNOOZE_KEY, emptySet()).orEmpty().toSet()

    private fun saveSnoozedKeys(keys: Set<String>) =
        getSharedPreferences(SNOOZE_PREFS, MODE_PRIVATE).edit().putStringSet(SNOOZE_KEY, keys).apply()

    private fun publishCounts() {
        val all = try {
            activeNotifications
        } catch (e: Exception) {
            null
        } ?: return
        val active = snoozeBlocked(all)
        publishPausedCount()
        val ranking = try {
            currentRanking
        } catch (e: Exception) {
            null
        }

        class Acc {
            var childCount = 0
            var hasChildren = false
            var summaryCount = 0
            var last = 0L
        }

        val acc = HashMap<String, Acc>()
        for (sbn in active) {
            val n = sbn.notification ?: continue
            // The launcher's own notifications are ignored, except the test ones (see TestNotifications)
            val packageName = ownerOf(sbn) ?: continue
            if (!sbn.isClearable) continue
            if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0) continue
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && ranking != null) {
                val r = Ranking()
                if (ranking.getRanking(sbn.key, r) && !r.canShowBadge()) continue
            }

            val a = acc.getOrPut(NotificationRepository.key(packageName, sbn.user.toString())) { Acc() }
            a.last = maxOf(a.last, sbn.postTime)
            val isSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0
            if (isSummary) {
                a.summaryCount += maxOf(1, n.number)
            } else {
                a.hasChildren = true
                a.childCount += messagesIn(n)
            }
        }

        NotificationRepository.publish(acc.mapValues { (_, a) ->
            // With children present the summary only repeats them; alone, it stands for itself
            AppNotifications(if (a.hasChildren) a.childCount else a.summaryCount, a.last)
        }.filterValues { it.count > 0 })
    }

    /** Messages carried by one notification: MessagingStyle messages, else Notification.number, else 1. */
    private fun messagesIn(n: Notification): Int {
        val messages = try {
            @Suppress("DEPRECATION")
            n.extras?.getParcelableArray(Notification.EXTRA_MESSAGES)
        } catch (e: Exception) {
            null
        }
        val fromMessages = messages?.size ?: 0
        return when {
            fromMessages > 0 -> fromMessages
            n.number > 0 -> n.number
            else -> 1
        }
    }

    companion object {
        private const val SNOOZE_PREFS = "app.minimalauncher.detox_snoozed"
        private const val SNOOZE_KEY = "KEYS"

        /** The connected listener, used to apply detox changes right away. */
        @Volatile
        var instance: NotificationListener? = null
            private set
    }
}
