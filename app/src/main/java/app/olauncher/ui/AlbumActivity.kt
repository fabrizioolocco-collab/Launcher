package app.olauncher.ui

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
import app.olauncher.helper.MediaThumbnails
import app.olauncher.helper.MenuEntry
import app.olauncher.helper.dpToPx
import app.olauncher.helper.showConfirmDialog
import app.olauncher.helper.showMenuDialog
import app.olauncher.helper.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Grid of the photos and videos of an album folder (newest first), opened from the home search or
 * the explorer. Tapping one opens it in the gallery (Fossify when installed), which lets you swipe
 * through the rest of the folder; Fossify can't be asked to open the album grid itself.
 *
 * Long press starts a multiple selection: share, copy, move (to a folder chosen in the explorer,
 * where new folders can be made), delete.
 */
class AlbumActivity : AppCompatActivity() {

    private lateinit var dir: File
    private var media: List<File> = emptyList()
    /** MediaStore address of each file: fast system thumbnails and gallery opening. */
    private var uris: Map<String, Uri> = emptyMap()

    private val selected = LinkedHashSet<String>()
    private val selecting get() = selected.isNotEmpty()
    /** Selecting by sliding over the photos (after a long press, or sideways during a selection). */
    private lateinit var dragSelect: DragSelect
    private lateinit var title: TextView
    private lateinit var count: TextView
    private lateinit var backButton: ImageView
    private lateinit var folderButton: ImageView
    private lateinit var selectionBar: LinearLayout
    private lateinit var grid: RecyclerView

