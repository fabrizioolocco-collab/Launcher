package app.olauncher.helper

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.drawable.toBitmap
import app.olauncher.R
import app.olauncher.data.AppModel
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * The parking button on home: a pseudo-app (package [PACKAGE]) that sits on the grid like any
 * icon. A tap waits for a precise GPS fix and saves the position; a long press opens the saved
 * position in Google Maps (the item menu when nothing is saved yet).
 */
object ParkingShortcut {

    const val PACKAGE = "minima:parking"

    /** A fix at least this precise is taken right away (metres); otherwise the best one at the timeout. */
    private const val GOOD_ACCURACY_M = 8f
    private const val TIMEOUT_MS = 90_000L
    /** A last known fix older than this is not shown as a starting point. */
    private const val LAST_KNOWN_MAX_AGE_MS = 2 * 60_000L

    class Position(val latitude: Double, val longitude: Double, val accuracy: Float, val time: Long)

    private fun prefs(context: Context) = context.getSharedPreferences("app.minimalauncher.parking", Context.MODE_PRIVATE)

    fun isParking(packageName: String?) = packageName == PACKAGE

    /** True once the button was added to home: the pseudo-app then exists in the home app list. */
    fun isEnabled(context: Context) = prefs(context).getBoolean("ENABLED", false)

    fun setEnabled(context: Context, enabled: Boolean) = prefs(context).edit { putBoolean("ENABLED", enabled) }

    fun app(context: Context) = AppModel.App(
        appLabel = app.olauncher.data.Prefs(context).getAppRenameLabel(PACKAGE).ifBlank { context.getString(R.string.ml_parking) },
        key = null,
        appPackage = PACKAGE,
        activityClassName = null,
        user = Process.myUserHandle(),
    )

    fun saved(context: Context): Position? {
        val p = prefs(context)
        if (!p.contains("LAT")) return null
        return Position(
            java.lang.Double.longBitsToDouble(p.getLong("LAT", 0)),
            java.lang.Double.longBitsToDouble(p.getLong("LON", 0)),
            p.getFloat("ACC", 0f),
            p.getLong("TIME", 0),
        )
    }

    private fun store(context: Context, location: Location) = prefs(context).edit {
        putLong("LAT", java.lang.Double.doubleToRawLongBits(location.latitude))
        putLong("LON", java.lang.Double.doubleToRawLongBits(location.longitude))
        putFloat("ACC", location.accuracy)
        putLong("TIME", System.currentTimeMillis())
    }

    /** "Saved at 12:31 (±8 m)", or "no parking saved". */
    fun describe(context: Context): String {
        val position = saved(context) ?: return context.getString(R.string.ml_parking_none)
        val time = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()).format(Date(position.time))
        return context.getString(R.string.ml_parking_saved_at, time, position.accuracy.toInt())
    }

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- save

    /**
     * Waits for the GPS with a dialog showing the current precision; saves by itself once the fix
     * is precise enough, or when the user taps "Save now", or the best fix at the timeout.
     */
    @SuppressLint("MissingPermission")
    fun save(activity: Activity) {
        if (!hasPermission(activity)) {
            activity.showToast(activity.getString(R.string.ml_parking_permission))
            ActivityCompat.requestPermissions(
                activity, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_LOCATION
            )
            return
        }
        val manager = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER) && !manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            activity.showToast(activity.getString(R.string.ml_parking_no_gps))
            try {
                activity.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return
        }
        val handler = Handler(Looper.getMainLooper())
        var best: Location? = null
        var done = false
        lateinit var dialog: StatusDialog
        lateinit var listener: LocationListener
        lateinit var timeout: Runnable

        fun stop() {
            handler.removeCallbacks(timeout)
            try {
                manager.removeUpdates(listener)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun finish(location: Location) {
            if (done) return
            done = true
            stop()
            store(activity, location)
            dialog.dismiss()
            activity.showToast(activity.getString(R.string.ml_parking_saved, location.accuracy.toInt()))
        }

        fun offer(location: Location) {
            if (!location.hasAccuracy()) return
            val current = best
            if (current == null || location.accuracy <= current.accuracy) best = location
            val shown = best ?: location
            dialog.setMessage(activity.getString(R.string.ml_parking_accuracy, shown.accuracy.toInt()))
            dialog.setActionEnabled(true)
            if (location.accuracy <= GOOD_ACCURACY_M) finish(location)
        }

        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = offer(location)

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {
            }

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        timeout = Runnable {
            val location = best
            if (location != null) finish(location)
            else dialog.setMessage(activity.getString(R.string.ml_parking_no_signal))
        }
        dialog = activity.showStatusDialog(
            title = activity.getString(R.string.ml_parking),
            message = activity.getString(R.string.ml_parking_searching),
            action = activity.getString(R.string.ml_parking_save_now),
        ) { best?.let { finish(it) } }
        dialog.setActionEnabled(false)
        dialog.setOnDismiss { stop() }
        // GPS for precision, network and fused for a quick first fix (indoors the GPS may never answer)
        val providers = mutableListOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) providers += LocationManager.FUSED_PROVIDER
        var started = 0
        providers.forEach { provider ->
            try {
                if (!manager.isProviderEnabled(provider)) return@forEach
                manager.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
                started++
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (started == 0) dialog.setMessage(activity.getString(R.string.ml_parking_no_signal))
        // A recent last known fix is a starting point the user can save right away
        providers.mapNotNull { provider ->
            try {
                manager.getLastKnownLocation(provider)
            } catch (e: Exception) {
                null
            }
        }.filter { System.currentTimeMillis() - it.time < LAST_KNOWN_MAX_AGE_MS && it.accuracy > GOOD_ACCURACY_M }
            .minByOrNull { it.accuracy }?.let { offer(it) }
        handler.postDelayed(timeout, TIMEOUT_MS)
    }

    // ---------------------------------------------------------------- open

    /** Opens the saved position in Google Maps (any maps app as a fallback). */
    fun openMaps(context: Context) {
        val position = saved(context) ?: run {
            context.showToast(context.getString(R.string.ml_parking_none))
            return
        }
        val label = Uri.encode(context.getString(R.string.ml_parking))
        val uri = Uri.parse("geo:${position.latitude},${position.longitude}?q=${position.latitude},${position.longitude}($label)")
        val intents = listOf(
            Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps"),
            Intent(Intent.ACTION_VIEW, uri),
        )
        for (intent in intents) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                // try the next one
            }
        }
        context.showToast(context.getString(R.string.ml_parking_no_maps))
    }

    /** Default icon: an outline "P" in the theme's text colour. */
    fun icon(context: Context, sizePx: Int): Bitmap? {
        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_parking)?.mutate() ?: return null
        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        // Soft grey like the line icons around it, not pure white/black
        DrawableCompat.setTint(drawable, if (night) 0xFFD6D6D6.toInt() else 0xFF2A2A2A.toInt())
        return drawable.toBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    }

    /** Menu entries of the parking button: save now, open in maps. */
    fun menuEntries(activity: Activity): List<MenuEntry> = listOf(
        MenuEntry(activity.getString(R.string.ml_parking_save)) { save(activity) },
        MenuEntry(activity.getString(R.string.ml_parking_open_maps)) { openMaps(activity) },
    )

    const val REQUEST_LOCATION = 7201
}
