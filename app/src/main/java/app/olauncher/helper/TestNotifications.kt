package app.olauncher.helper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.HomeSection
import app.olauncher.data.HomeSectionsRepository
import app.olauncher.data.SectionApp

/**
 * Test tool: posts notifications that the launcher attributes to a fake "Test app", placed in a
 * "Test" section on home, to check badges and counters without waiting for real notifications.
 * Android only lets an app post notifications as itself, so the fake app lives inside the launcher.
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
    private const val SECTION_ID = "ml-test-section"

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
        ensureTestSection(context)

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

    /** Removes the test notifications (like opening the app would). */
    fun clearNotifications(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        val last = prefs(context).getInt(KEY_COUNTER, 0)
        for (i in 1..last) manager.cancel(FIRST_ID + i)
        manager.cancel(SUMMARY_ID)
        prefs(context).edit { putInt(KEY_COUNTER, 0) }
    }

    /** Clears notifications and removes the fake app and the Test section from home. */
    fun disable(context: Context) {
        clearNotifications(context)
        prefs(context).edit { putBoolean(KEY_ENABLED, false) }
        val repo = HomeSectionsRepository(context)
        val sections = repo.load()
        sections.removeAll { it.id == SECTION_ID }
        sections.forEach { s -> s.apps.removeAll { it.packageName == FAKE_PACKAGE } }
        repo.save(sections)
    }

    fun isTestNotification(notification: Notification): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notification.channelId == CHANNEL_ID

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.ml_test_channel), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { setShowBadge(true) }
        )
    }

    private fun ensureTestSection(context: Context) {
        val repo = HomeSectionsRepository(context)
        val sections = repo.load()
        val app = SectionApp(FAKE_PACKAGE, null, Process.myUserHandle().toString())
        val section = sections.firstOrNull { it.id == SECTION_ID }
            ?: HomeSection(id = SECTION_ID, name = context.getString(R.string.ml_test_section)).also { sections.add(it) }
        if (!section.contains(app)) section.apps.add(app)
        repo.save(sections)
    }
}
