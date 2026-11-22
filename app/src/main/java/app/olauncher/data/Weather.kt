package app.olauncher.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import app.olauncher.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.coroutines.resume

/** One hour of the forecast. Times are epoch millis. */
class WeatherHour(
    val time: Long,
    val code: Int,
    val isDay: Boolean,
    val temp: Float,
    val feels: Float,
    val precip: Float,
    val precipChance: Int,
    val wind: Float,
    val windDir: Int,
    val pressure: Float,
    val cloud: Int,
    val humidity: Int,
    val dewPoint: Float,
    val uv: Float,
)

/** One day of the forecast (its local midnight in [time]). */
class WeatherDay(
    val time: Long,
    val code: Int,
    val max: Float,
    val min: Float,
    val precip: Float,
    val precipChance: Int,
    val wind: Float,
    val windDir: Int,
    val sunrise: Long,
    val sunset: Long,
    val uv: Float,
)

class Forecast(val fetched: Long, val place: String, val hours: List<WeatherHour>, val days: List<WeatherDay>) {
    /** The hours from the current one on. */
    fun upcoming(now: Long = System.currentTimeMillis()): List<WeatherHour> = hours.filter { it.time + 3_600_000L > now }
}

/**
 * The forecast from Open-Meteo (free, no account) for the phone's position: hourly for 7 days
 * and daily. Kept in a file and refreshed when older than an hour; the last position is kept too,
 * so it works when the phone doesn't have a fresh fix.
 */
object WeatherRepository {
    private const val FILE = "weather.json"
    private const val MAX_AGE = 60 * 60_000L
    private const val PREFS = "app.minimalauncher.weather"

    /** After a download that failed, the next one by itself waits this long. */
    private const val RETRY = 5 * 60_000L

    @Volatile
    private var memory: Forecast? = null

    /** Why the last download failed (null when it went well): shown in the Weather app. */
    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    private var lastAttempt = 0L

    /** A lookup of the place's name still going on: some phones never answer, and it must not be waited for. */
    @Volatile
    private var placeTask: java.util.concurrent.FutureTask<String?>? = null

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** What's saved, without network (may be old or null). */
    fun cached(context: Context): Forecast? {
        memory?.let { return it }
        return try {
            val file = File(context.filesDir, FILE)
            if (!file.exists()) null else parse(JSONObject(file.readText())).also { memory = it }
        } catch (e: Exception) {
            null
        }
    }

    fun isStale(context: Context): Boolean = (cached(context)?.fetched ?: 0L) < System.currentTimeMillis() - MAX_AGE

    /** Old, and not just tried: time for a download that nobody asked for (the widget, while it stays on screen). */
    fun shouldRetry(context: Context): Boolean = isStale(context) && System.currentTimeMillis() - lastAttempt > RETRY

