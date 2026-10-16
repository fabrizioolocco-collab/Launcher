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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
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
 * Simple file explorer for the phone storage and OneDrive: browse, open with the phone's apps,
 * share, rename, delete, copy and move (also between phone and OneDrive).
 *
 * Opened from the home search (a folder, or the folder of a file), the home menu or its own
 * "File" icon in the app list. Extras pick the starting folder: [EXTRA_LOCAL_PATH] for the
 * phone, [EXTRA_CLOUD_ID] (empty = root) for OneDrive; none shows the two roots.
 */
class FileExplorerActivity : AppCompatActivity() {

    /** Where we are: the roots list, a phone folder or a OneDrive folder (id null = root). */
    private sealed class Location {
        object Roots : Location()
        data class Local(val dir: File) : Location()
        data class Cloud(val id: String?, val name: String) : Location()
    }

    private class Entry(
        val name: String,
        val isFolder: Boolean,
        val size: Long,
        val modified: Long,
        val local: File? = null,
        val cloud: OneDriveSearch.Item? = null,
        val mime: String? = null,
    )

    private class Clip(val entry: Entry, val move: Boolean)

    private lateinit var oneDrive: OneDriveSearch
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var status: TextView
    private lateinit var list: RecyclerView
    private lateinit var pasteBar: LinearLayout
    private lateinit var pasteText: TextView
    private lateinit var newFolder: TextView

    private var location: Location = Location.Roots
    /** OneDrive parent of the folder shown (for "up"); null at the root. */
    private var cloudParent: Location.Cloud? = null
    private var entries: List<Entry> = emptyList()
    private var clip: Clip? = null
    private var waitingAccess = false

    private val storageRoot: File get() = Environment.getExternalStorageDirectory()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        oneDrive = OneDriveSearch(this)
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

