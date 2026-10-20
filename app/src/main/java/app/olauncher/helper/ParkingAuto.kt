package app.olauncher.helper

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import app.olauncher.R

/**
 * Automatic parking: when the phone disconnects from the car's Bluetooth (chosen among the paired
 * devices) the position is saved by itself, after a drive of at least [MIN_DRIVE_MS]. The fix comes
 * from [ParkingLocationService]; a notification (can be turned off) says it's saved and walks there.
 */
object ParkingAuto {

    /** Shorter connections (a Bluetooth hiccup, getting in and out) don't move the saved spot. */
    const val MIN_DRIVE_MS = 2 * 60_000L

    const val REQUEST_PERMISSIONS = 7202

    private const val CHANNEL_SAVED = "parking_saved"
    const val CHANNEL_WORKING = "parking_working"
    private const val NOTIFICATION_SAVED = 7301
    const val NOTIFICATION_WORKING = 7302

    private fun prefs(context: Context) = context.getSharedPreferences("app.minimalauncher.parking", Context.MODE_PRIVATE)

    /** The car's Bluetooth address, or null: automatic saving off. */
    fun carAddress(context: Context): String? = prefs(context).getString("CAR_ADDRESS", null)

    fun carName(context: Context): String? = prefs(context).getString("CAR_NAME", null)

    fun isOn(context: Context) = carAddress(context) != null

    private fun setCar(context: Context, address: String?, name: String?) = prefs(context).edit {
        putString("CAR_ADDRESS", address)
        putString("CAR_NAME", name)
        remove("CONNECTED_AT")
    }

    var Context.notifySaved: Boolean
        get() = prefs(this).getBoolean("NOTIFY", true)
        set(value) = prefs(this).edit { putBoolean("NOTIFY", value) }

    // ---------------------------------------------------------------- Bluetooth events

    fun onCarConnected(context: Context) = prefs(context).edit { putLong("CONNECTED_AT", System.currentTimeMillis()) }

    fun onCarDisconnected(context: Context) {
        val since = prefs(context).getLong("CONNECTED_AT", 0L)
        prefs(context).edit { remove("CONNECTED_AT") }
        // Unknown start (e.g. the app was updated while driving): counts as a drive
        if (since != 0L && System.currentTimeMillis() - since < MIN_DRIVE_MS) return
        if (!hasLocation(context)) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, ParkingLocationService::class.java))
        } catch (e: Exception) {
            // Not allowed to start now: the latest fix of the drive is still better than nothing
            e.printStackTrace()
            ParkingLocationService.lastKnown(context)?.let { saved(context, it) }
        }
    }

    /** Stores the fix and, when wanted, says so with a "take me there" notification. */
    fun saved(context: Context, location: Location) {
        ParkingShortcut.store(context, location)
        if (context.notifySaved) notifySaved(context)
    }

    // ---------------------------------------------------------------- permissions

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasBluetooth(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || granted(context, Manifest.permission.BLUETOOTH_CONNECT)

    private fun hasLocation(context: Context) = granted(context, Manifest.permission.ACCESS_FINE_LOCATION) &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION))

    private fun hasNotifications(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(context, Manifest.permission.POST_NOTIFICATIONS)

    /** True when everything automatic saving needs is granted. */
    fun isReady(context: Context) = hasBluetooth(context) && hasLocation(context)

    /**
     * Asks for the next missing permission (Bluetooth, precise location, location "all the time",
     * notifications); called again from the permission result, so it walks through them in turn.
     */
    fun continueSetup(activity: Activity) {
        if (!isOn(activity)) return
        when {
            !hasBluetooth(activity) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_PERMISSIONS)

            !granted(activity, Manifest.permission.ACCESS_FINE_LOCATION) ->
                ActivityCompat.requestPermissions(
                    activity, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_PERMISSIONS
                )

            !hasLocation(activity) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                // Android 11+ only grants it from the app's settings page: say what to choose first
                activity.showConfirmDialog(
                    title = activity.getString(R.string.ml_parking_auto),
                    message = activity.getString(R.string.ml_parking_bg_location),
                    action = activity.getString(R.string.ml_parking_grant),
                ) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) openAppSettings(activity)
                    else ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQUEST_PERMISSIONS)
                }

            activity.notifySaved && !hasNotifications(activity) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_PERMISSIONS)
        }
    }

    private fun openAppSettings(context: Context) = try {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    } catch (e: Exception) {
        e.printStackTrace()
    }

    // ---------------------------------------------------------------- menu

    /** The car (choose among the paired devices), the notification switch, missing permissions. */
    fun menuEntries(activity: Activity): List<MenuEntry> {
        val entries = mutableListOf(
            MenuEntry(
                activity.getString(R.string.ml_parking_auto),
                value = carName(activity) ?: activity.getString(R.string.ml_parking_auto_off),
                section = activity.getString(R.string.ml_parking_auto_section),
            ) { pickCar(activity) }
        )
        if (isOn(activity)) {
            if (!isReady(activity)) entries += MenuEntry(activity.getString(R.string.ml_parking_permissions_missing), destructive = true) {
                continueSetup(activity)
            }
            entries += MenuEntry(activity.getString(R.string.ml_parking_notify), checked = activity.notifySaved, onToggle = {
                activity.notifySaved = it
                if (it) continueSetup(activity)
                true
            })
        }
        return entries
    }

    @SuppressLint("MissingPermission")
    private fun pickCar(activity: Activity) {
        if (!hasBluetooth(activity) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activity.showToast(activity.getString(R.string.ml_parking_bt_permission))
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_PICK_CAR)
            return
        }
        val devices = try {
            (activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bondedDevices.orEmpty()
        } catch (e: Exception) {
            e.printStackTrace()
            emptySet()
        }
        val current = carAddress(activity)
        val entries = devices.map { it.address to (it.name ?: it.address) }.sortedBy { it.second.lowercase() }.map { (address, name) ->
            MenuEntry(name, value = if (address == current) "✓" else null) {
                setCar(activity, address, name)
                activity.showToast(activity.getString(R.string.ml_parking_auto_set, name))
                continueSetup(activity)
            }
        } + MenuEntry(activity.getString(R.string.ml_parking_auto_off), section = "") { setCar(activity, null, null) }
        if (devices.isEmpty()) activity.showToast(activity.getString(R.string.ml_parking_no_devices))
        activity.showMenuDialog(activity.getString(R.string.ml_parking_pick_car), entries)
    }

    const val REQUEST_PICK_CAR = 7203

    /** From the activity's permission result. */
    fun onPermissionResult(activity: Activity, requestCode: Int) {
        when (requestCode) {
            REQUEST_PICK_CAR -> if (hasBluetooth(activity)) pickCar(activity)
            REQUEST_PERMISSIONS -> continueSetup(activity)
        }
    }

    // ---------------------------------------------------------------- notifications

    fun channels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SAVED, context.getString(R.string.ml_parking_channel_saved), NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WORKING, context.getString(R.string.ml_parking_channel_working), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notifySaved(context: Context) {
        if (!hasNotifications(context)) return
        channels(context)
        val walk = ParkingShortcut.navigationIntent(context) ?: return
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(context, NOTIFICATION_SAVED, walk.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
        val notification = NotificationCompat.Builder(context, CHANNEL_SAVED)
            .setSmallIcon(R.drawable.ic_m_parking)
            .setContentTitle(context.getString(R.string.ml_parking_auto_saved))
            .setContentText(ParkingShortcut.describe(context))
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.ml_parking_take_me), open)
            .setAutoCancel(true)
            .build()
        try {
            context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_SAVED, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun workingNotification(context: Context): Notification {
        channels(context)
        return NotificationCompat.Builder(context, CHANNEL_WORKING)
            .setSmallIcon(R.drawable.ic_m_parking)
            .setContentTitle(context.getString(R.string.ml_parking_auto_saving))
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}

/** The car connecting and disconnecting (Bluetooth ACL events are allowed in the manifest). */
class ParkingBluetoothReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val car = ParkingAuto.carAddress(context) ?: return
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        val address = try {
            device?.address
        } catch (e: SecurityException) {
            null
        } ?: return
        if (!address.equals(car, ignoreCase = true)) return
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> ParkingAuto.onCarConnected(context)
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> ParkingAuto.onCarDisconnected(context)
        }
    }
}