    /** Copy or move waiting for the destination folder. */
    private var pendingMove: Boolean? = null
    private val folderPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val target = result.data?.getStringExtra(FileExplorerActivity.EXTRA_LOCAL_PATH)?.let { File(it) }
        val move = pendingMove
        pendingMove = null
        if (target != null && move != null) transfer(target, move)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dir = File(intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return })
        setContentView(buildLayout())
        reload()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = if (selecting) clearSelection() else finish()
        })
        updateSelection()
    }

    private var firstResume = true

    override fun onResume() {
        super.onResume()
        if (firstResume) {
            firstResume = false
            return
        }
        // Back from the viewer or an editor: a saved copy or a deleted file must show at once
        reload()
    }

    private var loading: Job? = null

    /** Reads the photos again, off the main thread (a chat folder holds thousands); [then] runs once they are shown. */
    private fun reload(then: () -> Unit = {}) {
        loading?.cancel()
        loading = lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                MediaThumbnails.mediaIn(listOf(dir)) to MediaThumbnails.mediaUris(this@AlbumActivity, dir)
            }
            media = found.first
            uris = found.second
            selected.retainAll(media.map { it.absolutePath }.toSet())
            updateSelection()
            grid.adapter?.notifyDataSetChanged()
            then()
        }
    }

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
        folderButton = UiKit.iconButton(this, R.drawable.ic_m_folder_open, getString(R.string.ml_search_open_folder)) {
            FileExplorerActivity.openLocal(this@AlbumActivity, dir)
        }
        val bar = UiKit.appBar(this, dir.name, onBack = { if (selecting) clearSelection() else finish() }, actions = listOf(folderButton))
        bar.view.setPadding(6.dpToPx(), 0, 6.dpToPx(), 8.dpToPx())
        backButton = bar.back
        title = bar.title
        count = bar.subtitle
        root.addView(bar.view)
        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@AlbumActivity, COLUMNS)
            adapter = MediaAdapter()
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(8.dpToPx(), 0, 8.dpToPx(), 24.dpToPx())
        }
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        dragSelect = DragSelect(grid, { selecting },
            isSelected = { p -> media.getOrNull(p)?.absolutePath in selected },
            setSelected = { p, on -> media.getOrNull(p)?.let { if (on) selected.add(it.absolutePath) else selected.remove(it.absolutePath) } },
            onChanged = {
                updateSelection()
                grid.adapter?.notifyDataSetChanged()
            })
        selectionBar = UiKit.toolBar(this).apply { isVisible = false }
        listOf(
            Tool(R.drawable.ic_m_share, R.string.ml_explorer_share) { shareSelected() },
            Tool(R.drawable.ic_m_copy, R.string.ml_explorer_copy) { pickDestination(move = false) },
            Tool(R.drawable.ic_m_move, R.string.ml_explorer_move) { pickDestination(move = true) },
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

    /** A tool of the selection bar: line icon, label, action. */
    private class Tool(val icon: Int, val label: Int, val action: () -> Unit)

    // ---------------------------------------------------------------- selection

    private fun toggle(file: File) {
        if (!selected.remove(file.absolutePath)) selected.add(file.absolutePath)
        updateSelection()
        grid.adapter?.notifyDataSetChanged()
    }

    private fun clearSelection() {
        selected.clear()
        updateSelection()
        grid.adapter?.notifyDataSetChanged()
    }

    private fun selectedFiles() = media.filter { it.absolutePath in selected }

    private fun updateSelection() {
        backButton.setImageResource(if (selecting) R.drawable.ic_m_close else R.drawable.ic_m_arrow_back)
        backButton.contentDescription = getString(if (selecting) R.string.close else R.string.ml_cal_back)
        title.text = if (selecting) getString(R.string.ml_explorer_selected, selected.size) else dir.name
        count.text = getString(R.string.ml_album_count, media.size)
        count.isVisible = !selecting
        folderButton.isVisible = !selecting
        selectionBar.isVisible = selecting
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

    /** Copy / move: the destination is chosen in the explorer (where new folders can be made). */
    private fun pickDestination(move: Boolean) {
        pendingMove = move
        folderPicker.launch(Intent(this, FolderPickerActivity::class.java).putExtra(FileExplorerActivity.EXTRA_LOCAL_PATH, dir.absolutePath))
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
            items += MenuEntry(getString(R.string.ml_explorer_open_with)) {
                val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(file), mimeOf(file.name) ?: "image/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(intent, file.name))
            }
        }
        items += MenuEntry(getString(R.string.ml_explorer_select_all)) {
            media.forEach { selected.add(it.absolutePath) }
            updateSelection()
            grid.adapter?.notifyDataSetChanged()
        }
        showMenuDialog(getString(R.string.ml_explorer_selected, chosen.size), items)
    }

    /** After moving, copying or deleting: let the gallery know, then reload the grid. */
    private fun afterChange(paths: List<String>) {
        if (paths.isNotEmpty()) MediaScannerConnection.scanFile(applicationContext, paths.toTypedArray(), null, null)
        selected.clear()
        reload { if (media.isEmpty()) finish() }
    }

    // ---------------------------------------------------------------- media

    private fun mimeOf(name: String) =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())

    /** Opens a photo or video in the gallery (Fossify first), which shows the rest of the folder too. */
    private fun open(file: File) {
        if (app.olauncher.data.Prefs(this).builtInViewer) {
            app.olauncher.ui.media.MediaViewerActivity.open(this, file)
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

    private inner class MediaAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = media.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val side = parent.width.takeIf { it > 0 }?.let { (it - parent.paddingLeft - parent.paddingRight) / COLUMNS }
                ?: (resources.displayMetrics.widthPixels / COLUMNS)
            val cell = FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(side, side)
                setPadding(2.dpToPx(), 2.dpToPx(), 2.dpToPx(), 2.dpToPx())
                isClickable = true
                isLongClickable = true
            }
            cell.addView(ImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                // Rounded corners: clipped to the background's outline
                background = GradientDrawable().apply {
                    cornerRadius = 12.dpToPx().toFloat()
                    setColor(0x22888888)
                }
                clipToOutline = true
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
            return object : RecyclerView.ViewHolder(cell) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val file = media[position]
            val cell = holder.itemView as FrameLayout
            val image = cell.getChildAt(0) as ImageView
            val play = cell.getChildAt(1)
            val mark = cell.getChildAt(2) as FrameLayout
            val isSelected = file.absolutePath in selected
            play.isVisible = mimeOf(file.name)?.startsWith("video/") == true
            mark.removeAllViews()
            if (selecting) mark.addView(UiKit.checkBadge(this@AlbumActivity, isSelected, 24))
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
        private const val EXTRA_PATH = "path"
        private const val COLUMNS = 3
        private val GALLERIES = listOf("org.fossify.gallery", "com.simplemobiletools.gallery.pro")

        fun open(context: Context, dir: File) = context.startActivity(
            Intent(context, AlbumActivity::class.java).putExtra(EXTRA_PATH, dir.absolutePath)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
