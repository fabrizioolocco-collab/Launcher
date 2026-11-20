package app.olauncher.ui.recorder

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * The text of a transcript and where each piece of it is in the audio: the ".json" kept next to
 * the ".txt" holds the timed segments (and the timed words, when the transcriber gives them), so
 * the recording's screen can follow the audio word by word and jump to a word that is tapped.
 */
object TranscriptText {

    /** Characters [from, to) of the text are heard from [start] to [end] (seconds). */
    class Span(val from: Int, val to: Int, val start: Double, val end: Double)

    /** The text, and the span of each segment in it. */
    class Built(val text: String, val spans: List<Span>)

    /** Paragraphs: a new one at a pause, or after a full stop once it's long enough. [times]: each starts with its time. */
    fun build(segments: List<Groq.Segment>, times: Boolean): Built {
        val text = StringBuilder()
        val spans = mutableListOf<Span>()
        var paragraphStart = 0
        var lastEnd = 0.0
        var first = true
        for (s in segments) {
            val paragraph = text.substring(paragraphStart)
            val ended = paragraph.trimEnd().lastOrNull()?.let { it in ".?!…" } == true
            if (first || s.start - lastEnd > 2.0 && ended || paragraph.length > 500 && ended) {
                if (!first) text.append("\n\n")
                if (times) {
                    val t = s.start.toLong()
                    text.append(String.format(Locale.US, "[%02d:%02d:%02d] ", t / 3600, t / 60 % 60, t % 60))
                }
                paragraphStart = text.length
            } else text.append(' ')
            val from = text.length
            text.append(s.text)
            spans += Span(from, text.length, s.start, s.end)
            lastEnd = s.end
            first = false
        }
        text.append("\n")
        return Built(text.toString(), spans)
    }

    fun save(file: File, segments: List<Groq.Segment>, words: List<Groq.Segment>) {
        fun array(list: List<Groq.Segment>) = JSONArray().also { out ->
            list.forEach { out.put(JSONArray().put(it.start).put(it.end).put(it.text)) }
        }
        file.writeText(JSONObject().put("segments", array(segments)).put("words", array(words)).toString())
    }

    /** The timed segments and words saved with a transcript, null when there are none. */
    fun load(file: File): Pair<List<Groq.Segment>, List<Groq.Segment>>? {
        if (!file.isFile) return null
        return try {
            val json = JSONObject(file.readText())
            fun list(name: String): List<Groq.Segment> {
                val array = json.optJSONArray(name) ?: return emptyList()
                return (0 until array.length()).map { i ->
                    val item = array.getJSONArray(i)
                    Groq.Segment(item.getDouble(0), item.getDouble(1), item.getString(2))
                }
            }
            val segments = list("segments")
            if (segments.isEmpty()) null else segments to list("words")
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The span of each word of [built]: with the times of [words] where they match the words of a
     * segment one to one, otherwise spread over the segment's time by their place in it.
     */
    fun wordSpans(built: Built, words: List<Groq.Segment>): List<Span> {
        val out = mutableListOf<Span>()
        var next = 0
        for (segment in built.spans) {
            // The words written in this segment
            val tokens = mutableListOf<IntRange>()
            var i = segment.from
            while (i < segment.to) {
                while (i < segment.to && built.text[i].isWhitespace()) i++
                val begin = i
                while (i < segment.to && !built.text[i].isWhitespace()) i++
                if (i > begin) tokens += begin until i
            }
            // The timed words heard during it
            while (next < words.size && words[next].start < segment.start - 0.05) next++
            var last = next
            while (last < words.size && words[last].start < segment.end - 0.005) last++
            if (tokens.isNotEmpty() && last - next == tokens.size) {
                tokens.forEachIndexed { k, token -> out += Span(token.first, token.last + 1, words[next + k].start, words[next + k].end) }
            } else {
                val length = (segment.to - segment.from).coerceAtLeast(1)
                val duration = (segment.end - segment.start).coerceAtLeast(0.0)
                tokens.forEach { token ->
                    out += Span(
                        token.first, token.last + 1,
                        segment.start + duration * (token.first - segment.from) / length,
                        segment.start + duration * (token.last + 1 - segment.from) / length,
                    )
                }
            }
            next = last
        }
        return out
    }
}
