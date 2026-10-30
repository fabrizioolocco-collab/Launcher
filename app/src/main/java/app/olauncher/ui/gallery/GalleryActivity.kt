package app.olauncher.ui.gallery

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
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
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.ui.DragSelect
import app.olauncher.ui.FileExplorerActivity
import app.olauncher.ui.FolderPickerActivity
import app.olauncher.ui.UiKit
import app.olauncher.helper.CameraRoll
import app.olauncher.helper.MediaThumbnails
import app.olauncher.helper.MenuEntry
import app.olauncher.helper.dpToPx
import app.olauncher.helper.showConfirmDialog
import app.olauncher.helper.showMenuDialog
import app.olauncher.helper.showTextInputDialog
import app.olauncher.helper.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Gallery, a tool of its own (in the app list, and behind the camera folder on home), with two tabs:
 *
 * - Photos: the camera's photos and videos together with those of the chosen folders (screenshots,
 *   WhatsApp, Telegram, Download...), newest first, like "Recents" on the iPhone ([CameraRoll]).
 * - Albums: the user's own folder tree (DCIM/Foto, or the folder chosen), shown as it is on the phone:
 *   a folder holding folders shows only them (2024 → its albums), a folder without subfolders is an
 *   album and shows its photos. The tiles' order is the user's (drag them), their size and the albums'
 *   previews are chosen in the ⋮ menu. Folders are made, renamed, moved and deleted here; photos are
 *   moved or copied into them from the selection. Nothing is ever organized automatically.
 *
 * Long press starts a multiple selection, of photos (share, copy, move into an album, delete) or of
 * folder tiles (move, rename, delete); on a tile, a long press that moves drags it instead.
 *
 * Search (the bar's magnifier): photos by name, date ("agosto 2024", "12/08") or the folder they come
 * from (WhatsApp, Screenshots); where folders are shown, the albums of the whole tree below by name.
 */
class GalleryActivity : AppCompatActivity() {

    private enum class Tab { PHOTOS, ALBUMS }

    /**
     * A folder tile in the Albums tab: [folders] inside it (a folder of folders) or else its [photos]
     * (an album); [loose] is the "Other photos" tile of a folder that has folders and photos.
     */
    private class Folder(val dir: File, val name: String, val folders: Int, val photos: Int, val loose: Boolean = false) {
        val key: String get() = if (loose) "" else dir.absolutePath
    }

    /** The camera folder, the base of the Photos tab. */
    private lateinit var dir: File
    /** The folders of the Photos tab: [dir], plus the merged ones. */
    private var sources: List<File> = emptyList()

    private var tab = Tab.PHOTOS
    /** Albums tab: the top of the tree, the folder shown, and whether only its loose photos are shown. */
    private lateinit var albumRoot: File
    private lateinit var albumDir: File
    private var looseView = false

    /** Shown now: photos (Photos tab, an album) or folders (a folder of folders), never both. */
    private var media: List<File> = emptyList()
    private var folders: MutableList<Folder> = mutableListOf()
    /** What the tab read, before the search filter. */
    private var allMedia: List<File> = emptyList()
    private var allFolders: List<Folder> = emptyList()
    /** Album covers found so far (folder path → newest photo, or null when it has none). */
    private val covers = HashMap<String, File?>()
    private val showingFolders get() = tab == Tab.ALBUMS && folders.isNotEmpty() && media.isEmpty()
    /** MediaStore address of each file: fast system thumbnails and gallery opening. */
    private var uris: Map<String, Uri> = emptyMap()

    private val selected = LinkedHashSet<String>()
    private val selecting get() = selected.isNotEmpty()
    /** Selecting by sliding over the photos (after a long press, or sideways during a selection). */
    private lateinit var dragSelect: DragSelect
    private lateinit var title: TextView
    private lateinit var count: TextView
    private lateinit var backButton: ImageView
    private lateinit var sourcesButton: ImageView
    private lateinit var newFolderButton: ImageView
    private lateinit var menuButton: ImageView
    private lateinit var searchButton: ImageView
    private lateinit var searchRow: LinearLayout
    private lateinit var searchInput: android.widget.EditText
    private lateinit var tabs: LinearLayout
    private lateinit var selectionBar: LinearLayout
    private lateinit var folderBar: LinearLayout
    private lateinit var grid: RecyclerView
    private lateinit var emptyView: TextView

    /** Copy or move waiting for a folder chosen in the explorer. */
    private var pendingMove: Boolean? = null
    private val folderPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val target = result.data?.getStringExtra(FileExplorerActivity.EXTRA_LOCAL_PATH)?.let { File(it) }
        val move = pendingMove
        pendingMove = null
        if (target != null && move != null) transfer(target, move)
    }
    /** A folder added to the Photos tab from the explorer. */
    private val sourcePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val folder = result.data?.getStringExtra(FileExplorerActivity.EXTRA_LOCAL_PATH)?.let { File(it) } ?: return@registerForActivityResult
        CameraRoll.set(this, dir, folder, true)
        reload { showSources() }
    }
    /** Folders of the Albums tab moved elsewhere: the destination chosen in the explorer. */
    private var foldersToMove: List<File> = emptyList()
    private val folderMovePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val target = result.data?.getStringExtra(FileExplorerActivity.EXTRA_LOCAL_PATH)?.let { File(it) }
        val chosen = foldersToMove
        foldersToMove = emptyList()
        if (target != null && chosen.isNotEmpty()) moveFolders(chosen, target)
    }
    /** The top of the Albums tab, chosen in the explorer. */
    private val albumRootPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val folder = result.data?.getStringExtra(FileExplorerActivity.EXTRA_LOCAL_PATH)?.let { File(it) } ?: return@registerForActivityResult
        CameraRoll.setAlbumRoot(this, folder)
        albumRoot = folder
        albumDir = folder
        looseView = false
        reload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dir = CameraRoll.cameraDir()
        albumRoot = CameraRoll.albumRoot(this)
        folderColumns = CameraRoll.albumColumns(this)
        albumPreviews = CameraRoll.albumPreviews(this)
        albumDir = albumRoot
        setContentView(buildLayout())
        // The folder choice asked from home's long press: once the folders are known
        val askSources = savedInstanceState == null && intent.getBooleanExtra(EXTRA_SHOW_SOURCES, false)
        reload { if (askSources) showSources() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })
        updateBars()
    }

    private var firstResume = true

    override fun onResume() {
        super.onResume()
        if (firstResume) {
            firstResume = false
            return
        }
        // Back from the viewer or an editor (or WhatsApp): a saved copy, a deleted or a new file must show at once
        // (unless a reload is already going, like the one after adding a folder, which then shows the choice)
        if (loading?.isActive != true) reload()
    }

    /** Back: ends a selection, then the search, then goes up the album folders, then leaves. */
    private fun goBack() {
        when {
            selecting -> clearSelection()
            searchRow.isVisible -> closeSearch()
            tab == Tab.ALBUMS && looseView -> {
                looseView = false
                reload()
            }
            tab == Tab.ALBUMS && !same(albumDir, albumRoot) -> {
                albumDir = albumDir.parentFile ?: albumRoot
                reload()
            }
            else -> finish()
        }
    }

    private fun switchTab(to: Tab) {
        if (tab == to) return
        tab = to
        loaded = false
        selected.clear()
        media = emptyList()
        folders = mutableListOf()
        allMedia = emptyList()
        allFolders = emptyList()
        grid.adapter?.notifyDataSetChanged()
        updateTabs()
        reload()
    }

    private var loading: Job? = null
    /** What the tab shows has been read (until then an empty grid is not "empty"). */
    private var loaded = false

    /**
     * Reads what the tab shows again, off the main thread (the Photos tab merges chat folders with
     * thousands of files, the folder tiles count the photos of whole trees); [then] runs once shown.
     */
    private fun reload(then: () -> Unit = {}) {
        loading?.cancel()
        val shownTab = tab
        loading = lifecycleScope.launch {
            if (shownTab == Tab.PHOTOS) {
                val found = withContext(Dispatchers.IO) {
                    val folders = listOf(dir) + CameraRoll.included(this@GalleryActivity, dir)
                    Triple(folders, MediaThumbnails.mediaIn(folders),
                        folders.fold(emptyMap<String, Uri>()) { all, folder -> all + MediaThumbnails.mediaUris(this@GalleryActivity, folder) })
                }
                sources = found.first
                allMedia = found.second
                allFolders = emptyList()
                uris = found.third
            } else {
                // A folder gone meanwhile (deleted or renamed elsewhere): back to the nearest one still there
                while (!albumDir.isDirectory && !same(albumDir, albumRoot)) albumDir = albumDir.parentFile ?: albumRoot
                val shown = albumDir
                val loose = looseView
                val found = withContext(Dispatchers.IO) { readAlbum(shown, loose) }
                allMedia = found.first
                allFolders = found.second
                uris = if (allMedia.isEmpty()) emptyMap() else MediaThumbnails.mediaUris(this@GalleryActivity, shown)
            }
            loaded = true
            applySearch()
            then()
        }
    }

    /** Shows the media or folders now in [media] / [folders] (all of them, or what the search found). */
    private fun showResults() {
        selected.retainAll((media.map { it.absolutePath } + folders.map { it.key }).toSet())
        updateBars()
        grid.adapter?.notifyDataSetChanged()
        grid.scrollToPosition(0)
    }

    // ---------------------------------------------------------------- search

    private var query = ""
    private val searching get() = query.isNotEmpty()
    private var searchJob: Job? = null
    /** What a photo can be found by (name, folder, dates), read once per file. */
    private val searchTexts = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun openSearch() {
        searchRow.isVisible = true
        UiKit.setToolActive(searchButton, true)
        searchInput.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(searchInput, 0)
    }

    /** Hides the field and shows everything again ([refresh] false: something else is reloading anyway). */
    private fun closeSearch(refresh: Boolean = true) {
        searchJob?.cancel()
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(searchInput.windowToken, 0)
        searchRow.isVisible = false
        UiKit.setToolActive(searchButton, false)
        val had = searching
        query = ""
        if (searchInput.text.isNotEmpty()) searchInput.setText("")
        if (had && refresh) applySearch()
    }

    private fun fold(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase()

    /**
     * Filters what the tab read by the query (every word must be found, accents and case ignored):
     * photos by name, folder and date; where folders are shown, the folders of the whole tree below.
     */
    private fun applySearch() {
        searchJob?.cancel()
        if (!searching) {
            media = allMedia
            folders = allFolders.toMutableList()
            showResults()
            return
        }
        val words = fold(query).split(' ').filter { it.isNotEmpty() }
        val inFolders = tab == Tab.ALBUMS && allMedia.isEmpty() && allFolders.isNotEmpty()
        val base = albumDir
        val photos = allMedia
        searchJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(150)
            if (inFolders) {
                folders = withContext(Dispatchers.IO) { findFolders(base, words) }.toMutableList()
                media = emptyList()
            } else {
                media = withContext(Dispatchers.IO) {
                    val formats = searchFormats()
                    photos.filter { f ->
                        val text = searchTexts.getOrPut(f.absolutePath) { searchText(f, formats) }
                        words.all { text.contains(it) }
                    }
                }
                folders = mutableListOf()
            }
            showResults()
        }
    }

    /** The ways a date is written in a search: "12 agosto 2024", "12/08/2024", "2024-08-12". */
    private fun searchFormats() = listOf(
        java.text.SimpleDateFormat("d MMMM yyyy EEEE", java.util.Locale.getDefault()),
        java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault()),
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()),
    )

    private fun searchText(file: File, formats: List<java.text.SimpleDateFormat>): String {
        val date = java.util.Date(file.lastModified())
        return fold(file.name + " " + (file.parentFile?.name ?: "") + " " + formats.joinToString(" ") { it.format(date) })
    }

    /** Folders in the tree of [base] whose path (from [base]) holds every word, shown as "2024 › Mare". */
    private fun findFolders(base: File, words: List<String>): List<Folder> {
        val found = mutableListOf<Folder>()
        fun walk(dir: File, path: List<String>) {
            if (path.size > MAX_SEARCH_DEPTH) return
            for (sub in ordered(dir, subfolders(dir))) {
                if (found.size >= MAX_SEARCH_RESULTS) return
                val subPath = path + sub.name
                val text = fold(subPath.joinToString(" "))
                if (words.all { text.contains(it) } && fold(sub.name).let { name -> words.any { name.contains(it) } }) {
                    val (folders, photos) = quickCount(sub)
                    found += Folder(sub, subPath.joinToString(" › "), folders, photos)
                }
                walk(sub, subPath)
            }
        }
        walk(base, emptyList())
        return found
    }

    /**
     * What [folder] shows: its subfolders when it has any (plus "Other photos" for photos beside them),
     * else its photos. Quick: the tiles only count names (no file is read); covers come later, one by one.
     */
    private fun readAlbum(folder: File, loose: Boolean): Pair<List<File>, List<Folder>> {
        val subs = ordered(folder, subfolders(folder))
        if (loose || subs.isEmpty()) return MediaThumbnails.mediaIn(listOf(folder)) to emptyList()
        val tiles = subs.map { sub ->
            val (folders, photos) = quickCount(sub)
            Folder(sub, sub.name, folders, photos)
        }
        val own = quickCount(folder).second
        val other = if (own == 0) emptyList()
        else listOf(Folder(folder, getString(R.string.ml_gallery_other_photos), 0, own, loose = true))
        return emptyList<File>() to tiles + other
    }

    /** The folders inside [folder] as on the phone, hidden ones left out. */
    private fun subfolders(folder: File): List<File> =
        folder.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.sortedBy { it.name.lowercase() }.orEmpty()

    /** [subs] in the order the user dragged them to; the others (new ones) after, by name. */
    private fun ordered(folder: File, subs: List<File>): List<File> {
        val order = CameraRoll.albumOrder(this, folder).withIndex().associate { it.value to it.index }
        if (order.isEmpty()) return subs
        return subs.sortedWith(compareBy<File>({ order[it.name] ?: Int.MAX_VALUE }, { it.name.lowercase() }))
    }

    /** Folders and photos directly in [folder], by their names: only what isn't named as a photo is checked on disk. */
    private fun quickCount(folder: File): Pair<Int, Int> {
        var folders = 0
        var photos = 0
        folder.listFiles()?.forEach {
            if (it.name.startsWith(".")) return@forEach
            if (MediaThumbnails.isMedia(mimeOf(it.name))) photos++ else if (it.isDirectory) folders++
        }
        return folders to photos
    }

    /** Photos and videos in the whole tree of [folder] (to say what a delete takes away). */
    private fun treeCount(folder: File): Int {
        val (_, photos) = quickCount(folder)
        return photos + subfolders(folder).sumOf { treeCount(it) }
    }

    /** The folders merged with the camera's, each turned on or off; another one can be added. */
    private fun showSources() {
        val entries = CameraRoll.options(this, dir).map { (folder, name) ->
            MenuEntry(name, checked = sources.any { it.absolutePath == folder.absolutePath }, onToggle = { on ->
                CameraRoll.set(this, dir, folder, on)
                reload()
                true
            }, onInfo = { showFolderInfo(folder, name) })
        } + MenuEntry(getString(R.string.ml_gallery_add_folder), icon = R.drawable.ic_m_add) {
            sourcePicker.launch(Intent(this, FolderPickerActivity::class.java)
                .putExtra(FileExplorerActivity.EXTRA_LOCAL_PATH, storageRoot.absolutePath))
        }
        showMenuDialog(getString(R.string.ml_gallery_sources), entries)
    }

    /** Where a gallery folder is: its full path, and a way to open it in the explorer. */
    private fun showFolderInfo(folder: File, name: String) {
        showMenuDialog(name, listOf(
            MenuEntry(relative(folder), section = getString(R.string.ml_gallery_folder_path)),
            MenuEntry(getString(R.string.ml_search_open_folder), icon = R.drawable.ic_m_folder_open) {
                FileExplorerActivity.openLocal(this, folder)
            },
        ))
    }

    // ---------------------------------------------------------------- layout

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // A solid background: the theme shows the wallpaper, which the recent apps preview leaves out (gray)
        val night = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        root.setBackgroundColor(if (night) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top + 8.dpToPx(), 0, bars.bottom)
            insets
        }
        sourcesButton = UiKit.iconButton(this, R.drawable.ic_m_sliders, getString(R.string.ml_gallery_sources)) { showSources() }
        newFolderButton = UiKit.iconButton(this, R.drawable.ic_m_add, getString(R.string.ml_gallery_new_folder)) { askNewFolder(albumDir) {} }
        menuButton = UiKit.iconButton(this, R.drawable.ic_m_more, getString(R.string.ml_explorer_more)) { showAlbumsMenu() }
        searchButton = UiKit.iconButton(this, R.drawable.ic_m_search, getString(R.string.ml_explorer_search)) {
            if (searchRow.isVisible) closeSearch() else openSearch()
        }
        val bar = UiKit.appBar(this, getString(R.string.ml_gallery), onBack = { goBack() },
            actions = listOf(searchButton, sourcesButton, newFolderButton, menuButton))
        bar.view.setPadding(6.dpToPx(), 0, 6.dpToPx(), 8.dpToPx())
        backButton = bar.back
        title = bar.title
        count = bar.subtitle
        root.addView(bar.view)

        // Search field under the bar, shown by the magnifier
        searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dpToPx(), 0, 8.dpToPx(), 10.dpToPx())
            isVisible = false
        }
        searchInput = android.widget.EditText(this).apply {
            UiKit.styleInput(this)
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
                    val typed = s?.toString()?.trim().orEmpty()
                    if (typed == query) return
                    query = typed
                    applySearch()
                }
            })
        }
        searchRow.addView(searchInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        searchRow.addView(UiKit.iconButton(this, R.drawable.ic_m_close, getString(R.string.close)) { closeSearch() })
        root.addView(searchRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = UiKit.cardBackground(this@GalleryActivity, 14)
            setPadding(4.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
        }
        listOf(Tab.PHOTOS to R.string.ml_gallery_tab_photos, Tab.ALBUMS to R.string.ml_gallery_tab_albums).forEach { (t, label) ->
            tabs.addView(TextView(this).apply {
                text = getString(label)
                textSize = 14f
                gravity = Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                isClickable = true
                isFocusable = true
                setOnClickListener { switchTab(t) }
            }, LinearLayout.LayoutParams(0, 36.dpToPx(), 1f))
        }
        root.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(16.dpToPx(), 0, 16.dpToPx(), 12.dpToPx())
        })
        updateTabs()

        val content = FrameLayout(this)
        grid = RecyclerView(this).apply {
            // Twelve spans: photos take four (three a row), folder tiles 12 / the columns chosen
            layoutManager = GridLayoutManager(this@GalleryActivity, SPANS).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int) = if (showingFolders) SPANS / folderColumns else SPANS / COLUMNS
                }
            }
            adapter = GridAdapter()
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(8.dpToPx(), 0, 8.dpToPx(), 24.dpToPx())
        }
        content.addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        emptyView = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(UiKit.secondary(this@GalleryActivity))
            setPadding(32.dpToPx(), 48.dpToPx(), 32.dpToPx(), 0)
            isVisible = false
        }
        content.addView(emptyView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        dragSelect = DragSelect(grid, { selecting },
            isSelected = { p -> keyAt(p) in selected },
            setSelected = { p, on -> keyAt(p)?.let { if (on) selected.add(it) else selected.remove(it) } },
            onChanged = {
                updateBars()
                grid.adapter?.notifyDataSetChanged()
            })
        selectionBar = UiKit.toolBar(this).apply { isVisible = false }
        listOf(
            Tool(R.drawable.ic_m_share, R.string.ml_explorer_share) { shareSelected() },
            Tool(R.drawable.ic_m_copy, R.string.ml_explorer_copy) { pickAlbum(albumRoot, move = false) },
            Tool(R.drawable.ic_m_move, R.string.ml_explorer_move) { pickAlbum(albumRoot, move = true) },
            Tool(R.drawable.ic_m_delete, R.string.ml_explorer_delete) { deleteSelected() },
            Tool(R.drawable.ic_m_more, R.string.ml_explorer_more) { showMore() },
        ).forEach { tool ->
            selectionBar.addView(UiKit.toolButton(this, tool.icon, getString(tool.label)) { tool.action() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(selectionBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(12.dpToPx(), 8.dpToPx(), 12.dpToPx(), 8.dpToPx())
        })
        // The same bar for selected folder tiles
        folderBar = UiKit.toolBar(this).apply { isVisible = false }
        listOf(
            Tool(R.drawable.ic_m_move, R.string.ml_explorer_move) { moveSelectedFolders() },
            Tool(R.drawable.ic_m_rename, R.string.rename) { renameSelectedFolder() },
            Tool(R.drawable.ic_m_delete, R.string.ml_explorer_delete) { deleteSelectedFolders() },
            Tool(R.drawable.ic_m_more, R.string.ml_explorer_more) { showFolderMore() },
        ).forEach { tool ->
            folderBar.addView(UiKit.toolButton(this, tool.icon, getString(tool.label)) { tool.action() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(folderBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(12.dpToPx(), 8.dpToPx(), 12.dpToPx(), 8.dpToPx())
        })
        dragHelper.attachToRecyclerView(grid)
        return root
    }

    // ---------------------------------------------------------------- tiles: order (drag) and selection

    private var dragMoved = false
    private var dragFolder: Folder? = null

    /** Folder tiles: a long press that moves drags the tile to a new place; one that doesn't selects it. */
    private val dragHelper = androidx.recyclerview.widget.ItemTouchHelper(object : androidx.recyclerview.widget.ItemTouchHelper.Callback() {
        // Not on search results: they come from several folders, their order isn't one folder's
        override fun isLongPressDragEnabled() = showingFolders && !selecting && !searching
        override fun isItemViewSwipeEnabled() = false

        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
            if (!showingFolders || searching || folders.getOrNull(viewHolder.bindingAdapterPosition)?.loose != false) return 0
            val all = androidx.recyclerview.widget.ItemTouchHelper.UP or androidx.recyclerview.widget.ItemTouchHelper.DOWN or
                    androidx.recyclerview.widget.ItemTouchHelper.LEFT or androidx.recyclerview.widget.ItemTouchHelper.RIGHT
            return makeMovementFlags(all, 0)
        }

        override fun onMove(recyclerView: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
            val a = from.bindingAdapterPosition
            val b = to.bindingAdapterPosition
            // "Other photos" stays last
            if (a < 0 || b < 0 || folders.getOrNull(b)?.loose != false) return false
            folders.add(b, folders.removeAt(a))
            recyclerView.adapter?.notifyItemMoved(a, b)
            dragMoved = true
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                dragMoved = false
                dragFolder = folders.getOrNull(viewHolder.bindingAdapterPosition)
                viewHolder.itemView.alpha = 0.7f
                viewHolder.itemView.scaleX = 1.05f
                viewHolder.itemView.scaleY = 1.05f
            }
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            viewHolder.itemView.alpha = 1f
            viewHolder.itemView.scaleX = 1f
            viewHolder.itemView.scaleY = 1f
            if (dragMoved) CameraRoll.saveAlbumOrder(this@GalleryActivity, albumDir, folders.filterNot { it.loose }.map { it.dir.name })
            else dragFolder?.let { toggleKey(it.key) }
            dragFolder = null
        }
    })

    /** What position [p] holds, for selecting by sliding: a photo's path, a folder's ("Other photos": none). */
    private fun keyAt(p: Int): String? = if (showingFolders) folders.getOrNull(p)?.key?.ifEmpty { null } else media.getOrNull(p)?.absolutePath

    private fun toggleKey(key: String) {
        if (key.isEmpty()) return
        if (!selected.remove(key)) selected.add(key)
        updateBars()
        grid.adapter?.notifyDataSetChanged()
    }

    /** The tab switch: the chosen one filled with the text colour, its label in the background's. */
    private fun updateTabs() {
        val primary = UiKit.primary(this)
        val onPrimary = if (androidx.core.graphics.ColorUtils.calculateLuminance(primary) > 0.5) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        listOf(Tab.PHOTOS, Tab.ALBUMS).forEachIndexed { i, t ->
            val label = tabs.getChildAt(i) as TextView
            val chosen = t == tab
            label.background = if (chosen) GradientDrawable().apply {
                cornerRadius = 10.dpToPx().toFloat()
                setColor(primary)
            } else null
            label.setTextColor(if (chosen) onPrimary else UiKit.secondary(this))
        }
    }

    /** A tool of the selection bar: line icon, label, action. */
    private class Tool(val icon: Int, val label: Int, val action: () -> Unit)

    // ---------------------------------------------------------------- selection

    private fun toggle(file: File) {
        if (!selected.remove(file.absolutePath)) selected.add(file.absolutePath)
        updateBars()
        grid.adapter?.notifyDataSetChanged()
    }

    private fun clearSelection() {
        selected.clear()
        updateBars()
        grid.adapter?.notifyDataSetChanged()
    }

    private fun selectedFiles() = media.filter { it.absolutePath in selected }

    /** Title, path and buttons for what is shown: selection, the Photos tab, a folder or an album. */
    private fun updateBars() {
        val albums = tab == Tab.ALBUMS
        val atRoot = same(albumDir, albumRoot) && !looseView
        backButton.setImageResource(if (selecting) R.drawable.ic_m_close else R.drawable.ic_m_arrow_back)
        backButton.contentDescription = getString(if (selecting) R.string.close else R.string.ml_cal_back)
        // A tool of its own: Android's back gesture leaves it; the arrow only goes up the album folders
        backButton.isVisible = selecting || (albums && !atRoot)
        title.text = when {
            selecting -> getString(R.string.ml_explorer_selected, selected.size)
            !albums -> getString(R.string.ml_gallery)
            looseView -> getString(R.string.ml_gallery_other_photos)
            atRoot -> getString(R.string.ml_gallery_tab_albums)
            else -> albumDir.name
        }
        count.text = when {
            !albums -> getString(R.string.ml_album_count, media.size)
            showingFolders -> relativePath(albumDir)
            else -> relativePath(albumDir) + " · " + getString(R.string.ml_gallery_n_photos, media.size)
        }
        count.isVisible = !selecting
        sourcesButton.isVisible = !selecting && !albums
        // New folders go where folders are shown (or in an empty folder): an album with photos stays an album
        newFolderButton.isVisible = !selecting && albums && !looseView && media.isEmpty()
        menuButton.isVisible = !selecting && albums && !looseView
        searchButton.isVisible = !selecting
        searchInput.hint = getString(if (albums && allMedia.isEmpty() && allFolders.isNotEmpty()) R.string.ml_gallery_search_albums_hint
            else R.string.ml_gallery_search_hint)
        tabs.isVisible = !selecting
        selectionBar.isVisible = selecting && !showingFolders
        folderBar.isVisible = selecting && showingFolders
        // Rename is for one folder at a time
        folderBar.getChildAt(1).alpha = if (selected.size == 1) 1f else 0.35f
        emptyView.isVisible = !selecting && media.isEmpty() && folders.isEmpty() && loaded
        emptyView.text = getString(when {
            searching -> R.string.ml_explorer_no_results
            albums -> R.string.ml_gallery_empty_folder
            else -> R.string.ml_gallery_empty
        })
    }

    private fun uriFor(file: File): Uri = uris[file.absolutePath] ?: FileProvider.getUriForFile(this, "$packageName.files", file)

    private fun shareSelected() {
        val files = selectedFiles()
        if (files.isEmpty()) return
        val intent = if (files.size == 1) Intent(Intent.ACTION_SEND).setType(mimeOf(files[0].name) ?: "image/*")
            .putExtra(Intent.EXTRA_STREAM, uriFor(files[0]))
        else Intent(Intent.ACTION_SEND_MULTIPLE).setType(
            if (files.all { mimeOf(it.name)?.startsWith("image/") == true }) "image/*"
            else if (files.all { mimeOf(it.name)?.startsWith("video/") == true }) "video/*" else "*/*"
        ).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files.map { uriFor(it) }))
        startActivity(Intent.createChooser(intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
    }

    /**
     * Copy / move into an album: the album folders from [folder] down. A folder of folders is entered,
     * an album (no subfolders) receives the photos; a new folder can be made where the list is.
     */
    private fun pickAlbum(folder: File, move: Boolean) {
        lifecycleScope.launch {
            val subs = withContext(Dispatchers.IO) { subfolders(folder).map { it to subfolders(it).isNotEmpty() } }
            val entries = mutableListOf<MenuEntry>()
            if (!same(folder, albumRoot)) entries += MenuEntry(getString(R.string.ml_gallery_up), icon = R.drawable.ic_m_up) {
                pickAlbum(folder.parentFile ?: albumRoot, move)
            }
            entries += MenuEntry(getString(R.string.ml_gallery_new_folder_here), icon = R.drawable.ic_m_add) {
                askNewFolder(folder) { made -> transfer(made, move) }
            }
            subs.forEach { (sub, hasFolders) ->
                entries += if (hasFolders) MenuEntry(sub.name, icon = R.drawable.ic_m_folder, value = "›") { pickAlbum(sub, move) }
                else MenuEntry(sub.name, icon = R.drawable.ic_m_image) { transfer(sub, move) }
            }
            // A folder outside the albums (the explorer, where new folders can be made too)
            entries += MenuEntry(getString(R.string.ml_gallery_other_folder), icon = R.drawable.ic_m_folder_open) {
                pendingMove = move
                folderPicker.launch(Intent(this@GalleryActivity, FolderPickerActivity::class.java)
                    .putExtra(FileExplorerActivity.EXTRA_LOCAL_PATH, folder.absolutePath))
            }
            val verb = getString(if (move) R.string.ml_gallery_move_to else R.string.ml_gallery_copy_to)
            showMenuDialog("$verb ${relativePath(folder)}", entries)
        }
    }

    private fun uniqueTarget(target: File, name: String): File {
        var f = File(target, name)
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
        var i = 1
        while (f.exists()) f = File(target, "$base (${i++})$ext")
        return f
    }

    private fun transfer(target: File, move: Boolean) {
        // The photos may come from several folders: those already in the target stay put
        val files = selectedFiles().filter { it.parentFile?.absolutePath != target.absolutePath }
        if (files.isEmpty()) {
            showToast(getString(R.string.ml_album_same_folder))
            return
        }
        lifecycleScope.launch {
            val changed = mutableListOf<String>()
            val ok = withContext(Dispatchers.IO) {
                files.map { src ->
                    try {
                        val dest = uniqueTarget(target, src.name)
                        val done = (move && src.renameTo(dest)) || (src.copyRecursively(dest) && (!move || src.delete()))
                        if (done) {
                            changed += dest.absolutePath
                            if (move) changed += src.absolutePath
                        }
                        done
                    } catch (e: Exception) {
                        false
                    }
                }.all { it }
            }
            val label = getString(if (move) R.string.ml_explorer_move else R.string.ml_explorer_copy)
            if (!ok) showToast(getString(R.string.ml_explorer_failed, label))
            else showToast(getString(if (move) R.string.ml_gallery_moved else R.string.ml_gallery_copied, files.size, target.name))
            afterChange(changed)
        }
    }

    private fun deleteSelected() {
        val files = selectedFiles()
        showConfirmDialog(
            getString(R.string.ml_explorer_delete),
            if (files.size == 1) getString(R.string.ml_explorer_delete_confirm, files[0].name)
            else getString(R.string.ml_explorer_delete_many_confirm, files.size),
            getString(R.string.ml_explorer_delete),
        ) {
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { files.map { it.delete() }.all { it } }
                if (!ok) showToast(getString(R.string.ml_explorer_failed, getString(R.string.ml_explorer_delete)))
                afterChange(files.map { it.absolutePath })
            }
        }
    }

    private fun showMore() {
        val chosen = selectedFiles()
        val items = mutableListOf<MenuEntry>()
        chosen.singleOrNull()?.let { file ->
            // The folder it comes from (camera, WhatsApp, Download...)
            file.parentFile?.let { folder ->
                items += MenuEntry(getString(R.string.ml_gallery_show_in_folder), icon = R.drawable.ic_m_folder_open, value = folder.name) {
                    FileExplorerActivity.openLocal(this, folder)
                }
            }
            items += MenuEntry(getString(R.string.ml_explorer_open_with)) {
                val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(file), mimeOf(file.name) ?: "image/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(intent, file.name))
            }
        }
        items += MenuEntry(getString(R.string.ml_explorer_select_all)) {
            media.forEach { selected.add(it.absolutePath) }
            updateBars()
            grid.adapter?.notifyDataSetChanged()
        }
        showMenuDialog(getString(R.string.ml_explorer_selected, chosen.size), items)
    }

    /** After moving, copying or deleting: let the gallery know, then reload the grid. */
    private fun afterChange(paths: List<String>) {
        if (paths.isNotEmpty()) MediaScannerConnection.scanFile(applicationContext, paths.toTypedArray(), null, null)
        searchTexts.clear()
        selected.clear()
        reload()
    }

    // ---------------------------------------------------------------- album folders

    /** Albums tab: tiles per row (2 large, 3 medium, 4 small) and whether albums show their newest photo. */
    private var folderColumns = 2
    private var albumPreviews = true

    /**
     * The bar's ⋮ in the Albums tab: how the tiles look, then (at the top) where the albums are, or
     * (inside a folder) what can be done with it.
     */
    private fun showAlbumsMenu() {
        val sizes = listOf(2 to R.string.ml_gallery_size_large, 3 to R.string.ml_gallery_size_medium, 4 to R.string.ml_gallery_size_small)
        val entries = mutableListOf(
            MenuEntry(getString(R.string.ml_gallery_tile_size), icon = R.drawable.ic_m_resize,
                value = getString(sizes.first { it.first == folderColumns }.second), section = getString(R.string.ml_gallery_view)) {
                showMenuDialog(getString(R.string.ml_gallery_tile_size), sizes.map { (columns, label) ->
                    MenuEntry(getString(label), icon = if (columns == folderColumns) R.drawable.ic_m_check else 0) {
                        folderColumns = columns
                        CameraRoll.setAlbumColumns(this, columns)
                        grid.adapter = GridAdapter()
                    }
                })
            },
            MenuEntry(getString(R.string.ml_gallery_album_previews), checked = albumPreviews, onToggle = { on ->
                albumPreviews = on
                CameraRoll.setAlbumPreviews(this, on)
                grid.adapter?.notifyDataSetChanged()
                true
            }),
        )
        if (same(albumDir, albumRoot)) {
            entries += MenuEntry(getString(R.string.ml_gallery_album_root), icon = R.drawable.ic_m_folder, value = relative(albumRoot),
                section = getString(R.string.ml_gallery_tab_albums)) {
                albumRootPicker.launch(Intent(this, FolderPickerActivity::class.java)
                    .putExtra(FileExplorerActivity.EXTRA_LOCAL_PATH, albumRoot.absolutePath))
            }
            entries += MenuEntry(getString(R.string.ml_gallery_show_in_folder), icon = R.drawable.ic_m_folder_open) {
                FileExplorerActivity.openLocal(this, albumRoot)
            }
        } else {
            val folder = albumDir
            entries += MenuEntry(getString(R.string.rename), icon = R.drawable.ic_m_rename, section = folder.name) { askRename(folder) }
            entries += MenuEntry(getString(R.string.ml_gallery_move_folder), icon = R.drawable.ic_m_move) { pickFolderTarget(listOf(folder)) }
            entries += MenuEntry(getString(R.string.ml_gallery_show_in_folder), icon = R.drawable.ic_m_folder_open) {
                FileExplorerActivity.openLocal(this, folder)
            }
            entries += MenuEntry(getString(R.string.ml_explorer_delete), icon = R.drawable.ic_m_delete, destructive = true) { askDelete(listOf(folder)) }
        }
        showMenuDialog(getString(R.string.ml_gallery_tab_albums), entries)
    }

    private fun selectedFolders() = folders.filter { !it.loose && it.key in selected }.map { it.dir }

    private fun moveSelectedFolders() = selectedFolders().takeIf { it.isNotEmpty() }?.let { pickFolderTarget(it) }

    private fun renameSelectedFolder() {
        val folder = selectedFolders().singleOrNull() ?: return showToast(getString(R.string.ml_gallery_rename_one))
        askRename(folder)
    }

    private fun deleteSelectedFolders() = selectedFolders().takeIf { it.isNotEmpty() }?.let { askDelete(it) }

    private fun showFolderMore() {
        val chosen = selectedFolders()
        val items = mutableListOf<MenuEntry>()
        chosen.singleOrNull()?.let { folder ->
            items += MenuEntry(getString(R.string.ml_gallery_show_in_folder), icon = R.drawable.ic_m_folder_open) {
                FileExplorerActivity.openLocal(this, folder)
            }
        }
        items += MenuEntry(getString(R.string.ml_explorer_select_all)) {
            folders.filterNot { it.loose }.forEach { selected.add(it.key) }
            updateBars()
            grid.adapter?.notifyDataSetChanged()
        }
        showMenuDialog(getString(R.string.ml_explorer_selected, chosen.size), items)
    }

    /** Where folders go: chosen in the explorer, starting from the folder they are in. */
    private fun pickFolderTarget(chosen: List<File>) {
        foldersToMove = chosen
        folderMovePicker.launch(Intent(this, FolderPickerActivity::class.java)
            .putExtra(FileExplorerActivity.EXTRA_LOCAL_PATH, (chosen.first().parentFile ?: albumRoot).absolutePath))
    }

    /** A new folder in [parent]; [then] gets it once made. */
    private fun askNewFolder(parent: File, then: (File) -> Unit) {
        showTextInputDialog(getString(R.string.ml_gallery_new_folder), "", getString(R.string.ml_gallery_folder_name), getString(R.string.ml_save)) { name ->
            val made = File(parent, name)
            when {
                !validName(name) -> showToast(getString(R.string.ml_gallery_bad_name))
                made.exists() -> showToast(getString(R.string.ml_gallery_folder_exists))
                !made.mkdirs() -> showToast(getString(R.string.ml_explorer_failed, getString(R.string.ml_gallery_new_folder)))
                else -> {
                    reload()
                    then(made)
                }
            }
        }
    }

    private fun askRename(folder: File) {
        showTextInputDialog(getString(R.string.rename), folder.name, getString(R.string.ml_gallery_folder_name), getString(R.string.ml_save)) { name ->
            if (name == folder.name) return@showTextInputDialog
            val renamed = File(folder.parentFile ?: return@showTextInputDialog, name)
            when {
                !validName(name) -> showToast(getString(R.string.ml_gallery_bad_name))
                renamed.exists() -> showToast(getString(R.string.ml_gallery_folder_exists))
                else -> {
                    // Keeps its place in the dragged order
                    val parent = folder.parentFile
                    if (parent != null) {
                        val order = CameraRoll.albumOrder(this, parent)
                        if (folder.name in order) CameraRoll.saveAlbumOrder(this, parent, order.map { if (it == folder.name) name else it })
                    }
                    relocate(listOf(folder to renamed), getString(R.string.rename))
                }
            }
        }
    }

    private fun moveFolders(chosen: List<File>, target: File) {
        val moves = chosen.filter { !same(target, it.parentFile ?: target) }.mapNotNull { folder ->
            val dest = File(target, folder.name)
            when {
                // Not into itself or one of its own folders
                (target.absolutePath + "/").startsWith(folder.absolutePath + "/") -> {
                    showToast(getString(R.string.ml_gallery_bad_target))
                    null
                }
                dest.exists() -> {
                    showToast(getString(R.string.ml_gallery_folder_exists))
                    null
                }
                else -> folder to dest
            }
        }
        if (moves.isNotEmpty()) relocate(moves, getString(R.string.ml_gallery_move_folder))
    }

    /** Renames or moves folders (copying when a plain rename can't); the gallery is told about every photo. */
    private fun relocate(moves: List<Pair<File, File>>, label: String) {
        lifecycleScope.launch {
            val paths = mutableListOf<String>()
            var failed = false
            withContext(Dispatchers.IO) {
                for ((folder, dest) in moves) {
                    val before = folder.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toList()
                    val ok = folder.renameTo(dest) || (folder.copyRecursively(dest) && folder.deleteRecursively())
                    if (!ok) failed = true
                    else paths += before + dest.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toList()
                }
            }
            if (failed) showToast(getString(R.string.ml_explorer_failed, label))
            // Inside a folder that moved: follow it
            moves.firstOrNull { (folder, _) -> (albumDir.absolutePath + "/").startsWith(folder.absolutePath + "/") }?.let { (folder, dest) ->
                albumDir = File(dest.absolutePath + albumDir.absolutePath.removePrefix(folder.absolutePath))
            }
            covers.clear()
            afterChange(paths)
        }
    }

    /** Deletes folders with all they hold, after a confirmation that says how much goes. */
    private fun askDelete(chosen: List<File>) {
        lifecycleScope.launch {
            val photos = withContext(Dispatchers.IO) { chosen.sumOf { treeCount(it) } }
            val message = if (chosen.size == 1) getString(R.string.ml_gallery_delete_folder_confirm, chosen[0].name, photos)
            else getString(R.string.ml_gallery_delete_folders_confirm, chosen.size, photos)
            showConfirmDialog(getString(R.string.ml_explorer_delete), message, getString(R.string.ml_explorer_delete)) {
                lifecycleScope.launch {
                    val paths = mutableListOf<String>()
                    var failed = false
                    withContext(Dispatchers.IO) {
                        for (folder in chosen) {
                            val all = folder.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toList()
                            if (folder.deleteRecursively()) paths += all else failed = true
                        }
                    }
                    if (failed) showToast(getString(R.string.ml_explorer_failed, getString(R.string.ml_explorer_delete)))
                    chosen.firstOrNull { (albumDir.absolutePath + "/").startsWith(it.absolutePath + "/") }?.let {
                        albumDir = it.parentFile ?: albumRoot
                        looseView = false
                    }
                    covers.clear()
                    afterChange(paths)
                }
            }
        }
    }

    private fun validName(name: String) = name.isNotBlank() && name != "." && name != ".." && name.none { it in "/\\:*?\"<>|" }

    private val storageRoot: File get() = android.os.Environment.getExternalStorageDirectory()

    /** "/DCIM/Foto" for a folder of the phone storage. */
    private fun relative(folder: File) = folder.absolutePath.removePrefix(storageRoot.absolutePath).ifEmpty { "/" }

    /** "DCIM › Foto › 2025" for the subtitle and the album choice. */
    private fun relativePath(folder: File) = relative(folder).trim('/').split('/').filter { it.isNotEmpty() }.joinToString(" › ")

    private fun same(a: File, b: File) = a.absolutePath.trimEnd('/') == b.absolutePath.trimEnd('/')

    // ---------------------------------------------------------------- media

    private fun mimeOf(name: String) =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())

    /** Opens a photo or video: the viewer swipes through the Photos tab, or through the album it is in. */
    private fun open(file: File) {
        if (app.olauncher.data.Prefs(this).builtInViewer) {
            val through = if (tab == Tab.PHOTOS) sources else listOf(albumDir)
            // In the gallery's own task: back from the viewer returns here
            startActivity(app.olauncher.ui.media.MediaViewerActivity.intent(this, file, through))
            return
        }
        val mime = mimeOf(file.name) ?: "image/*"
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(file), mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        for (gallery in GALLERIES) {
            try {
                startActivity(Intent(intent).setPackage(gallery))
                return
            } catch (e: Exception) {
                // not installed: try the next one
            }
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            showToast(getString(R.string.ml_search_cannot_open))
        }
    }

    private fun openFolder(folder: Folder) {
        if (folder.loose) looseView = true else albumDir = folder.dir
        // A folder found by the search: opened as it is
        if (searchRow.isVisible) closeSearch(refresh = false)
        reload()
    }

    private inner class GridAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = if (showingFolders) folders.size else media.size

        override fun getItemViewType(position: Int) = if (showingFolders) TYPE_FOLDER else TYPE_PHOTO

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(if (viewType == TYPE_FOLDER) folderCell(parent) else photoCell(parent)) {}

        private fun cellSide(parent: ViewGroup, columns: Int) =
            parent.width.takeIf { it > 0 }?.let { (it - parent.paddingLeft - parent.paddingRight) / columns }
                ?: (resources.displayMetrics.widthPixels / columns)

        private fun roundImage(ctx: Context, radiusDp: Int) = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            // Rounded corners: clipped to the background's outline
            background = GradientDrawable().apply {
                cornerRadius = radiusDp.dpToPx().toFloat()
                setColor(0x22888888)
            }
            clipToOutline = true
        }

        private fun photoCell(parent: ViewGroup): View {
            val side = cellSide(parent, COLUMNS)
            val cell = FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(side, side)
                setPadding(2.dpToPx(), 2.dpToPx(), 2.dpToPx(), 2.dpToPx())
                isClickable = true
                isLongClickable = true
            }
            cell.addView(roundImage(parent.context, 12), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // Play mark of the videos: a line icon in a small dark circle
            cell.addView(FrameLayout(parent.context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x99000000.toInt())
                }
                addView(ImageView(parent.context).apply {
                    setImageResource(R.drawable.ic_m_play)
                    imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
                }, FrameLayout.LayoutParams(18.dpToPx(), 18.dpToPx(), Gravity.CENTER))
                isVisible = false
            }, FrameLayout.LayoutParams(34.dpToPx(), 34.dpToPx(), Gravity.CENTER))
            // Selection mark (a check badge, filled when selected)
            cell.addView(FrameLayout(parent.context).apply { isVisible = false }, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END
            ).apply {
                setMargins(0, 8.dpToPx(), 8.dpToPx(), 0)
            })
            return cell
        }

        /**
         * A folder tile: a square (an album's newest photo, or a line icon on a card: always for a folder
         * of folders, for albums when previews are off) with the selection mark, then the name and count.
         */
        private fun folderCell(parent: ViewGroup): View {
            val ctx = parent.context
            val side = cellSide(parent, folderColumns) - 12.dpToPx()
            val small = folderColumns >= 4
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setPadding(6.dpToPx(), 0, 6.dpToPx(), (if (small) 10 else 14).dpToPx())
                isClickable = true
                isLongClickable = true
            }
            val box = FrameLayout(ctx)
            box.addView(roundImage(ctx, if (small) 12 else 16), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // Without a preview: the kind as a line icon on a card
            box.addView(FrameLayout(ctx).apply {
                background = UiKit.cardBackground(ctx, if (small) 12 else 16)
                addView(ImageView(ctx).apply { imageTintList = ColorStateList.valueOf(UiKit.secondary(ctx)) },
                    FrameLayout.LayoutParams(side * 3 / 10, side * 3 / 10, Gravity.CENTER))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // Selection mark (a check badge, filled when selected)
            box.addView(FrameLayout(ctx).apply { isVisible = false }, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END
            ).apply {
                setMargins(0, 8.dpToPx(), 8.dpToPx(), 0)
            })
            cell.addView(box, LinearLayout.LayoutParams(side, side))
            cell.addView(TextView(ctx).apply {
                textSize = if (small) 13f else 15f
                setTextColor(UiKit.primary(ctx))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(2.dpToPx(), 6.dpToPx(), 2.dpToPx(), 0)
            }, LinearLayout.LayoutParams(side, ViewGroup.LayoutParams.WRAP_CONTENT))
            cell.addView(TextView(ctx).apply {
                textSize = if (small) 11.5f else 12.5f
                setTextColor(UiKit.secondary(ctx))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(2.dpToPx(), 0, 2.dpToPx(), 0)
            }, LinearLayout.LayoutParams(side, ViewGroup.LayoutParams.WRAP_CONTENT))
            return cell
        }

        /** The newest photo of an album, read once in the background; [show] gets it (null: none). */
        private fun withCover(folder: Folder, show: (File?) -> Unit) {
            val key = folder.dir.absolutePath + if (folder.loose) "#loose" else ""
            if (covers.containsKey(key)) return show(covers[key])
            lifecycleScope.launch {
                val cover = withContext(Dispatchers.IO) { MediaThumbnails.mediaIn(listOf(folder.dir)).firstOrNull() }
                covers[key] = cover
                show(cover)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (showingFolders) bindFolder(holder.itemView as LinearLayout, folders[position]) else bindPhoto(holder.itemView as FrameLayout, media[position])
        }

        private fun bindFolder(cell: LinearLayout, folder: Folder) {
            val box = cell.getChildAt(0) as FrameLayout
            val image = box.getChildAt(0) as ImageView
            val plain = box.getChildAt(1) as FrameLayout
            val mark = box.getChildAt(2) as FrameLayout
            val isAlbum = folder.folders == 0
            (cell.getChildAt(1) as TextView).text = folder.name
            (cell.getChildAt(2) as TextView).text = when {
                folder.folders > 0 && folder.photos > 0 -> getString(R.string.ml_gallery_folders_photos, folder.folders, folder.photos)
                folder.folders > 0 -> resources.getQuantityString(R.plurals.ml_gallery_n_folders, folder.folders, folder.folders)
                else -> getString(R.string.ml_gallery_n_photos, folder.photos)
            }
            cell.contentDescription = folder.name
            val isSelected = folder.key in selected
            mark.removeAllViews()
            if (selecting && !folder.loose) mark.addView(UiKit.checkBadge(this@GalleryActivity, isSelected, 24))
            mark.isVisible = selecting && !folder.loose
            box.alpha = if (selecting && !isSelected) 0.6f else 1f
            box.scaleX = if (isSelected) 0.92f else 1f
            box.scaleY = box.scaleX
            cell.setOnClickListener { if (selecting) toggleKey(folder.key) else openFolder(folder) }
            // Not selecting: the long press is the drag helper's (drag to reorder, or select when it doesn't move);
            // search results aren't dragged, their long press selects
            if (selecting || searching) cell.setOnLongClickListener {
                toggleKey(folder.key)
                dragSelect.start(folders.indexOf(folder))
                true
            } else {
                cell.setOnLongClickListener(null)
                cell.isLongClickable = true
            }
            // A folder of folders never shows a photo; an album does when previews are on
            val preview = isAlbum && albumPreviews && folder.photos > 0
            val icon = plain.getChildAt(0) as ImageView
            icon.setImageResource(if (isAlbum) R.drawable.ic_m_image else R.drawable.ic_m_folder)
            plain.isVisible = !preview
            image.setImageDrawable(null)
            image.tag = null
            if (preview) {
                val path = folder.dir.absolutePath
                cell.tag = path
                withCover(folder) { cover ->
                    if (cell.tag != path) return@withCover
                    if (cover == null) plain.isVisible = true
                    else MediaThumbnails.load(lifecycleScope, image, cover, box.layoutParams.width.coerceAtLeast(64))
                }
            } else cell.tag = null
        }

        private fun bindPhoto(cell: FrameLayout, file: File) {
            val image = cell.getChildAt(0) as ImageView
            val play = cell.getChildAt(1)
            val mark = cell.getChildAt(2) as FrameLayout
            val isSelected = file.absolutePath in selected
            play.isVisible = mimeOf(file.name)?.startsWith("video/") == true
            mark.removeAllViews()
            if (selecting) mark.addView(UiKit.checkBadge(this@GalleryActivity, isSelected, 24))
            mark.isVisible = selecting
            image.alpha = if (selecting && !isSelected) 0.6f else 1f
            image.scaleX = if (isSelected) 0.9f else 1f
            image.scaleY = image.scaleX
            cell.setOnClickListener { if (selecting) toggle(file) else open(file) }
            cell.setOnLongClickListener {
                toggle(file)
                dragSelect.start(media.indexOf(file))
                true
            }
            MediaThumbnails.load(lifecycleScope, image, file, cell.layoutParams.width.coerceAtLeast(64), uris[file.absolutePath])
        }
    }

    companion object {
        private const val EXTRA_SHOW_SOURCES = "show_sources"
        private const val COLUMNS = 3
        private const val SPANS = 12
        private const val MAX_SEARCH_DEPTH = 8
        private const val MAX_SEARCH_RESULTS = 200
        private const val TYPE_PHOTO = 0
        private const val TYPE_FOLDER = 1
        private val GALLERIES = listOf("org.fossify.gallery", "com.simplemobiletools.gallery.pro")

        /** Opens the gallery; [showSources] opens the choice of its folders too. */
        fun open(context: Context, showSources: Boolean = false) = context.startActivity(
            Intent(context, GalleryActivity::class.java).putExtra(EXTRA_SHOW_SOURCES, showSources)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