    private fun startLocation(intent: Intent?): Location {
        intent?.getStringExtra(EXTRA_LOCAL_PATH)?.let { return Location.Local(File(it)) }
        if (intent?.hasExtra(EXTRA_CLOUD_ID) == true) {
            val id = intent.getStringExtra(EXTRA_CLOUD_ID)?.ifBlank { null }
            return Location.Cloud(id, intent.getStringExtra(EXTRA_CLOUD_NAME) ?: getString(R.string.ml_search_onedrive))
        }
        return Location.Roots
    }

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
        when (val loc = location) {
            Location.Roots -> finish()
            is Location.Local -> location =
                if (loc.dir.absolutePath == storageRoot.absolutePath) Location.Roots
                else Location.Local(loc.dir.parentFile ?: storageRoot)

            is Location.Cloud -> location = if (loc.id == null) Location.Roots else cloudParent ?: Location.Cloud(null, getString(R.string.ml_search_onedrive))
        }
        if (location != Location.Roots || !isFinishing) load()
    }

    private fun open(loc: Location) {
        location = loc
        load()
    }

    private fun load() {
        updatePasteBar()
        when (val loc = location) {
            Location.Roots -> showRoots()
            is Location.Local -> loadLocal(loc)
            is Location.Cloud -> loadCloud(loc)
        }
    }

    private fun showStatus(text: String?, onClick: (() -> Unit)? = null) {
        status.isVisible = text != null
        status.text = text
        if (onClick != null) status.setOnClickListener { onClick() } else status.setOnClickListener(null)
    }

    private fun setEntries(list: List<Entry>) {
        entries = list.sortedWith(compareBy({ !it.isFolder }, { it.name.lowercase() }))
        this.list.adapter?.notifyDataSetChanged()
        this.list.scrollToPosition(0)
    }

    private fun showRoots() {
        title.setText(R.string.ml_explorer)
        subtitle.text = ""
        newFolder.isVisible = false
        showStatus(null)
        entries = listOf(
            Entry(getString(R.string.ml_search_phone), true, 0, 0, local = storageRoot),
            Entry(getString(R.string.ml_search_onedrive), true, 0, 0, cloud = ROOT_MARKER),
        )
        list.adapter?.notifyDataSetChanged()
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

    private fun loadLocal(loc: Location.Local) {
        title.text = if (loc.dir.absolutePath == storageRoot.absolutePath) getString(R.string.ml_search_phone) else loc.dir.name
        subtitle.text = "📱 /" + relative(loc.dir)
        newFolder.isVisible = true
        if (!hasFilesAccess()) {
            setEntries(emptyList())
            showStatus(getString(R.string.ml_search_allow_files)) { askFilesAccess() }
            return
        }
        val files = loc.dir.listFiles()
        if (files == null) {
            setEntries(emptyList())
            showStatus(getString(R.string.ml_explorer_cannot_read))
            return
        }
        setEntries(files.filter { !it.name.startsWith(".") }.map { f ->
            Entry(f.name, f.isDirectory, if (f.isDirectory) 0 else f.length(), f.lastModified(), local = f, mime = mimeOf(f.name))
        })
        showStatus(if (entries.isEmpty()) getString(R.string.ml_explorer_empty) else null)
    }

    private fun loadCloud(loc: Location.Cloud) {
        title.text = loc.name
        subtitle.text = "☁️ " + getString(R.string.ml_search_onedrive)
        newFolder.isVisible = true
        if (!oneDrive.isConnected()) {
            setEntries(emptyList())
            showStatus(getString(R.string.ml_onedrive_connect)) { if (oneDrive.connect()) waitingAccess = true }
            return
        }
        setEntries(emptyList())
        showStatus(getString(R.string.ml_explorer_loading))
        lifecycleScope.launch {
            val (items, self) = withContext(Dispatchers.IO) { oneDrive.children(loc.id) to oneDrive.item(loc.id) }
            if (location != loc) return@launch
            if (items == null) {
                showStatus(getString(R.string.ml_onedrive_error)) { if (oneDrive.connect()) waitingAccess = true }
                return@launch
            }
            cloudParent = self?.parentId?.let { pid ->
                val parentName = self.path?.substringAfterLast('/')?.ifBlank { null } ?: getString(R.string.ml_search_onedrive)
                Location.Cloud(if (self.path == null) null else pid, parentName)
            }
            self?.path?.let { subtitle.text = "☁️ /$it/${loc.name}" }
            setEntries(items.map { Entry(it.name, it.isFolder, it.size, 0, cloud = it, mime = it.mimeType ?: mimeOf(it.name)) })
            showStatus(if (entries.isEmpty()) getString(R.string.ml_explorer_empty) else null)
        }
    }

    private fun relative(f: File) = f.absolutePath.removePrefix(storageRoot.absolutePath).trimStart('/')

    private fun mimeOf(name: String) =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())

    // ---------------------------------------------------------------- items

    private fun onTap(entry: Entry) {
        when {
            entry.cloud === ROOT_MARKER -> open(Location.Cloud(null, getString(R.string.ml_search_onedrive)))
            entry.isFolder && entry.local != null -> open(Location.Local(entry.local))
            entry.isFolder && entry.cloud != null -> open(Location.Cloud(entry.cloud.id, entry.name))
            else -> withLocalFile(entry) { file -> view(file, entry.mime) }
        }
    }

    private fun onLongPress(entry: Entry) {
        if (location == Location.Roots) return
        val items = mutableListOf<MenuEntry>()
        if (!entry.isFolder) {
            items += MenuEntry(getString(R.string.ml_explorer_open_with)) { withLocalFile(entry) { view(it, entry.mime, chooser = true) } }
            items += MenuEntry(getString(R.string.ml_explorer_share)) { withLocalFile(entry) { share(it, entry.mime) } }
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

    /** The entry as a local file: itself on the phone, a fresh download for OneDrive. */
    private fun withLocalFile(entry: Entry, action: (File) -> Unit) {
        entry.local?.let { return action(it) }
        val item = entry.cloud ?: return
        showToast(getString(R.string.ml_onedrive_downloading, item.name))
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { oneDrive.download(item) }
            if (file == null) showToast(getString(R.string.ml_onedrive_download_failed)) else action(file)
        }
    }

    private fun needsWriteAccess(entry: Entry? = null): Boolean {
        val cloud = entry?.cloud != null || location is Location.Cloud
        if (cloud && !oneDrive.canWrite()) {
            showConfirmDialog(
                getString(R.string.ml_search_onedrive), getString(R.string.ml_explorer_reconnect), getString(R.string.ml_onedrive_connect),
            ) { if (oneDrive.connect()) waitingAccess = true }
            return true
        }
        return false
    }

    private fun askRename(entry: Entry) {
        if (needsWriteAccess(entry)) return
        showTextInputDialog(getString(R.string.rename), entry.name, entry.name, getString(R.string.ml_save)) { name ->
            run(getString(R.string.rename)) {
                entry.local?.let { it.renameTo(File(it.parentFile, name)) }
                    ?: entry.cloud?.let { oneDrive.rename(it.id, name) } ?: false
            }
        }
    }

    private fun askDelete(entry: Entry) {
        if (needsWriteAccess(entry)) return
        showConfirmDialog(getString(R.string.ml_explorer_delete), getString(R.string.ml_explorer_delete_confirm, entry.name), getString(R.string.ml_explorer_delete)) {
            run(getString(R.string.ml_explorer_delete)) {
                entry.local?.deleteRecursively() ?: entry.cloud?.let { oneDrive.delete(it.id) } ?: false
            }
        }
    }

    private fun askNewFolder() {
        if (location == Location.Roots || needsWriteAccess()) return
        showTextInputDialog(getString(R.string.ml_explorer_new_folder), "", getString(R.string.ml_explorer_folder_name), getString(R.string.ml_save)) { name ->
            run(getString(R.string.ml_explorer_new_folder)) {
                when (val loc = location) {
                    is Location.Local -> File(loc.dir, name).mkdirs()
                    is Location.Cloud -> oneDrive.createFolder(loc.id, name) != null
                    Location.Roots -> false
                }
            }
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
        if (move && needsWriteAccess(entry)) return
        clip = Clip(entry, move)
        updatePasteBar()
    }

    private fun updatePasteBar() {
        val c = clip
        pasteBar.isVisible = c != null && location != Location.Roots
        if (c != null) pasteText.text = getString(if (c.move) R.string.ml_explorer_moving else R.string.ml_explorer_copying, c.entry.name)
    }

    private fun paste() {
        val c = clip ?: return
        val target = location
        if (target == Location.Roots || needsWriteAccess()) return
        val entry = c.entry
        // A folder can't go inside itself
        if (entry.local != null && target is Location.Local &&
            (target.dir.absolutePath + "/").startsWith(entry.local.absolutePath + "/")
        ) {
            showToast(getString(R.string.ml_explorer_into_itself))
            return
        }
        clip = null
        val label = getString(if (c.move) R.string.ml_explorer_move else R.string.ml_explorer_copy)
        run(label) {
            when (target) {
                is Location.Local -> pasteToLocal(entry, target.dir, c.move)
                is Location.Cloud -> pasteToCloud(entry, target.id, c.move)
                Location.Roots -> false
            }
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
        entry.local?.let { src ->
            val dest = uniqueTarget(dir, src.name)
            if (move && src.renameTo(dest)) return true
            if (!src.copyRecursively(dest)) return false
            return !move || src.deleteRecursively()
        }
        val item = entry.cloud ?: return false
        if (!downloadTree(item, uniqueTarget(dir, item.name))) return false
        return !move || oneDrive.delete(item.id)
    }

    private fun downloadTree(item: OneDriveSearch.Item, dest: File): Boolean {
        if (!item.isFolder) return oneDrive.downloadTo(item, dest)
        if (!dest.mkdirs()) return false
        val children = oneDrive.children(item.id) ?: return false
        return children.all { downloadTree(it, File(dest, it.name)) }
    }

    private fun pasteToCloud(entry: Entry, folderId: String?, move: Boolean): Boolean {
        entry.cloud?.let { item ->
            return if (move) oneDrive.move(item.id, folderId) else oneDrive.copy(item.id, folderId)
        }
        val src = entry.local ?: return false
        if (!uploadTree(src, folderId)) return false
        return !move || src.deleteRecursively()
    }

    private fun uploadTree(src: File, folderId: String?): Boolean {
        if (!src.isDirectory) return oneDrive.upload(src, folderId)
        val folder = oneDrive.createFolder(folderId, src.name) ?: return false
        return src.listFiles().orEmpty().all { uploadTree(it, folder.id) }
    }

    // ---------------------------------------------------------------- list

    private inner class EntriesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8.dpToPx(), 0, 8.dpToPx())
                isClickable = true
                isLongClickable = true
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            row.addView(TextView(parent.context, null, 0, R.style.TextLarge).apply {
                textSize = 20f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(40.dpToPx(), ViewGroup.LayoutParams.WRAP_CONTENT))
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
            return object : RecyclerView.ViewHolder(row) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val entry = entries[position]
            val row = holder.itemView as LinearLayout
            val glyph = row.getChildAt(0) as TextView
            val texts = row.getChildAt(1) as LinearLayout
            val name = texts.getChildAt(0) as TextView
            val info = texts.getChildAt(1) as TextView
            glyph.text = when {
                location == Location.Roots -> if (entry.local != null) "📱" else "☁️"
                entry.isFolder -> "📁"
                entry.mime?.startsWith("image/") == true -> "🖼️"
                entry.mime?.startsWith("video/") == true -> "🎬"
                entry.mime?.startsWith("audio/") == true -> "🎵"
                else -> "📄"
            }
            name.text = entry.name
            info.text = when {
                location == Location.Roots -> ""
                entry.isFolder -> getString(R.string.ml_explorer_folder)
                else -> listOfNotNull(
                    Formatter.formatShortFileSize(this@FileExplorerActivity, entry.size),
                    entry.modified.takeIf { it > 0 }?.let { DateFormat.getMediumDateFormat(this@FileExplorerActivity).format(it) },
                ).joinToString(" · ")
            }
            info.isVisible = info.text.isNotEmpty()
            row.setOnClickListener { onTap(entry) }
            row.setOnLongClickListener {
                onLongPress(entry)
                true
            }
        }
    }

    companion object {
        const val EXTRA_LOCAL_PATH = "local_path"
        const val EXTRA_CLOUD_ID = "cloud_id"
        const val EXTRA_CLOUD_NAME = "cloud_name"

        /** Stands for the OneDrive root in the roots list. */
        private val ROOT_MARKER = OneDriveSearch.Item("", "", null, "", true, null, null, 0)

        fun openLocal(context: Context, dir: File) = context.startActivity(
            Intent(context, FileExplorerActivity::class.java).putExtra(EXTRA_LOCAL_PATH, dir.absolutePath)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )

        fun openCloud(context: Context, id: String?, name: String) = context.startActivity(
            Intent(context, FileExplorerActivity::class.java).putExtra(EXTRA_CLOUD_ID, id ?: "").putExtra(EXTRA_CLOUD_NAME, name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )

        fun openRoots(context: Context) = context.startActivity(
            Intent(context, FileExplorerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
