package app.olauncher.ui

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
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
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
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
import app.olauncher.data.FolderView
import app.olauncher.data.FolderViewsRepository
import app.olauncher.helper.MediaThumbnails
import app.olauncher.helper.MenuEntry
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.showConfirmDialog
import app.olauncher.helper.showMenuDialog
import app.olauncher.helper.showTextInputDialog
import app.olauncher.helper.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Simple file explorer for the phone storage: browse, open with the phone's apps, share, rename,
 * delete, copy and move.
 *
 * Each folder can have its own view (☰): list or grid with 2-6 columns, sort order (or a manual
 * order, set by dragging), what to show. Subfolders use the settings of the closest folder above
 * that has some ([FolderViewsRepository]).
 *
 * Opened from the home search (a folder, or the folder of a file), the home menu or its own
 * "File" icon in the app list. [EXTRA_LOCAL_PATH] picks the starting folder (default: storage root).
 */
class FileExplorerActivity : AppCompatActivity() {

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

    private class Clip(val entry: Entry, val move: Boolean)

    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var status: TextView
    private lateinit var list: RecyclerView
    private lateinit var pasteBar: LinearLayout
    private lateinit var pasteText: TextView
    private lateinit var newFolder: TextView

    private lateinit var location: Location
    private lateinit var views: FolderViewsRepository
    /** Settings of the folder shown and where they come from (null = defaults). */
    private var view = FolderView()
    private var viewSource: File? = null
    private var entries: MutableList<Entry> = mutableListOf()
    private var clip: Clip? = null
    private var waitingAccess = false