/**
 * A short foreground service right after the car disconnects: a fresh fix for up to [TIMEOUT_MS]
 * (taken at once under [GOOD_ACCURACY_M]), else the best one, else the latest fix of the drive.
 */
class ParkingLocationService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var manager: LocationManager? = null
    private var best: Location? = null
    private var done = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = offer(location)

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val timeout = Runnable { finish() }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start must go foreground, even when already looking
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            ServiceCompat.startForeground(this, ParkingAuto.NOTIFICATION_WORKING, ParkingAuto.workingNotification(this), type)
        } catch (e: Exception) {
            e.printStackTrace()
            if (manager == null) {
                lastKnown(this)?.let { ParkingAuto.saved(this, it) }
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (manager != null) return START_NOT_STICKY
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        manager = lm
        val providers = mutableListOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) providers += LocationManager.FUSED_PROVIDER
        var started = 0
        providers.forEach { provider ->
            try {
                if (!lm.isProviderEnabled(provider)) return@forEach
                lm.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
                started++
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (started == 0) finish() else handler.postDelayed(timeout, TIMEOUT_MS)
        return START_NOT_STICKY
    }

    private fun offer(location: Location) {
        if (!location.hasAccuracy()) return
        val current = best
        if (current == null || location.accuracy <= current.accuracy) best = location
        if (location.accuracy <= GOOD_ACCURACY_M) finish()
    }

    private fun finish() {
        if (done) return
        done = true
        handler.removeCallbacks(timeout)
        try {
            manager?.removeUpdates(listener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // A poor fresh fix (e.g. in a garage) loses to a better one from the last minutes of the drive
        val fresh = best
        val recent = lastKnown(this)
        val location = when {
            fresh == null -> recent
            recent != null && fresh.accuracy > POOR_ACCURACY_M && recent.accuracy < fresh.accuracy -> recent
            else -> fresh
        }
        location?.let { ParkingAuto.saved(this, it) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        try {
            manager?.removeUpdates(listener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onDestroy()
    }

    companion object {
        private const val TIMEOUT_MS = 30_000L
        private const val GOOD_ACCURACY_M = 10f
        private const val POOR_ACCURACY_M = 40f
        private const val RECENT_MS = 10 * 60_000L

        /** The most precise fix of the last minutes (the drive), from any provider. */
        @SuppressLint("MissingPermission")
        fun lastKnown(context: Context): Location? {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = mutableListOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) providers += LocationManager.FUSED_PROVIDER
            return providers.mapNotNull { provider ->
                try {
                    lm.getLastKnownLocation(provider)
                } catch (e: Exception) {
                    null
                }
            }.filter { it.hasAccuracy() && System.currentTimeMillis() - it.time < RECENT_MS }.minByOrNull { it.accuracy }
        }
    }
}
