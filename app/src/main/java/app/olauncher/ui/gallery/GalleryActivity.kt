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
 *   album and shows its photos. Folders are made, renamed, moved and deleted here; photos are moved
 *   or copied into them from the selection. Nothing is ever organized automatically.
 *
 * Long press starts a multiple selection: share, copy, move (into an album), delete.
 */
class GalleryActivity : AppCompatActivity() {

    private enum class Tab { PHOTOS, ALBUMS }

    /** A folder tile in the Albums tab; [loose] is the "Other photos" tile of a folder that has folders and photos. */
    private class Folder(val dir: File, val name: String, val cover: File?, val folders: Int, val photos: Int, val loose: Boolean = false)

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
    private var folders: List<Folder> = emptyList()
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
    private lateinit var tabs: LinearLayout
    private lateinit var selectionBar: LinearLayout
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
    /** A folder of the Albums tab moved elsewhere: the destination chosen in the explorer. */
    private var folderToMove: File? = null
    private val folderMovePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val target = result.data?.getStringExtra(FileExplorerActivity.EXTRA_LOCAL_PATH)?.let { File(it) }
        val folder = folderToMove
        folderToMove = null
        if (target != null && folder != null) moveFolder(folder, target)
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

    /** Back: ends a selection, then goes up the album folders, then leaves. */
    private fun goBack() {
        when {
            selecting -> clearSelection()
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
        folders = emptyList()
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
                media = found.second
                folders = emptyList()
                uris = found.third
            } else {
                // A folder gone meanwhile (deleted or renamed elsewhere): back to the nearest one still there
                while (!albumDir.isDirectory && !same(albumDir, albumRoot)) albumDir = albumDir.parentFile ?: albumRoot
                val shown = albumDir
                val loose = looseView
                val found = withContext(Dispatchers.IO) { readAlbum(shown, loose) }
                media = found.first
                folders = found.second
                uris = if (media.isEmpty()) emptyMap() else MediaThumbnails.mediaUris(this@GalleryActivity, shown)
            }
            loaded = true
            selected.retainAll(media.map { it.absolutePath }.toSet())
            updateBars()
            grid.adapter?.notifyDataSetChanged()
            grid.scrollToPosition(0)
            then()
        }
    }

    /** What [folder] shows: its subfolders when it has any (plus "Other photos" for photos beside them), else its photos. */
    private fun readAlbum(folder: File, loose: Boolean): Pair<List<File>, List<Folder>> {
        val subs = subfolders(folder)
        if (loose || subs.isEmpty()) return MediaThumbnails.mediaIn(listOf(folder)) to emptyList()
        val tiles = subs.map { sub ->
            val (photos, cover) = treeStats(sub)
            Folder(sub, sub.name, cover, subfolders(sub).size, photos)
        }
        val own = MediaThumbnails.mediaIn(listOf(folder))
        val other = if (own.isEmpty()) emptyList()
        else listOf(Folder(folder, getString(R.string.ml_gallery_other_photos), own.first(), 0, own.size, loose = true))
        return emptyList<File>() to tiles + other
    }

    /** The folders inside [folder] as the user keeps them: by name, hidden ones left out. */
    private fun subfolders(folder: File): List<File> =
        folder.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.sortedBy { it.name.lowercase() }.orEmpty()

    /** Photos and videos in the whole tree of [folder], and the cover: its newest photo, else the first subfolder's. */
    private fun treeStats(folder: File): Pair<Int, File?> {
        val own = MediaThumbnails.mediaIn(listOf(folder))
        var total = own.size
        var cover = own.firstOrNull()
        for (sub in subfolders(folder)) {
            val (n, c) = treeStats(sub)
            total += n
            if (cover == null) cover = c
        }
        return total to cover
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
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top + 8.dpToPx(), 0, bars.bottom)
            insets
        }
        sourcesButton = UiKit.iconButton(this, R.drawable.ic_m_sliders, getString(R.string.ml_gallery_sources)) { showSources() }
        newFolderButton = UiKit.iconButton(this, R.drawable.ic_m_add, getString(R.string.ml_gallery_new_folder)) { askNewFolder(albumDir) {} }
        menuButton = UiKit.iconButton(this, R.drawable.ic_m_more, getString(R.string.ml_explorer_more)) { showFolderMenu(albumDir) }
        val bar = UiKit.appBar(this, getString(R.string.ml_gallery), onBack = { goBack() },
            actions = listOf(sourcesButton, newFolderButton, menuButton))
        bar.view.setPadding(6.dpToPx(), 0, 6.dpToPx(), 8.dpToPx())
        backButton = bar.back
        title = bar.title
        count = bar.subtitle
        root.addView(bar.view)

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
            // Six spans: photos take two (three a row), folder tiles three (two a row)
            layoutManager = GridLayoutManager(this@GalleryActivity, SPANS).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int) = if (showingFolders) SPANS / FOLDER_COLUMNS else SPANS / COLUMNS
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
            isSelected = { p -> media.getOrNull(p)?.absolutePath in selected },
            setSelected = { p, on -> media.getOrNull(p)?.let { if (on) selected.add(it.absolutePath) else selected.remove(it.absolutePath) } },
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
        return root
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
        tabs.isVisible = !selecting
        selectionBar.isVisible = selecting
        emptyView.isVisible = !selecting && media.isEmpty() && folders.isEmpty() && loaded
        emptyView.text = getString(if (albums) R.string.ml_gallery_empty_folder else R.string.ml_gallery_empty)
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
        selected.clear()
        reload()
    }

    // ---------------------------------------------------------------- album folders

    /** Menu of an album folder (the tile's ⋮, or the bar's inside it); at the top: where the albums are. */
    private fun showFolderMenu(folder: File) {
        if (same(folder, albumRoot)) {
            showMenuDialog(getString(R.string.ml_gallery_tab_albums), listOf(
                MenuEntry(getString(R.string.ml_gallery_album_root), icon = R.drawable.ic_m_folder, value = relative(albumRoot)) {
                    albumRootPicker.launch(Intent(this, FolderPickerActivity::class.java)
                        .putExtra(FileExplorerActivity.EXTRA_LOCAL_PATH, albumRoot.absolutePath))
                },
                MenuEntry(getString(R.string.ml_gallery_show_in_folder), icon = R.drawable.ic_m_folder_open) {
                    FileExplorerActivity.openLocal(this, folder)
                },
            ))
            return
        }
        showMenuDialog(folder.name, listOf(
            MenuEntry(getString(R.string.rename), icon = R.drawable.ic_m_rename) { askRename(folder) },
            MenuEntry(getString(R.string.ml_gallery_move_folder), icon = R.drawable.ic_m_move) {
                folderToMove = folder
                folderMovePicker.launch(Intent(this, FolderPickerActivity::class.java)
                    .putExtra(FileExplorerActivity.EXTRA_LOCAL_PATH, (folder.parentFile ?: albumRoot).absolutePath))
            },
            MenuEntry(getString(R.string.ml_gallery_show_in_folder), icon = R.drawable.ic_m_folder_open) {
                FileExplorerActivity.openLocal(this, folder)
            },
            MenuEntry(getString(R.string.ml_explorer_delete), icon = R.drawable.ic_m_delete, destructive = true) { askDelete(folder) },
        ))
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
                else -> relocate(folder, renamed, getString(R.string.rename))
            }
        }
    }

    private fun moveFolder(folder: File, target: File) {
        val dest = File(target, folder.name)
        when {
            same(target, folder.parentFile ?: target) -> return
            // Not into itself or one of its own folders
            (target.absolutePath + "/").startsWith(folder.absolutePath + "/") -> showToast(getString(R.string.ml_gallery_bad_target))
            dest.exists() -> showToast(getString(R.string.ml_gallery_folder_exists))
            else -> relocate(folder, dest, getString(R.string.ml_gallery_move_folder))
        }
    }

    /** Renames or moves [folder] to [dest] (copying when a plain rename can't); the gallery is told about every photo. */
    private fun relocate(folder: File, dest: File, label: String) {
        lifecycleScope.launch {
            val paths = withContext(Dispatchers.IO) {
                val before = folder.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toList()
                val ok = folder.renameTo(dest) || (folder.copyRecursively(dest) && folder.deleteRecursively())
                if (!ok) null else before + dest.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toList()
            }
            if (paths == null) {
                showToast(getString(R.string.ml_explorer_failed, label))
                return@launch
            }
            // Inside the folder that moved: follow it
            if ((albumDir.absolutePath + "/").startsWith(folder.absolutePath + "/"))
                albumDir = File(dest.absolutePath + albumDir.absolutePath.removePrefix(folder.absolutePath))
            afterChange(paths)
        }
    }

    private fun askDelete(folder: File) {
        lifecycleScope.launch {
            val (photos, _) = withContext(Dispatchers.IO) { treeStats(folder) }
            showConfirmDialog(
                getString(R.string.ml_explorer_delete),
                getString(R.string.ml_gallery_delete_folder_confirm, folder.name, photos),
                getString(R.string.ml_explorer_delete),
            ) {
                lifecycleScope.launch {
                    val paths = withContext(Dispatchers.IO) {
                        val all = folder.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toList()
                        if (folder.deleteRecursively()) all else null
                    }
                    if (paths == null) showToast(getString(R.string.ml_explorer_failed, getString(R.string.ml_explorer_delete)))
                    if ((albumDir.absolutePath + "/").startsWith(folder.absolutePath + "/")) {
                        albumDir = folder.parentFile ?: albumRoot
                        looseView = false
                    }
                    afterChange(paths.orEmpty())
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

        /** A folder tile: square cover (with the number of its folders), name, counts and a ⋮ for its menu. */
        private fun folderCell(parent: ViewGroup): View {
            val ctx = parent.context
            val side = cellSide(parent, FOLDER_COLUMNS) - 12.dpToPx()
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setPadding(6.dpToPx(), 0, 6.dpToPx(), 14.dpToPx())
            }
            val coverBox = FrameLayout(ctx).apply {
                isClickable = true
                foreground = UiKit.ripple(ctx)
            }
            coverBox.addView(roundImage(ctx, 16), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // Folder mark with the number of folders inside
            coverBox.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = 10.dpToPx().toFloat()
                    setColor(0x99000000.toInt())
                }
                setPadding(7.dpToPx(), 3.dpToPx(), 8.dpToPx(), 3.dpToPx())
                addView(ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_m_folder)
                    imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
                }, LinearLayout.LayoutParams(14.dpToPx(), 14.dpToPx()).apply { marginEnd = 4.dpToPx() })
                addView(TextView(ctx).apply {
                    textSize = 12f
                    setTextColor(0xFFFFFFFF.toInt())
                })
                isVisible = false
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
                setMargins(8.dpToPx(), 0, 0, 8.dpToPx())
            })
            cell.addView(coverBox, LinearLayout.LayoutParams(side, side))
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                setPadding(2.dpToPx(), 6.dpToPx(), 0, 0)
            }
            row.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(ctx).apply {
                    textSize = 15f
                    setTextColor(UiKit.primary(ctx))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                addView(TextView(ctx).apply {
                    textSize = 12.5f
                    setTextColor(UiKit.secondary(ctx))
                    maxLines = 1
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(UiKit.iconButton(ctx, R.drawable.ic_m_more, getString(R.string.ml_explorer_more), UiKit.secondary(ctx)) {},
                LinearLayout.LayoutParams(36.dpToPx(), 36.dpToPx()))
            cell.addView(row, LinearLayout.LayoutParams(side, ViewGroup.LayoutParams.WRAP_CONTENT))
            return cell
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (showingFolders) bindFolder(holder.itemView as LinearLayout, folders[position]) else bindPhoto(holder.itemView as FrameLayout, media[position])
        }

        private fun bindFolder(cell: LinearLayout, folder: Folder) {
            val coverBox = cell.getChildAt(0) as FrameLayout
            val image = coverBox.getChildAt(0) as ImageView
            val mark = coverBox.getChildAt(1) as LinearLayout
            val row = cell.getChildAt(1) as LinearLayout
            val texts = row.getChildAt(0) as LinearLayout
            val more = row.getChildAt(1)
            (texts.getChildAt(0) as TextView).text = folder.name
            (texts.getChildAt(1) as TextView).text =
                if (folder.folders > 0) getString(R.string.ml_gallery_folders_photos, folder.folders, folder.photos)
                else getString(R.string.ml_gallery_n_photos, folder.photos)
            mark.isVisible = folder.folders > 0
            (mark.getChildAt(1) as TextView).text = folder.folders.toString()
            coverBox.setOnClickListener { openFolder(folder) }
            coverBox.contentDescription = folder.name
            // "Other photos" is not a folder of its own: no menu
            more.isVisible = !folder.loose
            more.setOnClickListener { showFolderMenu(folder.dir) }
            image.setImageDrawable(null)
            image.tag = null
            folder.cover?.let { MediaThumbnails.load(lifecycleScope, image, it, coverBox.layoutParams.width.coerceAtLeast(64)) }
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
        private const val FOLDER_COLUMNS = 2
        private const val SPANS = 6
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
