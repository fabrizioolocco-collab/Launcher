package app.olauncher.ui.recorder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.helper.MenuEntry
import app.olauncher.helper.dpToPx
import app.olauncher.helper.pillButton
import app.olauncher.helper.showConfirmDialog
import app.olauncher.helper.showMenuDialog
import app.olauncher.helper.showTextInputDialog
import app.olauncher.helper.showToast
import app.olauncher.ui.FileExplorerActivity
import app.olauncher.ui.FolderPickerActivity
import app.olauncher.ui.UiKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sqrt

/**
 * The recorder ("Recorder" in the app list): records the microphone, keeps the recordings in
 * folders and subfolders of a normal phone folder, has them transcribed (Whisper on Groq) and
 * written up by an AI. A recording opens in [RecordingActivity].
 */
class RecorderActivity : AppCompatActivity() {

    /** A folder or a recording; [info] replaces the second line (search results). */
    private class Item(val file: File, val isFolder: Boolean, val transcribed: Boolean = false, val writeUps: Int = 0, val info: String? = null)

    private lateinit var bar: UiKit.AppBar
    private lateinit var status: TextView
    private lateinit var list: RecyclerView
    private lateinit var emptyView: View
    private lateinit var panel: LinearLayout
    private var timer: TextView? = null
    private var level: ProgressBar? = null

    private lateinit var root: File
    private lateinit var dir: File
    private var items: List<Item> = emptyList()
    /** The search whose results are shown, null when a folder is. */
    private var query: String? = null
    private var waitingAccess = false
    private var onPicked: ((File) -> Unit)? = null
    private val ticker = Handler(Looper.getMainLooper())

