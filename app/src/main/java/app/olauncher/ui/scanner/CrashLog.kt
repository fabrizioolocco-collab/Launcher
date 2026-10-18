package app.olauncher.ui.scanner

import android.content.Context
import android.os.Build
import android.util.Log
import app.olauncher.BuildConfig
import java.io.File
import java.util.Date

/**
 * Keeps the last crash of the app (once the scanner was opened) in a file, so the next time the
 * scanner opens it can show the error and copy it, to be sent.
 */
object CrashLog {

    private fun file(context: Context) = File(context.applicationContext.filesDir, "scanner-crash.txt")

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(file(context), previous))
    }

    fun save(context: Context, e: Throwable) = write(file(context), e)

    /** The saved error, removed once read; null when there's none. */
    fun take(context: Context): String? {
        val f = file(context)
        if (!f.exists()) return null
        return try {
            f.readText().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        } finally {
            f.delete()
        }
    }

    private fun write(f: File, e: Throwable) {
        try {
            f.writeText("MinimaLauncher ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}\n${Date()}\n\n${Log.getStackTraceString(e)}")
        } catch (_: Throwable) {
        }
    }

    private class Handler(private val f: File, private val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            write(f, e)
            previous?.uncaughtException(t, e)
        }
    }
}
