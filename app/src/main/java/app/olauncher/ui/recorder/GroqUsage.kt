package app.olauncher.ui.recorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.text.format.DateFormat
import android.view.View
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.MutableLiveData
import app.olauncher.R
import app.olauncher.helper.MenuEntry
import app.olauncher.helper.dpToPx
import app.olauncher.helper.showMenuDialog
import app.olauncher.ui.UiKit
import java.io.File

/**
 * How much of Groq's free plan is left. For the AI (write-ups, chat) it is what Groq itself said
 * with its last answer: requests left today, tokens left this minute. For the transcripts it is a
 * count kept here of the audio sent in the last hour and day (right as long as the key is used
 * by this app only).
 */
object GroqUsage {

    /** Free plan: seconds of audio an hour and a day. */
    const val AUDIO_HOUR = 7200
    const val AUDIO_DAY = 28800

    private const val PREFS = "app.minimalauncher.recorder"

    /** What Groq said after a request: the limits, what is left and when each starts again. */
    class Snapshot(
        val model: String, val at: Long,
        val requestLimit: Int, val requestsLeft: Int, val requestReset: Long,
        val tokenLimit: Int, val tokensLeft: Int, val tokenReset: Long,
    ) {
        /** Left now: everything again once the reset time is past. */
        fun requestsNow(now: Long) = if (requestReset in 1..now) requestLimit else requestsLeft
        fun tokensNow(now: Long) = if (tokenReset in 1..now) tokenLimit else tokensLeft
    }

    /** Goes up when there is something new to show. */
    val changes = MutableLiveData(0)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Starts listening to Groq's answers (once is enough, more is harmless). */
    fun attach(context: Context) {
        val app = context.applicationContext
        Groq.listener = { kind, model, headers ->
            val now = System.currentTimeMillis()
            fun number(name: String) = headers[name]?.toDoubleOrNull()?.toInt() ?: -1
            fun reset(name: String) = headers[name]?.let { duration(it) }?.let { now + it } ?: 0L
            val text = listOf(
                model.orEmpty().replace('|', ' '), now,
                number("limit-requests"), number("remaining-requests"), reset("reset-requests"),
                number("limit-tokens"), number("remaining-tokens"), reset("reset-tokens"),
            ).joinToString("|")
            prefs(app).edit { putString("usage_$kind", text) }
            changes.postValue((changes.value ?: 0) + 1)
        }
    }

    /** "2m59.56s", "7.66s", "1h2m3s", "250ms" as milliseconds. */
    private fun duration(text: String): Long? {
        var total = 0.0
        var found = false
        Regex("([0-9.]+)(ms|h|m|s)").findAll(text).forEach { match ->
            val value = match.groupValues[1].toDoubleOrNull() ?: return@forEach
            found = true
            total += when (match.groupValues[2]) {
                "h" -> value * 3_600_000
                "m" -> value * 60_000
                "s" -> value * 1000
                else -> value
            }
        }
        return if (found) total.toLong() else null
    }

    /** What Groq last said for the AI ([kind] "chat") or the transcripts ("audio"), null when nothing yet. */
    fun snapshot(ctx: Context, kind: String): Snapshot? {
        val parts = prefs(ctx).getString("usage_$kind", null)?.split('|') ?: return null
        if (parts.size < 8) return null
        return try {
            Snapshot(parts[0], parts[1].toLong(), parts[2].toInt(), parts[3].toInt(), parts[4].toLong(), parts[5].toInt(), parts[6].toInt(), parts[7].toLong())
        } catch (e: Exception) {
            null
        }
    }

