package app.olauncher.ui.recorder

import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.core.content.edit
import app.olauncher.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings and files of the recorder. A recording is an audio file in a normal phone folder (so
 * it can be synced and seen by any app); its transcript is the ".txt" with the same name next to
 * it, its AI write-ups are the "name - Title.md" files. They are renamed, moved and deleted together.
 */
object RecorderStore {

    private const val PREFS = "app.minimalauncher.recorder"
    /** Apart from the rest: the key is left out of the settings backup. */
    private const val KEYS = "app.minimalauncher.groq"

    const val ENGINE_GROQ = "groq"
    const val DEFAULT_WHISPER = "whisper-large-v3"
    const val DEFAULT_CHAT = "llama-3.3-70b-versatile"
    val WHISPER_MODELS = listOf("whisper-large-v3", "whisper-large-v3-turbo")

    private val AUDIO = setOf("ogg", "opus", "m4a", "mp3", "wav", "flac", "webm", "mp4", "mpga", "mpeg", "aac", "amr", "3gp", "m4b", "wma")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun keys(ctx: Context) = ctx.getSharedPreferences(KEYS, Context.MODE_PRIVATE)

    /** The folder the recordings live in (Documents/Recordings until another one is chosen). */
    fun root(ctx: Context): File {
        prefs(ctx).getString("root", null)?.let { return File(it) }
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), ctx.getString(R.string.ml_rec_folder))
        setRoot(ctx, dir)
        return dir
    }

    fun setRoot(ctx: Context, dir: File) = prefs(ctx).edit { putString("root", dir.absolutePath) }

    fun apiKey(ctx: Context): String = keys(ctx).getString("key", "").orEmpty()
    fun setApiKey(ctx: Context, key: String) = keys(ctx).edit { putString("key", key.trim()) }

    fun whisperModel(ctx: Context): String = prefs(ctx).getString("whisper", DEFAULT_WHISPER) ?: DEFAULT_WHISPER
    fun setWhisperModel(ctx: Context, model: String) = prefs(ctx).edit { putString("whisper", model) }

    fun chatModel(ctx: Context): String = prefs(ctx).getString("chat", DEFAULT_CHAT) ?: DEFAULT_CHAT
    fun setChatModel(ctx: Context, model: String) = prefs(ctx).edit { putString("chat", model) }

    /** What makes the transcripts: [ENGINE_GROQ], or the id of a [LocalWhisper] model on the phone. */
    fun engine(ctx: Context): String = prefs(ctx).getString("engine", ENGINE_GROQ) ?: ENGINE_GROQ
    fun setEngine(ctx: Context, engine: String) = prefs(ctx).edit { putString("engine", engine) }

    /** The model on the phone that makes the transcripts, null when Groq does. */
    fun localModel(ctx: Context) = LocalWhisper.model(engine(ctx))

    /** The user chose the AI model (otherwise the best one Groq has is used). */
    fun chatModelChosen(ctx: Context) = prefs(ctx).contains("chat")

    /** Recordings whose transcription was started and hasn't ended: they go on when the recorder is opened again. */
    fun pending(ctx: Context): Set<String> = prefs(ctx).getStringSet("pending", emptySet()).orEmpty().toSet()

    @Synchronized
    fun setPending(ctx: Context, audio: File, pending: Boolean) {
        val all = pending(ctx).toMutableSet()
        if (pending) all += audio.absolutePath else all -= audio.absolutePath
        prefs(ctx).edit { putStringSet("pending", all) }
    }

    /** Language spoken in the recordings (ISO code), "" to let the model find it. */
    fun language(ctx: Context): String = prefs(ctx).getString("language", "").orEmpty()
    fun setLanguage(ctx: Context, code: String) = prefs(ctx).edit { putString("language", code) }

    /** Each paragraph of the transcript starts with its time ("[00:12:30]"). */
    fun timestamps(ctx: Context) = prefs(ctx).getBoolean("timestamps", false)
    fun setTimestamps(ctx: Context, on: Boolean) = prefs(ctx).edit { putBoolean("timestamps", on) }

    /** A recording is transcribed as soon as it ends. */
    fun autoTranscribe(ctx: Context) = prefs(ctx).getBoolean("auto", false)
    fun setAutoTranscribe(ctx: Context, on: Boolean) = prefs(ctx).edit { putBoolean("auto", on) }

    fun isAudio(file: File) = file.isFile && file.extension.lowercase() in AUDIO

    /** Ogg/Opus stays readable when the recording is cut short (older phones only write M4A). */
    fun extension() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "ogg" else "m4a"

    fun newRecording(dir: File): File {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.getDefault()).format(Date())
        var file = File(dir, "$stamp.${extension()}")
        var i = 2
        while (file.exists() || transcript(file).exists()) file = File(dir, "$stamp (${i++}).${extension()}")
        return file
    }

    fun transcript(audio: File) = File(audio.parentFile, audio.nameWithoutExtension + ".txt")

    /** Where each phrase and word of the transcript is in the audio (see [TranscriptText]). */
    fun timing(audio: File) = File(audio.parentFile, audio.nameWithoutExtension + ".json")

    fun elaboration(audio: File, title: String) = File(audio.parentFile, audio.nameWithoutExtension + " - " + title + ".md")

    /** Title of a write-up: its file name without the recording's name. */
    fun titleOf(audio: File, elaboration: File) = elaboration.nameWithoutExtension.removePrefix(audio.nameWithoutExtension + " - ")

    /** The AI write-ups of a recording, oldest first. */
    fun elaborations(audio: File): List<File> {
        val dir = audio.parentFile ?: return emptyList()
        val base = audio.nameWithoutExtension + " - "
        val files = dir.listFiles() ?: return emptyList()
        // "A - B - Summary.md" belongs to the recording "A - B", not to "A"
        val longer = files.filter { isAudio(it) && it.nameWithoutExtension.startsWith(base) }.map { it.nameWithoutExtension + " - " }
        return files.filter { f -> f.isFile && f.name.startsWith(base) && f.extension.lowercase() == "md" && longer.none { f.name.startsWith(it) } }
            .sortedBy { it.lastModified() }
    }

    /** The files that go with a recording: transcript and write-ups. */
    private fun companions(audio: File) = listOf(transcript(audio), timing(audio)).filter { it.isFile } + elaborations(audio)

    /** Renames the recording and what goes with it; the new audio file, or null when it can't be done. */
    fun rename(audio: File, name: String): File? {
        val clean = name.trim().replace('/', '_')
        if (clean.isEmpty()) return null
        val target = File(audio.parentFile, clean + "." + audio.extension)
        if (target.exists() || transcript(target).exists()) return null
        val others = companions(audio)
        val oldBase = audio.nameWithoutExtension
        if (!audio.renameTo(target)) return null
        others.forEach { it.renameTo(File(it.parentFile, clean + it.name.removePrefix(oldBase))) }
        return target
    }

    fun move(audio: File, dir: File): Boolean {
        if (File(dir, audio.name).exists()) return false
        val others = companions(audio)
        if (!moveFile(audio, File(dir, audio.name))) return false
        others.forEach { moveFile(it, File(dir, it.name)) }
        return true
    }

    /** Rename, or copy and delete when the folders are on different storages. */
    fun moveFile(from: File, to: File): Boolean {
        if (from.renameTo(to)) return true
        return try {
            // The date stays: it is the day the recording shows under
            val date = from.lastModified()
            from.copyRecursively(to) && (from.isDirectory || to.setLastModified(date) || true) && from.deleteRecursively()
        } catch (e: Exception) {
            false
        }
    }

    fun delete(audio: File): Boolean {
        companions(audio).forEach { it.delete() }
        return audio.delete()
    }

    /** "1:02:03" or "02:03". */
    fun clock(millis: Long): String {
        val s = millis / 1000
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
    }
}
