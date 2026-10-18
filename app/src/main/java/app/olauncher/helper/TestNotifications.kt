package app.olauncher.helper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import app.olauncher.R
import app.olauncher.data.AppModel
import kotlinx.coroutines.delay

/**
 * Test tool: posts notifications that the launcher attributes to a fake "Test app" (shown in the
 * news strip), to check badges and counters without waiting for real notifications.
 * Android only lets an app post notifications as itself, so the fake app lives inside the launcher.
 *
 * A second tool ([sendMany]) fills the news strip: ten fake apps, each with a random icon from the
 * icon pack (or from an installed app without a pack), get one notification each. They are not
 * placed on home either, so they show up only in the news strip.
 */
object TestNotifications {

    const val FAKE_PACKAGE = "app.minimalauncher.testapp"
    const val CHANNEL_ID = "ml_test"
    private const val GROUP = "ml_test_group"
    private const val SUMMARY_ID = 9_000
    private const val FIRST_ID = 9_001
    private const val PREFS_FILE = "app.minimalauncher.test"
    private const val KEY_ENABLED = "ENABLED"
    private const val KEY_COUNTER = "COUNTER"

    private const val MANY_CHANNEL_ID = "ml_test_many"
    private const val MANY_PREFIX = "$FAKE_PACKAGE.fake"
    private const val MANY_COUNT = 10
    private const val MANY_FIRST_ID = 9_100
    private const val EXTRA_FAKE_PACKAGE = "app.minimalauncher.FAKE_PACKAGE"
    private const val KEY_MANY_ENABLED = "MANY_ENABLED"
    private const val KEY_MANY_LABEL = "MANY_LABEL_"
    private const val KEY_MANY_ICON = "MANY_ICON_"

