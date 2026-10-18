package app.olauncher.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.TextUtils
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.data.FolderView
import app.olauncher.data.FolderViewsRepository
import app.olauncher.helper.MediaThumbnails
import app.olauncher.helper.MenuEntry
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.pillButton
import app.olauncher.helper.showConfirmDialog
import app.olauncher.helper.showDropdown
import app.olauncher.helper.showMenuDialog
import app.olauncher.helper.showProgressDialog
import app.olauncher.helper.showTextInputDialog
import app.olauncher.helper.showToast
import app.olauncher.ui.scanner.ScannerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Simple file explorer for the phone storage and OneDrive: browse, open with the phone's apps,
 * share, rename, delete, copy and move (also between phone and OneDrive).
 *
 * Each folder can have its own view (the sliders icon): list or grid with 2-6 columns, sort order (or a manual
 * order, set by dragging), what to show. Subfolders use the settings of the closest folder above
 * that has some ([FolderViewsRepository]).
 *
 * Opened from the home search (a folder, or the folder of a file), the home menu or its own
 * "File" icon in the app list. Extras pick the starting folder: [EXTRA_LOCAL_PATH] for the phone,
 * [EXTRA_CLOUD] for the OneDrive root; none shows the two roots (Phone and OneDrive).
 */
open class FileExplorerActivity : AppCompatActivity() {

    /** Folder picker mode (see [FolderPickerActivity]): a bar returns the folder shown. */
    protected open val pickMode = false

    /** OneDrive offered too: always when browsing, in picker mode when asked ([EXTRA_ALLOW_CLOUD], e.g. home folders). */
    private val allowCloud get() = !pickMode || intent?.getBooleanExtra(EXTRA_ALLOW_CLOUD, false) == true
    private var useButton: View? = null


    /** Where we are: the roots list, a phone folder or a OneDrive folder (id null = root). */
    private sealed class Location {
        object Roots : Location()
        data class Local(val dir: File) : Location()
        /** [parent] is where "up" goes (null at the root). */
        data class Cloud(val id: String?, val name: String, val parent: Cloud?) : Location() {
            /** Path from the OneDrive root ("" at the root). */
            val path: String get() = if (parent == null) "" else parent.path + "/" + name
        }
    }

    /** A phone file ([file]), a OneDrive item ([cloud]) or, in the roots list, a root ([root]). */
    private class Entry(
        val name: String,
        val isFolder: Boolean,
        val size: Long,
        val modified: Long,
        val file: File? = null,
        val mime: String? = null,
        val cloud: OneDrive.Item? = null,
        val root: Location? = null,
        /** Second line in place of size and date. */
        val info: String? = null,
    ) {
        /** Identity for the selection. */
        val key: String get() = file?.absolutePath ?: cloud?.let { "onedrive:" + it.id } ?: "root:$name"
    }

    private class Clip(val entries: List<Entry>, val move: Boolean)

    /**
     * A OneDrive file opened from its copy in the cache: when the explorer comes back, the copy (if
     * edited) and files saved next to it (copies made by the editors) go back to [folder].
     */
    private class Opened(val file: File, val folder: Location.Cloud, var modified: Long, var names: Set<String>)

    /** A tool of the bottom bars: line icon, label, action. */
    private class Tool(val icon: Int, val label: Int, val action: () -> Unit)

    private lateinit var oneDrive: OneDrive
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var status: TextView
    private lateinit var statusRow: LinearLayout
    private lateinit var statusSpinner: android.widget.ProgressBar
    private lateinit var list: RecyclerView
    private lateinit var pasteBar: LinearLayout
    private lateinit var pasteText: TextView
    private lateinit var newFolder: ImageView
    private lateinit var emptyView: View

    private lateinit var location: Location
    private lateinit var views: FolderViewsRepository
    /** Settings of the folder shown and where they come from (null = defaults). */
    private var view = FolderView()
    private var viewSource: File? = null
    private var entries: MutableList<Entry> = mutableListOf()
    /** Names the user hid in the folder shown (see [hideSelected]). */
    private var hiddenNames: Set<String> = emptySet()
    private var clip: Clip? = null

    /** Multiple selection (paths), started by a long press. */
    private val selected = LinkedHashSet<String>()
    private val selecting get() = selected.isNotEmpty()
    private var loadedKey: String? = null
    private lateinit var backButton: ImageView
    private lateinit var selectionBar: LinearLayout
    private lateinit var headerButtons: List<View>
    private var waitingAccess = false
    private val opened = mutableListOf<Opened>()

    /** OneDrive folder a scan is being made for. */
    private var scanFolder: Location.Cloud? = null

