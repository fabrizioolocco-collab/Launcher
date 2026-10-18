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
 * Simple file explorer for the phone storage: browse, open with the phone's apps, share, rename,
 * delete, copy and move.
 *
 * Each folder can have its own view (the sliders icon): list or grid with 2-6 columns, sort order (or a manual
 * order, set by dragging), what to show. Subfolders use the settings of the closest folder above
 * that has some ([FolderViewsRepository]).
 *
 * Opened from the home search (a folder, or the folder of a file), the home menu or its own
 * "File" icon in the app list. [EXTRA_LOCAL_PATH] picks the starting folder (default: storage root).
 */
open class FileExplorerActivity : AppCompatActivity() {

    /** Folder picker mode (see [FolderPickerActivity]): a bar returns the folder shown. */
    protected open val pickMode = false


    /** The folder shown. */
    private data class Location(val dir: File)

    private class Entry(
        val name: String,
        val isFolder: Boolean,
        val size: Long,
        val modified: Long,
        val file: File,
        val mime: String? = null,
    )

    private class Clip(val files: List<File>, val move: Boolean)

    /** A tool of the bottom bars: line icon, label, action. */
    private class Tool(val icon: Int, val label: Int, val action: () -> Unit)

    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var status: TextView
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
    private var clip: Clip? = null

    /** Multiple selection (paths), started by a long press. */
    private val selected = LinkedHashSet<String>()
    private val selecting get() = selected.isNotEmpty()
    private var loadedDir: String? = null
    private lateinit var backButton: ImageView
    private lateinit var selectionBar: LinearLayout
    private lateinit var headerButtons: List<View>
    private var waitingAccess = false

