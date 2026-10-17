package app.olauncher.helper

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import app.olauncher.data.CalendarWidgetConfig
import java.util.Calendar
import java.util.TimeZone

/** Reads the phone's calendars and events (Calendar Provider) and opens them in the calendar app. */
object CalendarEvents {

    class CalendarInfo(val id: Long, val name: String, val account: String, val color: Int, val visible: Boolean)

    /** One occurrence of an event; [startDay]..[endDay] are the local Julian days it covers. */
    class Event(
        val id: Long,
        val title: String,
        val begin: Long,
        val end: Long,
        val allDay: Boolean,
        val color: Int,
        val startDay: Int,
        val endDay: Int,
    )

    private const val DAY_MS = 86_400_000L
    private const val EPOCH_JULIAN_DAY = 2440588

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Local Julian day of [millis]. */
    fun julianDay(millis: Long): Int {
        val local = millis + TimeZone.getDefault().getOffset(millis)
        return (Math.floorDiv(local, DAY_MS) + EPOCH_JULIAN_DAY).toInt()
    }

    /** Local midnight at the start of Julian day [day]. */
    fun dayStart(day: Int): Long {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = (day - EPOCH_JULIAN_DAY) * DAY_MS }
        return Calendar.getInstance().apply {
            clear()
            set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
        }.timeInMillis
    }

    /** All the calendars on the phone, by account. Blocking. */
    fun calendars(context: Context): List<CalendarInfo> {
        if (!hasPermission(context)) return emptyList()
        val list = mutableListOf<CalendarInfo>()
        try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(
                    CalendarContract.Calendars._ID,
                    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.ACCOUNT_NAME,
                    CalendarContract.Calendars.CALENDAR_COLOR,
                    CalendarContract.Calendars.VISIBLE,
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) list += CalendarInfo(
                    id = c.getLong(0),
                    name = c.getString(1).orEmpty(),
                    account = c.getString(2).orEmpty(),
                    color = c.getInt(3),
                    visible = c.getInt(4) == 1,
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list.sortedWith(compareBy({ it.account.lowercase() }, { it.name.lowercase() }))
    }

    /** Occurrences touching days [firstDay]..[lastDay] from the calendars chosen in [config]. Blocking. */
    fun events(context: Context, firstDay: Int, lastDay: Int, config: CalendarWidgetConfig): List<Event> {
        if (!hasPermission(context)) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_BY_DAY_URI.buildUpon().also {
            ContentUris.appendId(it, firstDay.toLong())
            ContentUris.appendId(it, lastDay.toLong())
        }.build()
        val selection = StringBuilder()
        if (config.calendars.isEmpty()) selection.append("${CalendarContract.Instances.VISIBLE} = 1")
        else selection.append("${CalendarContract.Instances.CALENDAR_ID} IN (${config.calendars.joinToString(",")})")
        selection.append(" AND (${CalendarContract.Instances.STATUS} IS NULL OR ${CalendarContract.Instances.STATUS} != ${CalendarContract.Instances.STATUS_CANCELED})")
        val list = mutableListOf<Event>()
        try {
            context.contentResolver.query(
                uri,
                arrayOf(
                    CalendarContract.Instances.EVENT_ID,
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY,
                    CalendarContract.Instances.DISPLAY_COLOR,
                    CalendarContract.Instances.START_DAY,
                    CalendarContract.Instances.END_DAY,
                    CalendarContract.Instances.SELF_ATTENDEE_STATUS,
                ),
                selection.toString(), null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val allDay = c.getInt(4) == 1
                    if (allDay && !config.showAllDay) continue
                    if (!config.showDeclined && c.getInt(8) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                    val begin = c.getLong(2)
                    val end = c.getLong(3).coerceAtLeast(begin)
                    list += Event(
                        id = c.getLong(0),
                        title = c.getString(1).orEmpty(),
                        begin = begin,
                        end = end,
                        allDay = allDay,
                        color = c.getInt(5),
                        startDay = c.getInt(6),
                        // A timed event ending at midnight doesn't show on the next day
                        endDay = if (allDay) c.getInt(7) else julianDay(if (end > begin) end - 1 else end),
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list.sortedWith(compareBy({ !it.allDay }, { it.begin }, { it.end }))
    }

    private fun start(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            context.showToast(context.getString(app.olauncher.R.string.ml_cal_no_app))
        }
    }

    fun openEvent(context: Context, event: Event) = start(
        context,
        Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.id))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end)
    )

    /** The calendar app on [day]. */
    fun openDay(context: Context, day: Int) {
        val uri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
        ContentUris.appendId(uri, dayStart(day) + 12 * 3_600_000L)
        start(context, Intent(Intent.ACTION_VIEW, uri.build()))
    }

    /** New event on [day]: at the next hour when it's today, at 9:00 otherwise. */
    fun addEvent(context: Context, day: Int) {
        val now = System.currentTimeMillis()
        val begin = if (day == julianDay(now)) Calendar.getInstance().apply {
            add(Calendar.HOUR_OF_DAY, 1)
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis else dayStart(day) + 9 * 3_600_000L
        start(
            context,
            Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + 3_600_000L)
        )
    }
}
