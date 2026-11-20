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

    /** A transcript: its phrases, and its single words when the model times them too. */
    class Transcription(val segments: List<Segment>, val words: List<Segment>)

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

    /**
     * Told what Groq says is left of the free plan after each request: [kind] is "chat" or "audio",
     * the headers are its "x-ratelimit-..." ones without that prefix (see [GroqUsage]).
     */
    @Volatile
    var listener: ((kind: String, model: String?, headers: Map<String, String>) -> Unit)? = null

    private val LIMIT_HEADERS = listOf("limit-requests", "remaining-requests", "reset-requests", "limit-tokens", "remaining-tokens", "reset-tokens")

    private fun report(conn: HttpURLConnection, kind: String, model: String?) {
        val told = listener ?: return
        if (kind.isEmpty()) return
        try {
            val headers = HashMap<String, String>()
            LIMIT_HEADERS.forEach { name -> conn.getHeaderField("x-ratelimit-$name")?.let { headers[name] = it } }
            if (headers.isNotEmpty()) told(kind, model, headers)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** The answer as text, or a [GroqException] with Groq's own message. */
    private fun read(conn: HttpURLConnection, kind: String = "", model: String? = null): String {
        try {
            val code = conn.responseCode
            report(conn, kind, model)
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
    ): Transcription = retrying(onWait, cancelled) {
        val boundary = "----minima" + System.nanoTime()
        val fields = mutableListOf("model" to model, "response_format" to "verbose_json", "temperature" to "0")
        if (language.isNotEmpty()) fields += "language" to language
        // The time of each word too, to follow the audio on the text
        fields += "timestamp_granularities[]" to "segment"
        fields += "timestamp_granularities[]" to "word"
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
        val json = JSONObject(read(conn, "audio", model))
        val array = json.optJSONArray("segments") ?: JSONArray()
        val segments = (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Segment(o.optDouble("start", 0.0), o.optDouble("end", 0.0), o.optString("text").trim())
        }.filter { it.text.isNotEmpty() }
        val timed = json.optJSONArray("words") ?: JSONArray()
        val words = (0 until timed.length()).map { i ->
            val o = timed.getJSONObject(i)
            Segment(o.optDouble("start", 0.0), o.optDouble("end", 0.0), o.optString("word").trim())
        }.filter { it.text.isNotEmpty() }
        Transcription(
            if (segments.isNotEmpty()) segments
            else json.optString("text").trim().let { if (it.isEmpty()) emptyList() else listOf(Segment(0.0, 0.0, it)) },
            words,
        )
    }

    /** One answer of a text model. */
    fun chat(
        key: String, model: String, system: String, user: String,
        onWait: (Long) -> Unit = {}, cancelled: () -> Boolean = { false },
    ): String = chatMessages(key, model, listOf("system" to system, "user" to user), onWait, cancelled)

    /** One answer to a conversation: [messages] are (role, text), roles "system", "user" and "assistant". */
    fun chatMessages(
        key: String, model: String, messages: List<Pair<String, String>>,
        onWait: (Long) -> Unit = {}, cancelled: () -> Boolean = { false },
    ): String = retrying(onWait, cancelled) {
        val array = JSONArray()
        messages.forEach { (role, text) -> array.put(JSONObject().put("role", role).put("content", text)) }
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.3)
            .put("messages", array)
            .toString().toByteArray()
        val conn = open("/chat/completions", key, "POST")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setFixedLengthStreamingMode(body.size)
        conn.outputStream.use { it.write(body) }
        val content = JSONObject(read(conn, "chat", model)).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
        // Reasoning models may put their thinking first
        content.replace(Regex("(?s)<think>.*?</think>"), "").trim()
    }

    /** The model to use among [models] when the user chose none: the most capable ones first. */
    fun bestChatModel(models: List<String>): String? {
        val wanted = listOf("llama-3.3-70b", "gpt-oss-120b", "kimi-k2", "llama-4", "70b", "120b", "qwen", "llama")
        for (w in wanted) models.firstOrNull { it.contains(w, ignoreCase = true) }?.let { return it }
        return models.firstOrNull()
    }

    /** The text models the key can use. */
    fun chatModels(key: String): List<String> {
        val data = JSONObject(read(open("/models", key, "GET"))).getJSONArray("data")
        val skipped = listOf("whisper", "guard", "tts", "orpheus", "playai", "safeguard", "compound", "allam")
        return (0 until data.length()).map { data.getJSONObject(it).optString("id") }
            .filter { id -> id.isNotEmpty() && skipped.none { id.contains(it, ignoreCase = true) } }
            .sorted()
    }
}