    private val folderPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val path = result.data?.getStringExtra(FileExplorerActivity.EXTRA_LOCAL_PATH)
        val picked = onPicked
        onPicked = null
        if (result.resultCode == RESULT_OK && path != null) picked?.invoke(File(path))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = RecorderStore.root(this)
        dir = savedInstanceState?.getString(STATE_DIR)?.let { File(it) }?.takeIf { it.isDirectory } ?: root
        setContentView(buildLayout())
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })
        RecorderService.state.observe(this) { renderPanel(it) }
        RecorderJobs.running.observe(this) { list.adapter?.notifyDataSetChanged() }
        RecorderJobs.errors.observe(this) { list.adapter?.notifyDataSetChanged() }
        resumePending()
        RecorderJobs.changes.observe(this) { load() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_DIR, dir.absolutePath)
    }

    override fun onResume() {
        super.onResume()
        waitingAccess = false
        load()
    }

    override fun onDestroy() {
        ticker.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Transcriptions cut short (the phone restarted, the system closed the app) go on by themselves. */
    private fun resumePending() {
        RecorderStore.pending(this).map { File(it) }.forEach { audio ->
            if (RecorderJobs.isBusy(audio)) return@forEach
            if (audio.isFile && hasFilesAccess() && RecorderJobs.transcriptBlocker(this) == null) RecorderJobs.transcribe(this, audio)
            else RecorderStore.setPending(this, audio, false)
        }
    }

    private fun goBack() {
        when {
            query != null -> {
                query = null
                load()
            }
            dir.absolutePath != root.absolutePath && dir.absolutePath.startsWith(root.absolutePath + "/") -> {
                dir = dir.parentFile ?: root
                load()
            }
            else -> finish()
        }
    }

    // ---------------------------------------------------------------- layout

    private fun buildLayout(): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8.dpToPx(), 0, 8.dpToPx(), 0)
        }
        ViewCompat.setOnApplyWindowInsetsListener(layout) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top + 4.dpToPx(), v.paddingRight, bars.bottom + 8.dpToPx())
            insets
        }
        bar = UiKit.appBar(this, getString(R.string.ml_rec_app), onBack = { goBack() }, actions = listOf(
            UiKit.iconButton(this, R.drawable.ic_m_search, getString(R.string.ml_explorer_search)) { askSearch() },
            UiKit.iconButton(this, R.drawable.ic_m_add, getString(R.string.ml_explorer_new_folder)) { askNewFolder() },
            UiKit.iconButton(this, R.drawable.ic_m_settings, getString(R.string.ml_rec_settings)) { showSettings() },
        ))
        layout.addView(bar.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        status = TextView(this, null, 0, R.style.TextSmall).apply {
            setTextColor(UiKit.secondary(this@RecorderActivity))
            setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 12.dpToPx())
            isVisible = false
        }
        layout.addView(status)
        val area = FrameLayout(this)
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@RecorderActivity)
            adapter = ItemsAdapter()
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(4.dpToPx(), 0, 4.dpToPx(), 8.dpToPx())
        }
        area.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        emptyView = UiKit.emptyState(this, R.drawable.ic_m_mic, getString(R.string.ml_rec_empty)).apply { isVisible = false }
        area.addView(emptyView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        layout.addView(area, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiKit.cardBackground(this@RecorderActivity, 20)
            setPadding(14.dpToPx(), 12.dpToPx(), 14.dpToPx(), 12.dpToPx())
        }
        layout.addView(panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(4.dpToPx(), 8.dpToPx(), 4.dpToPx(), 0)
        })
        renderPanel(RecorderService.state.value)
        return layout
    }

    /** The bottom panel: the "Record" button, or the time, level, pause and stop of the recording going on. */
    private fun renderPanel(state: RecorderService.State?) {
        val primary = UiKit.primary(this)
        ticker.removeCallbacksAndMessages(null)
        panel.removeAllViews()
        timer = null
        level = null
        if (state == null) {
            panel.addView(pillButton(this, getString(R.string.ml_rec_record), filled = true, color = primary) { startRecording() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 52.dpToPx()))
            return
        }
        panel.addView(TextView(this, null, 0, R.style.TextSmall).apply {
            text = getString(if (state.paused) R.string.ml_rec_paused else R.string.ml_rec_recording) + " · " + state.file.nameWithoutExtension
            setTextColor(UiKit.secondary(this@RecorderActivity))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            gravity = Gravity.CENTER
        })
        timer = TextView(this).apply {
            textSize = 34f
            setTextColor(primary)
            gravity = Gravity.CENTER
            text = RecorderStore.clock(state.elapsed())
        }
        panel.addView(timer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        level = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(primary)
            progressBackgroundTintList = ColorStateList.valueOf(androidx.core.graphics.ColorUtils.setAlphaComponent(primary, 0x30))
        }
        panel.addView(level, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 4.dpToPx()
            bottomMargin = 12.dpToPx()
        })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(
            pillButton(this, getString(if (state.paused) R.string.ml_rec_resume else R.string.ml_rec_pause), filled = false, color = primary) {
                RecorderService.send(this, if (state.paused) RecorderService.ACTION_RESUME else RecorderService.ACTION_PAUSE)
            },
            LinearLayout.LayoutParams(0, 48.dpToPx(), 1f).apply { marginEnd = 8.dpToPx() }
        )
        buttons.addView(
            pillButton(this, getString(R.string.ml_rec_stop), filled = true, color = primary) { RecorderService.send(this, RecorderService.ACTION_STOP) },
            LinearLayout.LayoutParams(0, 48.dpToPx(), 1f)
        )
        panel.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        tick()
    }

    private fun tick() {
        val state = RecorderService.state.value ?: return
        timer?.text = RecorderStore.clock(state.elapsed())
        // The square root makes quiet speech visible
        level?.progress = if (state.paused) 0 else (sqrt(RecorderService.amplitude() / 32767f) * 100).toInt()
        ticker.postDelayed({ tick() }, 150)
    }

    // ---------------------------------------------------------------- recording

    private fun hasFilesAccess() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun askFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            waitingAccess = true
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQUEST_FILES)
    }

    private fun startRecording() {
        if (!hasFilesAccess()) return askFilesAccess()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) wanted += Manifest.permission.POST_NOTIFICATIONS
            requestPermissions(wanted.toTypedArray(), REQUEST_MIC)
            return
        }
        // A search shows results from everywhere: the recording goes to the folder behind it
        if (!dir.isDirectory && !dir.mkdirs()) {
            showToast(getString(R.string.ml_rec_failed))
            return
        }
        RecorderService.start(this, RecorderStore.newRecording(dir))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
            else showToast(getString(R.string.ml_rec_allow_mic))
        } else load()
    }

    // ---------------------------------------------------------------- list

    private fun load() {
        val q = query
        bar.title.text = if (q != null) "«$q»" else if (dir.absolutePath == root.absolutePath) getString(R.string.ml_rec_app) else dir.name
        UiKit.setSubtitle(bar, if (q != null || dir.absolutePath == root.absolutePath) null else "/" + dir.absolutePath.removePrefix(root.absolutePath).trimStart('/'))
        if (!hasFilesAccess()) {
            show(emptyList())
            emptyView.isVisible = false
            status.text = getString(R.string.ml_rec_allow_files)
            status.isVisible = true
            status.setOnClickListener { askFilesAccess() }
            return
        }
        status.isVisible = false
        val shownDir = dir
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { if (q != null) find(q) else listDir(shownDir) }
            if (query != q || dir != shownDir) return@launch
            show(found)
        }
    }

    private fun show(found: List<Item>) {
        items = found
        list.adapter?.notifyDataSetChanged()
        emptyView.isVisible = found.isEmpty()
    }

    /** Folders by name, then the recordings, newest first. */
    private fun listDir(d: File): List<Item> {
        d.mkdirs()
        val files = d.listFiles().orEmpty().filter { !it.name.startsWith(".") }
        val names = files.map { it.name }.toSet()
        val folders = files.filter { it.isDirectory }.sortedBy { it.name.lowercase() }.map { Item(it, true) }
        val recordings = files.filter { RecorderStore.isAudio(it) }.sortedByDescending { it.lastModified() }.map { audio ->
            Item(audio, false, transcribed = audio.nameWithoutExtension + ".txt" in names, writeUps = RecorderStore.elaborations(audio).size)
        }
        return folders + recordings
    }

    /** Recordings, in any folder, with [q] in the name, the transcript or a write-up (with the words around it). */
    private fun find(q: String): List<Item> {
        val key = q.lowercase()
        return root.walkTopDown().filter { RecorderStore.isAudio(it) }.mapNotNull { audio ->
            val texts = listOf(RecorderStore.transcript(audio)) + RecorderStore.elaborations(audio)
            val snippet = texts.firstNotNullOfOrNull { f ->
                val text = try {
                    if (f.isFile) f.readText() else ""
                } catch (e: Exception) {
                    ""
                }
                val at = text.lowercase().indexOf(key)
                if (at < 0) null else "…" + text.substring((at - 40).coerceAtLeast(0), (at + key.length + 60).coerceAtMost(text.length)).replace('\n', ' ') + "…"
            }
            if (snippet == null && !audio.nameWithoutExtension.lowercase().contains(key)) null
            else Item(audio, false, info = snippet ?: ("/" + (audio.parentFile?.absolutePath.orEmpty().removePrefix(root.absolutePath).trimStart('/'))))
        }.sortedByDescending { it.file.lastModified() }.toList()
    }

    private fun askSearch() {
        showTextInputDialog(getString(R.string.ml_explorer_search), query.orEmpty(), getString(R.string.ml_rec_search_hint), getString(R.string.ml_explorer_search)) { text ->
            query = text
            load()
        }
    }

    private fun askNewFolder() {
        if (!hasFilesAccess()) return askFilesAccess()
        showTextInputDialog(getString(R.string.ml_explorer_new_folder), "", getString(R.string.ml_explorer_folder_name), getString(R.string.ml_save)) { name ->
            if (!File(dir, name.replace('/', '_')).mkdirs()) showToast(getString(R.string.ml_rec_cannot))
            load()
        }
    }

    private fun onTap(item: Item) {
        if (item.isFolder) {
            query = null
            dir = item.file
            load()
            return
        }
        if (RecorderService.state.value?.file?.absolutePath == item.file.absolutePath) return
        RecordingActivity.open(this, item.file)
    }

    private fun onLongPress(item: Item) {
        val file = item.file
        if (RecorderService.state.value?.file?.absolutePath == file.absolutePath) return
        val entries = mutableListOf<MenuEntry>()
        if (!item.isFolder && !RecorderJobs.isBusy(file)) entries += MenuEntry(getString(R.string.ml_rec_transcribe), icon = R.drawable.ic_m_text) {
            val blocker = RecorderJobs.transcriptBlocker(this)
            if (blocker != null) showToast(blocker) else RecorderJobs.transcribe(this, file)
        }
        entries += MenuEntry(getString(R.string.rename), icon = R.drawable.ic_m_rename) {
            val old = if (item.isFolder) file.name else file.nameWithoutExtension
            showTextInputDialog(getString(R.string.rename), old, old, getString(R.string.ml_save)) { name ->
                val ok = if (item.isFolder) file.renameTo(File(file.parentFile, name.replace('/', '_'))) else RecorderStore.rename(file, name) != null
                if (!ok) showToast(getString(R.string.ml_rec_cannot))
                load()
            }
        }
        entries += MenuEntry(getString(R.string.ml_explorer_move), icon = R.drawable.ic_m_move) {
            pickFolder { target ->
                val inside = item.isFolder && (target.absolutePath + "/").startsWith(file.absolutePath + "/")
                val ok = !inside && target.absolutePath != file.parentFile?.absolutePath &&
                        if (item.isFolder) !File(target, file.name).exists() && RecorderStore.moveFile(file, File(target, file.name)) else RecorderStore.move(file, target)
                if (!ok) showToast(getString(R.string.ml_rec_cannot))
                load()
            }
        }
        if (!item.isFolder) entries += MenuEntry(getString(R.string.ml_rec_share_audio), icon = R.drawable.ic_m_share) { shareFile(file, "audio/*") }
        entries += MenuEntry(getString(R.string.ml_explorer_delete), icon = R.drawable.ic_m_delete, destructive = true) {
            showConfirmDialog(getString(R.string.ml_explorer_delete), getString(R.string.ml_explorer_delete_confirm, file.name), getString(R.string.ml_explorer_delete)) {
                if (item.isFolder) file.deleteRecursively() else RecorderStore.delete(file)
                load()
            }
        }
        showMenuDialog(if (item.isFolder) file.name else file.nameWithoutExtension, entries)
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

    private fun pickFolder(start: File = root, picked: (File) -> Unit) {
        onPicked = picked
        folderPicker.launch(Intent(this, FolderPickerActivity::class.java).putExtra(FileExplorerActivity.EXTRA_LOCAL_PATH, start.absolutePath)
            .putExtra(FileExplorerActivity.EXTRA_FOLDERS_ONLY, true))
    }

    // ---------------------------------------------------------------- settings

    private fun showSettings() {
        val key = RecorderStore.apiKey(this)
        val language = RecorderStore.language(this)
        showMenuDialog(getString(R.string.ml_rec_settings), listOf(
            MenuEntry(getString(R.string.ml_rec_key), section = getString(R.string.ml_rec_section_groq),
                value = if (key.isEmpty()) getString(R.string.ml_rec_key_none) else "…" + key.takeLast(4)) {
                showTextInputDialog(getString(R.string.ml_rec_key), key, "gsk_…", getString(R.string.ml_save), allowBlank = true,
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) { RecorderStore.setApiKey(this, it) }
            },
            MenuEntry(getString(R.string.ml_rec_key_get)) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys")))
                } catch (e: Exception) {
                    showToast(getString(R.string.ml_rec_cannot))
                }
            },
            MenuEntry(getString(R.string.ml_rec_engine), section = getString(R.string.ml_rec_section_transcript), value = engineName(RecorderStore.engine(this))) { pickEngine() },
            MenuEntry(getString(R.string.ml_rec_model_whisper), value = RecorderStore.whisperModel(this)) {
                showMenuDialog(getString(R.string.ml_rec_model_whisper), RecorderStore.WHISPER_MODELS.map { model ->
                    MenuEntry(model) { RecorderStore.setWhisperModel(this, model) }
                })
            },
            MenuEntry(getString(R.string.ml_rec_language), value = languageName(language)) {
                showMenuDialog(getString(R.string.ml_rec_language), LANGUAGES.map { code -> MenuEntry(languageName(code)) { RecorderStore.setLanguage(this, code) } })
            },
            MenuEntry(getString(R.string.ml_rec_timestamps), checked = RecorderStore.timestamps(this), onToggle = { on ->
                RecorderStore.setTimestamps(this, on)
                true
            }),
            MenuEntry(getString(R.string.ml_rec_auto), checked = RecorderStore.autoTranscribe(this), onToggle = { on ->
                RecorderStore.setAutoTranscribe(this, on)
                true
            }),
            MenuEntry(getString(R.string.ml_rec_model_chat), section = getString(R.string.ml_rec_section_ai), value = if (RecorderStore.chatModelChosen(this)) RecorderStore.chatModel(this) else getString(R.string.ml_rec_language_auto)) { pickChatModel() },
            MenuEntry(getString(R.string.ml_rec_root), section = getString(R.string.ml_rec_section_files), value = root.name) {
                pickFolder(root.parentFile ?: root) { target ->
                    RecorderStore.setRoot(this, target)
                    root = target
                    dir = target
                    query = null
                    load()
                }
            },
            MenuEntry(getString(R.string.ml_rec_battery)) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (e: Exception) {
                    showToast(getString(R.string.ml_rec_cannot))
                }
            },
        ))
    }

    private fun engineName(engine: String) =
        LocalWhisper.model(engine)?.let { getString(R.string.ml_rec_engine_local, it.label) } ?: getString(R.string.ml_rec_engine_groq)

    /** Groq, or a model on the phone (downloaded the first time it's chosen); a downloaded one can be deleted from here. */
    private fun pickEngine() {
        val entries = mutableListOf(MenuEntry(getString(R.string.ml_rec_engine_groq)) { RecorderStore.setEngine(this, RecorderStore.ENGINE_GROQ) })
        LocalWhisper.MODELS.forEach { model ->
            val downloaded = LocalWhisper.isDownloaded(this, model)
            val busy = RecorderJobs.isBusy(LocalWhisper.file(this, model))
            val state = getString(if (busy) R.string.ml_rec_downloading else if (downloaded) R.string.ml_rec_model_ready else R.string.ml_rec_model_missing)
            entries += MenuEntry(getString(R.string.ml_rec_engine_local, model.label), value = "${model.megabytes} MB · $state") {
                when {
                    !LocalWhisper.supported() -> showToast(getString(R.string.ml_rec_local_unsupported))
                    downloaded || busy -> RecorderStore.setEngine(this, model.id)
                    else -> showConfirmDialog(
                        getString(R.string.ml_rec_engine_local, model.label), getString(R.string.ml_rec_model_download, model.megabytes), getString(R.string.ml_rec_download),
                    ) {
                        RecorderStore.setEngine(this, model.id)
                        RecorderJobs.download(this, model)
                    }
                }
            }
        }
        val kept = LocalWhisper.MODELS.filter { LocalWhisper.isDownloaded(this, it) && !RecorderJobs.isBusy(LocalWhisper.file(this, it)) }
        if (kept.isNotEmpty()) entries += MenuEntry(getString(R.string.ml_rec_models_delete), destructive = true) {
            showMenuDialog(getString(R.string.ml_rec_models_delete), kept.map { model ->
                MenuEntry("${model.label} (${model.megabytes} MB)", destructive = true) {
                    LocalWhisper.file(this, model).delete()
                    if (RecorderStore.engine(this) == model.id) RecorderStore.setEngine(this, RecorderStore.ENGINE_GROQ)
                }
            })
        }
        showMenuDialog(getString(R.string.ml_rec_engine), entries)
    }

    private fun languageName(code: String) =
        if (code.isEmpty()) getString(R.string.ml_rec_language_auto) else java.util.Locale(code).getDisplayLanguage(java.util.Locale.getDefault()).replaceFirstChar { it.uppercase() }

    /** The text models Groq offers right now (they change over time), to choose from. */
    private fun pickChatModel() {
        val key = RecorderStore.apiKey(this)
        if (key.isEmpty()) return showToast(getString(R.string.ml_rec_key_missing))
        lifecycleScope.launch {
            val models = withContext(Dispatchers.IO) {
                try {
                    Groq.chatModels(key)
                } catch (e: Exception) {
                    null
                }
            }
            if (models.isNullOrEmpty()) return@launch showToast(getString(R.string.ml_rec_no_network))
            val current = RecorderStore.chatModel(this@RecorderActivity)
            showMenuDialog(getString(R.string.ml_rec_model_chat), models.map { model ->
                MenuEntry(if (model == current) "$model ✓" else model) { RecorderStore.setChatModel(this@RecorderActivity, model) }
            })
        }
    }

    // ---------------------------------------------------------------- adapter

    private inner class ItemsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(8.dpToPx(), 10.dpToPx(), 8.dpToPx(), 10.dpToPx())
                isClickable = true
                background = UiKit.ripple(ctx)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            row.addView(FrameLayout(ctx), LinearLayout.LayoutParams(40.dpToPx(), 40.dpToPx()).apply { marginEnd = 12.dpToPx() })
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(ctx, null, 0, R.style.TextLarge).apply {
                textSize = 16f
                setTextColor(UiKit.primary(ctx))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            })
            texts.addView(TextView(ctx, null, 0, R.style.TextSmall).apply {
                textSize = 13f
                setTextColor(UiKit.secondary(ctx))
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, 2.dpToPx(), 0, 0)
            })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return object : RecyclerView.ViewHolder(row) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            val ctx = this@RecorderActivity
            val row = holder.itemView as LinearLayout
            val tile = row.getChildAt(0) as FrameLayout
            val kind = if (item.isFolder) UiKit.FileKind.FOLDER else UiKit.FileKind.AUDIO
            if (tile.tag != kind) {
                tile.tag = kind
                tile.removeAllViews()
                tile.addView(UiKit.fileTile(ctx, kind, 40), FrameLayout.LayoutParams(40.dpToPx(), 40.dpToPx()))
            }
            val texts = row.getChildAt(1) as LinearLayout
            (texts.getChildAt(0) as TextView).text = if (item.isFolder) item.file.name else item.file.nameWithoutExtension
            val info = texts.getChildAt(1) as TextView
            val job = RecorderJobs.running.value?.get(item.file.absolutePath)
            val recording = RecorderService.state.value?.file?.absolutePath == item.file.absolutePath
            info.text = when {
                item.isFolder -> ""
                recording -> getString(R.string.ml_rec_recording)
                job != null -> job
                item.info != null -> item.info
                RecorderJobs.errorOf(item.file) != null -> RecorderJobs.errorOf(item.file)
                else -> listOfNotNull(
                    DateFormat.getMediumDateFormat(ctx).format(item.file.lastModified()) + " " + DateFormat.getTimeFormat(ctx).format(item.file.lastModified()),
                    Formatter.formatShortFileSize(ctx, item.file.length()),
                    if (item.transcribed) getString(R.string.ml_rec_transcribed) else null,
                    if (item.writeUps > 0) getString(R.string.ml_rec_write_ups, item.writeUps) else null,
                ).joinToString(" · ")
            }
            info.isVisible = info.text.isNotEmpty()
            row.setOnClickListener { onTap(item) }
            row.setOnLongClickListener {
                onLongPress(item)
                true
            }
        }
    }

    companion object {
        private const val STATE_DIR = "dir"
        private const val REQUEST_MIC = 1
        private const val REQUEST_FILES = 2
        /** "" lets the model find the language. */
        private val LANGUAGES = listOf("", "it", "en", "fr", "de", "es", "pt")
    }
}
