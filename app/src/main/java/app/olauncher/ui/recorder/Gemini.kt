package app.olauncher.ui.recorder

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Google's Gemini API (https://aistudio.google.com), for the write-ups and the chat: its free
 * plan takes a whole transcript in one request. Everything here does network I/O.
 */
object Gemini {

    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"

    private fun open(path: String, key: String, method: String) = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 30_000
        readTimeout = 600_000
        setRequestProperty("x-goog-api-key", key)
        setRequestProperty("Accept", "application/json")
    }

    /** The answer as text, or a [Groq.GroqException] with Google's own message. */
    private fun read(conn: HttpURLConnection): String {
        try {
            val code = conn.responseCode
            if (code in 200..299) return conn.inputStream.bufferedReader().use { it.readText() }
            val body = try {
                conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            } catch (e: Exception) {
                ""
            }
            var message = body.take(300).ifBlank { "HTTP $code" }
            var wait = 0L
            try {
                val error = JSONObject(body).getJSONObject("error")
                message = error.optString("message", message).lineSequence().firstOrNull { it.isNotBlank() } ?: message
                // "Try again in 34s", when a limit by the minute was hit
                val details = error.optJSONArray("details") ?: JSONArray()
                for (i in 0 until details.length()) {
                    details.getJSONObject(i).optString("retryDelay").removeSuffix("s").toDoubleOrNull()?.let { wait = Math.ceil(it).toLong() }
                }
            } catch (e: Exception) {
                // Not the usual error: the text as it came
            }
            if (code == 429) {
                // The day's requests are over: waiting a few seconds won't help
                if (body.contains("PerDay", ignoreCase = true) || body.contains("per day", ignoreCase = true)) wait = 100_000
                else if (wait == 0L) wait = 20
            }
            throw Groq.GroqException(message, code, wait)
        } finally {
            conn.disconnect()
        }
    }

    /** One answer to a conversation: [messages] are (role, text), roles "system", "user" and "assistant". */
    fun chat(
        key: String, model: String, messages: List<Pair<String, String>>,
        onWait: (Long) -> Unit = {}, cancelled: () -> Boolean = { false },
    ): String = Groq.retrying(onWait, cancelled) {
        val contents = JSONArray()
        messages.filter { it.first != "system" }.forEach { (role, text) ->
            contents.put(JSONObject().put("role", if (role == "assistant") "model" else "user").put("parts", JSONArray().put(JSONObject().put("text", text))))
        }
        val body = JSONObject().put("contents", contents).put("generationConfig", JSONObject().put("temperature", 0.3))
        val system = messages.filter { it.first == "system" }.joinToString("\n\n") { it.second }
        if (system.isNotEmpty()) body.put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        val bytes = body.toString().toByteArray()
        val conn = open("/models/$model:generateContent", key, "POST")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setFixedLengthStreamingMode(bytes.size)
        conn.outputStream.use { it.write(bytes) }
        val json = JSONObject(read(conn))
        val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw Groq.GroqException(json.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty().ifEmpty { "no answer" })
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }.trim()
    }

    /** The text models the key can use ("gemini-…"), newest first. */
    fun models(key: String): List<String> {
        val found = mutableListOf<String>()
        var page = ""
        // A few pages at most
        for (round in 0 until 5) {
            val json = JSONObject(read(open("/models?pageSize=200" + if (page.isEmpty()) "" else "&pageToken=$page", key, "GET")))
            val list = json.optJSONArray("models") ?: JSONArray()
            for (i in 0 until list.length()) {
                val model = list.getJSONObject(i)
                val methods = model.optJSONArray("supportedGenerationMethods")?.toString().orEmpty()
                val name = model.optString("name").removePrefix("models/")
                if (name.startsWith("gemini-") && methods.contains("generateContent") && SKIPPED.none { name.contains(it) }) found += name
            }
            page = json.optString("nextPageToken")
            if (page.isEmpty()) break
        }
        return found.distinct().sortedWith(compareByDescending<String> { version(it) }.thenBy { it })
    }

    private val SKIPPED = listOf("tts", "image", "embedding", "live", "audio", "robotics", "computer-use", "vision", "thinking")

    private fun version(name: String) = Regex("gemini-(\\d+(\\.\\d+)?)").find(name)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

    /**
     * The model to use when the user chose none: the newest "flash-lite", whose free plan allows the
     * most requests a day; a "flash" where there is none.
     */
    fun best(models: List<String>): String? {
        val stable = models.filter { !it.contains("preview") && !it.contains("exp") }.ifEmpty { models }
        return stable.firstOrNull { it.contains("flash-lite") } ?: stable.firstOrNull { it.contains("flash") } ?: stable.firstOrNull()
    }
}
