package app.olauncher.ui.recorder

import android.content.Context
import app.olauncher.R
import java.io.File
import java.security.MessageDigest

/** What the write-ups and the chat share: the model to use and how a long transcript is made to fit. */
object RecorderAi {

    /** Characters of a recording given to the model with each question of the chat. */
    private const val CONTEXT = 12_000
    private const val PIECE = 8_000

    /** The model chosen in the settings, or the best one Groq has now (they come and go). */
    fun model(ctx: Context, key: String): String {
        if (RecorderStore.chatModelChosen(ctx)) return RecorderStore.chatModel(ctx)
        return try {
            Groq.bestChatModel(Groq.chatModels(key))
        } catch (e: Groq.GroqException) {
            if (e.code == 401) throw e else null
        } ?: RecorderStore.DEFAULT_CHAT
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
     * What the chat knows of a recording: its transcript, or, when that is too long for one request
     * of the free plan, detailed notes of it (made once, piece by piece, then kept).
     * [onStep] says which piece is being read (done, total).
     */
    fun context(ctx: Context, key: String, model: String, transcript: String, onStep: (Int, Int) -> Unit, onWait: (Long) -> Unit, cancelled: () -> Boolean): String {
        val text = transcript.trim()
        if (text.length <= CONTEXT) return text
        val hash = MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
        val kept = File(File(ctx.cacheDir, "recorder-notes").apply { mkdirs() }, "$hash.txt")
        if (kept.isFile) return kept.readText()
        val system = ctx.getString(R.string.ml_rec_prompt_system)
        val pieces = chunks(text.split(Regex("\n\\s*\n")), PIECE)
        var done = 0
        var total = pieces.size
        fun ask(user: String): String {
            if (cancelled()) throw InterruptedException()
            onStep(done, total)
            val answer = Groq.chat(key, model, system, user, onWait, cancelled)
            done++
            return answer
        }
        var notes = pieces.mapIndexed { i, piece -> ask(ctx.getString(R.string.ml_rec_prompt_notes, i + 1, pieces.size) + "\n\n" + piece) }
        var rounds = 0
        while (notes.sumOf { it.length } > CONTEXT && notes.size > 1 && rounds++ < 4) {
            val groups = chunks(notes, PIECE)
            if (groups.size >= notes.size) break
            total += groups.size
            notes = groups.map { ask(ctx.getString(R.string.ml_rec_prompt_condense) + "\n\n" + it) }
        }
        val result = notes.joinToString("\n\n").take(CONTEXT * 2)
        kept.writeText(result)
        return result
    }
}
