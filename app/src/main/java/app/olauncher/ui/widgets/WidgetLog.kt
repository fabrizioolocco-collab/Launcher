package app.olauncher.ui.widgets

import android.content.Context
import androidx.core.content.edit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small persistent log of the widget add flow, shown in Settings to diagnose failures on a real device. */
object WidgetLog {
    private const val PREFS = "app.minimalauncher.widgetlog"
    private const val KEY = "LOG"
    private const val MAX_LINES = 60

    fun log(context: Context, message: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val lines = (prefs.getString(KEY, "").orEmpty().lines().filter { it.isNotBlank() } + "$time  $message")
            .takeLast(MAX_LINES)
        prefs.edit { putString(KEY, lines.joinToString("\n")) }
    }

    fun read(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
}
