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
import app.olauncher.helper.showTextInputDialog
import app.olauncher.helper.showToast
import app.olauncher.ui.scanner.ScannerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Simple file explorer for the phone storage: browse, open with the phone's apps,
 * share, rename, delete, copy and move.
 *
 * Each folder can have its own view (the sliders icon): list or grid with 2-6 columns, sort order (or a manual
 * order, set by dragging), what to show. Subfolders use the settings of the closest folder above
 * that has some ([FolderViewsRepository]).
 *
 * Opened from the home search (a folder, or the folder of a file), the home menu or its own
 * "File" icon in the app list. [EXTRA_LOCAL_PATH] picks the starting folder; none starts at the
 * storage root.
 */
open class FileExplorerActivity : AppCompatActivity() {

    /** Folder picker mode (see [FolderPickerActivity]): a bar returns the folder shown. */
    protected open val pickMode = false

    /** File picker mode (see [FilePickerActivity]): only the files asked for are listed, a tap returns one. */
    protected open val filePick = false

    /** Several files can be picked: a tap marks them, a button returns them. */
    private val pickMultiple
        get() = filePick && (intent?.getBooleanExtra(EXTRA_PICK_MULTIPLE, false) == true || intent?.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false) == true)

    /** Only pictures (photos, videos) are being picked: the explorer shows them as a gallery would. */
    private val pickingPictures: Boolean
        get() {
            if (!filePick) return false
            val types = (intent?.getStringArrayExtra(EXTRA_PICK_TYPES) ?: emptyArray()).toMutableList()
            intent?.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.let { types += it }
            if (intent?.action == Intent.ACTION_GET_CONTENT) intent?.type?.let { types += it }
            return types.isNotEmpty() && types.all { it.startsWith("image/") || it.startsWith("video/") }
        }

    /** A file of a kind the one who asked wants (its own request, or another app's "get content"). */
    private fun pickAccepts(name: String, mime: String?): Boolean {
        val types = (intent?.getStringArrayExtra(EXTRA_PICK_TYPES) ?: emptyArray()).toMutableList()
        intent?.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.let { types += it }
        if (intent?.action == Intent.ACTION_GET_CONTENT) intent?.type?.let { types += it }
        val extensions = intent?.getStringArrayExtra(EXTRA_PICK_EXTENSIONS) ?: emptyArray()
        if (types.isEmpty() && extensions.isEmpty()) return true
        if (name.substringAfterLast('.', "").lowercase() in extensions) return true
        return types.any { type ->
            when {
                type == "*/*" -> true
                type == "audio/*" && app.olauncher.ui.media.AudioPlayerActivity.isAudio(name, mime) -> true
                type.endsWith("/*") -> mime?.startsWith(type.removeSuffix("*")) == true
                else -> mime == type
            }
        }
    }

    /** Returns the files picked: as paths to this app, as readable addresses to another one that asked. */
    private fun finishPick(files: List<File>) {
        if (files.isEmpty()) return
        val result = Intent().putStringArrayListExtra(EXTRA_PICKED_PATHS, ArrayList(files.map { it.absolutePath }))
        if (intent?.action == Intent.ACTION_GET_CONTENT) try {
            val uris = files.map { uriFor(it) }
            result.setDataAndType(uris[0], mimeOf(files[0].name) ?: "*/*")
            if (uris.size > 1) {
                val clip = android.content.ClipData.newUri(contentResolver, files[0].name, uris[0])
                uris.drop(1).forEach { clip.addItem(android.content.ClipData.Item(it)) }
                result.clipData = clip
            }
            result.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            e.printStackTrace()
            showToast(getString(R.string.ml_search_cannot_open))
            return
        }
        setResult(RESULT_OK, result)
        finish()
    }

    private var useButton: View? = null


    /** A phone file, or (only in the search results) one with its folder as [info]. */
    private class Entry(
        val name: String,
        val isFolder: Boolean,
        val size: Long,
        val modified: Long,
        val file: File,
        val mime: String? = null,
        /** Second line in place of size and date. */
        val info: String? = null,
    ) {
        /** Identity for the selection. */
        val key: String get() = file.absolutePath
    }

    private class Clip(val entries: List<Entry>, val move: Boolean)

    /** A tool of the bottom bars: line icon, label, action. */
    private class Tool(val icon: Int, val label: Int, val action: () -> Unit)

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

    /** The folder shown. */
    private lateinit var location: File
    private lateinit var views: FolderViewsRepository
    /** Settings of the folder shown and where they come from (null = defaults). */
    private var view = FolderView()
    private var viewSource: File? = null
    private var entries: MutableList<Entry> = mutableListOf()
    /** The folder's items as listed (sorted), before the search filter. */
    private var folderEntries: List<Entry> = emptyList()

    /** Search: the folder filtered while typing, then (on the phone) the matches in its subfolders. */
    private lateinit var searchRow: LinearLayout
    private lateinit var searchInput: android.widget.EditText
    private lateinit var searchButton: ImageView
    private var query = ""
    private var searchJob: kotlinx.coroutines.Job? = null
    private val searching get() = query.isNotEmpty()
    /** Names the user hid in the folder shown (see [hideSelected]). */
    private var hiddenNames: Set<String> = emptySet()
    private var clip: Clip? = null

    /** Multiple selection (paths), started by a long press. */
    private val selected = LinkedHashSet<String>()
    private val selecting get() = selected.isNotEmpty()
    /** Selecting by sliding over the items (after a long press, or sideways during a selection). */
    private lateinit var dragSelect: DragSelect
    private var loadedKey: String? = null
    private lateinit var backButton: ImageView
    private lateinit var selectionBar: LinearLayout
    private lateinit var headerButtons: List<View>
    private var waitingAccess = false

    private val storageRoot: File get() = Environment.getExternalStorageDirectory()

    /** Identity of a place, for the back gesture and the selection. */
    private fun keyOf(dir: File): String = dir.absolutePath

    private val viewDir: File get() = location

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        views = FolderViewsRepository(this)
        // Left by the OneDrive access of older versions (sign-in and downloaded copies)
        Thread {
            deleteSharedPreferences("app.minimalauncher.onedrive")
            File(cacheDir, "onedrive").deleteRecursively()
        }.start()
        setContentView(buildLayout())
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = when {
                selecting -> clearSelection()
                searchRow.isVisible -> closeSearch()
                else -> goBack()
            }
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
            // Back from the viewer/editor: show the copies saved meanwhile
            load()
        }
        firstResume = false
    }

    private fun startLocation(intent: Intent?): File =
        intent?.getStringExtra(EXTRA_LOCAL_PATH)?.let { File(it) }
            // Picking a photo: from where the camera puts them
            ?: (if (pickingPictures) File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera").takeIf { it.isDirectory } else null)
            ?: storageRoot

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
        searchButton = UiKit.iconButton(this, R.drawable.ic_m_search, getString(R.string.ml_explorer_search)) {
            if (searchRow.isVisible) closeSearch() else openSearch()
        }
        val viewButton = UiKit.iconButton(this, R.drawable.ic_m_sliders, getString(R.string.ml_explorer_view)) { showViewMenu() }
        newFolder = UiKit.iconButton(this, R.drawable.ic_m_add, getString(R.string.ml_explorer_new_folder)) { if (pickMode) askNewFolder() else showAddMenu() }
        val bar = UiKit.appBar(this, "", onBack = { if (selecting) clearSelection() else goUp() }, actions = listOf(searchButton, viewButton, newFolder))
        bar.view.setPadding(6.dpToPx(), 0, 6.dpToPx(), 4.dpToPx())
        backButton = bar.back
        title = bar.title
        subtitle = bar.subtitle
        subtitle.compoundDrawablePadding = 4.dpToPx()
        subtitle.gravity = Gravity.CENTER_VERTICAL
        headerButtons = listOf(searchButton, viewButton, newFolder)
        root.addView(bar.view)
        // Search field under the bar, shown by the search button
        searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dpToPx(), 2.dpToPx(), 8.dpToPx(), 6.dpToPx())
            isVisible = false
        }
        searchInput = android.widget.EditText(this).apply {
            UiKit.styleInput(this)
            hint = getString(R.string.ml_explorer_search_hint)
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { v, _, _ ->
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
                true
            }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    query = s?.toString()?.trim().orEmpty()
                    applySearch()
                }
            })
        }
        searchRow.addView(searchInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        searchRow.addView(UiKit.iconButton(this, R.drawable.ic_m_close, getString(R.string.close)) { closeSearch() })
        root.addView(searchRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
        dragSelect = DragSelect(list, { selecting },
            isSelected = { p -> entries.getOrNull(p)?.key in selected },
            setSelected = { p, on ->
                entries.getOrNull(p)?.let { if (on) selected.add(it.key) else selected.remove(it.key) }
            },
            onChanged = {
                updateSelection()
                list.adapter?.notifyDataSetChanged()
            })
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
        if (filePick && pickMultiple) useButton = pillButton(this, getString(R.string.ml_explorer_pick_files), filled = true, color = primary) {
            finishPick(selectedEntries().filter { !it.isFolder }.map { it.file })
        }.also {
            it.isVisible = false
            root.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 48.dpToPx()).apply {
                setMargins(16.dpToPx(), 8.dpToPx(), 16.dpToPx(), 0)
            })
        }
        if (pickMode) useButton = pillButton(this, getString(R.string.ml_explorer_use_folder), filled = true, color = primary) {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_LOCAL_PATH, location.absolutePath))
            finish()
        }.also {
            root.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 48.dpToPx()).apply {
                setMargins(16.dpToPx(), 8.dpToPx(), 16.dpToPx(), 0)
            })
        }
        return root
    }

    /** The phone icon before the path. */
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
        val dir = location
        if (dir.absolutePath == storageRoot.absolutePath) finish() else open(dir.parentFile ?: storageRoot)
    }

    /** Back gesture: up one folder while inside the place the explorer was opened in; past it, leave the explorer. */
    private fun goBack() {
        val entry = entryKey
        val key = keyOf(location)
        val inside = key != entry && key.startsWith("$entry/")
        if (inside) goUp() else finish()
    }

    private fun open(loc: File) {
        location = loc
        load()
    }

    private fun load() {
        // A selection belongs to the folder it was made in
        val key = keyOf(location)
        val changed = loadedKey != key
        if (changed) selected.clear()
        if (changed && searchRow.isVisible) closeSearch(reload = false)
        loadedKey = key
        updatePasteBar()
        loadLocal(location)
        updateSelection()
    }

    private fun titleOf(dir: File): String =
        if (dir.absolutePath == storageRoot.absolutePath) getString(R.string.ml_search_phone) else dir.name

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
        // Picking files: the marked ones are returned, not shared or moved
        selectionBar.isVisible = selecting && !filePick
        if (filePick) useButton?.isVisible = selecting
        headerButtons.forEach { it.isVisible = !selecting }
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
        val files = chosen.map { it.file }
        val intent = if (files.size == 1) Intent(Intent.ACTION_SEND).setType(chosen[0].mime ?: "*/*")
            .putExtra(Intent.EXTRA_STREAM, uriFor(files[0]))
        else Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files.map { uriFor(it) }))
        startActivity(Intent.createChooser(intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
    }

    private fun clipSelected(move: Boolean) {
        clip = Clip(selectedEntries(), move)
        clearSelection()
    }

    private fun deleteSelected() {
        val chosen = selectedEntries()
        showConfirmDialog(
            getString(R.string.ml_explorer_delete),
            if (chosen.size == 1) getString(R.string.ml_explorer_delete_confirm, chosen[0].name)
            else getString(R.string.ml_explorer_delete_many_confirm, chosen.size),
            getString(R.string.ml_explorer_delete),
        ) {
            selected.clear()
            run(getString(R.string.ml_explorer_delete)) {
                chosen.map { it.file.deleteRecursively() }.all { it }
            }
        }
    }

    /** "More": the single-item actions, plus select all. */
    private fun showSelectionMenu() {
        val chosen = selectedEntries()
        val items = mutableListOf<MenuEntry>()
        chosen.singleOrNull()?.let { entry ->
            if (!entry.isFolder) items += MenuEntry(getString(R.string.ml_explorer_open_with)) {
                view(entry.file, entry.mime, chooser = true)
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
        folderEntries = if (sort) sorted(list) else list
        if (searching) applySearch() else showEntries(folderEntries)
    }

    private fun showEntries(list: List<Entry>) {
        entries = list.toMutableList()
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

    // ---------------------------------------------------------------- search

    private fun openSearch() {
        searchRow.isVisible = true
        UiKit.setToolActive(searchButton, true)
        searchInput.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(searchInput, 0)
    }

    /** Hides the field and shows the whole folder again ([reload] false: a new folder is being loaded anyway). */
    private fun closeSearch(reload: Boolean = true) {
        searchJob?.cancel()
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(searchInput.windowToken, 0)
        searchRow.isVisible = false
        UiKit.setToolActive(searchButton, false)
        val had = searching
        query = ""
        if (searchInput.text.isNotEmpty()) searchInput.setText("")
        if (had && reload) {
            showStatus(null)
            showEntries(folderEntries)
            emptyView.isVisible = entries.isEmpty()
        }
    }

    private fun fold(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase()

    /**
     * The folder's items whose name holds the query (accents and case ignored) at once; on the phone,
     * after a short pause, the matches in the subfolders follow, with their folder under the name.
     */
    private fun applySearch() {
        searchJob?.cancel()
        if (!searching) {
            showStatus(null)
            showEntries(folderEntries)
            emptyView.isVisible = entries.isEmpty()
            return
        }
        val key = fold(query)
        val here = folderEntries.filter { fold(it.name).contains(key) }
        showEntries(here)
        emptyView.isVisible = false
        val loc = location
        if (!hasFilesAccess()) {
            showStatus(if (here.isEmpty()) getString(R.string.ml_explorer_no_results) else null)
            return
        }
        showStatus(getString(R.string.ml_explorer_searching), busy = true)
        val showHidden = view.showHidden
        val foldersOnly = pickMode && intent?.getBooleanExtra(EXTRA_FOLDERS_ONLY, false) == true
        searchJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(250)
            val deeper = withContext(Dispatchers.IO) {
                val found = mutableListOf<File>()
                fun walk(dir: File, depth: Int) {
                    if (found.size >= MAX_SEARCH_RESULTS || depth > MAX_SEARCH_DEPTH) return
                    val children = dir.listFiles() ?: return
                    for (f in children) {
                        if (!showHidden && f.name.startsWith(".")) continue
                        if (depth > 0 && fold(f.name).contains(key) && (!foldersOnly || f.isDirectory) &&
                            (!filePick || f.isDirectory || pickAccepts(f.name, mimeOf(f.name)))
                        ) found += f
                        if (found.size >= MAX_SEARCH_RESULTS) return
                        if (f.isDirectory) walk(f, depth + 1)
                    }
                }
                walk(loc, 0)
                found.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            }
            if (location != loc || fold(query) != key) return@launch
            val more = deeper.map { f ->
                Entry(f.name, f.isDirectory, if (f.isDirectory) 0 else f.length(), f.lastModified(), f, mimeOf(f.name),
                    info = "/" + relative(f.parentFile ?: loc))
            }
            if (more.isNotEmpty()) {
                val start = entries.size
                entries.addAll(more)
                list.adapter?.notifyItemRangeInserted(start, more.size)
            }
            showStatus(if (entries.isEmpty()) getString(R.string.ml_explorer_no_results) else null)
        }
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

    private fun loadLocal(dir: File) {
        title.text = titleOf(dir)
        emptyView.isVisible = false
        setSubtitleIcon(R.drawable.ic_m_phone)
        subtitle.text = "/" + relative(dir)
        subtitle.isVisible = true
        // An album folder (only photos and videos) shows no path, like the album grid
        lifecycleScope.launch {
            if (location == dir && albumCover(dir) != null) subtitle.isVisible = false
        }
        if (!hasFilesAccess()) {
            setEntries(emptyList())
            showStatus(getString(R.string.ml_search_allow_files)) { askFilesAccess() }
            return
        }
        views.effective(dir).let { (v, source) ->
            // Picking a photo in a folder with no view of its own: thumbnails, the newest first
            view = if (pickingPictures && source == null) v.copy(grid = true, columns = 3, sort = FolderView.SORT_DATE, descending = true) else v
            viewSource = source
        }
        hiddenNames = views.hidden(dir)
        // Read off the main thread: a chat folder holds thousands of files, each one read for its
        // kind, size and date, which froze the screen (the tap on the folder seemed to do nothing)
        localLoad?.cancel()
        // Another folder: its items must not stay tappable while the new one is read
        if (folderEntries.firstOrNull()?.file?.parentFile != dir) setEntries(emptyList())
        localLoad = lifecycleScope.launch {
            val listed = withContext(Dispatchers.IO) {
                dir.listFiles()?.map { f ->
                    val folder = f.isDirectory
                    Entry(f.name, folder, if (folder) 0 else f.length(), f.lastModified(), f, mimeOf(f.name))
                }
            }
            if (location != dir) return@launch
            if (listed == null) {
                setEntries(emptyList())
                showStatus(getString(R.string.ml_explorer_cannot_read))
                return@launch
            }
            val foldersOnly = pickMode && intent?.getBooleanExtra(EXTRA_FOLDERS_ONLY, false) == true
            setEntries(listed.filter {
                (view.showHidden || !isHidden(it.name)) && (!foldersOnly || it.isFolder) && (!filePick || it.isFolder || pickAccepts(it.name, it.mime))
            })
            showStatus(null)
            emptyView.isVisible = entries.isEmpty()
        }
    }

    /** The phone folder being read, cancelled when another one is opened. */
    private var localLoad: kotlinx.coroutines.Job? = null

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
            source != null -> getString(R.string.ml_explorer_view_from, source.name.ifBlank { "/" })
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
        override fun isLongPressDragEnabled() = view.sort == FolderView.SORT_MANUAL && !searching
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
        if (filePick) return when {
            entry.isFolder -> open(entry.file)
            pickMultiple -> toggleSelect(entry)
            else -> finishPick(listOf(entry.file))
        }
        if (selecting) return toggleSelect(entry)
        val file = entry.file
        if (!entry.isFolder) return view(file, entry.mime)
        if (pickMode) return open(file)
        // A folder with only photos and videos opens as an album grid, like from the search
        lifecycleScope.launch {
            val album = albumCover(file) != null
            if (album) AlbumActivity.open(this@FileExplorerActivity, file) else open(file)
        }
    }

    /** Album covers found so far (folder path -> newest photo/video, or null when not an album). */
    private val albumCovers = HashMap<String, File?>()

    private suspend fun albumCover(dir: File): File? {
        if (albumCovers.containsKey(dir.absolutePath)) return albumCovers[dir.absolutePath]
        val cover = withContext(Dispatchers.IO) { MediaThumbnails.albumMedia(dir)?.firstOrNull() }
        albumCovers[dir.absolutePath] = cover
        return cover
    }

    /** Long press: starts (or extends) the multiple selection. */
    private fun onLongPress(entry: Entry) {
        // Picking files: only files, and only when several can be picked
        if (filePick && (!pickMultiple || entry.isFolder)) return
        toggleSelect(entry)
        dragSelect.start(entries.indexOf(entry))
    }

    private fun uriFor(file: File): Uri = FileProvider.getUriForFile(this, "$packageName.files", file)

    private fun view(file: File, mime: String?, chooser: Boolean = false) {
        if (!chooser && MediaThumbnails.isMedia(mime) && app.olauncher.data.Prefs(this).builtInViewer) {
            app.olauncher.ui.media.MediaViewerActivity.open(this, file)
            return
        }
        if (!chooser && app.olauncher.ui.media.AudioPlayerActivity.isAudio(file.name, mime) && app.olauncher.data.Prefs(this).builtInViewer) {
            app.olauncher.ui.media.AudioPlayerActivity.open(this, file)
            return
        }
        if (!chooser && mime == "application/pdf" && app.olauncher.data.Prefs(this).builtInPdf) {
            app.olauncher.ui.pdf.PdfViewerActivity.open(this, file)
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(file), mime ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(if (chooser) Intent.createChooser(intent, file.name) else intent)
        } catch (e: Exception) {
            showToast(getString(R.string.ml_search_cannot_open))
        }
    }

    private fun askRename(entry: Entry) {
        showTextInputDialog(getString(R.string.rename), entry.name, entry.name, getString(R.string.ml_save)) { name ->
            // A hidden item stays hidden under its new name
            if (entry.name in hiddenNames) {
                views.setHidden(viewDir, listOf(entry.name), hide = false)
                views.setHidden(viewDir, listOf(name), hide = true)
            }
            run(getString(R.string.rename)) {
                entry.file.renameTo(File(entry.file.parentFile, name))
            }
        }
    }

    /** "+": a new folder, or a scanned document saved (by default) in this folder. */
    private fun showAddMenu() {
        newFolder.showDropdown(listOf(
            MenuEntry(getString(R.string.ml_explorer_new_folder), icon = R.drawable.ic_m_folder) { askNewFolder() },
            MenuEntry(getString(R.string.ml_scan_start), icon = R.drawable.ic_m_scan) { ScannerActivity.open(this, viewDir) },
        ))
    }

    private fun askNewFolder() {
        showTextInputDialog(getString(R.string.ml_explorer_new_folder), "", getString(R.string.ml_explorer_folder_name), getString(R.string.ml_save)) { name ->
            // While copying or moving, go into the new folder: more can be made inside, then paste
            val dir = File(location, name)
            run(getString(R.string.ml_explorer_new_folder), then = { ok -> if (ok && clip != null) open(dir) }) { dir.mkdirs() }
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
        pasteBar.isVisible = c != null && !selecting
        if (c != null) pasteText.text = when (c.entries.size) {
            1 -> getString(if (c.move) R.string.ml_explorer_moving else R.string.ml_explorer_copying, c.entries[0].name)
            else -> getString(if (c.move) R.string.ml_explorer_moving_many else R.string.ml_explorer_copying_many, c.entries.size)
        }
    }

    private fun paste() {
        val c = clip ?: return
        val target = location
        // A folder can't go inside itself
        if (c.entries.any { e -> (target.absolutePath + "/").startsWith(e.file.absolutePath + "/") }) {
            showToast(getString(R.string.ml_explorer_into_itself))
            return
        }
        clip = null
        val label = getString(if (c.move) R.string.ml_explorer_move else R.string.ml_explorer_copy)
        run(label) {
            c.entries.map { entry -> pasteToLocal(entry, target, c.move) }.all { it }
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
        val src = entry.file
        val dest = uniqueTarget(dir, src.name)
        if (move && src.renameTo(dest)) return true
        if (!src.copyRecursively(dest)) return false
        return !move || src.deleteRecursively()
    }

    // ---------------------------------------------------------------- list

    private fun kindOf(entry: Entry) = UiKit.kindOf(entry.name, entry.mime, entry.isFolder)

    private fun shownName(entry: Entry) =
        if (view.showExtension || entry.isFolder || !entry.name.contains('.')) entry.name else entry.name.substringBeforeLast('.')

    private fun hasThumbnail(entry: Entry) = view.showThumbnails && !entry.isFolder &&
            (entry.mime?.startsWith("image/") == true || entry.mime?.startsWith("video/") == true)

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
            if (thumb) MediaThumbnails.load(lifecycleScope, image, entry.file, size)
            else {
                image.tag = null
                image.setImageBitmap(null)
            }
            // Albums show their newest photo instead of the folder icon
            if (entry.isFolder && view.showThumbnails) {
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
                isHidden(entry.name) -> 0.45f
                else -> 1f
            }
            root.setOnClickListener { onTap(entry) }
            // In manual order a long press drags the item (the menu opens if it isn't moved)
            if (view.sort == FolderView.SORT_MANUAL && !searching) root.setOnLongClickListener(null)
            else root.setOnLongClickListener {
                onLongPress(entry)
                true
            }
        }
    }

    companion object {
        private const val MAX_SEARCH_RESULTS = 300
        private const val MAX_SEARCH_DEPTH = 8

        const val EXTRA_LOCAL_PATH = "local_path"
        /** Folder picker: only the folders are listed (e.g. to move a recording, whose own files would be in the way). */
        const val EXTRA_FOLDERS_ONLY = "folders_only"
        /** File picker: the MIME types and file endings that can be picked, whether several can, and the paths picked. */
        const val EXTRA_PICK_TYPES = "pick_types"
        const val EXTRA_PICK_EXTENSIONS = "pick_extensions"
        const val EXTRA_PICK_MULTIPLE = "pick_multiple"
        const val EXTRA_PICKED_PATHS = "picked_paths"

        /** Name of the folder a stored path stands for. */
        fun folderName(path: String): String = File(path).name

        fun openLocal(context: Context, dir: File) = context.startActivity(
            Intent(context, FileExplorerActivity::class.java).putExtra(EXTRA_LOCAL_PATH, dir.absolutePath)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )

        /** Opens the explorer at the storage root. */
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

/**
 * The explorer as a file picker, for the launcher's own screens (see [PickFiles]) and for other
 * apps asking for a file ("get content"): browse, tap a file (or mark several and confirm).
 */
class FilePickerActivity : FileExplorerActivity() {
    override val filePick = true
}
