package app.olauncher.ui.compass

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import app.olauncher.R
import app.olauncher.helper.dpToPx
import app.olauncher.ui.UiKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Compass: the dial with the heading in degrees (magnetic or true north), and the altitude
 * from the GPS (above sea level) and from the barometer when the phone has one.
 */
class CompassActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensors: SensorManager
    private lateinit var locations: LocationManager
    private lateinit var dial: CompassView
    private lateinit var degrees: TextView
    private lateinit var direction: TextView
    private lateinit var northMode: TextView
    private lateinit var calibrate: TextView
    private lateinit var altitude: TextView
    private lateinit var altitudeInfo: TextView
    private lateinit var pressureInfo: TextView

    private var rotationSensor: Sensor? = null
    private var hasCompass = false
    private val gravity = FloatArray(3)
    private val magnetic = FloatArray(3)
    private var haveGravity = false
    private var haveMagnetic = false
    private val matrix = FloatArray(9)

    /** Smoothed heading as a unit vector, so that 359° -> 1° does not swing round the dial. */
    private var sinH = 0f
    private var cosH = 1f
    private var smoothed = false

    private var trueNorth = false
    private var declination = 0f
    private var location: Location? = null
    private var gpsAltitude: Double? = null
    private var pressure: Float? = null

    private var gpsVertical: Float? = null

    /** Sea-level pressure that makes the barometer an altimeter: from a good GPS fix, or from the weather. */
    private var seaLevelPressure: Float? = null
    private var calibratedOnGps = false
    private var calibratedOnWeather = false

    /** Height of the ground at [groundAt], from the elevation model. */
    private var ground: Double? = null
    private var groundAt: Location? = null
    private var lookingUp = false

    private val prefs by lazy { getSharedPreferences("compass", Context.MODE_PRIVATE) }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location)

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locations = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        rotationSensor = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        hasCompass = sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null
        trueNorth = prefs.getBoolean(KEY_TRUE_NORTH, false)
        setContentView(buildLayout())
        updateNorthMode()
        renderAltitude()
        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQUEST_LOCATION
            )
        }
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val bar = UiKit.appBar(this, getString(R.string.ml_compass_app), onBack = { finish() })
        bar.view.setPadding(4.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
        root.addView(bar.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (!hasCompass) {
            root.addView(
                UiKit.emptyState(this, R.drawable.ic_m_crosshair, getString(R.string.ml_compass_none)),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            )
        } else {
            // 273°  W
            val headingRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER or Gravity.BOTTOM
            }
            degrees = TextView(this).apply {
                textSize = 60f
                setTextColor(UiKit.primary(this@CompassActivity))
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                includeFontPadding = false
            }
            direction = TextView(this).apply {
                textSize = 26f
                setTextColor(UiKit.secondary(this@CompassActivity))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(12.dpToPx(), 0, 0, 8.dpToPx())
            }
            headingRow.addView(degrees)
            headingRow.addView(direction)
            root.addView(headingRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 8.dpToPx()
            })

            // Magnetic north | true north, tap to switch
            northMode = TextView(this).apply {
                textSize = 13f
                setTextColor(UiKit.secondary(this@CompassActivity))
                gravity = Gravity.CENTER
                background = UiKit.border(this@CompassActivity, 15)
                setPadding(14.dpToPx(), 6.dpToPx(), 14.dpToPx(), 6.dpToPx())
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    trueNorth = !trueNorth
                    prefs.edit().putBoolean(KEY_TRUE_NORTH, trueNorth).apply()
                    updateNorthMode()
                    showHeading()
                }
            }
            root.addView(northMode, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = 6.dpToPx()
            })

            calibrate = TextView(this).apply {
                text = getString(R.string.ml_compass_calibrate)
                textSize = 13f
                setTextColor(CompassView.ACCENT)
                gravity = Gravity.CENTER
                setPadding(24.dpToPx(), 8.dpToPx(), 24.dpToPx(), 0)
                isVisible = false
            }
            root.addView(calibrate, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            dial = CompassView(this).apply {
                cardinals = getString(R.string.ml_compass_cardinals).split(",").map { it.trim() }
            }
            root.addView(dial, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                setMargins(16.dpToPx(), 8.dpToPx(), 16.dpToPx(), 8.dpToPx())
            })
        }

        // Altitude card
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiKit.cardBackground(this@CompassActivity)
            setPadding(20.dpToPx(), 14.dpToPx(), 20.dpToPx(), 16.dpToPx())
        }
        val label = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(UiKit.icon(this@CompassActivity, R.drawable.ic_m_up, UiKit.secondary(this@CompassActivity), 18))
            addView(TextView(this@CompassActivity).apply {
                text = getString(R.string.ml_compass_altitude)
                textSize = 13f
                setTextColor(UiKit.secondary(this@CompassActivity))
                setPadding(6.dpToPx(), 0, 0, 0)
            })
        }
        altitude = TextView(this).apply {
            textSize = 36f
            setTextColor(UiKit.primary(this@CompassActivity))
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        altitudeInfo = TextView(this).apply {
            textSize = 13f
            setTextColor(UiKit.secondary(this@CompassActivity))
        }
        pressureInfo = TextView(this).apply {
            textSize = 13f
            setTextColor(UiKit.secondary(this@CompassActivity))
            setPadding(0, 2.dpToPx(), 0, 0)
        }
        card.addView(label)
        card.addView(altitude)
        card.addView(altitudeInfo)
        card.addView(pressureInfo)
        root.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(16.dpToPx(), 4.dpToPx(), 16.dpToPx(), 16.dpToPx())
        })
        return root
    }

    override fun onResume() {
        super.onResume()
        val rotation = rotationSensor
        if (rotation != null) {
            sensors.registerListener(this, rotation, SensorManager.SENSOR_DELAY_GAME)
        } else {
            sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        }
        // Also for its accuracy, which says when the compass needs calibrating
        sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sensors.registerListener(this, it, if (rotation != null) SensorManager.SENSOR_DELAY_UI else SensorManager.SENSOR_DELAY_GAME)
        }
        sensors.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        startLocation()
    }

    override fun onPause() {
        super.onPause()
        sensors.unregisterListener(this)
        try {
            locations.removeUpdates(locationListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ---------------------------------------------------------------- heading

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
                onMatrix()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                lowPass(event.values, gravity, haveGravity)
                haveGravity = true
                fallbackMatrix()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                if (rotationSensor != null) return
                lowPass(event.values, magnetic, haveMagnetic)
                haveMagnetic = true
                fallbackMatrix()
            }
            Sensor.TYPE_PRESSURE -> {
                val p = event.values[0]
                pressure = pressure?.let { it + (p - it) * 0.1f } ?: p
                renderAltitude()
            }
        }
    }

    private fun lowPass(input: FloatArray, output: FloatArray, primed: Boolean) {
        for (i in 0..2) output[i] = if (primed) output[i] + (input[i] - output[i]) * 0.15f else input[i]
    }

    private fun fallbackMatrix() {
        if (haveGravity && haveMagnetic && SensorManager.getRotationMatrix(matrix, null, gravity, magnetic)) onMatrix()
    }

    /**
     * Heading of the top of the phone when it lies flat, of its back (where the camera looks)
     * when it is held upright, where the top points at the sky.
     */
    private fun onMatrix() {
        // World frame: x east, y north, z up. Columns of the matrix: the phone's axes in it.
        val yEast = matrix[1]
        val yNorth = matrix[4]
        val angle = if (hypot(yEast, yNorth) > 0.5f) atan2(yEast, yNorth) else atan2(-matrix[2], -matrix[5])
        val s = sin(angle)
        val c = cos(angle)
        if (!smoothed) {
            sinH = s
            cosH = c
            smoothed = true
        } else {
            sinH += (s - sinH) * SMOOTHING
            cosH += (c - cosH) * SMOOTHING
        }
        showHeading()
    }

    private fun showHeading() {
        if (!hasCompass || !smoothed) return
        var h = Math.toDegrees(atan2(sinH, cosH).toDouble()).toFloat()
        if (trueNorth) h += declination
        h = ((h % 360f) + 360f) % 360f
        dial.heading = h
        val shown = h.roundToInt() % 360
        degrees.text = getString(R.string.ml_compass_degrees, shown)
        val points = getString(R.string.ml_compass_points).split(",")
        direction.text = points[((h + 11.25f) / 22.5f).toInt() % 16].trim()
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (!hasCompass) return
        if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD || sensor.type == Sensor.TYPE_ROTATION_VECTOR && accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            calibrate.isVisible = accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW
        }
    }

    private fun updateNorthMode() {
        if (!hasCompass) return
        northMode.text = if (!trueNorth) getString(R.string.ml_compass_magnetic)
        else if (location == null) getString(R.string.ml_compass_true_waiting)
        else getString(R.string.ml_compass_true, String.format(Locale.getDefault(), "%+.1f°", declination))
    }

    // ---------------------------------------------------------------- altitude

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startLocation() {
        if (!hasLocationPermission()) return
        // GPS for the altitude; network and fused find the position indoors too (as Maps does)
        val providers = mutableListOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) providers += LocationManager.FUSED_PROVIDER
        try {
            val last = (providers + LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { runCatching { locations.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
            if (last != null && location == null) setPlace(last)
            providers.forEach { provider ->
                try {
                    if (locations.isProviderEnabled(provider)) {
                        locations.requestLocationUpdates(provider, 1000L, 0f, locationListener, Looper.getMainLooper())
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
        renderAltitude()
    }

    /** Position for the declination, the coordinates and the ground height (any fix, also an old one). */
    private fun setPlace(fix: Location) {
        val current = location
        // A rough fix (network) does not replace a recent precise one (GPS)
        if (current != null && fix.accuracy > current.accuracy * 2 && fix.time - current.time < 30_000) return
        location = fix
        declination = GeomagneticField(fix.latitude.toFloat(), fix.longitude.toFloat(), fix.altitude.toFloat(), System.currentTimeMillis()).declination
        updateNorthMode()
        showHeading()
        lookUpGround(fix)
        renderAltitude()
    }

    private fun onLocation(fix: Location) {
        setPlace(fix)
        if (!fix.hasAltitude() || fix.provider == LocationManager.NETWORK_PROVIDER) return
        val vertical = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && fix.hasVerticalAccuracy()) fix.verticalAccuracyMeters else null
        if (vertical != null && vertical > 50f) return
        lifecycleScope.launch {
            // The GPS gives the height above the ellipsoid: on Android 14+ turned into height above sea level
            val msl = withContext(Dispatchers.Default) { seaLevelAltitude(fix) }
            gpsAltitude = msl
            gpsVertical = vertical
            val p = pressure
            if (p != null && vertical != null && vertical < 12f) {
                seaLevelPressure = (p / (1 - msl / 44330.0).pow(5.255)).toFloat()
                calibratedOnGps = true
            }
            renderAltitude()
        }
    }

    private fun seaLevelAltitude(fix: Location): Double {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                if (!fix.hasMslAltitude()) android.location.altitude.AltitudeConverter().addMslAltitudeToLocation(this, fix)
                if (fix.hasMslAltitude()) return fix.mslAltitudeMeters
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return fix.altitude
    }

    /**
     * Height of the ground at [fix] (90 m elevation model) and the sea-level pressure there now,
     * from Open-Meteo: an altitude also indoors, where the GPS finds no satellites, and the
     * reference that turns the barometer into an altimeter. Again after moving 500 m.
     */
    private fun lookUpGround(fix: Location) {
        val previous = groundAt
        if (lookingUp || previous != null && previous.distanceTo(fix) < 500f) return
        lookingUp = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val url = String.format(
                        Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.5f&longitude=%.5f&current=pressure_msl",
                        fix.latitude, fix.longitude
                    )
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15_000
                        readTimeout = 20_000
                    }
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    val json = JSONObject(body)
                    val elevation = json.optDouble("elevation").takeIf { !it.isNaN() }
                    val msl = json.optJSONObject("current")?.optDouble("pressure_msl")?.takeIf { !it.isNaN() }
                    elevation to msl
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
            lookingUp = false
            if (result == null) return@launch
            groundAt = fix
            ground = result.first
            val msl = result.second
            if (msl != null && !calibratedOnGps) {
                seaLevelPressure = msl.toFloat()
                calibratedOnWeather = true
            }
            renderAltitude()
        }
    }

    private fun renderAltitude() {
        val p = pressure
        val baro = p?.let { SensorManager.getAltitude(seaLevelPressure ?: SensorManager.PRESSURE_STANDARD_ATMOSPHERE, it) }
        val gps = gpsAltitude
        val fix = location
        val terrain = ground
        // A calibrated barometer is the steadiest (and sees the floor you are on), then a good GPS fix,
        // then the ground under the position, last the barometer on the standard atmosphere
        val goodGps = gps != null && (gpsVertical ?: 0f) <= 25f
        val main = when {
            baro != null && seaLevelPressure != null -> baro.toDouble()
            goodGps -> gps
            terrain != null -> terrain
            gps != null -> gps
            else -> baro?.toDouble()
        }
        altitude.text = main?.let { getString(R.string.ml_compass_meters, it.roundToInt()) } ?: "—"

        val info = mutableListOf<String>()
        when {
            !hasLocationPermission() -> info += getString(R.string.ml_compass_no_location)
            gps != null -> {
                var s = getString(R.string.ml_compass_gps, gps.roundToInt())
                gpsVertical?.let { s += " ±" + it.roundToInt() + " m" }
                info += s
            }
            fix == null -> info += getString(R.string.ml_compass_searching)
            else -> info += getString(R.string.ml_compass_no_gps)
        }
        if (terrain != null) info += getString(R.string.ml_compass_ground, terrain.roundToInt())
        if (fix != null) info += coordinates(fix)
        altitudeInfo.text = info.joinToString(" · ")

        pressureInfo.isVisible = p != null
        if (p != null && baro != null) {
            pressureInfo.text = getString(R.string.ml_compass_pressure, p.roundToInt()) + " · " + getString(
                when {
                    calibratedOnGps -> R.string.ml_compass_baro
                    calibratedOnWeather -> R.string.ml_compass_baro_weather
                    else -> R.string.ml_compass_baro_std
                }, baro.roundToInt()
            )
        }
    }

    private fun coordinates(fix: Location): String {
        val lat = String.format(Locale.getDefault(), "%.4f°%s", abs(fix.latitude), if (fix.latitude >= 0) "N" else "S")
        val lon = String.format(Locale.getDefault(), "%.4f°%s", abs(fix.longitude), if (fix.longitude >= 0) "E" else getString(R.string.ml_compass_west))
        return "$lat $lon"
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) startLocation()
        else renderAltitude()
    }

    companion object {
        private const val REQUEST_LOCATION = 41
        private const val KEY_TRUE_NORTH = "true_north"
        private const val SMOOTHING = 0.12f
    }
}
