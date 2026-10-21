package app.olauncher.helper

import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

/**
 * Temporary test (Settings > System): can Outlook open one given event? Takes the next event of
 * an Outlook calendar synced to the phone, shows which apps can open it and tries the ways there are.
 */
object OutlookProbe {

    const val OUTLOOK = "com.microsoft.office.outlook"

    class Found(val id: Long, val title: String, val begin: Long, val end: Long, val accountType: String, val syncId: String?)

    private fun isOutlook(accountType: String) =
        accountType.contains("outlook", ignoreCase = true) || accountType.contains("microsoft", ignoreCase = true)

    /** The next event (from a week ago) of a calendar synced by Outlook, or null. Blocking. */
    fun nextOutlookEvent(context: Context): Found? {
        if (!CalendarEvents.hasPermission(context)) return null
        val types = HashMap<Long, String>()
        try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.ACCOUNT_TYPE),
                null, null, null
            )?.use { c -> while (c.moveToNext()) c.getString(1)?.takeIf { isOutlook(it) }?.let { types[c.getLong(0)] = it } }
            if (types.isEmpty()) return null
            val from = System.currentTimeMillis() - 7 * 86_400_000L
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(
                    CalendarContract.Events._ID, CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART,
                    CalendarContract.Events.DTEND, CalendarContract.Events.CALENDAR_ID, CalendarContract.Events._SYNC_ID,
                ),
                "${CalendarContract.Events.CALENDAR_ID} IN (${types.keys.joinToString(",")}) AND ${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DELETED} = 0",
                arrayOf(from.toString()),
                "${CalendarContract.Events.DTSTART} ASC"
            )?.use { c ->
                if (c.moveToFirst()) return Found(
                    id = c.getLong(0),
                    title = c.getString(1).orEmpty(),
                    begin = c.getLong(2),
                    end = if (c.isNull(3)) c.getLong(2) + 3_600_000L else c.getLong(3),
                    accountType = types[c.getLong(4)].orEmpty(),
                    syncId = c.getString(5),
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun viewIntent(found: Found) =
        Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, found.id))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, found.begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, found.end)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** What the phone knows about the event, for the dialog. */
    fun describe(context: Context, found: Found): String {
        val handlers = context.packageManager.queryIntentActivities(viewIntent(found), 0)
            .map { it.activityInfo.packageName }.distinct()
        val when_ = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(found.begin))
        return "${found.title.ifBlank { "-" }}\n$when_\n\nAccount: ${found.accountType}\n" +
                "Outlook id: ${if (found.syncId.isNullOrBlank()) "-" else "sì"}\n" +
                "App che aprono l'evento: ${handlers.joinToString(", ").ifBlank { "nessuna" }}\n" +
                "Outlook tra queste: ${if (OUTLOOK in handlers) "sì" else "no"}"
    }

    /** A: the event, asked to Outlook itself. */
    fun openInOutlook(context: Context, found: Found) = start(context, viewIntent(found).setPackage(OUTLOOK))

    /** B: the event, with the phone's app picker (to see who offers to open it). */
    fun openWithChooser(context: Context, found: Found) =
        start(context, Intent.createChooser(viewIntent(found), found.title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    /** C: Outlook's own link to the day view of its calendar. */
    fun openOutlookDay(context: Context) =
        start(context, Intent(Intent.ACTION_VIEW, Uri.parse("ms-outlook://events/view?action=day")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun start(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "Nessuna app risponde", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Errore: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }
}
