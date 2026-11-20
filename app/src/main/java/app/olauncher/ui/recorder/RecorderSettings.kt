package app.olauncher.ui.recorder

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.InputType
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.olauncher.R
import app.olauncher.helper.MenuEntry
import app.olauncher.helper.showConfirmDialog
import app.olauncher.helper.showMenuDialog
import app.olauncher.helper.showTextInputDialog
import app.olauncher.helper.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The recorder's settings, the same from each of its screens: the whole menu, and the two quick
 * choices that the screens also offer on their own (what makes the transcripts, what the AI is).
 */
object RecorderSettings {

    /** "" lets the model find the language. */
    private val LANGUAGES = listOf("", "it", "en", "fr", "de", "es", "pt")

    private fun open(activity: AppCompatActivity, url: String) = try {
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        activity.showToast(activity.getString(R.string.ml_rec_cannot))
    }

    private fun mark(label: String, chosen: Boolean) = if (chosen) "$label ✓" else label

    private fun keyValue(activity: AppCompatActivity, key: String) = if (key.isEmpty()) activity.getString(R.string.ml_rec_key_none) else "…" + key.takeLast(4)

    private fun languageName(activity: AppCompatActivity, code: String) =
        if (code.isEmpty()) activity.getString(R.string.ml_rec_language_auto)
        else java.util.Locale(code).getDisplayLanguage(java.util.Locale.getDefault()).replaceFirstChar { it.uppercase() }

    private fun chosenModel(activity: AppCompatActivity): String = when {
        RecorderAi.provider(activity) == RecorderAi.GEMINI -> RecorderStore.geminiModel(activity)
        RecorderStore.chatModelChosen(activity) -> RecorderStore.chatModel(activity)
        else -> ""
    }

    /** What makes the transcripts, in a few words: "Groq", or the model on the phone. */
    fun engineShort(activity: AppCompatActivity): String =
        RecorderStore.localModel(activity)?.let { activity.getString(R.string.ml_rec_engine_phone, it.label) } ?: "Groq"

    /** The AI in a few words: "Gemini · automatic", "Groq · llama-…". */
    fun aiShort(activity: AppCompatActivity): String =
        (if (RecorderAi.provider(activity) == RecorderAi.GEMINI) "Gemini" else "Groq") + " · " +
                chosenModel(activity).ifEmpty { activity.getString(R.string.ml_rec_language_auto).lowercase() }

