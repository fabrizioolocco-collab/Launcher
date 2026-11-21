package app.olauncher.ui.recorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.helper.showToast

/**
 * A recording started and stopped from the home screen, without opening the recorder: a double tap
 * on its icon starts one in the recorder's main folder, another double tap ends it. While it goes
 * on the icon is green.
 */
object QuickRecord {

    const val GREEN = 0xFF2ECC71.toInt()
    private const val ALIAS = "app.olauncher.RecorderAlias"

    fun isRecorder(app: AppModel.App) = app.activityClassName == ALIAS

    val isRecording get() = RecorderService.state.value != null

    /**
     * Stops the recording going on, or starts one. False when one can't be started from here (the
     * microphone or the files were never allowed): the recorder itself must be opened, which asks.
     */
    fun toggle(ctx: Context): Boolean {
        if (isRecording) {
            RecorderService.send(ctx, RecorderService.ACTION_STOP)
            ctx.showToast(ctx.getString(R.string.ml_rec_quick_stopped))
            return true
        }
        val files = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        val microphone = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!files || !microphone) return false
        return try {
            val dir = RecorderStore.root(ctx)
            if (!dir.isDirectory && !dir.mkdirs()) throw IllegalStateException("No folder")
            RecorderService.start(ctx, RecorderStore.newRecording(dir))
            ctx.showToast(ctx.getString(R.string.ml_rec_quick_started))
            true
        } catch (e: Exception) {
            e.printStackTrace()
            ctx.showToast(ctx.getString(R.string.ml_rec_failed))
            true
        }
    }
}
