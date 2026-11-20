package app.olauncher.ui.recorder

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import app.olauncher.R
import app.olauncher.helper.MenuEntry
import app.olauncher.helper.dpToPx
import app.olauncher.helper.pillButton
import app.olauncher.helper.showConfirmDialog
import app.olauncher.helper.showMenuDialog
import app.olauncher.helper.showTextInputDialog
import app.olauncher.helper.showToast
import app.olauncher.ui.UiKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A recording: its player, its transcript and its AI write-ups (one chip each), with the tools to
 * make them, copy them and share them.
 */
class RecordingActivity : AppCompatActivity() {

    private lateinit var audio: File
    /** The text shown: the transcript or a write-up. */
    private lateinit var shown: File

    private lateinit var playButton: ImageView
    private lateinit var seek: SeekBar
    private lateinit var time: TextView
    private lateinit var chips: LinearLayout
    private lateinit var status: TextView
    private lateinit var working: WorkingBar
    private lateinit var text: TextView
    private lateinit var scroll: ScrollView

    private var player: MediaPlayer? = null
    private var seeking = false
    private val ticker = Handler(Looper.getMainLooper())
    private var content = ""

    /** The words of the transcript shown and when each is heard; empty when the text can't follow the audio. */
    private var words: List<TranscriptText.Span> = emptyList()
    private var spoken = -1
    private var spokenMark: Any? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path == null || !File(path).isFile) {
            finish()
            return
        }
        audio = File(path)
        GroqUsage.attach(this)
        shown = RecorderStore.transcript(audio)
        setContentView(buildLayout())
        preparePlayer()
        RecorderJobs.running.observe(this) { showStatus() }
        RecorderJobs.errors.observe(this) { showStatus() }
        RecorderJobs.changes.observe(this) { reload() }
    }

    override fun onStop() {
        super.onStop()
        pause()
    }

    override fun onDestroy() {
        ticker.removeCallbacksAndMessages(null)
        try {
            player?.release()
        } catch (ignored: Exception) {
        }
        player = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- layout

    private fun buildLayout(): View {
        val primary = UiKit.primary(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8.dpToPx(), 0, 8.dpToPx(), 0)
        }
        ViewCompat.setOnApplyWindowInsetsListener(layout) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top + 4.dpToPx(), v.paddingRight, bars.bottom + 8.dpToPx())
            insets
        }
        val bar = UiKit.appBar(this, audio.nameWithoutExtension, onBack = { finish() }, actions = listOf(
            UiKit.iconButton(this, R.drawable.ic_m_more, getString(R.string.ml_explorer_more)) { showMenu() },
        ))
        layout.addView(bar.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Player
        val playerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UiKit.cardBackground(this@RecordingActivity, 18)
            setPadding(4.dpToPx(), 4.dpToPx(), 14.dpToPx(), 4.dpToPx())
        }
        playButton = UiKit.iconButton(this, R.drawable.ic_m_play, getString(R.string.ml_rec_play)) { togglePlay() }
        playerRow.addView(playButton)
        seek = SeekBar(this).apply {
            progressTintList = ColorStateList.valueOf(primary)
            thumbTintList = ColorStateList.valueOf(primary)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) showTime(progress)
                }

                override fun onStartTrackingTouch(bar: SeekBar?) {
                    seeking = true
                }

                override fun onStopTrackingTouch(bar: SeekBar?) {
                    seeking = false
                    try {
                        player?.seekTo(progress)
                    } catch (ignored: Exception) {
                    }
                    follow(progress, scrollTo = true)
                }
            })
        }
        playerRow.addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        time = TextView(this, null, 0, R.style.TextSmall).apply {
            textSize = 12.5f
            setTextColor(UiKit.secondary(this@RecordingActivity))
        }
        playerRow.addView(time)
        layout.addView(playerRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(4.dpToPx(), 4.dpToPx(), 4.dpToPx(), 0)
        })

        // Transcript and write-ups
        chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        layout.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(4.dpToPx(), 0, 4.dpToPx(), 0)
            addView(chips)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 10.dpToPx()
        })
        status = TextView(this, null, 0, R.style.TextSmall).apply {
            setTextColor(primary)
            setPadding(12.dpToPx(), 10.dpToPx(), 12.dpToPx(), 0)
            isVisible = false
            setOnClickListener {
                // An error goes away when tapped; something being made can be stopped
                if (!RecorderJobs.isBusy(audio)) RecorderJobs.setError(audio, null)
                else showConfirmDialog(getString(R.string.ml_rec_app), getString(R.string.ml_rec_cancel_jobs), getString(R.string.ml_rec_stop)) {
                    RecorderJobs.cancel(this@RecordingActivity)
                }
            }
        }
        layout.addView(status)
        working = WorkingBar(this).apply { isVisible = false }
        layout.addView(working, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 6.dpToPx()).apply {
            setMargins(12.dpToPx(), 8.dpToPx(), 12.dpToPx(), 2.dpToPx())
        })
        text = TextView(this, null, 0, R.style.TextSmall).apply {
            textSize = 16f
            setTextColor(primary)
            setLineSpacing(0f, 1.25f)
            setTextIsSelectable(true)
            setPadding(12.dpToPx(), 12.dpToPx(), 12.dpToPx(), 24.dpToPx())
        }
        // A tap on a word plays from there (a long press still selects text)
        var downX = 0f
        var downY = 0f
        text.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
                    val still = Math.abs(event.x - downX) < slop && Math.abs(event.y - downY) < slop
                    if (still && event.eventTime - event.downTime < 300 && words.isNotEmpty()) playFrom(text.getOffsetForPosition(event.x, event.y))
                }
            }
            false
        }
        scroll = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(text)
        }
        layout.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val tools = UiKit.toolBar(this)
        listOf(
            Triple(R.drawable.ic_m_text, R.string.ml_rec_transcribe) { transcribe() },
            Triple(R.drawable.ic_m_star, R.string.ml_rec_ai) { showAiMenu() },
            Triple(R.drawable.ic_m_copy, R.string.ml_explorer_copy) { copy() },
            Triple(R.drawable.ic_m_share, R.string.ml_explorer_share) { share() },
        ).forEach { (icon, label, action) ->
            tools.addView(UiKit.toolButton(this, icon, getString(label)) { action() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        // Bottom left: what is left of Groq's free plan
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val usage = UsageRing(this)
        GroqUsage.changes.observe(this) { usage.invalidate() }
        bottom.addView(usage, LinearLayout.LayoutParams(44.dpToPx(), 44.dpToPx()))
        bottom.addView(tools, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 4.dpToPx() })
        layout.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 8.dpToPx(), 4.dpToPx(), 0)
        })
        return layout
    }

    // ---------------------------------------------------------------- player

    private fun preparePlayer() {
        try {
            player = MediaPlayer().apply {
                setDataSource(audio.absolutePath)
                prepare()
                setOnCompletionListener {
                    ticker.removeCallbacksAndMessages(null)
                    playButton.setImageResource(R.drawable.ic_m_play)
                    seek.progress = 0
                    showTime(0)
                }
            }
            seek.max = player?.duration ?: 0
        } catch (e: Exception) {
            e.printStackTrace()
            player = null
            seek.isEnabled = false
        }
        showTime(0)
    }

    private fun showTime(position: Int) {
        val total = try {
            player?.duration ?: 0
        } catch (e: Exception) {
            0
        }
        time.text = RecorderStore.clock(position.toLong()) + " / " + RecorderStore.clock(total.toLong())
    }

    private fun togglePlay() {
        val p = player ?: return showToast(getString(R.string.ml_rec_cannot))
        try {
            if (p.isPlaying) return pause()
            p.start()
            playButton.setImageResource(R.drawable.ic_m_pause)
            tick()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun pause() {
        ticker.removeCallbacksAndMessages(null)
        try {
            player?.takeIf { it.isPlaying }?.pause()
        } catch (ignored: Exception) {
        }
        if (::playButton.isInitialized) playButton.setImageResource(R.drawable.ic_m_play)
    }

    private fun tick() {
        val p = player ?: return
        try {
            if (!p.isPlaying) return
            if (!seeking) {
                seek.progress = p.currentPosition
                showTime(p.currentPosition)
                follow(p.currentPosition, scrollTo = true)
            }
        } catch (e: Exception) {
            return
        }
        ticker.postDelayed({ tick() }, if (words.isEmpty()) 300 else 100)
    }

    // ---------------------------------------------------------------- text that follows the audio

    /** Marks the word heard at [position] (ms); [scrollTo] keeps it in sight. */
    private fun follow(position: Int, scrollTo: Boolean) {
        if (words.isEmpty()) return
        val seconds = position / 1000.0
        // The last word that has started
        var low = 0
        var high = words.size - 1
        var index = -1
        while (low <= high) {
            val mid = (low + high) / 2
            if (words[mid].start <= seconds) {
                index = mid
                low = mid + 1
            } else high = mid - 1
        }
        if (index == spoken) return
        spoken = index
        val shownText = text.text as? android.text.Spannable ?: return
        spokenMark?.let { shownText.removeSpan(it) }
        spokenMark = null
        val word = words.getOrNull(index) ?: return
        if (word.to > shownText.length) return
        val mark = android.text.style.BackgroundColorSpan(androidx.core.graphics.ColorUtils.setAlphaComponent(UiKit.primary(this), 0x45))
        shownText.setSpan(mark, word.from, word.to, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spokenMark = mark
        if (!scrollTo) return
        val layout = text.layout ?: return
        val y = text.top + layout.getLineTop(layout.getLineForOffset(word.from))
        val margin = 48.dpToPx()
        if (y < scroll.scrollY + margin || y > scroll.scrollY + scroll.height - 2 * margin) scroll.smoothScrollTo(0, (y - scroll.height / 3).coerceAtLeast(0))
    }

    /** Plays from the word at character [offset] of the text. */
    private fun playFrom(offset: Int) {
        val p = player ?: return
        val word = words.lastOrNull { it.from <= offset } ?: return
        try {
            p.seekTo((word.start * 1000).toInt())
            if (!p.isPlaying) togglePlay()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ---------------------------------------------------------------- texts

    private fun reload() {
        if (!audio.isFile) {
            finish()
            return
        }
        val transcript = RecorderStore.transcript(audio)
        val writeUps = RecorderStore.elaborations(audio)
        // What was just made comes to the front
        RecorderJobs.produced?.takeIf { it.first == audio.absolutePath }?.let {
            RecorderJobs.produced = null
            shown = File(it.second)
        }
        if (shown != transcript && !shown.isFile) shown = transcript
        chips.removeAllViews()
        val primary = UiKit.primary(this)
        (listOf(transcript) + writeUps).forEach { file ->
            val label = if (file == transcript) getString(R.string.ml_rec_transcript) else RecorderStore.titleOf(audio, file)
            val chip = pillButton(this, label, filled = file == shown, color = primary) {
                shown = file
                reload()
            }.apply {
                textSize = 14f
                setPadding(16.dpToPx(), 0, 16.dpToPx(), 0)
                // A long press on a write-up offers to delete it
                if (file != transcript) setOnLongClickListener {
                    showConfirmDialog(getString(R.string.ml_explorer_delete), getString(R.string.ml_explorer_delete_confirm, label), getString(R.string.ml_explorer_delete)) {
                        file.delete()
                        reload()
                    }
                    true
                }
            }
            chips.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 36.dpToPx()).apply { marginEnd = 8.dpToPx() })
        }
        val file = shown
        lifecycleScope.launch {
            var timed: List<TranscriptText.Span> = emptyList()
            val read = withContext(Dispatchers.IO) {
                try {
                    val body = if (file.isFile) file.readText() else ""
                    // The transcript as it was made (not edited since) can follow the audio
                    if (file == transcript && body.isNotEmpty()) TranscriptText.load(RecorderStore.timing(audio))?.let { (segments, spokenWords) ->
                        val starts = body.startsWith("[")
                        val built = TranscriptText.build(segments, starts)
                        if (built.text.trimEnd() == body.trimEnd()) timed = TranscriptText.wordSpans(built, spokenWords)
                    }
                    body
                } catch (e: Exception) {
                    ""
                }
            }
            if (file != shown) return@launch
            val changed = read != content
            content = read
            words = timed
            spoken = -1
            spokenMark = null
            text.setText(read.ifEmpty { getString(R.string.ml_rec_no_transcript_hint) }, TextView.BufferType.SPANNABLE)
            try {
                player?.let { follow(it.currentPosition, scrollTo = false) }
            } catch (ignored: Exception) {
            }
            text.alpha = if (read.isEmpty()) 0.6f else 1f
            if (changed) scroll.scrollTo(0, 0)
        }
        showStatus()
    }

    private fun showStatus() {
        val job = RecorderJobs.running.value?.get(audio.absolutePath)
        val error = RecorderJobs.errorOf(audio)
        status.text = job?.let { "$it…" } ?: error?.let { getString(R.string.ml_rec_error_shown, it) }
        status.setTextColor(if (job == null && error != null) 0xFFD9534F.toInt() else UiKit.primary(this))
        status.isVisible = job != null || error != null
        working.fraction = WorkingBar.fractionOf(job)
        working.isVisible = job != null
    }

    /** Nothing going on for this recording, and what [forAi] (or a transcript) needs is there. */
    private fun ready(forAi: Boolean): Boolean {
        val blocker = when {
            RecorderJobs.isBusy(audio) -> getString(R.string.ml_rec_busy)
            forAi -> if (RecorderStore.apiKey(this).isEmpty()) getString(R.string.ml_rec_key_missing) else null
            else -> RecorderJobs.transcriptBlocker(this)
        }
        if (blocker != null) showToast(blocker, android.widget.Toast.LENGTH_LONG)
        return blocker == null
    }

    private fun transcribe() {
        if (!ready(forAi = false)) return
        if (!RecorderStore.transcript(audio).isFile) return RecorderJobs.transcribe(this, audio)
        showConfirmDialog(getString(R.string.ml_rec_transcribe), getString(R.string.ml_rec_transcribe_again), getString(R.string.ml_rec_transcribe)) {
            RecorderJobs.transcribe(this, audio)
        }
    }

    private fun showAiMenu() {
        if (!ready(forAi = true)) return
        if (!RecorderStore.transcript(audio).isFile) return showToast(getString(R.string.ml_rec_no_transcript))
        fun preset(title: Int, prompt: Int, rewrite: Boolean = false) = MenuEntry(getString(title)) {
            RecorderJobs.elaborate(this, audio, getString(title), getString(prompt), rewrite)
        }
        showMenuDialog(getString(R.string.ml_rec_ai), listOf(
            preset(R.string.ml_rec_ai_summary, R.string.ml_rec_ai_summary_prompt),
            preset(R.string.ml_rec_ai_minutes, R.string.ml_rec_ai_minutes_prompt),
            preset(R.string.ml_rec_ai_points, R.string.ml_rec_ai_points_prompt),
            preset(R.string.ml_rec_ai_clean, R.string.ml_rec_ai_clean_prompt, rewrite = true),
            MenuEntry(getString(R.string.ml_rec_chat_open), icon = R.drawable.ic_m_chat) { ChatActivity.open(this, audio) },
        ), header = { dialog ->
            TextView(dialog.context, null, 0, R.style.TextSmall).apply {
                text = getString(R.string.ml_rec_ai_model,
                    if (RecorderStore.chatModelChosen(this@RecordingActivity)) RecorderStore.chatModel(this@RecordingActivity) else getString(R.string.ml_rec_language_auto))
                alpha = 0.6f
                setTextColor(UiKit.primary(dialog.context))
                setPadding(0, 8.dpToPx(), 0, 4.dpToPx())
            }
        })
    }

    private fun copy() {
        if (content.isEmpty()) return
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(audio.nameWithoutExtension, content))
        showToast(getString(R.string.ml_rec_copied))
    }

    /** The text shown: as text (any app takes it), as a file when it's too long for that. */
    private fun share() {
        if (content.isEmpty()) return
        if (content.length <= MAX_SHARED_TEXT) {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, shown.nameWithoutExtension).putExtra(Intent.EXTRA_TEXT, content), null))
        } else shareFile(shown, "text/plain")
    }

    private fun shareFile(file: File, type: String) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
        } catch (e: Exception) {
            showToast(getString(R.string.ml_rec_cannot))
        }
    }

    private fun showMenu() {
        val entries = mutableListOf(
            MenuEntry(getString(R.string.ml_rec_share_audio), icon = R.drawable.ic_m_share) { shareFile(audio, "audio/*") },
            MenuEntry(getString(R.string.rename), icon = R.drawable.ic_m_rename) {
                val old = audio.nameWithoutExtension
                showTextInputDialog(getString(R.string.rename), old, old, getString(R.string.ml_save)) { name ->
                    if (RecorderJobs.isBusy(audio)) return@showTextInputDialog showToast(getString(R.string.ml_rec_busy))
                    val renamed = RecorderStore.rename(audio, name) ?: return@showTextInputDialog showToast(getString(R.string.ml_rec_cannot))
                    // The player and the texts belong to the old files: start again on the new ones
                    finish()
                    open(this, renamed)
                }
            },
        )
        if (shown != RecorderStore.transcript(audio) && shown.isFile) {
            val file = shown
            entries += MenuEntry(getString(R.string.ml_rec_delete_write_up, RecorderStore.titleOf(audio, file)), icon = R.drawable.ic_m_delete, destructive = true) {
                showConfirmDialog(getString(R.string.ml_explorer_delete), getString(R.string.ml_explorer_delete_confirm, file.name), getString(R.string.ml_explorer_delete)) {
                    file.delete()
                    reload()
                }
            }
        }
        entries += MenuEntry(getString(R.string.ml_rec_delete_recording), icon = R.drawable.ic_m_delete, destructive = true) {
            showConfirmDialog(getString(R.string.ml_explorer_delete), getString(R.string.ml_rec_delete_confirm, audio.nameWithoutExtension), getString(R.string.ml_explorer_delete)) {
                pause()
                RecorderStore.delete(audio)
                finish()
            }
        }
        showMenuDialog(audio.nameWithoutExtension, entries)
    }

    companion object {
        private const val EXTRA_PATH = "path"
        private const val MAX_SHARED_TEXT = 100_000

        fun open(context: Context, audio: File) =
            context.startActivity(Intent(context, RecordingActivity::class.java).putExtra(EXTRA_PATH, audio.absolutePath))
    }
}