    private val storageRoot: File get() = Environment.getExternalStorageDirectory()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        views = FolderViewsRepository(this)
        setContentView(buildLayout())
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goUp()
        })
        location = startLocation(intent)
        load()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        location = startLocation(intent)
        load()
    }

    override fun onResume() {
        super.onResume()
        if (waitingAccess) {
            waitingAccess = false
            load()
        }
    }

    private fun startLocation(intent: Intent?): Location =
        Location(intent?.getStringExtra(EXTRA_LOCAL_PATH)?.let { File(it) } ?: storageRoot)

    // ---------------------------------------------------------------- layout

    private fun buildLayout(): View {
        val primary = getColorFromAttr(R.attr.primaryColor)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dpToPx(), 0, 20.dpToPx(), 0)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top + 16.dpToPx(), v.paddingRight, bars.bottom + 8.dpToPx())
            insets
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this, null, 0, R.style.TextLarge).apply {
            text = "←"
            textSize = 26f
            setPadding(0, 4.dpToPx(), 16.dpToPx(), 4.dpToPx())
            isClickable = true
            setOnClickListener { goUp() }
        })
        title = TextView(this, null, 0, R.style.TextLarge).apply {
            setTypeface(typeface, Typeface.BOLD)
            textSize = 22f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        newFolder = TextView(this, null, 0, R.style.TextLarge).apply {
            text = "＋"
            textSize = 24f
            contentDescription = getString(R.string.ml_explorer_new_folder)
            setPadding(16.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
            isClickable = true
            setOnClickListener { askNewFolder() }
        }
        header.addView(TextView(this, null, 0, R.style.TextLarge).apply {
            text = "☰"
            textSize = 22f
            contentDescription = getString(R.string.ml_explorer_view)
            setPadding(16.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
            isClickable = true
            setOnClickListener { showViewMenu() }
        })
        header.addView(newFolder)
        root.addView(header)
        subtitle = TextView(this, null, 0, R.style.TextSmall).apply {
            alpha = 0.6f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
            setPadding(0, 2.dpToPx(), 0, 8.dpToPx())
        }
        root.addView(subtitle)
        status = TextView(this, null, 0, R.style.TextSmall).apply {
            alpha = 0.7f
            setPadding(0, 12.dpToPx(), 0, 12.dpToPx())
            isVisible = false
        }
        root.addView(status)
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@FileExplorerActivity)
            adapter = EntriesAdapter()
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        dragHelper.attachToRecyclerView(list)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        pasteBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.rounded_rect_shade_color)
            setPadding(16.dpToPx(), 6.dpToPx(), 8.dpToPx(), 6.dpToPx())
            isVisible = false
        }
        pasteText = TextView(this, null, 0, R.style.TextSmall).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        pasteBar.addView(pasteText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pasteBar.addView(TextView(this, null, 0, R.style.TextSmallBold).apply {
            setText(R.string.ml_explorer_paste)
            setTextColor(primary)
            setPadding(12.dpToPx(), 10.dpToPx(), 12.dpToPx(), 10.dpToPx())
            isClickable = true
            setOnClickListener { paste() }
        })
        pasteBar.addView(TextView(this, null, 0, R.style.TextSmall).apply {
            text = "✕"
            setPadding(12.dpToPx(), 10.dpToPx(), 8.dpToPx(), 10.dpToPx())
            isClickable = true
            setOnClickListener {
                clip = null
                updatePasteBar()
            }
        })
        root.addView(pasteBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 8.dpToPx()
        })
        return root
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

    private fun open(loc: Location) {
        location = loc
        load()
    }

    private fun load() {
        updatePasteBar()
        loadLocal(location)
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
        subtitle.text = "📱 /" + relative(loc.dir)
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
        showStatus(if (entries.isEmpty()) getString(R.string.ml_explorer_empty) else null)
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
        if (entry.isFolder) open(Location(entry.file)) else view(entry.file, entry.mime)
    }

    private fun onLongPress(entry: Entry) {
        val items = mutableListOf<MenuEntry>()
        if (!entry.isFolder) {
            items += MenuEntry(getString(R.string.ml_explorer_open_with)) { view(entry.file, entry.mime, chooser = true) }
            items += MenuEntry(getString(R.string.ml_explorer_share)) { share(entry.file, entry.mime) }
        }
        items += MenuEntry(getString(R.string.ml_explorer_copy)) { setClip(entry, move = false) }
        items += MenuEntry(getString(R.string.ml_explorer_move)) { setClip(entry, move = true) }
        items += MenuEntry(getString(R.string.rename)) { askRename(entry) }
        items += MenuEntry(getString(R.string.ml_explorer_delete)) { askDelete(entry) }
        showMenuDialog(entry.name, items)
    }

    private fun uriFor(file: File): Uri = FileProvider.getUriForFile(this, "$packageName.files", file)

    private fun view(file: File, mime: String?, chooser: Boolean = false) {
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

    private fun askNewFolder() {
        showTextInputDialog(getString(R.string.ml_explorer_new_folder), "", getString(R.string.ml_explorer_folder_name), getString(R.string.ml_save)) { name ->
            run(getString(R.string.ml_explorer_new_folder)) { File(location.dir, name).mkdirs() }
        }
    }

    /** Runs a file operation off the main thread, then reloads the folder. */
    private fun run(label: String, op: () -> Boolean) {
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
        }
    }

    // ---------------------------------------------------------------- copy / move

    private fun setClip(entry: Entry, move: Boolean) {
        clip = Clip(entry, move)
        updatePasteBar()
    }

    private fun updatePasteBar() {
        val c = clip
        pasteBar.isVisible = c != null
        if (c != null) pasteText.text = getString(if (c.move) R.string.ml_explorer_moving else R.string.ml_explorer_copying, c.entry.name)
    }

    private fun paste() {
        val c = clip ?: return
        val target = location.dir
        val entry = c.entry
        // A folder can't go inside itself
        if ((target.absolutePath + "/").startsWith(entry.file.absolutePath + "/")) {
            showToast(getString(R.string.ml_explorer_into_itself))
            return
        }
        clip = null
        val label = getString(if (c.move) R.string.ml_explorer_move else R.string.ml_explorer_copy)
        run(label) { pasteTo(entry.file, target, c.move) }
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

    private fun glyphOf(entry: Entry) = when {
        entry.isFolder -> "📁"
        entry.mime?.startsWith("image/") == true -> "🖼️"
        entry.mime?.startsWith("video/") == true -> "🎬"
        entry.mime?.startsWith("audio/") == true -> "🎵"
        else -> "📄"
    }

    private fun shownName(entry: Entry) =
        if (view.showExtension || entry.isFolder || !entry.name.contains('.')) entry.name else entry.name.substringBeforeLast('.')

    private fun hasThumbnail(entry: Entry) = view.showThumbnails && !entry.isFolder &&
            (entry.mime?.startsWith("image/") == true || entry.mime?.startsWith("video/") == true)

    private fun roundedImage() = android.widget.ImageView(this).apply {
        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, outline: android.graphics.Outline) =
                outline.setRoundRect(0, 0, v.width, v.height, 10.dpToPx().toFloat())
        }
    }

    private inner class EntriesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = entries.size

        override fun getItemViewType(position: Int) = if (view.grid) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(if (viewType == 1) gridCell(parent) else listRow(parent)) {}

        private fun listRow(parent: ViewGroup): View {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 6.dpToPx(), 0, 6.dpToPx())
                isClickable = true
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val icon = android.widget.FrameLayout(parent.context)
            icon.addView(TextView(parent.context, null, 0, R.style.TextLarge).apply {
                textSize = 20f
                gravity = Gravity.CENTER
            }, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            icon.addView(roundedImage(), android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            row.addView(icon, LinearLayout.LayoutParams(40.dpToPx(), 40.dpToPx()).apply { marginEnd = 12.dpToPx() })
            val texts = LinearLayout(parent.context).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(parent.context, null, 0, R.style.TextLarge).apply {
                textSize = 17f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            })
            texts.addView(TextView(parent.context, null, 0, R.style.TextSmall).apply {
                alpha = 0.6f
                maxLines = 1
            })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return row
        }

        private fun gridCell(parent: ViewGroup): View {
            val width = (parent.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) - parent.paddingLeft - parent.paddingRight
            val side = width / view.columns
            val cell = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(3.dpToPx(), 3.dpToPx(), 3.dpToPx(), 6.dpToPx())
                isClickable = true
                layoutParams = RecyclerView.LayoutParams(side, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val box = android.widget.FrameLayout(parent.context).apply {
                setBackgroundResource(R.drawable.rounded_rect_shade_color)
            }
            box.addView(TextView(parent.context, null, 0, R.style.TextLarge).apply {
                textSize = (side / resources.displayMetrics.scaledDensity / 3).coerceIn(18f, 44f)
                gravity = Gravity.CENTER
            }, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            box.addView(roundedImage(), android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val inner = side - 6.dpToPx()
            cell.addView(box, LinearLayout.LayoutParams(inner, inner))
            cell.addView(TextView(parent.context, null, 0, R.style.TextSmall).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_HORIZONTAL
                textSize = if (view.columns >= 5) 11f else 13f
                setPadding(0, 4.dpToPx(), 0, 0)
            })
            return cell
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val entry = entries[position]
            val root = holder.itemView as LinearLayout
            val iconBox = root.getChildAt(0) as android.widget.FrameLayout
            val glyph = iconBox.getChildAt(0) as TextView
            val image = iconBox.getChildAt(1) as android.widget.ImageView
            val thumb = hasThumbnail(entry)
            glyph.text = glyphOf(entry)
            glyph.isVisible = !thumb
            image.isVisible = thumb
            if (thumb) {
                val size = if (view.grid) iconBox.layoutParams.width.coerceAtLeast(64) else 40.dpToPx()
                MediaThumbnails.load(lifecycleScope, image, entry.file, size)
            } else image.setImageBitmap(null)
            if (view.grid) {
                val name = root.getChildAt(1) as TextView
                name.text = shownName(entry)
                name.isVisible = view.showNames
            } else {
                val texts = root.getChildAt(1) as LinearLayout
                (texts.getChildAt(0) as TextView).text = shownName(entry)
                val info = texts.getChildAt(1) as TextView
                info.text = listOfNotNull(
                    entry.size.takeIf { view.showSize && !entry.isFolder }?.let { Formatter.formatShortFileSize(this@FileExplorerActivity, it) },
                    entry.modified.takeIf { view.showDate && it > 0 }?.let { DateFormat.getMediumDateFormat(this@FileExplorerActivity).format(it) },
                ).joinToString(" · ")
                info.isVisible = info.text.isNotEmpty()
            }
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