    /** Length of an audio file in seconds (0 when it can't be told). */
    fun seconds(file: File): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            ((retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000).toInt()
        } catch (e: Exception) {
            0
        } finally {
            try {
                retriever.release()
            } catch (ignored: Exception) {
            }
        }
    }

    private fun audioLog(ctx: Context): List<Pair<Long, Int>> {
        val since = System.currentTimeMillis() - 24 * 3_600_000L
        return prefs(ctx).getString("usage_audio_log", "").orEmpty().split(';').mapNotNull { entry ->
            val time = entry.substringBefore(':').toLongOrNull() ?: return@mapNotNull null
            val length = entry.substringAfter(':').toIntOrNull() ?: return@mapNotNull null
            if (time < since) null else time to length
        }
    }

    /** Counts audio sent to Groq for a transcript. */
    @Synchronized
    fun addAudio(ctx: Context, seconds: Int) {
        if (seconds <= 0) return
        val log = audioLog(ctx) + (System.currentTimeMillis() to seconds)
        prefs(ctx).edit { putString("usage_audio_log", log.joinToString(";") { "${it.first}:${it.second}" }) }
        changes.postValue((changes.value ?: 0) + 1)
    }

    /** The day Gemini counts its requests by (it starts again at midnight in California). */
    private fun geminiDay() = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("America/Los_Angeles") }.format(java.util.Date())

    /** Counts a request made to Gemini (it doesn't say how many are left: the ones made today are counted here). */
    @Synchronized
    fun addGemini(ctx: Context) {
        val day = geminiDay()
        val made = if (prefs(ctx).getString("usage_gemini_day", "") == day) prefs(ctx).getInt("usage_gemini_count", 0) else 0
        prefs(ctx).edit { putString("usage_gemini_day", day).putInt("usage_gemini_count", made + 1) }
        changes.postValue((changes.value ?: 0) + 1)
    }

    fun geminiToday(ctx: Context) = if (prefs(ctx).getString("usage_gemini_day", "") == geminiDay()) prefs(ctx).getInt("usage_gemini_count", 0) else 0

    /** Seconds of audio sent in the last hour and in the last 24 hours. */
    fun audioUsed(ctx: Context): Pair<Int, Int> {
        val log = audioLog(ctx)
        val hourAgo = System.currentTimeMillis() - 3_600_000L
        return log.filter { it.first >= hourAgo }.sumOf { it.second } to log.sumOf { it.second }
    }

    /** The part left (0..1) of the scarcest thing; null when nothing is known yet. */
    fun left(ctx: Context): Float? {
        val now = System.currentTimeMillis()
        val parts = mutableListOf<Float>()
        // Groq's word on the AI counts only while Groq is the AI
        listOf("chat", "audio").filter { it != "chat" || RecorderAi.provider(ctx) == RecorderAi.GROQ }.forEach { kind ->
            snapshot(ctx, kind)?.takeIf { it.requestLimit > 0 }?.let { parts += it.requestsNow(now).toFloat() / it.requestLimit }
        }
        val (hour, day) = audioUsed(ctx)
        if (day > 0) {
            parts += 1f - hour.toFloat() / AUDIO_HOUR
            parts += 1f - day.toFloat() / AUDIO_DAY
        }
        return parts.minOrNull()?.coerceIn(0f, 1f)
    }

    /** The counters, as a list. */
    fun show(ctx: Context) {
        val now = System.currentTimeMillis()
        fun time(at: Long) = DateFormat.getTimeFormat(ctx).format(at)
        fun count(n: Int) = if (n >= 10_000) "${n / 1000}k" else n.toString()
        val entries = mutableListOf<MenuEntry>()
        val gemini = RecorderAi.provider(ctx) == RecorderAi.GEMINI
        if (gemini || geminiToday(ctx) > 0) {
            entries += MenuEntry(ctx.getString(R.string.ml_rec_usage_gemini_made), section = ctx.getString(R.string.ml_rec_usage_gemini), value = geminiToday(ctx).toString())
            entries += MenuEntry(ctx.getString(R.string.ml_rec_usage_gemini_note))
        }
        val chat = snapshot(ctx, "chat")
        if (gemini && chat == null) {
            // Groq isn't used for the AI: nothing to say about it
        } else if (chat == null) entries += MenuEntry(ctx.getString(R.string.ml_rec_usage_none), section = ctx.getString(R.string.ml_rec_usage_ai))
        else {
            if (chat.requestLimit > 0) entries += MenuEntry(ctx.getString(R.string.ml_rec_usage_requests), section = ctx.getString(R.string.ml_rec_usage_ai),
                value = ctx.getString(R.string.ml_rec_usage_left, count(chat.requestsNow(now)), count(chat.requestLimit)))
            if (chat.tokenLimit > 0) entries += MenuEntry(ctx.getString(R.string.ml_rec_usage_tokens),
                value = ctx.getString(R.string.ml_rec_usage_left, count(chat.tokensNow(now)), count(chat.tokenLimit)))
            if (chat.requestReset > now) entries += MenuEntry(ctx.getString(R.string.ml_rec_usage_reset), value = time(chat.requestReset))
            entries += MenuEntry(ctx.getString(R.string.ml_rec_usage_model), value = chat.model.ifEmpty { "?" })
            entries += MenuEntry(ctx.getString(R.string.ml_rec_usage_updated), value = time(chat.at))
        }
        val section = ctx.getString(R.string.ml_rec_usage_transcripts)
        if (RecorderStore.localModel(ctx) != null) entries += MenuEntry(ctx.getString(R.string.ml_rec_usage_local), section = section)
        val (hour, day) = audioUsed(ctx)
        entries += MenuEntry(ctx.getString(R.string.ml_rec_usage_hour), section = if (entries.any { it.section == section }) null else section,
            value = ctx.getString(R.string.ml_rec_usage_minutes, (AUDIO_HOUR - hour).coerceAtLeast(0) / 60, AUDIO_HOUR / 60))
        entries += MenuEntry(ctx.getString(R.string.ml_rec_usage_day),
            value = ctx.getString(R.string.ml_rec_usage_minutes, (AUDIO_DAY - day).coerceAtLeast(0) / 60, AUDIO_DAY / 60))
        snapshot(ctx, "audio")?.takeIf { it.requestLimit > 0 }?.let {
            entries += MenuEntry(ctx.getString(R.string.ml_rec_usage_requests),
                value = ctx.getString(R.string.ml_rec_usage_left, count(it.requestsNow(now)), count(it.requestLimit)))
        }
        ctx.showMenuDialog(ctx.getString(R.string.ml_rec_usage), entries)
    }
}

/** The small ring that shows how much of Groq's free plan is left; a tap lists the counters. */
class UsageRing(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val oval = RectF()

    init {
        contentDescription = context.getString(R.string.ml_rec_usage)
        isClickable = true
        isFocusable = true
        background = UiKit.roundRipple(context)
        setOnClickListener { GroqUsage.show(context) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(44.dpToPx(), 44.dpToPx())

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val primary = UiKit.primary(context)
        val stroke = 3.dpToPx().toFloat()
        val radius = 10.dpToPx().toFloat()
        oval.set(width / 2f - radius, height / 2f - radius, width / 2f + radius, height / 2f + radius)
        paint.strokeWidth = stroke
        paint.color = ColorUtils.setAlphaComponent(primary, 0x30)
        canvas.drawOval(oval, paint)
        // Nothing known yet: just the faint ring
        val left = GroqUsage.left(context) ?: return
        paint.color = if (left < 0.15f) 0xFFD9534F.toInt() else primary
        if (left > 0f) canvas.drawArc(oval, -90f, 360f * left, false, paint)
    }
}