    /** A fresh forecast (downloaded when the saved one is older than an hour, or [force]); null if it can't be had. */
    suspend fun refresh(context: Context, force: Boolean = false): Forecast? {
        val ctx = context.applicationContext
        if (!force && !isStale(ctx)) return cached(ctx)
        lastAttempt = System.currentTimeMillis()
        val position = position(ctx) ?: run {
            lastError = "no position"
            return cached(ctx)
        }
        return withContext(Dispatchers.IO) {
            try {
                val url = "https://api.open-meteo.com/v1/forecast?latitude=${position.first}&longitude=${position.second}" +
                        "&hourly=temperature_2m,apparent_temperature,precipitation,precipitation_probability,weather_code,is_day," +
                        "wind_speed_10m,wind_direction_10m,pressure_msl,cloud_cover,relative_humidity_2m,dew_point_2m,uv_index" +
                        "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max," +
                        "wind_speed_10m_max,wind_direction_10m_dominant,sunrise,sunset,uv_index_max" +
                        "&wind_speed_unit=ms&timezone=auto&forecast_days=7&timeformat=unixtime"
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 20_000
                }
                val status = conn.responseCode
                if (status != 200) {
                    conn.disconnect()
                    throw java.io.IOException("HTTP $status")
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val json = JSONObject(body)
                json.put("fetched", System.currentTimeMillis())
                json.put("place", placeName(ctx, position) ?: "")
                File(ctx.filesDir, FILE).writeText(json.toString())
                parse(json).also {
                    memory = it
                    lastError = null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                lastError = e.message?.takeIf { it.startsWith("HTTP ") } ?: e.javaClass.simpleName
                cached(ctx)
            }
        }
    }

    // ---------------------------------------------------------------- position

    /** The phone's position: a recent fix, a new coarse one, or the last one used. */
    @SuppressLint("MissingPermission")
    private suspend fun position(ctx: Context): Pair<Double, Double>? {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString("pos", null)?.split(",")?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
        if (!hasPermission(ctx)) return saved
        val manager = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        var best: Location? = providers.mapNotNull {
            try {
                manager.getLastKnownLocation(it)
            } catch (e: Exception) {
                null
            }
        }.maxByOrNull { it.time }
        if (best == null || System.currentTimeMillis() - best.time > 3 * 3_600_000L) {
            best = withTimeoutOrNull(10_000L) { oneFix(manager) } ?: best
        }
        val found = best?.let { it.latitude to it.longitude } ?: return saved
        // Rounded: enough for the weather, and the request doesn't carry the exact position
        val rounded = (Math.round(found.first * 100) / 100.0) to (Math.round(found.second * 100) / 100.0)
        prefs.edit { putString("pos", "${rounded.first},${rounded.second}") }
        return rounded
    }

    @SuppressLint("MissingPermission")
    private suspend fun oneFix(manager: LocationManager): Location? = suspendCancellableCoroutine { c ->
        val provider = if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) LocationManager.NETWORK_PROVIDER
        else if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else null
        if (provider == null) {
            c.resume(null)
            return@suspendCancellableCoroutine
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                manager.removeUpdates(this)
                if (c.isActive) c.resume(location)
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        try {
            manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
        } catch (e: Exception) {
            c.resume(null)
            return@suspendCancellableCoroutine
        }
        c.invokeOnCancellation { manager.removeUpdates(listener) }
    }

    /**
     * The name of the place, waited for a few seconds at most: the lookup can stay without an answer
     * for ever, and the forecast must not wait with it. Without an answer, the name found last time
     * for the same position.
     */
    @Suppress("DEPRECATION")
    private fun placeName(ctx: Context, position: Pair<Double, Double>): String? {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "${position.first},${position.second}"
        val saved = prefs.getString("place", null)?.takeIf { prefs.getString("placePos", null) == key }
        // One still without an answer: not another thread left waiting
        if (placeTask?.isDone == false) return saved
        val task = java.util.concurrent.FutureTask<String?> {
            android.location.Geocoder(ctx, Locale.getDefault()).getFromLocation(position.first, position.second, 1)
                ?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
        }
        placeTask = task
        Thread(task, "weather-place").apply { isDaemon = true }.start()
        val found = try {
            task.get(8, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
            null
        }
        if (found.isNullOrBlank()) return saved
        prefs.edit { putString("place", found).putString("placePos", key) }
        return found
    }

    // ---------------------------------------------------------------- parsing

    private fun parse(o: JSONObject): Forecast {
        val h = o.getJSONObject("hourly")
        val times = h.getJSONArray("time")
        fun f(name: String, i: Int) = h.optJSONArray(name)?.optDouble(i, 0.0)?.takeIf { !it.isNaN() }?.toFloat() ?: 0f
        fun n(name: String, i: Int) = h.optJSONArray(name)?.optInt(i, 0) ?: 0
        val hours = (0 until times.length()).map { i ->
            WeatherHour(
                time = times.getLong(i) * 1000L,
                code = n("weather_code", i),
                isDay = n("is_day", i) == 1,
                temp = f("temperature_2m", i),
                feels = f("apparent_temperature", i),
                precip = f("precipitation", i),
                precipChance = n("precipitation_probability", i),
                wind = f("wind_speed_10m", i),
                windDir = n("wind_direction_10m", i),
                pressure = f("pressure_msl", i),
                cloud = n("cloud_cover", i),
                humidity = n("relative_humidity_2m", i),
                dewPoint = f("dew_point_2m", i),
                uv = f("uv_index", i),
            )
        }
        val d = o.getJSONObject("daily")
        val dTimes = d.getJSONArray("time")
        fun df(name: String, i: Int) = d.optJSONArray(name)?.optDouble(i, 0.0)?.takeIf { !it.isNaN() }?.toFloat() ?: 0f
        fun dn(name: String, i: Int) = d.optJSONArray(name)?.optLong(i, 0L) ?: 0L
        val days = (0 until dTimes.length()).map { i ->
            WeatherDay(
                time = dTimes.getLong(i) * 1000L,
                code = dn("weather_code", i).toInt(),
                max = df("temperature_2m_max", i),
                min = df("temperature_2m_min", i),
                precip = df("precipitation_sum", i),
                precipChance = dn("precipitation_probability_max", i).toInt(),
                wind = df("wind_speed_10m_max", i),
                windDir = dn("wind_direction_10m_dominant", i).toInt(),
                sunrise = dn("sunrise", i) * 1000L,
                sunset = dn("sunset", i) * 1000L,
                uv = df("uv_index_max", i),
            )
        }
        return Forecast(o.optLong("fetched"), o.optString("place"), hours, days)
    }
}

/** Line icons and colors for the WMO weather codes Open-Meteo uses. */
object WeatherIcons {
    const val SUN = 0xFFF2B33D.toInt()
    const val MOON = 0xFF9F8CF0.toInt()
    const val CLOUD = 0xFFBFCBD6.toInt()
    const val RAIN = 0xFF4FA3F7.toInt()

    /** The icon for [code] by day or night. */
    fun icon(code: Int, isDay: Boolean): Int = when (code) {
        0 -> if (isDay) R.drawable.ic_w_sun else R.drawable.ic_w_moon
        1, 2 -> if (isDay) R.drawable.ic_w_sun_cloud else R.drawable.ic_w_moon_cloud
        3 -> R.drawable.ic_w_cloud
        45, 48 -> R.drawable.ic_w_fog
        51, 53, 55, 56, 57 -> R.drawable.ic_w_drizzle
        61, 63, 65, 66, 67, 80, 81, 82 -> R.drawable.ic_w_rain
        71, 73, 75, 77, 85, 86 -> R.drawable.ic_w_snow
        95, 96, 99 -> R.drawable.ic_w_storm
        else -> R.drawable.ic_w_cloud
    }

    /** Main tint of the icon. */
    fun tint(code: Int, isDay: Boolean): Int = when (code) {
        0, 1, 2 -> if (isDay) SUN else MOON
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82, 95, 96, 99 -> RAIN
        else -> CLOUD
    }

    /** A short description of [code]. */
    fun label(context: Context, code: Int): String = context.getString(
        when (code) {
            0 -> R.string.ml_w_clear
            1, 2 -> R.string.ml_w_partly
            3 -> R.string.ml_w_cloudy
            45, 48 -> R.string.ml_w_fog
            51, 53, 55, 56, 57 -> R.string.ml_w_drizzle
            61, 63, 65, 66, 67, 80, 81, 82 -> R.string.ml_w_rain
            71, 73, 75, 77, 85, 86 -> R.string.ml_w_snow
            95, 96, 99 -> R.string.ml_w_storm
            else -> R.string.ml_w_cloudy
        }
    )

    fun hourLabel(time: Long): String = SimpleDateFormat("H", Locale.getDefault()).format(time)
}
