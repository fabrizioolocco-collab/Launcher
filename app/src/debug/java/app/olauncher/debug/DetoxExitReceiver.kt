package app.olauncher.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import app.olauncher.helper.DetoxManager

/**
 * Debug builds only. Ends any detox session, strict included:
 *
 *   adb shell am broadcast -n app.minimalauncher.debug/app.olauncher.debug.DetoxExitReceiver
 */
class DetoxExitReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DetoxManager.end(context)
        Toast.makeText(context, "Detox ended (debug)", Toast.LENGTH_SHORT).show()
    }
}
