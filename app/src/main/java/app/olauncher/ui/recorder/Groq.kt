package app.olauncher.ui.recorder

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Groq's free API (https://console.groq.com): Whisper for the transcripts, its text models for
 * the write-ups. Everything here does network I/O: call it off the main thread.
 */
object Groq {

    class Segment(val start: Double, val end: Double, val text: String)

    /** [code] is the HTTP status (0: no answer); [retryAfter] the seconds to wait on a 429. */
    class GroqException(message: String, val code: Int = 0, val retryAfter: Long = 0) : Exception(message)

    private const val BASE = "https://api.groq.com/openai/v1"
    /** A longer wait than this is a daily limit: no point in waiting. */
    private const val MAX_WAIT = 300L

    private fun open(path: String, key: String, method: String) = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 30_000
        readTimeout = 600_000
        setRequestProperty("Authorization", "Bearer $key")
        setRequestProperty("Accept", "application/json")
    }

    /** The answer as text, or a [GroqException] with Groq's own message. */
    private fun read(conn: HttpURLConnection): String {
        try {
            val code = conn.responseCode
            if (code in 200..299) return conn.inputStream.bufferedReader().use { it.readText() }
            val body = try {
                conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            } catch (e: Exception) {
                ""
            }
            val message = try {
                JSONObject(body).getJSONObject("error").getString("message")
            } catch (e: Exception) {
                body.take(300).ifBlank { "HTTP $code" }
            }
            val wait = conn.getHeaderField("retry-after")?.toDoubleOrNull()?.let { Math.ceil(it).toLong() } ?: 0
            throw GroqException(message, code, wait)
        } finally {
            conn.disconnect()
        }
    }

    /** Runs [call] again after the wait Groq asks for when a rate limit is hit (free plan: per minute). */
    private fun <T> retrying(onWait: (Long) -> Unit, cancelled: () -> Boolean, call: () -> T): T {
        var attempts = 0
        while (true) {
            try {
                return call()
            } catch (e: GroqException) {
                if (e.code != 429 || e.retryAfter > MAX_WAIT || attempts++ >= 10 || cancelled()) throw e
                val wait = e.retryAfter.coerceAtLeast(3)
                onWait(wait)
                var left = wait * 1000
                while (left > 0 && !cancelled()) {
                    Thread.sleep(500)
                    left -= 500
                }
            }
        }
    }

    /** The speech of [file] (at most 25 MB) as timed segments. [language]: ISO code, "" to detect it. */
    fun transcribe(
        key: String, file: File, model: String, language: String,
        onWait: (Long) -> Unit = {}, cancelled: () -> Boolean = { false },
    ): List<Segment> = retrying(onWait, cancelled) {
        val boundary = "----minima" + System.nanoTime()
        val fields = mutableListOf("model" to model, "response_format" to "verbose_json", "temperature" to "0")
        if (language.isNotEmpty()) fields += "language" to language
        val head = StringBuilder()
        fields.forEach { (name, value) ->
            head.append("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
        }
        // Groq tells the format from the file name
        head.append("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.${file.extension.lowercase()}\"\r\n")
        head.append("Content-Type: application/octet-stream\r\n\r\n")
        val headBytes = head.toString().toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        val conn = open("/audio/transcriptions", key, "POST")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.setFixedLengthStreamingMode(headBytes.size + file.length() + tail.size)
        try {
            conn.outputStream.use { out ->
                out.write(headBytes)
                file.inputStream().use { it.copyTo(out, 64 * 1024) }
                out.write(tail)
            }
        } catch (e: java.io.IOException) {
            // The server may have refused the upload half way: its answer says why
            if (conn.errorStream == null) {
                conn.disconnect()
                throw e
            }
        }
        val json = JSONObject(read(conn))
        val array = json.optJSONArray("segments") ?: JSONArray()
        val segments = (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Segment(o.optDouble("start", 0.0), o.optDouble("end", 0.0), o.optString("text").trim())
        }.filter { it.text.isNotEmpty() }
        if (segments.isNotEmpty()) segments
        else json.optString("text").trim().let { if (it.isEmpty()) emptyList() else listOf(Segment(0.0, 0.0, it)) }
    }

    /** One answer of a text model. */
    fun chat(
        key: String, model: String, system: String, user: String,
        onWait: (Long) -> Unit = {}, cancelled: () -> Boolean = { false },
    ): String = retrying(onWait, cancelled) {
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.3)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
            .toString().toByteArray()
        val conn = open("/chat/completions", key, "POST")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setFixedLengthStreamingMode(body.size)
        conn.outputStream.use { it.write(body) }
        val content = JSONObject(read(conn)).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
        // Reasoning models may put their thinking first
        content.replace(Regex("(?s)<think>.*?</think>"), "").trim()
    }

    /** The text models the key can use. */
    fun chatModels(key: String): List<String> {
        val data = JSONObject(read(open("/models", key, "GET"))).getJSONArray("data")
        val skipped = listOf("whisper", "guard", "tts", "orpheus", "playai", "safeguard")
        return (0 until data.length()).map { data.getJSONObject(it).optString("id") }
            .filter { id -> id.isNotEmpty() && skipped.none { id.contains(it, ignoreCase = true) } }
            .sorted()
    }
}
