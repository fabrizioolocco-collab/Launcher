package app.olauncher.ui.recorder

import android.content.Context
import app.olauncher.R
import java.io.File
import java.security.MessageDigest

/**
 * The AI of the write-ups and the chat: Gemini or Groq, as chosen in the settings. With Gemini a
 * whole transcript fits in one request; with Groq's free plan a long one goes piece by piece.
 */
object RecorderAi {

    const val GEMINI = "gemini"
    const val GROQ = "groq"

    /** The model found for a provider when the user chose none: asked once, then remembered while the app runs. */
    private val found = HashMap<String, String>()

    fun provider(ctx: Context) = RecorderStore.aiProvider(ctx)

    private fun key(ctx: Context) = if (provider(ctx) == GEMINI) RecorderStore.geminiKey(ctx) else RecorderStore.apiKey(ctx)

    /** Why the AI can't be asked right now (no key), or null when it can. */
    fun blocker(ctx: Context): String? = when {
        key(ctx).isNotEmpty() -> null
        provider(ctx) == GEMINI -> ctx.getString(R.string.ml_rec_gemini_key_missing)
        else -> ctx.getString(R.string.ml_rec_key_missing)
    }

    /** Characters of a recording that go in one request: of a write-up, and with each question of the chat. */
    fun pieceSize(ctx: Context) = if (provider(ctx) == GEMINI) 120_000 else 8_000
    fun contextSize(ctx: Context) = if (provider(ctx) == GEMINI) 600_000 else 12_000

    /** The text models the chosen provider offers now, to pick one in the settings. */
    fun models(ctx: Context): List<String> = if (provider(ctx) == GEMINI) Gemini.models(key(ctx)) else Groq.chatModels(key(ctx))

    /** The model chosen in the settings, or the best one the provider has now (they come and go). */
    private fun model(ctx: Context, except: String? = null): String {
        val gemini = provider(ctx) == GEMINI
        val chosen = if (gemini) RecorderStore.geminiModel(ctx) else if (RecorderStore.chatModelChosen(ctx)) RecorderStore.chatModel(ctx) else ""
        if (chosen.isNotEmpty() && except == null) return chosen
        val name = provider(ctx)
        if (except == null) synchronized(found) { found[name] }?.let { return it }
        val all = models(ctx).filter { it != except }
        val best = (if (gemini) Gemini.best(all) else Groq.bestChatModel(all))
            ?: if (gemini) throw Exception(ctx.getString(R.string.ml_rec_no_model)) else RecorderStore.DEFAULT_CHAT
        synchronized(found) { found[name] = best }
        return best
    }

    /** One answer to [messages] ((role, text), roles "system", "user" and "assistant") from the chosen AI. */
    fun ask(ctx: Context, messages: List<Pair<String, String>>, onWait: (Long) -> Unit, cancelled: () -> Boolean): String {
        val key = key(ctx).ifEmpty { throw Exception(blocker(ctx)) }
        val gemini = provider(ctx) == GEMINI
        fun send(model: String): String {
            val answer = if (gemini) Gemini.chat(key, model, messages, onWait, cancelled) else Groq.chatMessages(key, model, messages, onWait, cancelled)
            if (gemini) GroqUsage.addGemini(ctx)
            return answer
        }
        val model = model(ctx)
        return try {
            send(model)
        } catch (e: Groq.GroqException) {
            // The model is gone or isn't for this key: another one, once
            if (e.code !in listOf(400, 403, 404) || e.message?.contains("model", ignoreCase = true) != true) throw e
            send(model(ctx, except = model))
        }
    }

    /** [paragraphs] put together into pieces of at most [limit] characters (a longer paragraph is cut at a space). */
    fun chunks(paragraphs: List<String>, limit: Int): List<String> {
        val out = mutableListOf<StringBuilder>()
        fun add(text: String) {
            val last = out.lastOrNull()
            if (last != null && last.length + text.length + 2 <= limit) last.append("\n\n").append(text)
            else out += StringBuilder(text)
        }
        for (p in paragraphs) {
            var rest = p.trim()
            while (rest.length > limit) {
                val cut = rest.lastIndexOf(' ', limit).takeIf { it > limit / 2 } ?: limit
                add(rest.substring(0, cut))
                rest = rest.substring(cut).trim()
            }
            if (rest.isNotEmpty()) add(rest)
        }
        return out.map { it.toString() }
    }

    /**
     * What the chat knows of a recording: its transcript, or, when that is too long for one request,
     * detailed notes of it (made once, piece by piece, then kept).
     * [onStep] says which piece is being read (done, total).
     */
    fun context(ctx: Context, transcript: String, onStep: (Int, Int) -> Unit, onWait: (Long) -> Unit, cancelled: () -> Boolean): String {
        val text = transcript.trim()
        val limit = contextSize(ctx)
        val piece = pieceSize(ctx)
        if (text.length <= limit) return text
        val hash = MessageDigest.getInstance("SHA-1").digest((provider(ctx) + text).toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
        val kept = File(File(ctx.cacheDir, "recorder-notes").apply { mkdirs() }, "$hash.txt")
        if (kept.isFile) return kept.readText()
        val system = ctx.getString(R.string.ml_rec_prompt_system)
        val pieces = chunks(text.split(Regex("\n\\s*\n")), piece)
        var done = 0
        var total = pieces.size
        fun note(user: String): String {
            if (cancelled()) throw InterruptedException()
            onStep(done, total)
            val answer = ask(ctx, listOf("system" to system, "user" to user), onWait, cancelled)
            done++
            return answer
        }
        var notes = pieces.mapIndexed { i, part -> note(ctx.getString(R.string.ml_rec_prompt_notes, i + 1, pieces.size) + "\n\n" + part) }
        var rounds = 0
        while (notes.sumOf { it.length } > limit && notes.size > 1 && rounds++ < 4) {
            val groups = chunks(notes, piece)
            if (groups.size >= notes.size) break
            total += groups.size
            notes = groups.map { note(ctx.getString(R.string.ml_rec_prompt_condense) + "\n\n" + it) }
        }
        val result = notes.joinToString("\n\n").take(limit * 2)
        kept.writeText(result)
        return result
    }
}
