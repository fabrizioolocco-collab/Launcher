package app.olauncher.listener

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import app.olauncher.R
import app.olauncher.helper.DetoxManager

class DeviceAdmin : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence? =
        if (DetoxManager.isActive(context)) context.getString(R.string.ml_detox_admin_disable_warning) else null

    fun onEnabled(intent: Intent?, context: Context) {
        super.onEnabled(context, intent!!)
        Toast.makeText(context, context.getString(R.string.enabled), Toast.LENGTH_SHORT).show()
    }

    fun onDisabled(intent: Intent?, context: Context) {
        super.onDisabled(context, intent!!)
        Toast.makeText(context, context.getString(R.string.enabled), Toast.LENGTH_SHORT).show()
    }
}