    /** The whole menu. [onPickRoot] (the list of recordings only) chooses the folder they are kept in. */
    fun show(activity: AppCompatActivity, onPickRoot: (() -> Unit)? = null, onChanged: () -> Unit = {}) {
        val groqKey = RecorderStore.apiKey(activity)
        val geminiKey = RecorderStore.geminiKey(activity)
        val local = RecorderStore.localModel(activity) != null
        val entries = mutableListOf(
            // Transcripts
            MenuEntry(activity.getString(R.string.ml_rec_engine), section = activity.getString(R.string.ml_rec_section_transcript), value = engineShort(activity)) {
                pickEngine(activity, onChanged)
            },
        )
        if (!local) entries += MenuEntry(activity.getString(R.string.ml_rec_model_whisper), value = RecorderStore.whisperModel(activity)) {
            activity.showMenuDialog(activity.getString(R.string.ml_rec_model_whisper), RecorderStore.WHISPER_MODELS.map { model ->
                MenuEntry(mark(model, model == RecorderStore.whisperModel(activity))) { RecorderStore.setWhisperModel(activity, model) }
            })
        }
        entries += MenuEntry(activity.getString(R.string.ml_rec_language), value = languageName(activity, RecorderStore.language(activity))) {
            activity.showMenuDialog(activity.getString(R.string.ml_rec_language), LANGUAGES.map { code ->
                MenuEntry(mark(languageName(activity, code), code == RecorderStore.language(activity))) { RecorderStore.setLanguage(activity, code) }
            })
        }
        entries += MenuEntry(activity.getString(R.string.ml_rec_timestamps), checked = RecorderStore.timestamps(activity), onToggle = { on ->
            RecorderStore.setTimestamps(activity, on)
            true
        })
        entries += MenuEntry(activity.getString(R.string.ml_rec_auto), checked = RecorderStore.autoTranscribe(activity), onToggle = { on ->
            RecorderStore.setAutoTranscribe(activity, on)
            true
        })
        // AI
        entries += MenuEntry(activity.getString(R.string.ml_rec_ai_with), section = activity.getString(R.string.ml_rec_section_ai),
            value = if (RecorderAi.provider(activity) == RecorderAi.GEMINI) "Gemini" else "Groq") { pickAi(activity, onChanged) }
        entries += MenuEntry(activity.getString(R.string.ml_rec_model_chat), value = chosenModel(activity).ifEmpty { activity.getString(R.string.ml_rec_language_auto) }) {
            pickModel(activity, onChanged)
        }
        // Keys
        entries += MenuEntry(activity.getString(R.string.ml_rec_gemini_key), section = activity.getString(R.string.ml_rec_section_keys), value = keyValue(activity, geminiKey)) {
            activity.showTextInputDialog(activity.getString(R.string.ml_rec_gemini_key), geminiKey, "AIza…", activity.getString(R.string.ml_save), allowBlank = true,
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) {
                RecorderStore.setGeminiKey(activity, it)
                onChanged()
            }
        }
        entries += MenuEntry(activity.getString(R.string.ml_rec_gemini_key_get)) { open(activity, "https://aistudio.google.com/apikey") }
        entries += MenuEntry(activity.getString(R.string.ml_rec_key), value = keyValue(activity, groqKey)) {
            activity.showTextInputDialog(activity.getString(R.string.ml_rec_key), groqKey, "gsk_…", activity.getString(R.string.ml_save), allowBlank = true,
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) {
                RecorderStore.setApiKey(activity, it)
                onChanged()
            }
        }
        entries += MenuEntry(activity.getString(R.string.ml_rec_key_get)) { open(activity, "https://console.groq.com/keys") }
        // Files and phone
        if (onPickRoot != null) entries += MenuEntry(activity.getString(R.string.ml_rec_root), section = activity.getString(R.string.ml_rec_section_files),
            value = RecorderStore.root(activity).name) { onPickRoot() }
        entries += MenuEntry(activity.getString(R.string.ml_rec_battery), section = if (onPickRoot == null) activity.getString(R.string.ml_rec_section_files) else null) {
            try {
                activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: Exception) {
                activity.showToast(activity.getString(R.string.ml_rec_cannot))
            }
        }
        activity.showMenuDialog(activity.getString(R.string.ml_rec_settings), entries)
    }

    /** Groq, or a model on the phone (downloaded the first time it's chosen); a downloaded one can be deleted from here. */
    fun pickEngine(activity: AppCompatActivity, onChanged: () -> Unit = {}) {
        val current = RecorderStore.engine(activity)
        val entries = mutableListOf(MenuEntry(mark(activity.getString(R.string.ml_rec_engine_groq), current == RecorderStore.ENGINE_GROQ)) {
            RecorderStore.setEngine(activity, RecorderStore.ENGINE_GROQ)
            onChanged()
        })
        LocalWhisper.MODELS.forEach { model ->
            val downloaded = LocalWhisper.isDownloaded(activity, model)
            val busy = RecorderJobs.isBusy(LocalWhisper.file(activity, model))
            val state = activity.getString(if (busy) R.string.ml_rec_downloading else if (downloaded) R.string.ml_rec_model_ready else R.string.ml_rec_model_missing)
            entries += MenuEntry(mark(activity.getString(R.string.ml_rec_engine_local, model.label), current == model.id), value = "${model.megabytes} MB · $state") {
                when {
                    !LocalWhisper.supported() -> activity.showToast(activity.getString(R.string.ml_rec_local_unsupported))
                    downloaded || busy -> {
                        RecorderStore.setEngine(activity, model.id)
                        onChanged()
                    }
                    else -> activity.showConfirmDialog(
                        activity.getString(R.string.ml_rec_engine_local, model.label), activity.getString(R.string.ml_rec_model_download, model.megabytes),
                        activity.getString(R.string.ml_rec_download),
                    ) {
                        RecorderStore.setEngine(activity, model.id)
                        RecorderJobs.download(activity, model)
                        onChanged()
                    }
                }
            }
        }
        val kept = LocalWhisper.MODELS.filter { LocalWhisper.isDownloaded(activity, it) && !RecorderJobs.isBusy(LocalWhisper.file(activity, it)) }
        if (kept.isNotEmpty()) entries += MenuEntry(activity.getString(R.string.ml_rec_models_delete), destructive = true) {
            activity.showMenuDialog(activity.getString(R.string.ml_rec_models_delete), kept.map { model ->
                MenuEntry("${model.label} (${model.megabytes} MB)", destructive = true) {
                    LocalWhisper.file(activity, model).delete()
                    if (RecorderStore.engine(activity) == model.id) RecorderStore.setEngine(activity, RecorderStore.ENGINE_GROQ)
                    onChanged()
                }
            })
        }
        activity.showMenuDialog(activity.getString(R.string.ml_rec_engine), entries)
    }

