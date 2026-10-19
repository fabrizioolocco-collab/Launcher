package app.olauncher.helper

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.olauncher.R
import app.olauncher.data.ALERT_ALARM
import app.olauncher.data.ALERT_NONE
import app.olauncher.data.NO_TIME
import app.olauncher.data.Todo
import app.olauncher.data.TodoStore
import app.olauncher.ui.todo.TodoActivity
import app.olauncher.ui.todo.TodoAlarmActivity
import java.util.Date

/**
 * Alerts of the todos: a notification, or an alarm (a full screen page with "Done" / "Snooze",
 * ringing with [AlarmRinger]: vibration first, then a sound growing louder). Only the next alert
 * is scheduled with the AlarmManager; when it goes off every alert that is due is given and the
 * next one is scheduled.
 */
object TodoAlarms {
    private const val CHANNEL_NOTIFY = "todo_notify"
    /** Silent: [AlarmRinger] vibrates and rings. */
    private const val CHANNEL_ALARM = "todo_alarm_v2"
    private const val OLD_CHANNEL_ALARM = "todo_alarm_v1"
    /** An alert missed by more than this (phone off) is not given any more. */
    private const val MAX_LATE = 30 * 60_000L

    const val ACTION_FIRE = "app.minimalauncher.todo.FIRE"
    const val ACTION_DONE = "app.minimalauncher.todo.DONE"
    const val EXTRA_ID = "todo_id"

    /** Gives the alerts that are due, then schedules the next one. */
    fun schedule(context: Context) {
        val ctx = context.applicationContext
        fireDue(ctx)
        val now = System.currentTimeMillis()
        val next = TodoStore.todos(ctx).filter { it.alert != ALERT_NONE }.map { it to it.alertTime() }
            .filter { (t, at) -> at > now && at != t.notified }.minByOrNull { it.second }
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, TodoAlarmReceiver::class.java).setAction(ACTION_FIRE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        if (next == null) {
            am.cancel(pi)
            return
        }
        val (todo, at) = next
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        try {
            when {
                exact && todo.alert == ALERT_ALARM -> {
                    val show = PendingIntent.getActivity(
                        ctx, 1, Intent(ctx, TodoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
                }
                exact -> am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                else -> am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** Alerts whose time has come and that weren't given yet. */
    private fun fireDue(ctx: Context) {
        val now = System.currentTimeMillis()
        val given = mutableMapOf<Long, Long>()
        TodoStore.todos(ctx).forEach { todo ->
            val at = todo.alertTime()
            if (at <= 0 || at > now + 5_000 || at == todo.notified) return@forEach
            given[todo.id] = at
            if (now - at <= MAX_LATE) show(ctx, todo)
        }
        if (given.isNotEmpty()) TodoStore.markNotified(ctx, given)
    }

    private fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun channels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_NOTIFY, ctx.getString(R.string.ml_todo_channel_notify), NotificationManager.IMPORTANCE_HIGH)
        )
        nm.deleteNotificationChannel(OLD_CHANNEL_ALARM)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALARM, ctx.getString(R.string.ml_todo_channel_alarm), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
                setBypassDnd(true)
            }
        )
    }

    fun notificationId(id: Long) = (id % Int.MAX_VALUE).toInt() + 1000

    private fun show(ctx: Context, todo: Todo) {
        if (!canNotify(ctx)) return
        channels(ctx)
        val alarm = todo.alert == ALERT_ALARM
        val category = TodoStore.category(ctx, todo.category)?.name
        val time = if (todo.time != NO_TIME) android.text.format.DateFormat.getTimeFormat(ctx).format(Date(todo.alertTime())) else null
        val text = listOfNotNull(time, category).joinToString("  ·  ")
        val open = PendingIntent.getActivity(
            ctx, notificationId(todo.id), Intent(ctx, TodoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(ctx, if (alarm) CHANNEL_ALARM else CHANNEL_NOTIFY)
            .setSmallIcon(R.drawable.ic_todo_notification)
            .setContentTitle(todo.title)
            .setContentText(text.ifBlank { ctx.getString(R.string.ml_todo_app) })
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (alarm) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .addAction(0, ctx.getString(R.string.ml_todo_done), action(ctx, ACTION_DONE, todo.id))
        if (alarm) {
            // Postponing opens the alarm page on its choices (5 min ... tomorrow, custom)
            val postpone = PendingIntent.getActivity(
                ctx, notificationId(todo.id) * 4 + 2,
                Intent(ctx, TodoAlarmActivity::class.java).putExtra(EXTRA_ID, todo.id).putExtra(TodoAlarmActivity.EXTRA_POSTPONE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(0, ctx.getString(R.string.ml_todo_postpone), postpone)
            val page = PendingIntent.getActivity(
                ctx, notificationId(todo.id),
                Intent(ctx, TodoAlarmActivity::class.java).putExtra(EXTRA_ID, todo.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.setFullScreenIntent(page, true).setOngoing(true)
        }
        if (alarm) builder.setSilent(true)
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(notificationId(todo.id), builder.build())
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
        if (alarm) {
            AlarmRinger.start(ctx, todo.id)
            openAlarmPage(ctx, todo.id)
        }
    }

    /**
     * The full screen intent only takes the screen while the phone is locked: with the phone in
     * use (e.g. on the detox page) it is a plain notification, so the page is opened right away
     * (allowed while the launcher is home or its accessibility service runs).
     */
    private fun openAlarmPage(ctx: Context, id: Long) {
        try {
            ctx.startActivity(
                Intent(ctx, TodoAlarmActivity::class.java).putExtra(EXTRA_ID, id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            )
        } catch (e: Exception) {
            // Not allowed from the background: the notification's full screen intent stays
            e.printStackTrace()
        }
    }

    private fun action(ctx: Context, action: String, id: Long): PendingIntent = PendingIntent.getBroadcast(
        ctx, notificationId(id) * 4 + 1,
        Intent(ctx, TodoAlarmReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun dismiss(ctx: Context, id: Long) {
        AlarmRinger.stop(id)
        ctx.getSystemService(NotificationManager::class.java).cancel(notificationId(id))
    }
}

/** The scheduled alert, the notification buttons and the events that wipe the alarms (reboot, clock change). */
class TodoAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(TodoAlarms.EXTRA_ID, -1L)
        when (intent.action) {
            TodoAlarms.ACTION_DONE -> {
                TodoAlarms.dismiss(context, id)
                TodoStore.complete(context, id)
            }

            else -> TodoAlarms.schedule(context)
        }
    }
}