    private val storageRoot: File get() = Environment.getExternalStorageDirectory()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        views = FolderViewsRepository(this)
        setContentView(buildLayout())
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = if (selecting) clearSelection() else goBack()
        })
        location = startLocation(intent)
        entryDir = location.dir.absolutePath
        load()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The activity is reused (singleTask): the new launch sets a new entry folder for the back gesture
        setIntent(intent)
        location = startLocation(intent)
        entryDir = location.dir.absolutePath
        load()
    }

    /** Folder the explorer was opened in: the back gesture leaves from there. */
    private var entryDir = ""

    private var firstResume = true

    override fun onResume() {
        super.onResume()
        if (waitingAccess) {
            waitingAccess = false
            load()
        } else if (!firstResume && ::location.isInitialized) load() // back from the viewer/editor: show saved copies
        firstResume = false
    }

    private fun startLocation(intent: Intent?): Location =
        Location(intent?.getStringExtra(EXTRA_LOCAL_PATH)?.let { File(it) } ?: storageRoot)

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
        // The phone icon before the path
        ContextCompat.getDrawable(this, R.drawable.ic_m_phone)?.mutate()?.let { d ->
            d.setTint(secondary)
            d.setBounds(0, 0, 14.dpToPx(), 14.dpToPx())
            subtitle.setCompoundDrawablesRelative(d, null, null, null)
            subtitle.compoundDrawablePadding = 4.dpToPx()
            subtitle.gravity = Gravity.CENTER_VERTICAL
        }
        headerButtons = listOf(viewButton, newFolder)
        root.addView(bar.view)
        status = TextView(this, null, 0, R.style.TextSmall).apply {
            setTextColor(secondary)
            setPadding(20.dpToPx(), 12.dpToPx(), 20.dpToPx(), 12.dpToPx())
            isVisible = false
        }
        root.addView(status)
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
        if (pickMode) root.addView(pillButton(this, getString(R.string.ml_explorer_use_folder), filled = true, color = primary) {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_LOCAL_PATH, location.dir.absolutePath))
            finish()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 48.dpToPx()).apply {
            setMargins(16.dpToPx(), 8.dpToPx(), 16.dpToPx(), 0)
        })
        return root
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
        val dir = location.dir
        if (dir.absolutePath == storageRoot.absolutePath) {
            finish()
            return
        }
        open(Location(dir.parentFile ?: storageRoot))
    }

    /** Back gesture: up one folder while inside the folder the explorer was opened in; past it, leave the explorer. */
    private fun goBack() {
        val entry = entryDir.ifEmpty { startLocation(intent).dir.absolutePath }
        val dir = location.dir.absolutePath
        if (dir == entry || !dir.startsWith("$entry/")) finish() else goUp()
    }

    private fun open(loc: Location) {
        location = loc
        load()
    }

    private fun load() {
        // A selection belongs to the folder it was made in
        if (loadedDir != location.dir.absolutePath) selected.clear()
        loadedDir = location.dir.absolutePath
        updatePasteBar()
        loadLocal(location)
        updateSelection()
    }

    // ---------------------------------------------------------------- multiple selection

    private fun toggleSelect(entry: Entry) {
        val path = entry.file.absolutePath
        if (!selected.remove(path)) selected.add(path)
        updateSelection()
        list.adapter?.notifyDataSetChanged()
    }

    private fun clearSelection() {
        selected.clear()
        updateSelection()
        list.adapter?.notifyDataSetChanged()
    }

    private fun selectedFiles() = entries.filter { it.file.absolutePath in selected }

    private fun updateSelection() {
        backButton.setImageResource(if (selecting) R.drawable.ic_m_close else R.drawable.ic_m_arrow_back)
        backButton.contentDescription = getString(if (selecting) R.string.close else R.string.ml_cal_back)
        selectionBar.isVisible = selecting
        headerButtons.forEach { it.isVisible = !selecting }
        if (selecting) {
            title.text = getString(R.string.ml_explorer_selected, selected.size)
            pasteBar.isVisible = false
        } else {
            title.text = if (location.dir.absolutePath == storageRoot.absolutePath) getString(R.string.ml_search_phone) else location.dir.name
            updatePasteBar()
        }
    }

    private fun shareSelected() {
        val files = selectedFiles().filter { !it.isFolder }
        if (files.isEmpty()) {
            showToast(getString(R.string.ml_explorer_share_no_folders))
            return
        }
        val intent = if (files.size == 1) Intent(Intent.ACTION_SEND).setType(files[0].mime ?: "*/*")
            .putExtra(Intent.EXTRA_STREAM, uriFor(files[0].file))
        else Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files.map { uriFor(it.file) }))
        startActivity(Intent.createChooser(intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
    }

    private fun clipSelected(move: Boolean) {
        clip = Clip(selectedFiles().map { it.file }, move)
        clearSelection()
    }

    private fun deleteSelected() {
        val files = selectedFiles().map { it.file }
        showConfirmDialog(
            getString(R.string.ml_explorer_delete),
            if (files.size == 1) getString(R.string.ml_explorer_delete_confirm, files[0].name)
            else getString(R.string.ml_explorer_delete_many_confirm, files.size),
            getString(R.string.ml_explorer_delete),
        ) {
            selected.clear()
            run(getString(R.string.ml_explorer_delete)) { files.all { it.deleteRecursively() } }
        }
    }

    /** "More": the single-item actions, plus select all. */
    private fun showSelectionMenu() {
        val chosen = selectedFiles()
        val items = mutableListOf<MenuEntry>()
        chosen.singleOrNull()?.let { entry ->
            if (!entry.isFolder) items += MenuEntry(getString(R.string.ml_explorer_open_with)) { view(entry.file, entry.mime, chooser = true) }
            items += MenuEntry(getString(R.string.rename)) {
                clearSelection()
                askRename(entry)
            }
        }
        items += MenuEntry(getString(R.string.ml_explorer_select_all)) {
            entries.forEach { selected.add(it.file.absolutePath) }
            updateSelection()
            list.adapter?.notifyDataSetChanged()
        }
        items += MenuEntry(getString(R.string.ml_explorer_new_folder)) {
            clearSelection()
            askNewFolder()
        }
        showMenuDialog(getString(R.string.ml_explorer_selected, chosen.size), items)
    }

    private fun showStatus(text: String?, onClick: (() -> Unit)? = null) {
        status.isVisible = text != null
        status.text = text
        if (onClick != null) status.setOnClickListener { onClick() } else status.setOnClickListener(null)
    }

    private fun setEntries(list: List<Entry>) {
        entries = sorted(list).toMutableList()
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
            val order = views.manualOrder(location.dir).withIndex().associate { it.value to it.index }
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

    private fun loadLocal(loc: Location) {
        title.text = if (loc.dir.absolutePath == storageRoot.absolutePath) getString(R.string.ml_search_phone) else loc.dir.name
        emptyView.isVisible = false
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
        val files = loc.dir.listFiles()
        if (files == null) {
            setEntries(emptyList())
            showStatus(getString(R.string.ml_explorer_cannot_read))
            return
        }
        setEntries(files.filter { view.showHidden || !it.name.startsWith(".") }.map { f ->
            Entry(f.name, f.isDirectory, if (f.isDirectory) 0 else f.length(), f.lastModified(), f, mimeOf(f.name))
        })
        showStatus(null)
        emptyView.isVisible = entries.isEmpty()
    }

    // ---------------------------------------------------------------- view settings

    /** Changes the view of the folder shown (it gets its own settings, inherited by subfolders). */
    private fun updateView(change: (FolderView) -> FolderView) {
        view = change(view)
        viewSource = location.dir
        views.save(location.dir, view)
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
        val own = viewSource?.absolutePath == location.dir.absolutePath
        if (own) entries += MenuEntry(getString(R.string.ml_explorer_view_reset)) {
            views.reset(location.dir)
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
            if (dragMoved) views.saveManualOrder(location.dir, entries.map { it.name })
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
        if (!entry.isFolder) return view(entry.file, entry.mime)
        if (pickMode) return open(Location(entry.file))
        // A folder with only photos and videos opens as an album grid, like from the search
        lifecycleScope.launch {
            val album = albumCover(entry.file) != null
            if (album) AlbumActivity.open(this@FileExplorerActivity, entry.file) else open(Location(entry.file))
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
    private fun onLongPress(entry: Entry) = toggleSelect(entry)

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
        try {
            startActivity(if (chooser) Intent.createChooser(intent, file.name) else intent)
        } catch (e: Exception) {
            showToast(getString(R.string.ml_search_cannot_open))
        }
    }

    private fun share(file: File, mime: String?) {
        val intent = Intent(Intent.ACTION_SEND).setType(mime ?: "*/*")
            .putExtra(Intent.EXTRA_STREAM, uriFor(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, file.name))
    }

    private fun askRename(entry: Entry) {
        showTextInputDialog(getString(R.string.rename), entry.name, entry.name, getString(R.string.ml_save)) { name ->
            run(getString(R.string.rename)) { entry.file.renameTo(File(entry.file.parentFile, name)) }
        }
    }

    private fun askDelete(entry: Entry) {
        showConfirmDialog(getString(R.string.ml_explorer_delete), getString(R.string.ml_explorer_delete_confirm, entry.name), getString(R.string.ml_explorer_delete)) {
            run(getString(R.string.ml_explorer_delete)) { entry.file.deleteRecursively() }
        }
    }

    /** "+": a new folder, or a scanned document saved (by default) in this folder. */
    private fun showAddMenu() {
        newFolder.showDropdown(listOf(
            MenuEntry(getString(R.string.ml_explorer_new_folder), icon = R.drawable.ic_m_folder) { askNewFolder() },
            MenuEntry(getString(R.string.ml_scan_start), icon = R.drawable.ic_m_scan) { ScannerActivity.open(this, location.dir) },
        ))
    }

    private fun askNewFolder() {
        showTextInputDialog(getString(R.string.ml_explorer_new_folder), "", getString(R.string.ml_explorer_folder_name), getString(R.string.ml_save)) { name ->
            val dir = File(location.dir, name)
            // While copying or moving, go into the new folder: more can be made inside, then paste
            run(getString(R.string.ml_explorer_new_folder), then = { ok -> if (ok && clip != null) open(Location(dir)) }) { dir.mkdirs() }
        }
    }

    /** Runs a file operation off the main thread, then reloads the folder. */
    private fun run(label: String, then: ((Boolean) -> Unit)? = null, op: () -> Boolean) {
        showStatus(getString(R.string.ml_explorer_working, label))
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

    private fun setClip(files: List<File>, move: Boolean) {
        clip = Clip(files, move)
        updatePasteBar()
    }

    private fun updatePasteBar() {
        val c = clip
        pasteBar.isVisible = c != null && !selecting
        if (c != null) pasteText.text = when (c.files.size) {
            1 -> getString(if (c.move) R.string.ml_explorer_moving else R.string.ml_explorer_copying, c.files[0].name)
            else -> getString(if (c.move) R.string.ml_explorer_moving_many else R.string.ml_explorer_copying_many, c.files.size)
        }
    }

    private fun paste() {
        val c = clip ?: return
        val target = location.dir
        // A folder can't go inside itself
        if (c.files.any { (target.absolutePath + "/").startsWith(it.absolutePath + "/") }) {
            showToast(getString(R.string.ml_explorer_into_itself))
            return
        }
        clip = null
        val label = getString(if (c.move) R.string.ml_explorer_move else R.string.ml_explorer_copy)
        run(label) { c.files.map { pasteTo(it, target, c.move) }.all { it } }
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

    private fun pasteTo(src: File, dir: File, move: Boolean): Boolean {
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
                val path = entry.file.absolutePath
                image.tag = path
                lifecycleScope.launch {
                    val cover = albumCover(entry.file) ?: return@launch
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
                info.text = listOfNotNull(
                    entry.size.takeIf { view.showSize && !entry.isFolder }?.let { Formatter.formatShortFileSize(ctx, it) },
                    entry.modified.takeIf { view.showDate && it > 0 }?.let { DateFormat.getMediumDateFormat(ctx).format(it) },
                ).joinToString(" · ")
                info.isVisible = info.text.isNotEmpty()
            }
            // Selected items: a subtle fill in the list, a check badge in the grid
            val isSelected = entry.file.absolutePath in selected
            root.background = if (isSelected && !view.grid) selectedFill() else UiKit.ripple(ctx)
            if (view.grid && selecting) {
                badge.removeAllViews()
                badge.addView(UiKit.checkBadge(ctx, isSelected, 24))
                badge.isVisible = true
            } else badge.isVisible = false
            root.alpha = if (selecting && !isSelected) 0.6f else 1f
            root.setOnClickListener { onTap(entry) }
            // In manual order a long press drags the item (the menu opens if it isn't moved)
            if (view.sort == FolderView.SORT_MANUAL) root.setOnLongClickListener(null)
            else root.setOnLongClickListener {
                onLongPress(entry)
                true
            }
        }
    }

    companion object {
        const val EXTRA_LOCAL_PATH = "local_path"

        fun openLocal(context: Context, dir: File) = context.startActivity(
            Intent(context, FileExplorerActivity::class.java).putExtra(EXTRA_LOCAL_PATH, dir.absolutePath)
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
