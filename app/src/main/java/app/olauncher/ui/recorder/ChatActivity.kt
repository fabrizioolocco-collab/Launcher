package app.olauncher.ui.recorder

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import app.olauncher.R
import app.olauncher.helper.dpToPx
import app.olauncher.helper.showConfirmDialog
import app.olauncher.helper.showToast
import app.olauncher.ui.UiKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Questions to the AI about a recording, answered from its transcript. The conversation is kept
 * next to the recording as "name - Chat.md" (one of its write-ups), so it is there next time.
 */
class ChatActivity : AppCompatActivity() {

    private lateinit var audio: File
    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var status: TextView

    /** (role, text), roles "user" and "assistant". */
    private val history = mutableListOf<Pair<String, String>>()
    /** The transcript (or the notes of a long one), read at the first question. */
    private var context: String? = null
    private var busy = false
    @Volatile
    private var closed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path == null || !File(path).isFile) {
            finish()
            return
        }
        audio = File(path)
        GroqUsage.attach(this)
        setContentView(buildLayout())
        load()
    }

    override fun onDestroy() {
        closed = true
        super.onDestroy()
    }

    private fun chatFile() = RecorderStore.elaboration(audio, getString(R.string.ml_rec_chat))

    private fun buildLayout(): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8.dpToPx(), 0, 8.dpToPx(), 0)
        }
        ViewCompat.setOnApplyWindowInsetsListener(layout) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(v.paddingLeft, bars.top + 4.dpToPx(), v.paddingRight, maxOf(bars.bottom, ime.bottom) + 8.dpToPx())
            insets
        }
        val bar = UiKit.appBar(this, getString(R.string.ml_rec_chat), onBack = { finish() }, actions = listOf(
            UiKit.iconButton(this, R.drawable.ic_m_delete, getString(R.string.ml_rec_chat_clear)) { clear() },
        ))
        UiKit.setSubtitle(bar, audio.nameWithoutExtension)
        layout.addView(bar.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        messages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4.dpToPx(), 8.dpToPx(), 4.dpToPx(), 8.dpToPx())
        }
        scroll = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
            addView(messages)
        }
        layout.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        status = TextView(this, null, 0, R.style.TextSmall).apply {
            setTextColor(UiKit.secondary(this@ChatActivity))
            setPadding(12.dpToPx(), 4.dpToPx(), 12.dpToPx(), 6.dpToPx())
            isVisible = false
        }
        layout.addView(status)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        input = EditText(this).apply {
            UiKit.styleInput(this)
            hint = getString(R.string.ml_rec_chat_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 5
            textSize = 16f
        }
        val usage = UsageRing(this)
        GroqUsage.changes.observe(this) { usage.invalidate() }
        row.addView(usage, LinearLayout.LayoutParams(44.dpToPx(), 44.dpToPx()))
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 4.dpToPx() })
        row.addView(UiKit.iconButton(this, R.drawable.ic_m_send, getString(R.string.ml_rec_chat_send)) { send() })
        layout.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return layout
    }

    /** A message: the user's on the right in a filled bubble, the AI's on the left (its text can be selected). */
    private fun bubble(role: String, text: String, error: Boolean = false) {
        val mine = role == "user"
        val primary = UiKit.primary(this)
        val view = TextView(this, null, 0, R.style.TextSmall).apply {
            this.text = text
            textSize = 16f
            setLineSpacing(0f, 1.2f)
            setTextColor(if (error) 0xFFD9534F.toInt() else primary)
            setTextIsSelectable(true)
            setPadding(14.dpToPx(), 10.dpToPx(), 14.dpToPx(), 10.dpToPx())
            background = if (mine) GradientDrawable().apply {
                cornerRadius = 18.dpToPx().toFloat()
                setColor(ColorUtils.setAlphaComponent(primary, 0x26))
            } else UiKit.cardBackground(this@ChatActivity, 18)
        }
        messages.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = if (mine) Gravity.END else Gravity.START
            topMargin = 8.dpToPx()
            if (mine) marginStart = 40.dpToPx() else marginEnd = 24.dpToPx()
        })
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------------------------------------------------------------- the conversation's file

    private fun heading(role: String) = "### " + getString(if (role == "user") R.string.ml_rec_chat_you else R.string.ml_rec_chat_ai)

    private fun load() {
        history.clear()
        messages.removeAllViews()
        val file = chatFile()
        if (file.isFile) try {
            var role: String? = null
            val text = StringBuilder()
            fun flush() {
                val r = role
                if (r != null && text.isNotBlank()) history += r to text.toString().trim()
                text.setLength(0)
            }
            file.readLines().forEach { line ->
                when (line.trim()) {
                    heading("user") -> {
                        flush()
                        role = "user"
                    }
                    heading("assistant") -> {
                        flush()
                        role = "assistant"
                    }
                    else -> text.append(line).append('\n')
                }
            }
            flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (history.isEmpty()) bubble("assistant", getString(R.string.ml_rec_chat_intro))
        history.forEach { (role, text) -> bubble(role, text) }
    }

    private fun save() {
        try {
            chatFile().writeText(history.joinToString("\n\n") { (role, text) -> heading(role) + "\n\n" + text } + "\n")
            RecorderJobs.changed()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun clear() {
        if (busy || history.isEmpty()) return
        showConfirmDialog(getString(R.string.ml_rec_chat_clear), getString(R.string.ml_rec_chat_clear_confirm), getString(R.string.ml_explorer_delete)) {
            chatFile().delete()
            RecorderJobs.changed()
            load()
        }
    }

    // ---------------------------------------------------------------- asking

    private fun showStatus(text: String?) {
        status.text = text
        status.isVisible = text != null
    }

    private fun send() {
        val question = input.text.toString().trim()
        if (question.isEmpty() || busy) return
        val key = RecorderStore.apiKey(this)
        if (key.isEmpty()) return showToast(getString(R.string.ml_rec_key_missing))
        val transcript = RecorderStore.transcript(audio)
        if (!transcript.isFile) return showToast(getString(R.string.ml_rec_no_transcript))
        input.setText("")
        bubble("user", question)
        busy = true
        showStatus(getString(R.string.ml_rec_chat_thinking))
        val earlier = history.takeLast(HISTORY).map { (role, text) -> role to text.take(HISTORY_CHARS) }
        val known = context
        fun waiting(seconds: Long) = runOnUiThread { if (busy) showStatus(getString(R.string.ml_rec_waiting, seconds.toInt())) }
        lifecycleScope.launch {
            var failure: String? = null
            var about: String? = known
            val answer = withContext(Dispatchers.IO) {
                try {
                    val model = RecorderAi.model(this@ChatActivity, key)
                    val read = known ?: RecorderAi.context(this@ChatActivity, key, model, transcript.readText(), { done, total ->
                        runOnUiThread { if (busy) showStatus(getString(R.string.ml_rec_chat_reading, done + 1, total)) }
                    }, { waiting(it) }) { closed }
                    about = read
                    runOnUiThread { if (busy) showStatus(getString(R.string.ml_rec_chat_thinking)) }
                    val system = getString(R.string.ml_rec_chat_system) + "\n\n" + read
                    Groq.chatMessages(key, model, listOf("system" to system) + earlier + ("user" to question), { waiting(it) }) { closed }
                } catch (e: Groq.GroqException) {
                    failure = if (e.code == 401) getString(R.string.ml_rec_key_wrong) else e.message
                    null
                } catch (e: java.io.IOException) {
                    failure = getString(R.string.ml_rec_no_network)
                    null
                } catch (e: Exception) {
                    failure = e.message ?: e.javaClass.simpleName
                    null
                }
            }
            if (closed) return@launch
            busy = false
            showStatus(null)
            if (about != null) context = about
            if (answer.isNullOrBlank()) {
                bubble("assistant", getString(R.string.ml_rec_error_shown_plain, failure ?: getString(R.string.ml_rec_empty_answer)), error = true)
                // The question goes back in the field, to send it again
                if (input.text.isEmpty()) input.setText(question)
                return@launch
            }
            history += "user" to question
            history += "assistant" to answer
            bubble("assistant", answer)
            save()
        }
    }

    companion object {
        private const val EXTRA_PATH = "path"
        /** Earlier messages sent along with a question, and how much of each. */
        private const val HISTORY = 6
        private const val HISTORY_CHARS = 1500

        fun open(context: Context, audio: File) =
            context.startActivity(Intent(context, ChatActivity::class.java).putExtra(EXTRA_PATH, audio.absolutePath))
    }
}