    /** Gemini or Groq for the write-ups and the chat, and (from the same list) the model. */
    fun pickAi(activity: AppCompatActivity, onChanged: () -> Unit = {}) {
        val gemini = RecorderAi.provider(activity) == RecorderAi.GEMINI
        fun choose(provider: String) {
            RecorderStore.setAiProvider(activity, provider)
            onChanged()
            // No key yet: said at once, not at the first question
            RecorderAi.blocker(activity)?.let { activity.showToast(it, android.widget.Toast.LENGTH_LONG) }
        }
        activity.showMenuDialog(activity.getString(R.string.ml_rec_ai_with), listOf(
            MenuEntry(mark(activity.getString(R.string.ml_rec_ai_gemini), gemini)) { choose(RecorderAi.GEMINI) },
            MenuEntry(mark(activity.getString(R.string.ml_rec_ai_groq), !gemini)) { choose(RecorderAi.GROQ) },
            MenuEntry(activity.getString(R.string.ml_rec_model_chat), section = activity.getString(R.string.ml_rec_model_chat),
                value = chosenModel(activity).ifEmpty { activity.getString(R.string.ml_rec_language_auto) }) { pickModel(activity, onChanged) },
        ))
    }

    /** The text models the chosen AI offers right now (they change over time); "Automatic" lets the app pick. */
    fun pickModel(activity: AppCompatActivity, onChanged: () -> Unit = {}) {
        RecorderAi.blocker(activity)?.let { return activity.showToast(it, android.widget.Toast.LENGTH_LONG) }
        val gemini = RecorderAi.provider(activity) == RecorderAi.GEMINI
        activity.lifecycleScope.launch {
            var failure: String? = null
            val models = withContext(Dispatchers.IO) {
                try {
                    RecorderAi.models(activity)
                } catch (e: Groq.GroqException) {
                    failure = e.message
                    null
                } catch (e: Exception) {
                    null
                }
            }
            if (models.isNullOrEmpty()) return@launch activity.showToast(failure ?: activity.getString(R.string.ml_rec_no_network), android.widget.Toast.LENGTH_LONG)
            val current = chosenModel(activity)
            val entries = mutableListOf(MenuEntry(mark(activity.getString(R.string.ml_rec_language_auto), current.isEmpty())) {
                if (gemini) RecorderStore.setGeminiModel(activity, "") else RecorderStore.clearChatModel(activity)
                onChanged()
            })
            entries += models.map { model ->
                MenuEntry(mark(model, model == current)) {
                    if (gemini) RecorderStore.setGeminiModel(activity, model) else RecorderStore.setChatModel(activity, model)
                    onChanged()
                }
            }
            activity.showMenuDialog(activity.getString(R.string.ml_rec_model_chat), entries)
        }
    }
}