    /** A scan made for OneDrive comes back as a PDF in the cache: it is uploaded to the folder it was made for. */
    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val folder = scanFolder ?: return@registerForActivityResult
        scanFolder = null
        val pdf = result.data?.getStringExtra(ScannerActivity.EXTRA_PDF_PATH)?.let { File(it) } ?: return@registerForActivityResult
        run(getString(R.string.ml_onedrive_uploading)) {
            val ok = oneDrive.upload(pdf, folder.id)
            pdf.parentFile?.deleteRecursively()
            ok
        }
    }

    private val storageRoot: File get() = Environment.getExternalStorageDirectory()

    private fun cloudRoot() = Location.Cloud(null, getString(R.string.ml_search_onedrive), null)

    /** A OneDrive folder as a path that can be stored: "onedrive:" + its chain of folders (id, name) from the root. */
    private fun cloudPathOf(loc: Location.Cloud): String {
        val chain = generateSequence(loc) { it.parent }.filter { it.id != null }.toList().reversed()
        val array = org.json.JSONArray()
        chain.forEach { array.put(org.json.JSONObject().put("id", it.id).put("name", it.name)) }
        return CLOUD_PREFIX + array
    }

    private fun cloudLocationOf(path: String): Location.Cloud? = try {
        val array = org.json.JSONArray(path.removePrefix(CLOUD_PREFIX))
        (0 until array.length()).fold(cloudRoot()) { parent, i ->
            val o = array.getJSONObject(i)
            Location.Cloud(o.getString("id"), o.optString("name"), parent)
        }
    } catch (e: Exception) {
        null
    }

    /** Identity of a place, for the back gesture and the selection ("" = the roots, above everything). */
    private fun keyOf(loc: Location): String = when (loc) {
        Location.Roots -> ""
        is Location.Local -> loc.dir.absolutePath
        is Location.Cloud -> "onedrive:" + loc.path
    }

    /** The folder the view settings of a place are saved for (OneDrive folders under a virtual /onedrive). */
    private fun viewDirOf(loc: Location): File = when (loc) {
        Location.Roots -> File("/")
        is Location.Local -> loc.dir
        is Location.Cloud -> File(CLOUD_VIEWS, loc.path.trimStart('/'))
    }

    private val viewDir: File get() = viewDirOf(location)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        views = FolderViewsRepository(this)
        oneDrive = OneDrive(this)
        oneDrive.clearCache()
        setContentView(buildLayout())
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = if (selecting) clearSelection() else goBack()
        })
        location = startLocation(intent)
        entryKey = keyOf(location)
        load()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The activity is reused (singleTask): the new launch sets a new entry folder for the back gesture
        setIntent(intent)
        location = startLocation(intent)
        entryKey = keyOf(location)
        load()
    }

    /** Place the explorer was opened in ([keyOf]): the back gesture leaves from there. */
    private var entryKey = ""

    private var firstResume = true

    override fun onResume() {
        super.onResume()
        if (waitingAccess) {
            waitingAccess = false
            load()
        } else if (!firstResume && ::location.isInitialized) {
            // Back from the viewer/editor: edited OneDrive files go back; phone folders show saved copies
            if (!syncBack() && location is Location.Local) load()
        }
        firstResume = false
    }

    private fun startLocation(intent: Intent?): Location {
        intent?.getStringExtra(EXTRA_CLOUD_PATH)?.let { path -> cloudLocationOf(path)?.let { return it } }
        intent?.getStringExtra(EXTRA_LOCAL_PATH)?.let { return Location.Local(File(it)) }
        if (pickMode && !allowCloud) return Location.Local(storageRoot)
        if (intent?.getBooleanExtra(EXTRA_CLOUD, false) == true) return cloudRoot()
        return Location.Roots
    }

    // ---------------------------------------------------------------- layout

    private fun buildLayout(): View {
        val primary = UiKit.primary(this)
        val secondary = UiKit.secondary(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top + 8.dpToPx(), v.paddingRight, bars.bottom + 8.dpToPx())
            insets
        }
        val viewButton = UiKit.iconButton(this, R.drawable.ic_m_sliders, getString(R.string.ml_explorer_view)) { showViewMenu() }
        newFolder = UiKit.iconButton(this, R.drawable.ic_m_add, getString(R.string.ml_explorer_new_folder)) { if (pickMode) askNewFolder() else showAddMenu() }
        val bar = UiKit.appBar(this, "", onBack = { if (selecting) clearSelection() else goUp() }, actions = listOf(viewButton, newFolder))
        bar.view.setPadding(6.dpToPx(), 0, 6.dpToPx(), 4.dpToPx())
        backButton = bar.back
        title = bar.title
        subtitle = bar.subtitle
        subtitle.compoundDrawablePadding = 4.dpToPx()
        subtitle.gravity = Gravity.CENTER_VERTICAL
        headerButtons = listOf(viewButton, newFolder)
        root.addView(bar.view)
        // Status line, with a spinning circle while something loads or runs
        statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20.dpToPx(), 12.dpToPx(), 20.dpToPx(), 12.dpToPx())
            isVisible = false
        }
        statusSpinner = android.widget.ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(primary)
            isVisible = false
        }
        statusRow.addView(statusSpinner, LinearLayout.LayoutParams(18.dpToPx(), 18.dpToPx()).apply { marginEnd = 10.dpToPx() })
        status = TextView(this, null, 0, R.style.TextSmall).apply {
            setTextColor(secondary)
        }
        statusRow.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(statusRow)
        val listArea = FrameLayout(this)
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@FileExplorerActivity)
            adapter = EntriesAdapter()
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(12.dpToPx(), 0, 12.dpToPx(), 8.dpToPx())
            addItemDecoration(separators)
        }
        dragHelper.attachToRecyclerView(list)
        listArea.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        emptyView = UiKit.emptyState(this, R.drawable.ic_m_folder_open, getString(R.string.ml_explorer_empty)).apply { isVisible = false }
        listArea.addView(emptyView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(listArea, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        selectionBar = UiKit.toolBar(this).apply { isVisible = false }
        listOf(
            Tool(R.drawable.ic_m_share, R.string.ml_explorer_share) { shareSelected() },
            Tool(R.drawable.ic_m_copy, R.string.ml_explorer_copy) { clipSelected(move = false) },
            Tool(R.drawable.ic_m_move, R.string.ml_explorer_move) { clipSelected(move = true) },
            Tool(R.drawable.ic_m_delete, R.string.ml_explorer_delete) { deleteSelected() },
            Tool(R.drawable.ic_m_more, R.string.ml_explorer_more) { showSelectionMenu() },
        ).forEach { tool ->
            selectionBar.addView(UiKit.toolButton(this, tool.icon, getString(tool.label)) { tool.action() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(selectionBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(12.dpToPx(), 8.dpToPx(), 12.dpToPx(), 0)
        })
        pasteBar = UiKit.toolBar(this).apply {
            orientation = LinearLayout.VERTICAL
            isVisible = false
        }
        pasteText = TextView(this, null, 0, R.style.TextSmall).apply {
            setTextColor(primary)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(12.dpToPx(), 10.dpToPx(), 12.dpToPx(), 2.dpToPx())
        }
        pasteBar.addView(pasteText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val pasteTools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf(
            Tool(R.drawable.ic_m_paste, R.string.ml_explorer_paste) { paste() },
            Tool(R.drawable.ic_m_add, R.string.ml_explorer_new_folder) { askNewFolder() },
            Tool(R.drawable.ic_m_close, R.string.close) {
                clip = null
                updatePasteBar()
            },
        ).forEach { tool ->
            pasteTools.addView(UiKit.toolButton(this, tool.icon, getString(tool.label)) { tool.action() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        pasteBar.addView(pasteTools, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(pasteBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(12.dpToPx(), 8.dpToPx(), 12.dpToPx(), 0)
        })
        if (pickMode) useButton = pillButton(this, getString(R.string.ml_explorer_use_folder), filled = true, color = primary) {
            // A OneDrive folder comes back as a "onedrive:" path (see cloudPathOf)
            val path = when (val loc = location) {
                is Location.Cloud -> cloudPathOf(loc)
                is Location.Local -> loc.dir.absolutePath
                Location.Roots -> return@pillButton
            }
            setResult(RESULT_OK, Intent().putExtra(EXTRA_LOCAL_PATH, path))
            finish()
        }.also {
            root.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 48.dpToPx()).apply {
                setMargins(16.dpToPx(), 8.dpToPx(), 16.dpToPx(), 0)
            })
        }
        return root
    }

    /** The phone or cloud icon before the path. */
    private fun setSubtitleIcon(res: Int) {
        ContextCompat.getDrawable(this, res)?.mutate()?.let { d ->
            d.setTint(UiKit.secondary(this))
            d.setBounds(0, 0, 14.dpToPx(), 14.dpToPx())
            subtitle.setCompoundDrawablesRelative(d, null, null, null)
        }
    }

    /** Thin lines between the rows of the list (none in the grid). */
    private val separators = object : RecyclerView.ItemDecoration() {
        private val paint = android.graphics.Paint()

        override fun onDraw(c: android.graphics.Canvas, parent: RecyclerView, state: RecyclerView.State) {
            if (view.grid) return
            paint.color = UiKit.line(this@FileExplorerActivity)
            // From the names (after the tile) to the right edge
            val left = (parent.paddingLeft + 60.dpToPx()).toFloat()
            val right = (parent.width - parent.paddingRight).toFloat()
            val thickness = 1.dpToPx().coerceAtLeast(1).toFloat()
            for (i in 0 until parent.childCount - 1) {
                val child = parent.getChildAt(i)
                val y = child.bottom + child.translationY
                c.drawRect(left, y - thickness, right, y, paint)
            }
        }
    }

    // ---------------------------------------------------------------- navigation

    private fun goUp() {
        val up: Location? = when (val loc = location) {
            Location.Roots -> null
            is Location.Local ->
                if (loc.dir.absolutePath != storageRoot.absolutePath) Location.Local(loc.dir.parentFile ?: storageRoot)
                else if (!allowCloud) null else Location.Roots
            is Location.Cloud -> loc.parent ?: Location.Roots
        }
        if (up == null) finish() else open(up)
    }

    /** Back gesture: up one folder while inside the place the explorer was opened in; past it, leave the explorer. */
    private fun goBack() {
        val entry = entryKey
        val key = keyOf(location)
        val inside = key != entry && (entry.isEmpty() || key.startsWith("$entry/"))
        if (inside) goUp() else finish()
    }

    private fun open(loc: Location) {
        location = loc
        load()
    }

    private fun load() {
        // A selection belongs to the folder it was made in
        val key = keyOf(location)
        val changed = loadedKey != key
        if (changed) selected.clear()
        loadedKey = key
        updatePasteBar()
        when (val loc = location) {
            Location.Roots -> showRoots()
            is Location.Local -> loadLocal(loc)
            is Location.Cloud -> loadCloud(loc, clear = changed)
        }
        updateSelection()
    }

    private fun titleOf(loc: Location): String = when (loc) {
        Location.Roots -> getString(R.string.ml_explorer)
        is Location.Local -> if (loc.dir.absolutePath == storageRoot.absolutePath) getString(R.string.ml_search_phone) else loc.dir.name
        is Location.Cloud -> loc.name
    }

    // ---------------------------------------------------------------- multiple selection

    private fun toggleSelect(entry: Entry) {
        if (!selected.remove(entry.key)) selected.add(entry.key)
        updateSelection()
        list.adapter?.notifyDataSetChanged()
    }

    private fun clearSelection() {
        selected.clear()
        updateSelection()
        list.adapter?.notifyDataSetChanged()
    }

    private fun selectedEntries() = entries.filter { it.key in selected }

    private fun updateSelection() {
        backButton.setImageResource(if (selecting) R.drawable.ic_m_close else R.drawable.ic_m_arrow_back)
        backButton.contentDescription = getString(if (selecting) R.string.close else R.string.ml_cal_back)
        selectionBar.isVisible = selecting
        headerButtons.forEach { it.isVisible = !selecting && location != Location.Roots }
        useButton?.isVisible = location != Location.Roots
        if (selecting) {
            title.text = getString(R.string.ml_explorer_selected, selected.size)
            pasteBar.isVisible = false
        } else {
            title.text = titleOf(location)
            updatePasteBar()
        }
    }

    private fun shareSelected() {
        val chosen = selectedEntries().filter { !it.isFolder }
        if (chosen.isEmpty()) {
            showToast(getString(R.string.ml_explorer_share_no_folders))
            return
        }
        withLocalFiles(chosen) { files ->
            val intent = if (files.size == 1) Intent(Intent.ACTION_SEND).setType(chosen[0].mime ?: "*/*")
                .putExtra(Intent.EXTRA_STREAM, uriFor(files[0]))
            else Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files.map { uriFor(it) }))
            startActivity(Intent.createChooser(intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
        }
    }

    private fun clipSelected(move: Boolean) {
        val chosen = selectedEntries()
        if (move && needsWriteAccess(chosen)) return
        clip = Clip(chosen, move)
        clearSelection()
    }

    private fun deleteSelected() {
        val chosen = selectedEntries()
        if (needsWriteAccess(chosen)) return
        showConfirmDialog(
            getString(R.string.ml_explorer_delete),
            if (chosen.size == 1) getString(R.string.ml_explorer_delete_confirm, chosen[0].name)
            else getString(R.string.ml_explorer_delete_many_confirm, chosen.size),
            getString(R.string.ml_explorer_delete),
        ) {
            selected.clear()
            run(getString(R.string.ml_explorer_delete)) {
                chosen.map { it.file?.deleteRecursively() ?: it.cloud?.let { item -> oneDrive.delete(item.id) } ?: false }.all { it }
            }
        }
    }

    /** "More": the single-item actions, plus select all. */
    private fun showSelectionMenu() {
        val chosen = selectedEntries()
        val items = mutableListOf<MenuEntry>()
        chosen.singleOrNull()?.let { entry ->
            if (!entry.isFolder) items += MenuEntry(getString(R.string.ml_explorer_open_with)) {
                withLocalFiles(listOf(entry)) { view(it[0], entry.mime, chooser = true) }
            }
            items += MenuEntry(getString(R.string.rename)) {
                clearSelection()
                askRename(entry)
            }
        }
        // Hide keeps the files where they are: they just leave the list (back with "Hidden files" in the view)
        val allHidden = chosen.isNotEmpty() && chosen.all { it.name in hiddenNames }
        items += MenuEntry(getString(if (allHidden) R.string.ml_explorer_unhide else R.string.ml_explorer_hide)) {
            hideSelected(chosen, hide = !allHidden)
        }
        items += MenuEntry(getString(R.string.ml_explorer_select_all)) {
            entries.forEach { selected.add(it.key) }
            updateSelection()
            list.adapter?.notifyDataSetChanged()
        }
        items += MenuEntry(getString(R.string.ml_explorer_new_folder)) {
            clearSelection()
            askNewFolder()
        }
        showMenuDialog(getString(R.string.ml_explorer_selected, chosen.size), items)
    }

    private fun hideSelected(chosen: List<Entry>, hide: Boolean) {
        views.setHidden(viewDir, chosen.map { it.name }, hide)
        selected.clear()
        if (hide && !view.showHidden) showToast(getString(R.string.ml_explorer_hidden_hint))
        load()
    }

    /** The status line; [busy] shows the spinning circle (loading, an operation running). */
    private fun showStatus(text: String?, busy: Boolean = false, onClick: (() -> Unit)? = null) {
        statusRow.isVisible = text != null
        statusSpinner.isVisible = busy
        status.text = text
        if (onClick != null) statusRow.setOnClickListener { onClick() } else statusRow.setOnClickListener(null)
    }

    /** Hidden: a dot name, or hidden by the user in this folder. */
    private fun isHidden(name: String) = name.startsWith(".") || name in hiddenNames

    private fun setEntries(list: List<Entry>, sort: Boolean = true) {
        entries = (if (sort) sorted(list) else list).toMutableList()
        val manager = this.list.layoutManager
        if (view.grid && (manager !is GridLayoutManager || manager.spanCount != view.columns))
            this.list.layoutManager = GridLayoutManager(this, view.columns)
        else if (!view.grid && (manager !is LinearLayoutManager || manager is GridLayoutManager))
            this.list.layoutManager = LinearLayoutManager(this)
        // Cells are sized for the columns when created: rebuild them all
        this.list.adapter = EntriesAdapter()
        this.list.scrollToPosition(0)
    }

    private fun sorted(list: List<Entry>): List<Entry> {
        if (view.sort == FolderView.SORT_MANUAL) {
            val order = views.manualOrder(viewDir).withIndex().associate { it.value to it.index }
            // Items not placed yet go at the end, by name
            return list.sortedWith(compareBy<Entry>({ order[it.name] ?: Int.MAX_VALUE }, { it.name.lowercase() }))
        }
        val byKey: Comparator<Entry> = when (view.sort) {
            FolderView.SORT_DATE -> compareBy { it.modified }
            FolderView.SORT_SIZE -> compareBy { it.size }
            FolderView.SORT_TYPE -> compareBy<Entry> { it.name.substringAfterLast('.', "").lowercase() }.thenBy { it.name.lowercase() }
            else -> compareBy { it.name.lowercase() }
        }
        val ordered = if (view.descending) byKey.reversed() else byKey
        return if (view.foldersFirst) list.sortedWith(compareBy<Entry> { !it.isFolder }.then(ordered)) else list.sortedWith(ordered)
    }

    private fun hasFilesAccess() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun askFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            waitingAccess = true
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        load()
    }

    /** The two roots: the phone storage and OneDrive. */
    private fun showRoots() {
        title.text = titleOf(Location.Roots)
        emptyView.isVisible = false
        subtitle.isVisible = false
        showStatus(null)
        view = FolderView()
        viewSource = null
        hiddenNames = emptySet()
        setEntries(listOf(
            Entry(getString(R.string.ml_search_phone), true, 0, 0, root = Location.Local(storageRoot), info = getString(R.string.ml_explorer_phone_storage)),
            Entry(getString(R.string.ml_search_onedrive), true, 0, 0, root = cloudRoot(),
                info = getString(if (oneDrive.isConnected()) R.string.ml_onedrive_personal else R.string.ml_onedrive_connect)),
        ), sort = false)
    }

    private fun loadLocal(loc: Location.Local) {
        title.text = titleOf(loc)
        emptyView.isVisible = false
        setSubtitleIcon(R.drawable.ic_m_phone)
        subtitle.text = "/" + relative(loc.dir)
        subtitle.isVisible = true
        // An album folder (only photos and videos) shows no path, like the album grid
        lifecycleScope.launch {
            if (location == loc && albumCover(loc.dir) != null) subtitle.isVisible = false
        }
        if (!hasFilesAccess()) {
            setEntries(emptyList())
            showStatus(getString(R.string.ml_search_allow_files)) { askFilesAccess() }
            return
        }
        views.effective(loc.dir).let { (v, source) ->
            view = v
            viewSource = source
        }
        hiddenNames = views.hidden(loc.dir)
        val files = loc.dir.listFiles()
        if (files == null) {
            setEntries(emptyList())
            showStatus(getString(R.string.ml_explorer_cannot_read))
            return
        }
        setEntries(files.filter { view.showHidden || !isHidden(it.name) }.map { f ->
            Entry(f.name, f.isDirectory, if (f.isDirectory) 0 else f.length(), f.lastModified(), f, mimeOf(f.name))
        })
        showStatus(null)
        emptyView.isVisible = entries.isEmpty()
    }

    /** A OneDrive folder; [clear] empties the list while loading (a new folder, not a reload). */
    private fun loadCloud(loc: Location.Cloud, clear: Boolean) {
        title.text = titleOf(loc)
        emptyView.isVisible = false
        setSubtitleIcon(R.drawable.ic_m_cloud_line)
        subtitle.text = "/" + loc.path.trimStart('/')
        subtitle.isVisible = true
        views.effective(viewDirOf(loc)).let { (v, source) ->
            view = v
            viewSource = source
        }
        hiddenNames = views.hidden(viewDirOf(loc))
        if (!oneDrive.isConnected()) {
            setEntries(emptyList())
            // Why the sign-in was lost, if it was (e.g. Microsoft refused to renew it)
            val reason = oneDrive.lastSignInError()?.let { "\n" + getString(R.string.ml_onedrive_reason, it) }.orEmpty()
            showStatus(getString(R.string.ml_onedrive_connect) + reason) { oneDrive.connect() }
            return
        }
        if (clear) setEntries(emptyList())
        showStatus(getString(R.string.ml_explorer_loading), busy = true)
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { oneDrive.children(loc.id) }
            if (location != loc) return@launch
            if (items == null) {
                setEntries(emptyList())
                val reason = oneDrive.lastSignInError()?.let { "\n" + getString(R.string.ml_onedrive_reason, it) }.orEmpty()
                showStatus(getString(R.string.ml_onedrive_error) + reason) { oneDrive.connect() }
                return@launch
            }
            setEntries(items.filter { view.showHidden || !isHidden(it.name) }.map {
                Entry(it.name, it.isFolder, it.size, it.modified, mime = it.mimeType ?: mimeOf(it.name), cloud = it)
            })
            showStatus(null)
            emptyView.isVisible = entries.isEmpty()
        }
    }

    // ---------------------------------------------------------------- view settings

    /** Changes the view of the folder shown (it gets its own settings, inherited by subfolders). */
    private fun updateView(change: (FolderView) -> FolderView) {
        view = change(view)
        viewSource = viewDir
        views.save(viewDir, view)
        load()
    }

    private fun toggle(label: Int, value: Boolean, change: (FolderView, Boolean) -> FolderView) =
        MenuEntry(getString(label), checked = value, onToggle = { on ->
            updateView { change(it, on) }
            true
        })

    private fun sortLabel(sort: String) = getString(
        when (sort) {
            FolderView.SORT_DATE -> R.string.ml_explorer_sort_date
            FolderView.SORT_SIZE -> R.string.ml_explorer_sort_size
            FolderView.SORT_TYPE -> R.string.ml_explorer_sort_type
            FolderView.SORT_MANUAL -> R.string.ml_explorer_sort_manual
            else -> R.string.ml_explorer_sort_name
        }
    )

    private fun showViewMenu() {
        val v = view
        val entries = mutableListOf(
            toggle(R.string.ml_explorer_grid, v.grid) { it, on -> it.copy(grid = on) },
        )
        if (v.grid) entries += MenuEntry(getString(R.string.ml_explorer_columns, v.columns)) {
            showMenuDialog(getString(R.string.ml_explorer_columns_title), (2..6).map { n ->
                MenuEntry(n.toString()) { updateView { it.copy(columns = n) } }
            })
        }
        entries += MenuEntry(getString(R.string.ml_explorer_sort_by, sortLabel(v.sort))) {
            val sorts = listOf(FolderView.SORT_NAME, FolderView.SORT_DATE, FolderView.SORT_SIZE, FolderView.SORT_TYPE, FolderView.SORT_MANUAL)
            showMenuDialog(getString(R.string.ml_explorer_sort_title), sorts.map { sort ->
                MenuEntry(sortLabel(sort)) {
                    updateView { it.copy(sort = sort) }
                    if (sort == FolderView.SORT_MANUAL) showToast(getString(R.string.ml_explorer_manual_hint))
                }
            })
        }
        if (v.sort != FolderView.SORT_MANUAL) {
            entries += toggle(R.string.ml_explorer_descending, v.descending) { it, on -> it.copy(descending = on) }
            entries += toggle(R.string.ml_explorer_folders_first, v.foldersFirst) { it, on -> it.copy(foldersFirst = on) }
        }
        if (v.grid) entries += toggle(R.string.ml_explorer_show_names, v.showNames) { it, on -> it.copy(showNames = on) }
        else {
            entries += toggle(R.string.ml_explorer_show_size, v.showSize) { it, on -> it.copy(showSize = on) }
            entries += toggle(R.string.ml_explorer_show_date, v.showDate) { it, on -> it.copy(showDate = on) }
        }
        entries += toggle(R.string.ml_explorer_show_extension, v.showExtension) { it, on -> it.copy(showExtension = on) }
        entries += toggle(R.string.ml_explorer_show_thumbnails, v.showThumbnails) { it, on -> it.copy(showThumbnails = on) }
        entries += toggle(R.string.ml_explorer_show_hidden, v.showHidden) { it, on -> it.copy(showHidden = on) }
        val own = viewSource?.absolutePath == viewDir.absolutePath
        if (own) entries += MenuEntry(getString(R.string.ml_explorer_view_reset)) {
            views.reset(viewDir)
            load()
        }
        val source = viewSource
        val info = when {
            own -> getString(R.string.ml_explorer_view_own)
            source != null -> getString(R.string.ml_explorer_view_from,
                if (source == CLOUD_VIEWS) getString(R.string.ml_search_onedrive) else source.name.ifBlank { "/" })
            else -> getString(R.string.ml_explorer_view_default)
        }
        showMenuDialog(getString(R.string.ml_explorer_view), entries, header = { dialog ->
            TextView(dialog.context, null, 0, R.style.TextSmall).apply {
                text = info
                alpha = 0.6f
                setTextColor(dialog.context.getColorFromAttr(R.attr.primaryColor))
                setPadding(0, 8.dpToPx(), 0, 4.dpToPx())
            }
        })
    }

    // ---------------------------------------------------------------- manual order (drag)

    private var dragMoved = false
    private var dragEntry: Entry? = null

    /** Manual order: long press and drag to move an item; a long press without moving opens its menu. */
    private val dragHelper = androidx.recyclerview.widget.ItemTouchHelper(object : androidx.recyclerview.widget.ItemTouchHelper.Callback() {
        override fun isLongPressDragEnabled() = view.sort == FolderView.SORT_MANUAL
        override fun isItemViewSwipeEnabled() = false

        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
            val dirs = androidx.recyclerview.widget.ItemTouchHelper.UP or androidx.recyclerview.widget.ItemTouchHelper.DOWN or
                    if (view.grid) androidx.recyclerview.widget.ItemTouchHelper.LEFT or androidx.recyclerview.widget.ItemTouchHelper.RIGHT else 0
            return makeMovementFlags(dirs, 0)
        }

        override fun onMove(recyclerView: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
            val a = from.bindingAdapterPosition
            val b = to.bindingAdapterPosition
            if (a < 0 || b < 0) return false
            entries.add(b, entries.removeAt(a))
            recyclerView.adapter?.notifyItemMoved(a, b)
            dragMoved = true
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                dragMoved = false
                dragEntry = entries.getOrNull(viewHolder.bindingAdapterPosition)
                viewHolder.itemView.alpha = 0.6f
            }
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            viewHolder.itemView.alpha = 1f
            if (dragMoved) views.saveManualOrder(viewDir, entries.map { it.name })
            else dragEntry?.let { onLongPress(it) }
            dragEntry = null
        }
    })

    private fun relative(f: File) = f.absolutePath.removePrefix(storageRoot.absolutePath).trimStart('/')

    private fun mimeOf(name: String) =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())

    // ---------------------------------------------------------------- items

    private fun onTap(entry: Entry) {
        if (selecting) return toggleSelect(entry)
        entry.root?.let { return open(it) }
        entry.cloud?.let { item ->
            return if (item.isFolder) open(Location.Cloud(item.id, item.name, location as? Location.Cloud))
            else withLocalFiles(listOf(entry)) { view(it[0], entry.mime) }
        }
        val file = entry.file ?: return
        if (!entry.isFolder) return view(file, entry.mime)
        if (pickMode) return open(Location.Local(file))
        // A folder with only photos and videos opens as an album grid, like from the search
        lifecycleScope.launch {
            val album = albumCover(file) != null
            if (album) AlbumActivity.open(this@FileExplorerActivity, file) else open(Location.Local(file))
        }
    }

    /**
     * The entries as local files: themselves on the phone, fresh downloads (in the cache) for
     * OneDrive; [action] runs only when all of them are there.
     */
    private fun withLocalFiles(chosen: List<Entry>, action: (List<File>) -> Unit) {
        if (chosen.all { it.file != null }) return action(chosen.mapNotNull { it.file })
        // A dialog with the progress, so a slow download doesn't look stuck; Cancel stops it
        val total = chosen.sumOf { it.cloud?.size ?: 0L }
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val dialog = showProgressDialog(getString(R.string.ml_onedrive_downloading_title), chosen.singleOrNull()?.name ?: "") { cancelled.set(true) }
        var lastUpdate = 0L
        fun progress(done: Long) {
            val now = System.currentTimeMillis()
            if (now - lastUpdate < 100) return
            lastUpdate = now
            runOnUiThread {
                if (total <= 0) return@runOnUiThread dialog.setProgress(null)
                dialog.setProgress(done.toFloat() / total)
                dialog.setMessage(listOfNotNull(
                    chosen.singleOrNull()?.name,
                    getString(R.string.ml_onedrive_progress, Formatter.formatShortFileSize(this, done), Formatter.formatShortFileSize(this, total)),
                ).joinToString("\n"))
            }
        }
        lifecycleScope.launch {
            val folder = location as? Location.Cloud
            var before = 0L
            val files = withContext(Dispatchers.IO) {
                chosen.map { entry ->
                    if (cancelled.get()) return@map null
                    entry.file ?: entry.cloud?.let { item ->
                        val start = before
                        oneDrive.downloadToCache(item) { done ->
                            progress(start + done)
                            !cancelled.get()
                        }.also { before = start + item.size }
                    }
                }
            }
            dialog.dismiss()
            if (cancelled.get()) return@launch
            if (files.any { it == null }) {
                showToast(getString(R.string.ml_onedrive_download_failed))
                return@launch
            }
            if (folder != null) chosen.zip(files).filter { it.first.cloud != null }.forEach { (_, f) ->
                val file = f ?: return@forEach
                opened += Opened(file, folder, file.lastModified(), file.parentFile?.list()?.toSet().orEmpty())
            }
            action(files.filterNotNull())
        }
    }

    /**
     * Uploads OneDrive files edited since they were opened (replacing them) and files saved next to
     * them; true when there was something to upload (the folder reloads afterwards).
     */
    private fun syncBack(): Boolean {
        val uploads = mutableListOf<Triple<File, Location.Cloud, Boolean>>()
        for (o in opened) {
            val dir = o.file.parentFile ?: continue
            if (o.file.exists() && o.file.lastModified() != o.modified) uploads += Triple(o.file, o.folder, true)
            val names = dir.list()?.toSet().orEmpty()
            (names - o.names).forEach { uploads += Triple(File(dir, it), o.folder, false) }
            o.modified = o.file.lastModified()
            o.names = names
        }
        if (uploads.isEmpty()) return false
        run(getString(R.string.ml_onedrive_uploading)) {
            uploads.map { (file, folder, replace) -> file.isFile && oneDrive.upload(file, folder.id, replace) }.all { it }
        }
        return true
    }

    /** True (and asks to connect again) when changing OneDrive needs an editing permission the sign-in lacks. */
    private fun needsWriteAccess(chosen: List<Entry> = emptyList()): Boolean {
        val cloud = location is Location.Cloud || chosen.any { it.cloud != null }
        if (cloud && !oneDrive.canWrite()) {
            showConfirmDialog(
                getString(R.string.ml_search_onedrive), getString(R.string.ml_explorer_reconnect), getString(R.string.ml_onedrive_connect),
            ) { oneDrive.connect() }
            return true
        }
        return false
    }

    /** Album covers found so far (folder path -> newest photo/video, or null when not an album). */
    private val albumCovers = HashMap<String, File?>()

    private suspend fun albumCover(dir: File): File? {
        if (albumCovers.containsKey(dir.absolutePath)) return albumCovers[dir.absolutePath]
        val cover = withContext(Dispatchers.IO) { MediaThumbnails.albumMedia(dir)?.firstOrNull() }
        albumCovers[dir.absolutePath] = cover
        return cover
    }

    /** Long press: starts (or extends) the multiple selection; on the OneDrive root, offers to disconnect it. */
    private fun onLongPress(entry: Entry) {
        if (entry.root == null) return toggleSelect(entry)
        if (entry.root is Location.Cloud && oneDrive.isConnected()) showMenuDialog(entry.name, listOf(
            MenuEntry(getString(R.string.ml_onedrive_disconnect)) {
                oneDrive.disconnect()
                load()
            },
        ))
    }

    private fun uriFor(file: File): Uri = FileProvider.getUriForFile(this, "$packageName.files", file)

    private fun view(file: File, mime: String?, chooser: Boolean = false) {
        if (!chooser && MediaThumbnails.isMedia(mime) && app.olauncher.data.Prefs(this).builtInViewer) {
            app.olauncher.ui.media.MediaViewerActivity.open(this, file)
            return
        }
        if (!chooser && mime == "application/pdf" && app.olauncher.data.Prefs(this).builtInPdf) {
            app.olauncher.ui.pdf.PdfViewerActivity.open(this, file)
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(file), mime ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // A OneDrive copy can be edited by the app that opens it: the changes go back when returning here
        if (file.absolutePath.startsWith(File(cacheDir, "onedrive").absolutePath + "/")) intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            startActivity(if (chooser) Intent.createChooser(intent, file.name) else intent)
        } catch (e: Exception) {
            showToast(getString(R.string.ml_search_cannot_open))
        }
    }

    private fun askRename(entry: Entry) {
        if (needsWriteAccess(listOf(entry))) return
        showTextInputDialog(getString(R.string.rename), entry.name, entry.name, getString(R.string.ml_save)) { name ->
            // A hidden item stays hidden under its new name
            if (entry.name in hiddenNames) {
                views.setHidden(viewDir, listOf(entry.name), hide = false)
                views.setHidden(viewDir, listOf(name), hide = true)
            }
            run(getString(R.string.rename)) {
                entry.file?.let { it.renameTo(File(it.parentFile, name)) }
                    ?: entry.cloud?.let { oneDrive.rename(it.id, name) } ?: false
            }
        }
    }

    /** "+": a new folder, or a scanned document saved (by default) in this folder. */
    private fun showAddMenu() {
        newFolder.showDropdown(listOf(
            MenuEntry(getString(R.string.ml_explorer_new_folder), icon = R.drawable.ic_m_folder) { askNewFolder() },
            MenuEntry(getString(R.string.ml_scan_start), icon = R.drawable.ic_m_scan) {
                val loc = location
                if (loc !is Location.Cloud) ScannerActivity.open(this, viewDir)
                else if (!needsWriteAccess()) {
                    scanFolder = loc
                    scanLauncher.launch(ScannerActivity.namedIntent(this))
                }
            },
        ))
    }

    private fun askNewFolder() {
        if (location == Location.Roots || needsWriteAccess()) return
        showTextInputDialog(getString(R.string.ml_explorer_new_folder), "", getString(R.string.ml_explorer_folder_name), getString(R.string.ml_save)) { name ->
            val label = getString(R.string.ml_explorer_new_folder)
            // While copying or moving, go into the new folder: more can be made inside, then paste
            when (val loc = location) {
                is Location.Local -> {
                    val dir = File(loc.dir, name)
                    run(label, then = { ok -> if (ok && clip != null) open(Location.Local(dir)) }) { dir.mkdirs() }
                }
                is Location.Cloud -> {
                    var made: OneDrive.Item? = null
                    run(label, then = { ok ->
                        val item = made
                        if (ok && clip != null && item != null) open(Location.Cloud(item.id, item.name, loc))
                    }) {
                        made = oneDrive.createFolder(loc.id, name)
                        made != null
                    }
                }
                Location.Roots -> {}
            }
        }
    }

    /** Runs a file operation off the main thread, then reloads the folder. */
    private fun run(label: String, then: ((Boolean) -> Unit)? = null, op: () -> Boolean) {
        showStatus(getString(R.string.ml_explorer_working, label), busy = true)
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    op()
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
            if (!ok) showToast(getString(R.string.ml_explorer_failed, label))
            load()
            then?.invoke(ok)
        }
    }

    // ---------------------------------------------------------------- copy / move

    private fun updatePasteBar() {
        val c = clip
        pasteBar.isVisible = c != null && !selecting && location != Location.Roots
        if (c != null) pasteText.text = when (c.entries.size) {
            1 -> getString(if (c.move) R.string.ml_explorer_moving else R.string.ml_explorer_copying, c.entries[0].name)
            else -> getString(if (c.move) R.string.ml_explorer_moving_many else R.string.ml_explorer_copying_many, c.entries.size)
        }
    }

    private fun paste() {
        val c = clip ?: return
        val target = location
        if (target == Location.Roots || needsWriteAccess(if (c.move) c.entries else emptyList())) return
        // A folder can't go inside itself
        if (target is Location.Local && c.entries.any { e -> e.file?.let { (target.dir.absolutePath + "/").startsWith(it.absolutePath + "/") } == true }) {
            showToast(getString(R.string.ml_explorer_into_itself))
            return
        }
        clip = null
        val label = getString(if (c.move) R.string.ml_explorer_move else R.string.ml_explorer_copy)
        run(label) {
            c.entries.map { entry ->
                when (target) {
                    is Location.Local -> pasteToLocal(entry, target.dir, c.move)
                    is Location.Cloud -> pasteToCloud(entry, target.id, c.move)
                    Location.Roots -> false
                }
            }.all { it }
        }
    }

    private fun uniqueTarget(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
        var i = 1
        while (f.exists()) f = File(dir, "$base (${i++})$ext")
        return f
    }

    private fun pasteToLocal(entry: Entry, dir: File, move: Boolean): Boolean {
        entry.file?.let { src ->
            val dest = uniqueTarget(dir, src.name)
            if (move && src.renameTo(dest)) return true
            if (!src.copyRecursively(dest)) return false
            return !move || src.deleteRecursively()
        }
        val item = entry.cloud ?: return false
        if (!downloadTree(item, uniqueTarget(dir, item.name))) return false
        return !move || oneDrive.delete(item.id)
    }

    private fun downloadTree(item: OneDrive.Item, dest: File): Boolean {
        if (!item.isFolder) return oneDrive.downloadTo(item, dest)
        if (!dest.mkdirs()) return false
        val children = oneDrive.children(item.id) ?: return false
        return children.all { downloadTree(it, File(dest, it.name)) }
    }

    private fun pasteToCloud(entry: Entry, folderId: String?, move: Boolean): Boolean {
        entry.cloud?.let { item ->
            return if (move) oneDrive.move(item.id, folderId) else oneDrive.copy(item.id, folderId)
        }
        val src = entry.file ?: return false
        if (!uploadTree(src, folderId)) return false
        return !move || src.deleteRecursively()
    }

    private fun uploadTree(src: File, folderId: String?): Boolean {
        if (!src.isDirectory) return oneDrive.upload(src, folderId)
        val folder = oneDrive.createFolder(folderId, src.name) ?: return false
        return src.listFiles().orEmpty().all { uploadTree(it, folder.id) }
    }

    // ---------------------------------------------------------------- list

    private fun kindOf(entry: Entry) = when (entry.root) {
        is Location.Local -> UiKit.FileKind.PHONE
        is Location.Cloud -> UiKit.FileKind.CLOUD
        else -> UiKit.kindOf(entry.name, entry.mime, entry.isFolder)
    }

    private fun shownName(entry: Entry) =
        if (view.showExtension || entry.isFolder || !entry.name.contains('.')) entry.name else entry.name.substringBeforeLast('.')

    private fun hasThumbnail(entry: Entry) = view.showThumbnails && !entry.isFolder && (entry.cloud?.thumbnail != null ||
            entry.file != null && (entry.mime?.startsWith("image/") == true || entry.mime?.startsWith("video/") == true))

    /** Thumbnail with 12 dp rounded corners (clipped to its background). */
    private fun roundedImage() = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 12.dpToPx().toFloat()
            setColor(UiKit.card(this@FileExplorerActivity))
        }
        clipToOutline = true
    }

    /** Subtle fill of a selected row. */
    private fun selectedFill() = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = 12.dpToPx().toFloat()
        setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(UiKit.primary(this@FileExplorerActivity), 0x18))
    }

    private inner class EntriesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = entries.size

        override fun getItemViewType(position: Int) = if (view.grid) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(if (viewType == 1) gridCell(parent) else listRow(parent)) {}

        /** The icon box of a row or cell: file tile (0), thumbnail (1), selection badge (2). */
        private fun iconBox(ctx: Context, box: FrameLayout) = box.apply {
            addView(FrameLayout(ctx), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(roundedImage(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(FrameLayout(ctx).apply { isVisible = false }, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END
            ).apply { setMargins(0, 6.dpToPx(), 6.dpToPx(), 0) })
        }

        private fun listRow(parent: ViewGroup): View {
            val ctx = parent.context
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(8.dpToPx(), 10.dpToPx(), 8.dpToPx(), 10.dpToPx())
                isClickable = true
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            row.addView(iconBox(ctx, FrameLayout(ctx)), LinearLayout.LayoutParams(40.dpToPx(), 40.dpToPx()).apply { marginEnd = 12.dpToPx() })
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
                maxLines = 1
                setPadding(0, 2.dpToPx(), 0, 0)
            })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return row
        }

        private fun gridCell(parent: ViewGroup): View {
            val ctx = parent.context
            val width = (parent.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) - parent.paddingLeft - parent.paddingRight
            val side = width / view.columns
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(3.dpToPx(), 3.dpToPx(), 3.dpToPx(), 6.dpToPx())
                isClickable = true
                layoutParams = RecyclerView.LayoutParams(side, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val box = FrameLayout(ctx).apply {
                background = UiKit.border(ctx, 12, UiKit.card(ctx))
                clipToOutline = true
            }
            val inner = side - 6.dpToPx()
            cell.addView(iconBox(ctx, box), LinearLayout.LayoutParams(inner, inner))
            cell.addView(TextView(ctx, null, 0, R.style.TextSmall).apply {
                setTextColor(UiKit.primary(ctx))
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_HORIZONTAL
                textSize = if (view.columns >= 5) 11f else 13f
                setPadding(0, 6.dpToPx(), 0, 0)
            })
            return cell
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val entry = entries[position]
            val ctx = this@FileExplorerActivity
            val root = holder.itemView as LinearLayout
            val iconBox = root.getChildAt(0) as FrameLayout
            val tileHolder = iconBox.getChildAt(0) as FrameLayout
            val image = iconBox.getChildAt(1) as ImageView
            val badge = iconBox.getChildAt(2) as FrameLayout
            val thumb = hasThumbnail(entry)
            val kind = kindOf(entry)
            if (tileHolder.tag != kind) {
                tileHolder.tag = kind
                tileHolder.removeAllViews()
                val tileDp = if (view.grid) (iconBox.layoutParams.width / resources.displayMetrics.density * 0.5f).toInt().coerceIn(28, 64) else 40
                val px = tileDp.dpToPx()
                tileHolder.addView(UiKit.fileTile(ctx, kind, tileDp), FrameLayout.LayoutParams(px, px, Gravity.CENTER))
            }
            tileHolder.isVisible = !thumb
            image.isVisible = thumb
            val size = if (view.grid) iconBox.layoutParams.width.coerceAtLeast(64) else 40.dpToPx()
            if (thumb && entry.file != null) MediaThumbnails.load(lifecycleScope, image, entry.file, size)
            else if (thumb && entry.cloud != null) {
                val item = entry.cloud
                val url = (if (size > 200) item.thumbnailLarge else null) ?: item.thumbnail
                MediaThumbnails.loadBytes(lifecycleScope, image, "onedrive:${item.id}:${item.modified}", size) { url?.let { oneDrive.thumbnailBytes(it) } }
            } else {
                image.tag = null
                image.setImageBitmap(null)
            }
            // Albums show their newest photo instead of the folder icon
            if (entry.isFolder && view.showThumbnails && entry.file != null) {
                val folder = entry.file
                val path = folder.absolutePath
                image.tag = path
                lifecycleScope.launch {
                    val cover = albumCover(folder) ?: return@launch
                    if (image.tag != path) return@launch
                    tileHolder.isVisible = false
                    image.isVisible = true
                    MediaThumbnails.load(lifecycleScope, image, cover, size)
                }
            }
            if (view.grid) {
                val name = root.getChildAt(1) as TextView
                name.text = shownName(entry)
                name.isVisible = view.showNames
            } else {
                val texts = root.getChildAt(1) as LinearLayout
                (texts.getChildAt(0) as TextView).text = shownName(entry)
                val info = texts.getChildAt(1) as TextView
                info.text = entry.info ?: listOfNotNull(
                    entry.size.takeIf { view.showSize && !entry.isFolder }?.let { Formatter.formatShortFileSize(ctx, it) },
                    entry.modified.takeIf { view.showDate && it > 0 }?.let { DateFormat.getMediumDateFormat(ctx).format(it) },
                ).joinToString(" · ")
                info.isVisible = info.text.isNotEmpty()
            }
            // Selected items: a subtle fill in the list, a check badge in the grid
            val isSelected = entry.key in selected
            root.background = if (isSelected && !view.grid) selectedFill() else UiKit.ripple(ctx)
            if (view.grid && selecting) {
                badge.removeAllViews()
                badge.addView(UiKit.checkBadge(ctx, isSelected, 24))
                badge.isVisible = true
            } else badge.isVisible = false
            // Hidden items (shown with "Hidden files" on) are faded
            root.alpha = when {
                selecting && !isSelected -> 0.6f
                entry.root == null && isHidden(entry.name) -> 0.45f
                else -> 1f
            }
            root.setOnClickListener { onTap(entry) }
            // In manual order a long press drags the item (the menu opens if it isn't moved)
            if (view.sort == FolderView.SORT_MANUAL && entry.root == null) root.setOnLongClickListener(null)
            else root.setOnLongClickListener {
                onLongPress(entry)
                true
            }
        }
    }

    companion object {
        const val EXTRA_LOCAL_PATH = "local_path"
        const val EXTRA_CLOUD = "cloud"
        /** A OneDrive folder to open, as made by [cloudPathOf] (e.g. a home folder shortcut). */
        const val EXTRA_CLOUD_PATH = "cloud_path"
        /** Folder picker: OneDrive folders can be picked too (they come back as "onedrive:" paths). */
        const val EXTRA_ALLOW_CLOUD = "allow_cloud"
        const val CLOUD_PREFIX = "onedrive:"

        fun isCloudPath(path: String?) = path?.startsWith(CLOUD_PREFIX) == true

        /** Name of the folder a path (phone or OneDrive) stands for. */
        fun folderName(context: Context, path: String): String =
            if (!isCloudPath(path)) File(path).name
            else try {
                val chain = org.json.JSONArray(path.removePrefix(CLOUD_PREFIX))
                if (chain.length() == 0) context.getString(R.string.ml_search_onedrive) else chain.getJSONObject(chain.length() - 1).optString("name")
            } catch (e: Exception) {
                context.getString(R.string.ml_search_onedrive)
            }

        fun openCloudPath(context: Context, path: String) = context.startActivity(
            Intent(context, FileExplorerActivity::class.java).putExtra(EXTRA_CLOUD_PATH, path)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )

        /** Virtual folder the OneDrive view settings are saved under. */
        private val CLOUD_VIEWS = File("/onedrive")

        fun openLocal(context: Context, dir: File) = context.startActivity(
            Intent(context, FileExplorerActivity::class.java).putExtra(EXTRA_LOCAL_PATH, dir.absolutePath)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )

        fun openCloud(context: Context) = context.startActivity(
            Intent(context, FileExplorerActivity::class.java).putExtra(EXTRA_CLOUD, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )

        fun openRoots(context: Context) = context.startActivity(
            Intent(context, FileExplorerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/**
 * The explorer as a folder picker (any folder, Download and the storage root included, which the
 * system picker refuses): browse, then "Use this folder"; the path comes back as [FileExplorerActivity.EXTRA_LOCAL_PATH].
 */
class FolderPickerActivity : FileExplorerActivity() {
    override val pickMode = true
}
