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
        scheduleRecompute()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
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

    private fun publishCounts() {
        val active = try {
            activeNotifications
        } catch (e: Exception) {
            null
        } ?: return
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
            val packageName = if (sbn.packageName == BuildConfig.APPLICATION_ID) {
                if (!TestNotifications.isTestNotification(n)) continue
                TestNotifications.FAKE_PACKAGE
            } else sbn.packageName
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
}