    /** Icon spec of a fake app taken from an installed app (when there is no icon pack). */
    const val APP_ICON_PREFIX = "app:"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, false)

    fun fakeApp(context: Context) = AppModel.App(
        appLabel = context.getString(R.string.ml_test_app_name),
        key = null,
        appPackage = FAKE_PACKAGE,
        activityClassName = null,
        isNew = false,
        user = Process.myUserHandle(),
    )

    /** Posts one more test notification. Returns false if notifications are blocked for the launcher. */
    fun send(context: Context): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        ensureChannel(context)

        val n = prefs(context).getInt(KEY_COUNTER, 0) + 1
        prefs(context).edit {
            putInt(KEY_COUNTER, n)
            putBoolean(KEY_ENABLED, true)
        }
        val title = context.getString(R.string.ml_test_app_name)
        try {
            manager.notify(
                FIRST_ID + n, NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification_test)
                    .setContentTitle(title)
                    .setContentText(context.getString(R.string.ml_test_notification_text, n))
                    .setGroup(GROUP)
                    .setAutoCancel(true)
                    .build()
            )
            manager.notify(
                SUMMARY_ID, NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification_test)
                    .setContentTitle(title)
                    .setGroup(GROUP)
                    .setGroupSummary(true)
                    .setAutoCancel(true)
                    .build()
            )
        } catch (e: SecurityException) {
            return false
        }
        return true
    }

    /** The single test app and the fake apps of [sendMany]. */
    fun isFake(packageName: String) = packageName == FAKE_PACKAGE || packageName.startsWith(MANY_PREFIX)

    private fun manyPackage(i: Int) = "$MANY_PREFIX$i"

    private fun manyIndex(packageName: String) = packageName.removePrefix(MANY_PREFIX).toIntOrNull()

    /** The fake apps of [sendMany], while that test is on. */
    fun fakeApps(context: Context): List<AppModel.App> {
        val p = prefs(context)
        if (!p.getBoolean(KEY_MANY_ENABLED, false)) return emptyList()
        return (1..MANY_COUNT).map { i ->
            AppModel.App(
                appLabel = p.getString(KEY_MANY_LABEL + i, null) ?: context.getString(R.string.ml_test_fake_app_name, i),
                key = null,
                appPackage = manyPackage(i),
                activityClassName = null,
                isNew = false,
                user = Process.myUserHandle(),
            )
        }
    }

    /** Icon of a fake app: "pack:<pack>:<drawable>" (see [CustomIcons]) or [APP_ICON_PREFIX] + package. */
    fun iconSpec(context: Context, packageName: String): String? {
        val i = manyIndex(packageName) ?: return null
        return prefs(context).getString(KEY_MANY_ICON + i, null)
    }

    /** Package the launcher attributes a notification of its own to, or null if it isn't a test one. */
    fun ownerOf(notification: Notification): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return when (notification.channelId) {
            CHANNEL_ID -> FAKE_PACKAGE
            // Summaries the system adds when grouping have no fake package: ignored
            MANY_CHANNEL_ID -> notification.extras?.getString(EXTRA_FAKE_PACKAGE)
            else -> null
        }
    }

    /**
     * Ten fake apps with random icons from [iconPack] (or from installed apps when there is none)
     * each get a notification, a fraction of a second apart like real ones. Blocking: call it off
     * the main thread. [onAppsReady] runs once the fake apps exist, before the notifications arrive. Returns false if notifications are blocked for the launcher.
     */
    suspend fun sendMany(context: Context, iconPack: String, onAppsReady: suspend () -> Unit): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        ensureManyChannel(context)
        clearMany(context)

        val icons = randomIcons(context, iconPack)
        prefs(context).edit {
            putBoolean(KEY_MANY_ENABLED, true)
            for (i in 1..MANY_COUNT) {
                val (label, spec) = icons.getOrNull(i - 1) ?: (null to null)
                putString(KEY_MANY_LABEL + i, label ?: context.getString(R.string.ml_test_fake_app_name, i))
                putString(KEY_MANY_ICON + i, spec)
            }
        }
        // New random icons under the same fake packages
        IconPackManager.clearCache()
        onAppsReady()

        val iconPx = 128
        fakeApps(context).forEachIndexed { index, app ->
            val i = index + 1
            val largeIcon = IconPackManager.getIcon(context, app.appPackage, null, app.user, iconPack, iconPx)
            try {
                manager.notify(
                    MANY_FIRST_ID + i, NotificationCompat.Builder(context, MANY_CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_notification_test)
                        .setLargeIcon(largeIcon)
                        .setContentTitle(app.appLabel)
                        .setContentText(context.getString(R.string.ml_test_notification_text, i))
                        // A group per fake app, so the system doesn't bundle them as one app
                        .setGroup("$GROUP.$i")
                        .addExtras(android.os.Bundle().apply { putString(EXTRA_FAKE_PACKAGE, app.appPackage) })
                        .setAutoCancel(true)
                        .build()
                )
            } catch (e: SecurityException) {
                return false
            }
            delay(300)
        }
        return true
    }

    /** Up to ten (label, icon spec) pairs picked at random. */
    private fun randomIcons(context: Context, iconPack: String): List<Pair<String, String>> {
        if (iconPack.isNotBlank()) {
            val names = IconPackManager.packIconNames(context, iconPack)
            if (names.isNotEmpty()) return names.shuffled().take(MANY_COUNT).map { name ->
                prettyName(name) to "${CustomIcons.PACK_PREFIX}$iconPack:$name"
            }
        }
        val pm = context.packageManager
        val launchable = try {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        } catch (e: Exception) {
            emptyList()
        }
        return launchable.filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .shuffled().take(MANY_COUNT)
            .map { it.loadLabel(pm).toString() to APP_ICON_PREFIX + it.activityInfo.packageName }
    }

    /** "com_whatsapp_messenger" -> "Whatsapp messenger": a plausible app name from a drawable name. */
    private fun prettyName(drawable: String): String {
        val words = drawable.split('_', '-', '.').filter { it.isNotBlank() && it !in setOf("com", "org", "net", "app", "android", "ic") }
        return words.joinToString(" ").replaceFirstChar { it.uppercase() }.ifBlank { drawable }
    }

    /** Removes the notifications of the fake apps of [sendMany] and the apps themselves. */
    private fun clearMany(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        for (i in 1..MANY_COUNT) manager.cancel(MANY_FIRST_ID + i)
        prefs(context).edit { putBoolean(KEY_MANY_ENABLED, false) }
    }

    /** Removes the test notifications of [packageName] (like opening the app would). */
    fun clearNotifications(context: Context, packageName: String) {
        val i = manyIndex(packageName)
        if (i != null) NotificationManagerCompat.from(context).cancel(MANY_FIRST_ID + i)
        else clearNotifications(context)
    }

    /** Removes the test notifications (like opening the app would). */
    fun clearNotifications(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        val last = prefs(context).getInt(KEY_COUNTER, 0)
        for (i in 1..last) manager.cancel(FIRST_ID + i)
        manager.cancel(SUMMARY_ID)
        prefs(context).edit { putInt(KEY_COUNTER, 0) }
    }

    /** Clears notifications and removes the fake apps. */
    fun disable(context: Context) {
        clearNotifications(context)
        clearMany(context)
        prefs(context).edit { putBoolean(KEY_ENABLED, false) }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.ml_test_channel), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { setShowBadge(true) }
        )
    }

    private fun ensureManyChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(MANY_CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(MANY_CHANNEL_ID, context.getString(R.string.ml_test_many_channel), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(true) }
        )
    }
}
