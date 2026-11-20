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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.helper.MenuEntry
import app.olauncher.helper.dpToPx
import app.olauncher.helper.pillButton
import app.olauncher.helper.showConfirmDialog
import app.olauncher.helper.showDropdown
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

    /**
     * A folder or a recording; [info] replaces the second line (search results). [count]: the
     * recordings in a folder. [place]: on the calendar, the folder the recording is in ("" at the root).
     */
    private class Item(
        val file: File, val isFolder: Boolean, val transcribed: Boolean = false, val writeUps: Int = 0,
        val info: String? = null, val count: Int = 0, val place: String? = null,
    )

    private class HomeData(val folders: List<Item>, val byDay: Map<String, List<Item>>)

    private lateinit var bar: UiKit.AppBar
    private lateinit var status: TextView
    private lateinit var list: RecyclerView
    private lateinit var emptyView: View
    private lateinit var panel: LinearLayout
    private lateinit var usage: UsageRing
    private var timer: TextView? = null
    private var level: ProgressBar? = null

    /** The first page: the folders as chips, then the calendar of all the recordings and the chosen day's ones. */
    private lateinit var home: LinearLayout
    private lateinit var chips: LinearLayout
    private lateinit var chipsScroll: View
    private lateinit var monthTitle: TextView
    private lateinit var monthView: RecorderMonthView
    private lateinit var dayLabel: TextView
    private lateinit var addButton: View
    private var homeData = HomeData(emptyList(), emptyMap())
    /** The month shown and the day chosen ("yyyy-MM-dd"). */
    private var shownYear = 0
    private var shownMonth = 0
    private var selectedDay = ""
    /** All the folders as a grid, opened from the chips. */
    private var foldersPage = false

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
        GroqUsage.attach(this)
        root = RecorderStore.root(this)
        dir = savedInstanceState?.getString(STATE_DIR)?.let { File(it) }?.takeIf { it.isDirectory } ?: root
        showToday()
        setContentView(buildLayout())
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })
        RecorderService.state.observe(this) { renderPanel(it) }
        RecorderJobs.running.observe(this) { list.adapter?.notifyDataSetChanged() }
        RecorderJobs.errors.observe(this) { list.adapter?.notifyDataSetChanged() }
        GroqUsage.changes.observe(this) { usage.invalidate() }
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
        usage.invalidate()
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

    private val importer = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) importAudio(uris)
    }

    private fun dayKey(time: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(time))

    private fun showToday() {
        val now = java.util.Calendar.getInstance()
        shownYear = now.get(java.util.Calendar.YEAR)
        shownMonth = now.get(java.util.Calendar.MONTH)
        selectedDay = dayKey(now.timeInMillis)
    }

    private val isHome get() = query == null && !foldersPage && dir.absolutePath == root.absolutePath

    private fun goBack() {
        when {
            query != null -> {
                query = null
                load()
            }
            foldersPage -> {
                foldersPage = false
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
            UiKit.iconButton(this, R.drawable.ic_m_add, getString(R.string.ml_rec_add)) { showAddMenu() }.also { addButton = it },
            UiKit.iconButton(this, R.drawable.ic_m_gear, getString(R.string.ml_rec_settings)) { showSettings() },
        ))
        layout.addView(bar.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        status = TextView(this, null, 0, R.style.TextSmall).apply {
            setTextColor(UiKit.secondary(this@RecorderActivity))
            setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 12.dpToPx())
            isVisible = false
        }
        layout.addView(status)
        layout.addView(buildHome(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
        panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Bottom left: what is left of Groq's free plan
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        usage = UsageRing(this)
        bottom.addView(usage, LinearLayout.LayoutParams(44.dpToPx(), 44.dpToPx()).apply { bottomMargin = 4.dpToPx() })
        bottom.addView(panel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 4.dpToPx() })
        layout.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 8.dpToPx(), 4.dpToPx(), 0)
        })
        renderPanel(RecorderService.state.value)
        return layout
    }

    private fun buildHome(): View {
        val primary = UiKit.primary(this)
        home = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isVisible = false
        }
        chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chipsScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(4.dpToPx(), 0, 4.dpToPx(), 0)
            addView(chips)
        }
        home.addView(chipsScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 4.dpToPx()
            bottomMargin = 10.dpToPx()
        })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiKit.cardBackground(this@RecorderActivity, 20)
            setPadding(8.dpToPx(), 2.dpToPx(), 8.dpToPx(), 8.dpToPx())
        }
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        nav.addView(UiKit.iconButton(this, R.drawable.ic_m_left, getString(R.string.ml_rec_month_previous)) { shiftMonth(-1) })
        monthTitle = TextView(this).apply {
            textSize = 15f
            setTextColor(primary)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            // Back to today
            setOnClickListener {
                showToday()
                renderHome()
            }
        }
        nav.addView(monthTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        nav.addView(UiKit.iconButton(this, R.drawable.ic_m_right, getString(R.string.ml_rec_month_next)) { shiftMonth(1) })
        card.addView(nav)
        monthView = RecorderMonthView(this).apply {
            onPick = { day ->
                selectedDay = String.format(java.util.Locale.US, "%04d-%02d-%02d", shownYear, shownMonth + 1, day)
                renderHome()
            }
        }
        card.addView(monthView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        home.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(4.dpToPx(), 0, 4.dpToPx(), 0)
        })
        dayLabel = TextView(this, null, 0, R.style.TextSmall).apply {
            textSize = 12.5f
            setTextColor(UiKit.secondary(this@RecorderActivity))
            setPadding(12.dpToPx(), 12.dpToPx(), 12.dpToPx(), 2.dpToPx())
        }
        home.addView(dayLabel)
        return home
    }

    private fun shiftMonth(by: Int) {
        val calendar = java.util.Calendar.getInstance().apply {
            clear()
            set(shownYear, shownMonth, 1)
            add(java.util.Calendar.MONTH, by)
        }
        shownYear = calendar.get(java.util.Calendar.YEAR)
        shownMonth = calendar.get(java.util.Calendar.MONTH)
        renderHome()
    }

    /** The first page from [homeData]: the folders' chips, the month with its dots, the chosen day's recordings. */
    private fun renderHome() {
        val primary = UiKit.primary(this)
        chips.removeAllViews()
        chipsScroll.isVisible = homeData.folders.isNotEmpty()
        fun chip(label: String, onClick: () -> Unit) = pillButton(this, label, filled = false, color = primary) { onClick() }.apply {
            textSize = 14f
            setPadding(14.dpToPx(), 0, 14.dpToPx(), 0)
        }.also { chips.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 36.dpToPx()).apply { marginEnd = 8.dpToPx() }) }
        // With many folders: all of them on a page of their own
        if (homeData.folders.size > 3) chip(getString(R.string.ml_rec_folders_all, homeData.folders.size)) {
            foldersPage = true
            load()
        }
        homeData.folders.forEach { folder ->
            chip(if (folder.count > 0) "${folder.file.name}  ${folder.count}" else folder.file.name) { onTap(folder) }.setOnLongClickListener {
                onLongPress(folder)
                true
            }
        }
        val month = java.util.Calendar.getInstance().apply {
            clear()
            set(shownYear, shownMonth, 1)
        }
        monthTitle.text = java.text.SimpleDateFormat("LLLL yyyy", java.util.Locale.getDefault()).format(month.time).replaceFirstChar { it.uppercase() }
        val prefix = String.format(java.util.Locale.US, "%04d-%02d-", shownYear, shownMonth + 1)
        val today = dayKey(System.currentTimeMillis())
        fun dayIn(key: String) = if (key.startsWith(prefix)) key.substring(prefix.length).toIntOrNull() ?: 0 else 0
        val counts = HashMap<Int, Int>()
        homeData.byDay.forEach { (key, recordings) -> dayIn(key).takeIf { it > 0 }?.let { counts[it] = recordings.size } }
        monthView.show(shownYear, shownMonth, counts, dayIn(selectedDay), dayIn(today))
        val date = try {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(selectedDay)
        } catch (e: Exception) {
            null
        }
        val named = date?.let { java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.getDefault()).format(it) }.orEmpty()
        dayLabel.text = if (selectedDay == today) getString(R.string.ml_rec_today, named) else named.replaceFirstChar { it.uppercase() }
        show(homeData.byDay[selectedDay].orEmpty())
    }

    /** Folders with how many recordings each holds, and every recording (in any folder) under the day it was made. */
    private fun scanHome(): HomeData {
        root.mkdirs()
        val folders = root.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }.sortedBy { it.name.lowercase() }
            .map { folder -> Item(folder, true, count = folder.walkTopDown().count { RecorderStore.isAudio(it) }) }
        val recordings = root.walkTopDown().onEnter { !it.name.startsWith(".") || it == root }.filter { RecorderStore.isAudio(it) }.map { audio ->
            Item(
                audio, false, transcribed = RecorderStore.transcript(audio).isFile, writeUps = RecorderStore.elaborations(audio).size,
                place = audio.parentFile?.absolutePath.orEmpty().removePrefix(root.absolutePath).trimStart('/'),
            )
        }.toList()
        return HomeData(folders, recordings.sortedByDescending { it.file.lastModified() }.groupBy { dayKey(it.file.lastModified()) })
    }

    // ---------------------------------------------------------------- adding

    private fun showAddMenu() {
        addButton.showDropdown(listOf(
            MenuEntry(getString(R.string.ml_explorer_new_folder), icon = R.drawable.ic_m_folder) { askNewFolder() },
            MenuEntry(getString(R.string.ml_rec_import), icon = R.drawable.ic_m_download) {
                if (!hasFilesAccess()) askFilesAccess() else importer.launch("audio/*")
            },
        ))
    }

    /** Copies audio files chosen on the phone into the folder shown (the originals stay): they are of today. */
    private fun importAudio(uris: List<Uri>) {
        val target = if (query == null && !foldersPage) dir else root
        lifecycleScope.launch {
            val failed = withContext(Dispatchers.IO) {
                target.mkdirs()
                uris.count { !copyIn(it, target) }
            }
            if (failed > 0) showToast(getString(R.string.ml_rec_import_failed, failed))
            showToday()
            load()
        }
    }

    private fun copyIn(uri: Uri, target: File): Boolean = try {
        var name = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.replace('/', '_')?.takeIf { it.isNotBlank() } ?: "audio"
        if (File(name).extension.isEmpty()) {
            name += "." + (android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(contentResolver.getType(uri)) ?: "m4a")
        }
        val base = File(name).nameWithoutExtension
        val extension = File(name).extension
        var file = File(target, name)
        var i = 2
        while (file.exists() || RecorderStore.transcript(file).exists()) file = File(target, "$base (${i++}).$extension")
        val input = contentResolver.openInputStream(uri)
        if (input == null) false
        else {
            input.use { source -> file.outputStream().use { source.copyTo(it, 256 * 1024) } }
            true
        }
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }

    /** The bottom panel: the "Record" button, or the time, level, pause and stop of the recording going on. */
    private fun renderPanel(state: RecorderService.State?) {
        val primary = UiKit.primary(this)
        ticker.removeCallbacksAndMessages(null)
        panel.removeAllViews()
        timer = null
        level = null
        if (state == null) {
            // Just the button; the card comes with the recording going on
            panel.background = null
            panel.setPadding(0, 0, 0, 0)
            panel.addView(pillButton(this, getString(R.string.ml_rec_record), filled = true, color = primary) { startRecording() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 52.dpToPx()))
            return
        }
        panel.background = UiKit.cardBackground(this, 20)
        panel.setPadding(14.dpToPx(), 12.dpToPx(), 14.dpToPx(), 12.dpToPx())
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
        val page = foldersPage
        val atHome = isHome
        bar.title.text = when {
            q != null -> "«$q»"
            page -> getString(R.string.ml_rec_folders)
            dir.absolutePath == root.absolutePath -> getString(R.string.ml_rec_app)
            else -> dir.name
        }
        UiKit.setSubtitle(bar, if (q != null || page || dir.absolutePath == root.absolutePath) null else "/" + dir.absolutePath.removePrefix(root.absolutePath).trimStart('/'))
        home.isVisible = atHome && hasFilesAccess()
        // The page of all the folders is a grid, the rest a list
        val grid = page && q == null
        if (grid != (list.layoutManager is GridLayoutManager)) {
            list.layoutManager = if (grid) GridLayoutManager(this, 3) else LinearLayoutManager(this)
            list.adapter = ItemsAdapter()
        }
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
            if (atHome) {
                val data = withContext(Dispatchers.IO) { scanHome() }
                if (!isHome) return@launch
                homeData = data
                renderHome()
                return@launch
            }
            val found = withContext(Dispatchers.IO) {
                if (q != null) find(q) else if (page) listDir(root).filter { it.isFolder } else listDir(shownDir)
            }
            if (query != q || dir != shownDir || foldersPage != page) return@launch
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
        val folders = files.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
            .map { folder -> Item(folder, true, count = folder.walkTopDown().count { RecorderStore.isAudio(it) }) }
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
            foldersPage = false
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
        val geminiKey = RecorderStore.geminiKey(this)
        val gemini = RecorderAi.provider(this) == RecorderAi.GEMINI
        val chosenModel = if (gemini) RecorderStore.geminiModel(this) else if (RecorderStore.chatModelChosen(this)) RecorderStore.chatModel(this) else ""
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
            MenuEntry(getString(R.string.ml_rec_ai_with), section = getString(R.string.ml_rec_section_ai), value = if (gemini) "Gemini" else "Groq") {
                showMenuDialog(getString(R.string.ml_rec_ai_with), listOf(
                    MenuEntry(getString(R.string.ml_rec_ai_gemini)) { RecorderStore.setAiProvider(this, RecorderAi.GEMINI) },
                    MenuEntry(getString(R.string.ml_rec_ai_groq)) { RecorderStore.setAiProvider(this, RecorderAi.GROQ) },
                ))
            },
            MenuEntry(getString(R.string.ml_rec_gemini_key), value = if (geminiKey.isEmpty()) getString(R.string.ml_rec_key_none) else "…" + geminiKey.takeLast(4)) {
                showTextInputDialog(getString(R.string.ml_rec_gemini_key), geminiKey, "AIza…", getString(R.string.ml_save), allowBlank = true,
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) { RecorderStore.setGeminiKey(this, it) }
            },
            MenuEntry(getString(R.string.ml_rec_gemini_key_get)) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
                } catch (e: Exception) {
                    showToast(getString(R.string.ml_rec_cannot))
                }
            },
            MenuEntry(getString(R.string.ml_rec_model_chat), value = chosenModel.ifEmpty { getString(R.string.ml_rec_language_auto) }) { pickChatModel() },
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

    /** The text models the chosen AI offers right now (they change over time), to choose from; "Automatic" lets the app pick. */
    private fun pickChatModel() {
        RecorderAi.blocker(this)?.let { return showToast(it) }
        val gemini = RecorderAi.provider(this) == RecorderAi.GEMINI
        lifecycleScope.launch {
            var failure: String? = null
            val models = withContext(Dispatchers.IO) {
                try {
                    RecorderAi.models(this@RecorderActivity)
                } catch (e: Groq.GroqException) {
                    failure = e.message
                    null
                } catch (e: Exception) {
                    null
                }
            }
            if (models.isNullOrEmpty()) return@launch showToast(failure ?: getString(R.string.ml_rec_no_network), android.widget.Toast.LENGTH_LONG)
            val ctx = this@RecorderActivity
            val current = if (gemini) RecorderStore.geminiModel(ctx) else if (RecorderStore.chatModelChosen(ctx)) RecorderStore.chatModel(ctx) else ""
            fun mark(label: String, chosen: Boolean) = if (chosen) "$label ✓" else label
            val entries = mutableListOf(MenuEntry(mark(getString(R.string.ml_rec_language_auto), current.isEmpty())) {
                if (gemini) RecorderStore.setGeminiModel(ctx, "") else RecorderStore.clearChatModel(ctx)
            })
            entries += models.map { model ->
                MenuEntry(mark(model, model == current)) { if (gemini) RecorderStore.setGeminiModel(ctx, model) else RecorderStore.setChatModel(ctx, model) }
            }
            showMenuDialog(getString(R.string.ml_rec_model_chat), entries)
        }
    }

    // ---------------------------------------------------------------- adapter

    private inner class ItemsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = items.size

        override fun getItemViewType(position: Int) = if (list.layoutManager is GridLayoutManager) 1 else 0

        /** A folder on the page of all the folders: a square tile, its name and how many recordings it holds. */
        private fun folderCell(parent: ViewGroup): View {
            val ctx = parent.context
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(4.dpToPx(), 4.dpToPx(), 4.dpToPx(), 10.dpToPx())
                isClickable = true
                background = UiKit.ripple(ctx)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val side = ((parent.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) - parent.paddingLeft - parent.paddingRight) / 3 - 8.dpToPx()
            cell.addView(FrameLayout(ctx).apply {
                background = UiKit.border(ctx, 16, UiKit.card(ctx))
                addView(UiKit.fileTile(ctx, UiKit.FileKind.FOLDER, 44), FrameLayout.LayoutParams(44.dpToPx(), 44.dpToPx(), Gravity.CENTER))
            }, LinearLayout.LayoutParams(side, side))
            cell.addView(TextView(ctx, null, 0, R.style.TextSmall).apply {
                textSize = 13.5f
                setTextColor(UiKit.primary(ctx))
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, 6.dpToPx(), 0, 0)
            })
            cell.addView(TextView(ctx, null, 0, R.style.TextSmall).apply {
                textSize = 12f
                setTextColor(UiKit.secondary(ctx))
                gravity = Gravity.CENTER_HORIZONTAL
            })
            return cell
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == 1) return object : RecyclerView.ViewHolder(folderCell(parent)) {}
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
            // Shown while a transcript or a write-up of the recording is being made
            texts.addView(WorkingBar(ctx).apply { isVisible = false }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 4.dpToPx()).apply {
                topMargin = 6.dpToPx()
            })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return object : RecyclerView.ViewHolder(row) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            val ctx = this@RecorderActivity
            val row = holder.itemView as LinearLayout
            row.setOnClickListener { onTap(item) }
            row.setOnLongClickListener {
                onLongPress(item)
                true
            }
            if (holder.itemViewType == 1) {
                (row.getChildAt(1) as TextView).text = item.file.name
                (row.getChildAt(2) as TextView).text = item.count.toString()
                return
            }
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
                item.isFolder -> if (item.count > 0) item.count.toString() else ""
                recording -> getString(R.string.ml_rec_recording)
                job != null -> job
                item.info != null -> item.info
                RecorderJobs.errorOf(item.file) != null -> RecorderJobs.errorOf(item.file)
                else -> listOfNotNull(
                    // On the calendar the day is already said: the time, and the folder the recording is in
                    if (item.place != null) DateFormat.getTimeFormat(ctx).format(item.file.lastModified())
                    else DateFormat.getMediumDateFormat(ctx).format(item.file.lastModified()) + " " + DateFormat.getTimeFormat(ctx).format(item.file.lastModified()),
                    Formatter.formatShortFileSize(ctx, item.file.length()),
                    if (item.transcribed) getString(R.string.ml_rec_transcribed) else null,
                    if (item.writeUps > 0) getString(R.string.ml_rec_write_ups, item.writeUps) else null,
                    item.place?.takeIf { it.isNotEmpty() }?.let { "/$it" },
                ).joinToString(" · ")
            }
            info.isVisible = info.text.isNotEmpty()
            (texts.getChildAt(2) as WorkingBar).apply {
                fraction = WorkingBar.fractionOf(job)
                isVisible = job != null && !item.isFolder
            }
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
