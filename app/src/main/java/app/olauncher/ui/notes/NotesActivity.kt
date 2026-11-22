package app.olauncher.ui.notes

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The notes: one tree with every folder and, inside each, the notes it holds by their titles. A
 * folder opens and shuts in place, a note opens in the editor ([NoteEditorActivity]). A new note
 * or folder from the buttons at the bottom goes in the top folder; dragged, it goes anywhere.
 */
class NotesActivity : AppCompatActivity() {

    private sealed class Row {
        class FolderRow(val folder: NotesStore.Folder, val depth: Int, val open: Boolean) : Row()
        class NoteRow(val note: File, val depth: Int, val pinned: Boolean, val snippet: String? = null) : Row()
    }

    private lateinit var root: File
    private var rows: List<Row> = emptyList()

    /** The folder touched last (kept up to date through renames and moves). */
    private var target: File? = null
    private var query: String? = null
    private var inBin = false
    private var loading: Job? = null

    private lateinit var column: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var searchInput: EditText
    private lateinit var searchButton: ImageView
    private lateinit var menuButton: ImageView
    private lateinit var backButton: ImageView
    private lateinit var list: RecyclerView
    private lateinit var empty: FrameLayout
    private lateinit var buttons: LinearLayout
    private val adapter = Adapter()

    private val folderPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val path = result.data?.getStringExtra(FileExplorerActivity.EXTRA_LOCAL_PATH)
        val picked = onPicked
        onPicked = null
        if (result.resultCode == RESULT_OK && path != null) picked?.invoke(File(path))
    }
    private var onPicked: ((File) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        root = NotesStore.root(this)
        buildLayout()
        setContentView(column)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })
        intent.getStringExtra(EXTRA_QUERY)?.takeIf { it.isNotBlank() }?.let { startSearch(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_QUERY)?.takeIf { it.isNotBlank() }?.let { startSearch(it) }
    }

    override fun onResume() {
        super.onResume()
        // Back from the editor (a new title), or from the settings where the access was given
        reload()
    }

    private fun goBack() {
        when {
            query != null -> endSearch()
            inBin -> {
                inBin = false
                reload()
            }
            else -> finish()
        }
    }

    private fun hasAccess() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun askAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE, android.Manifest.permission.READ_EXTERNAL_STORAGE), 1)
    }

    // ---------------------------------------------------------------- reading the folder

    private fun reload() {
        loading?.cancel()
        if (!hasAccess()) {
            adapter.submit(emptyList())
            showEmpty(getString(R.string.ml_notes_access), getString(R.string.ml_notes_allow)) { askAccess() }
            return
        }
        val dir = root
        val shut = NotesStore.shut(this)
        val search = query
        val bin = inBin
        loading = lifecycleScope.launch {
            // A search waits for the typing to stop
            if (search != null) delay(200)
            val found = withContext(Dispatchers.IO) {
                when {
                    search != null -> NotesStore.search(dir, search).map { Row.NoteRow(it.note, 0, false, it.snippet) }
                    bin -> NotesStore.trashed(dir).map { Row.NoteRow(it, 0, false) }
                    else -> {
                        NotesStore.putPage(this@NotesActivity, dir)
                        val tree = NotesStore.scan(dir)
                        val pins = NotesStore.pins(dir).map { File(dir, it) }.filter { NotesStore.isNote(it) }
                        val out = ArrayList<Row>()
                        pins.forEach { out += Row.NoteRow(it, 0, true) }
                        fun walk(folder: NotesStore.Folder, depth: Int) {
                            for (sub in folder.folders) {
                                val open = sub.dir.absolutePath !in shut
                                out += Row.FolderRow(sub, depth, open)
                                if (open) walk(sub, depth + 1)
                            }
                            folder.notes.filter { it !in pins }.forEach { out += Row.NoteRow(it, depth, false) }
                        }
                        walk(tree, 0)
                        out
                    }
                }
            }
            rows = found
            adapter.submit(found)
            when {
                found.isNotEmpty() -> empty.isVisible = false
                search != null -> showEmpty(getString(R.string.ml_notes_none_found), null, null)
                bin -> showEmpty(getString(R.string.ml_notes_bin_empty), null, null)
                else -> showEmpty(getString(R.string.ml_notes_empty), null, null)
            }
            showWhere()
        }
    }

    private fun showEmpty(text: String, action: String?, onAction: (() -> Unit)?) {
        empty.removeAllViews()
        empty.addView(UiKit.emptyState(this, R.drawable.ic_m_doc, text, action, onAction), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        empty.isVisible = true
    }

    private fun name(dir: File) = if (dir == root) getString(R.string.ml_notes_app) else dir.relativeTo(root).invariantSeparatorsPath.replace("/", " › ")

    /** The bar and the buttons, for the tree, a search or the bin. */
    private fun showWhere() {
        val dir = target?.takeIf { it.isDirectory } ?: root
        target = dir
        val plain = query == null && !inBin
        buttons.isVisible = plain && hasAccess()
        titleView.text = getString(if (inBin) R.string.ml_notes_bin else R.string.ml_notes_app)
        backButton.isVisible = true
        searchButton.isVisible = !inBin && query == null
        menuButton.isVisible = query == null
    }

    // ---------------------------------------------------------------- search

    private fun startSearch(text: String = "") {
        inBin = false
        query = text
        titleView.isVisible = false
        searchInput.isVisible = true
        searchInput.setText(text)
        searchInput.setSelection(text.length)
        searchInput.requestFocus()
        if (text.isEmpty()) (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(searchInput, 0)
        reload()
    }

    private fun endSearch() {
        query = null
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(searchInput.windowToken, 0)
        searchInput.isVisible = false
        titleView.isVisible = true
        reload()
    }

    // ---------------------------------------------------------------- actions

    private fun newNote(dir: File) {
        val note = NotesStore.newNote(dir, getString(R.string.ml_notes_new_title))
        open(dir)
        startActivity(NoteEditorActivity.intent(this, note))
    }

    /** A note to type in, or a notebook to write in by hand. */
    private fun newWhat(dir: File) {
        showMenuDialog(
            getString(R.string.ml_notes_new_note),
            listOf(
                MenuEntry(getString(R.string.ml_notes_kind_text)) { newNote(dir) },
                MenuEntry(getString(R.string.ml_notes_kind_notebook)) { newNotebook(dir) },
            )
        )
    }

    private fun newNotebook(dir: File) {
        val note = NotesStore.newNotebook(dir, getString(R.string.ml_notes_new_notebook_title))
        open(dir)
        startActivity(NoteEditorActivity.intent(this, note))
    }

    private fun newFolder(dir: File) {
        showTextInputDialog(getString(R.string.ml_notes_new_folder), "", getString(R.string.ml_notes_folder_name), getString(R.string.ml_notes_create)) { name ->
            val made = NotesStore.newFolder(dir, name)
            if (made == null) showToast(getString(R.string.ml_notes_failed)) else {
                open(dir)
                target = made
            }
            reload()
        }
    }

    /** Makes sure a folder shows what is inside it (something was just put there). */
    private fun open(dir: File) {
        val shut = NotesStore.shut(this)
        if (shut.remove(dir.absolutePath)) NotesStore.setShut(this, shut)
    }

    private fun toggle(folder: File) {
        val shut = NotesStore.shut(this)
        if (!shut.remove(folder.absolutePath)) shut.add(folder.absolutePath)
        NotesStore.setShut(this, shut)
        target = folder
        reload()
    }

    private fun pickFolder(start: File, picked: (File) -> Unit) {
        onPicked = picked
        folderPicker.launch(
            Intent(this, FolderPickerActivity::class.java).putExtra(FileExplorerActivity.EXTRA_LOCAL_PATH, start.absolutePath)
                .putExtra(FileExplorerActivity.EXTRA_FOLDERS_ONLY, true)
        )
    }

    /** Every folder of the notes, to choose where a note goes. */
    private fun chooseFolder(titleText: String, done: (File) -> Unit) {
        val all = ArrayList<File>()
        fun walk(f: NotesStore.Folder) {
            all += f.dir
            f.folders.forEach { walk(it) }
        }
        walk(NotesStore.scan(root))
        showMenuDialog(titleText, all.map { dir -> MenuEntry(name(dir)) { done(dir) } })
    }

    private fun noteMenu(note: File) {
        if (inBin) {
            showMenuDialog(
                NotesStore.titleOf(note),
                listOf(
                    MenuEntry(getString(R.string.ml_notes_restore)) {
                        NotesStore.restore(root, note)
                        reload()
                    },
                    MenuEntry(getString(R.string.ml_notes_delete_forever), destructive = true) {
                        note.delete()
                        reload()
                    },
                )
            )
            return
        }
        val pinned = NotesStore.isPinned(root, note)
        showMenuDialog(
            NotesStore.titleOf(note),
            listOf(
                MenuEntry(getString(if (pinned) R.string.ml_notes_unpin else R.string.ml_notes_pin)) {
                    NotesStore.setPinned(root, note, !pinned)
                    reload()
                },
                MenuEntry(getString(R.string.ml_notes_rename)) {
                    showTextInputDialog(getString(R.string.ml_notes_rename), NotesStore.titleOf(note), getString(R.string.ml_notes_title_hint), getString(R.string.ml_notes_save)) {
                        NotesStore.rename(root, note, it)
                        reload()
                    }
                },
                MenuEntry(getString(R.string.ml_notes_move)) {
                    chooseFolder(getString(R.string.ml_notes_move)) { dir ->
                        if (NotesStore.move(root, note, dir) == null) showToast(getString(R.string.ml_notes_failed))
                        open(dir)
                        reload()
                    }
                },
                MenuEntry(getString(R.string.ml_notes_share)) {
                    runCatching {
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, NotesStore.titleOf(note)).putExtra(Intent.EXTRA_TEXT, NotesStore.text(note)), null))
                    }
                },
                MenuEntry(getString(R.string.ml_notes_delete), destructive = true) {
                    if (!NotesStore.trash(root, note)) showToast(getString(R.string.ml_notes_failed))
                    reload()
                },
            )
        )
    }

    private fun folderMenu(folder: NotesStore.Folder) {
        val dir = folder.dir
        showMenuDialog(
            dir.name,
            listOf(
                MenuEntry(getString(R.string.ml_notes_new_note_here)) { newNote(dir) },
                MenuEntry(getString(R.string.ml_notes_new_notebook_here)) { newNotebook(dir) },
                MenuEntry(getString(R.string.ml_notes_new_subfolder)) { newFolder(dir) },
                MenuEntry(getString(R.string.ml_notes_rename)) {
                    showTextInputDialog(getString(R.string.ml_notes_rename), dir.name, getString(R.string.ml_notes_folder_name), getString(R.string.ml_notes_save)) {
                        val renamed = NotesStore.renameFolder(root, dir, it)
                        if (target == dir) target = renamed
                        reload()
                    }
                },
                MenuEntry(getString(R.string.ml_notes_delete), destructive = true) {
                    val count = folder.count
                    val delete = {
                        NotesStore.trashFolder(root, dir)
                        if (target?.absolutePath?.startsWith(dir.absolutePath) == true) target = root
                        reload()
                    }
                    if (count == 0) delete()
                    else showConfirmDialog(dir.name, resources.getQuantityString(R.plurals.ml_notes_delete_folder, count, count), getString(R.string.ml_notes_delete)) { delete() }
                },
            )
        )
    }

    private fun mainMenu() {
        if (inBin) {
            showConfirmDialog(getString(R.string.ml_notes_bin), getString(R.string.ml_notes_empty_bin_q), getString(R.string.ml_notes_empty_bin)) {
                NotesStore.emptyTrash(root)
                reload()
            }
            return
        }
        showMenuDialog(
            getString(R.string.ml_notes_app),
            listOf(
                MenuEntry(getString(R.string.ml_notes_folder), value = root.name) {
                    pickFolder(root.parentFile ?: root) { dir ->
                        NotesStore.setRoot(this, dir)
                        root = dir
                        target = dir
                        reload()
                    }
                },
                MenuEntry(getString(R.string.ml_notes_bin)) {
                    inBin = true
                    reload()
                },
                MenuEntry(getString(R.string.ml_notes_pc)) {
                    showConfirmDialog(getString(R.string.ml_notes_pc), getString(R.string.ml_notes_pc_help, root.absolutePath), getString(R.string.ml_notes_ok)) {}
                },
            )
        )
    }

    // ---------------------------------------------------------------- dragging

    /** The note or the folder held under the finger. */
    private class Dragged(val file: File, val folder: NotesStore.Folder?, val origin: View) {
        /** It has been taken away from where it was: letting go is a move, not a long press. */
        var left = false
        var over = false
    }

    private var dragged: Dragged? = null

    /**
     * A long press: held and moved, the note (or the folder) is dragged to another folder; held and
     * let go where it is, it opens its menu, as a long press does everywhere else.
     */
    private fun hold(view: View, file: File, folder: NotesStore.Folder?): Boolean {
        val menu = { if (folder != null) folderMenu(folder) else noteMenu(file) }
        if (inBin || query != null) {
            menu()
            return true
        }
        dragged = Dragged(file, folder, view)
        val started = view.startDragAndDrop(android.content.ClipData.newPlainText("note", file.name), View.DragShadowBuilder(view), null, 0)
        if (!started) {
            dragged = null
            menu()
        }
        return true
    }

    /** Makes [view] a place to drop on: what is dropped goes into the folder [into] gives. */
    private fun dropOn(view: View, into: () -> File?) {
        view.setOnDragListener { v, e ->
            val held = dragged ?: return@setOnDragListener false
            when (e.action) {
                android.view.DragEvent.ACTION_DRAG_ENTERED -> {
                    if (v !== held.origin) {
                        held.left = true
                        v.alpha = 0.45f
                    }
                }
                android.view.DragEvent.ACTION_DRAG_EXITED -> {
                    if (v === held.origin) held.left = true
                    v.alpha = 1f
                }
                // Near the top or the bottom of the list, it scrolls under the finger
                android.view.DragEvent.ACTION_DRAG_LOCATION -> if (v.parent === list) {
                    val y = v.top + e.y
                    if (y < 72.dpToPx()) list.scrollBy(0, -24.dpToPx()) else if (y > list.height - 72.dpToPx()) list.scrollBy(0, 24.dpToPx())
                }
                android.view.DragEvent.ACTION_DROP -> {
                    v.alpha = 1f
                    val dir = into()
                    if (held.left && dir != null) drop(held, dir)
                }
                android.view.DragEvent.ACTION_DRAG_ENDED -> {
                    v.alpha = 1f
                    if (!held.over) {
                        held.over = true
                        dragged = null
                        // Never moved from its place: it was a long press
                        if (!held.left) v.post { if (held.folder != null) folderMenu(held.folder) else noteMenu(held.file) }
                    }
                }
            }
            true
        }
    }

    private fun drop(held: Dragged, dir: File) {
        val moved = if (held.folder != null) NotesStore.moveFolder(root, held.file, dir) else NotesStore.move(root, held.file, dir)
        if (moved == null) showToast(getString(R.string.ml_notes_cannot_move))
        else {
            open(dir)
            if (held.folder != null && target == held.file) target = moved
        }
        reload()
    }

    // ---------------------------------------------------------------- layout

    private fun buildLayout() {
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        ViewCompat.setOnApplyWindowInsetsListener(column) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val primary = UiKit.primary(this)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 56.dpToPx()
            setPadding(4.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
        }
        backButton = UiKit.iconButton(this, R.drawable.ic_m_arrow_back, getString(R.string.ml_cal_back)) { goBack() }
        titleView = TextView(this).apply {
            textSize = 22f
            setTextColor(primary)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(6.dpToPx(), 0, 6.dpToPx(), 0)
        }
        searchInput = EditText(this).apply {
            hint = getString(R.string.ml_notes_search_hint)
            textSize = 16f
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            isVisible = false
            addTextChangedListener { text ->
                if (query != null && text.toString() != query) {
                    query = text.toString()
                    reload()
                }
            }
        }
        UiKit.styleInput(searchInput)
        searchButton = UiKit.iconButton(this, R.drawable.ic_m_search, getString(R.string.ml_search)) { startSearch() }
        menuButton = UiKit.iconButton(this, R.drawable.ic_m_more, getString(R.string.ml_notes_more)) { mainMenu() }
        bar.addView(backButton)
        bar.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(searchInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 4.dpToPx() })
        bar.addView(searchButton)
        bar.addView(menuButton)
        column.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // Dropped on the bar at the top: out of every folder
        dropOn(bar) { root }

        val content = FrameLayout(this)
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@NotesActivity)
            adapter = this@NotesActivity.adapter
            itemAnimator = null
            clipToPadding = false
            setPadding(8.dpToPx(), 0, 8.dpToPx(), 16.dpToPx())
        }
        // Dropped on the empty part under the last row: out of every folder too
        dropOn(list) { root }
        empty = FrameLayout(this).apply { isVisible = false }
        content.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        content.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        column.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16.dpToPx(), 8.dpToPx(), 16.dpToPx(), 12.dpToPx())
        }
        buttons.addView(
            pillButton(this, getString(R.string.ml_notes_new_folder), filled = false, color = primary) { newFolder(root) },
            LinearLayout.LayoutParams(0, 52.dpToPx(), 1f).apply { marginEnd = 5.dpToPx() }
        )
        buttons.addView(
            pillButton(this, getString(R.string.ml_notes_new_note), filled = true, color = primary) { newWhat(root) },
            LinearLayout.LayoutParams(0, 52.dpToPx(), 1f).apply { marginStart = 5.dpToPx() }
        )
        column.addView(buttons)
    }

    private class Holder(view: View) : RecyclerView.ViewHolder(view) {
        lateinit var chevron: ImageView
        lateinit var icon: ImageView
        lateinit var title: TextView
        lateinit var under: TextView
        lateinit var side: TextView
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        private var items: List<Row> = emptyList()

        fun submit(list: List<Row>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ctx = this@NotesActivity
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = 48.dpToPx()
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            return Holder(row).apply {
                chevron = UiKit.icon(ctx, R.drawable.ic_m_chevron_down, UiKit.secondary(ctx), 16)
                icon = UiKit.icon(ctx, R.drawable.ic_m_folder, UiKit.primary(ctx), 20)
                val texts = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(10.dpToPx(), 6.dpToPx(), 8.dpToPx(), 6.dpToPx())
                }
                title = TextView(ctx).apply {
                    textSize = 16f
                    setTextColor(UiKit.primary(ctx))
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                under = TextView(ctx).apply {
                    textSize = 13f
                    setTextColor(UiKit.secondary(ctx))
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }
                side = TextView(ctx).apply {
                    textSize = 13f
                    setTextColor(UiKit.secondary(ctx))
                    setPadding(0, 0, 8.dpToPx(), 0)
                }
                texts.addView(title)
                texts.addView(under)
                row.addView(chevron)
                row.addView(icon, LinearLayout.LayoutParams(20.dpToPx(), 20.dpToPx()).apply { marginStart = 6.dpToPx() })
                row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(side)
            }
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val ctx = this@NotesActivity
            val view = holder.itemView
            when (val item = items[position]) {
                is Row.FolderRow -> {
                    val dir = item.folder.dir
                    view.setPadding((8 + item.depth * 22).dpToPx(), 0, 0, 0)
                    holder.chevron.visibility = View.VISIBLE
                    holder.chevron.setImageResource(if (item.open) R.drawable.ic_m_chevron_down else R.drawable.ic_m_chevron_right)
                    holder.icon.isVisible = true
                    holder.icon.setImageResource(if (item.open) R.drawable.ic_m_folder_open else R.drawable.ic_m_folder)
                    holder.title.text = dir.name
                    holder.title.setTypeface(null, Typeface.BOLD)
                    holder.under.isVisible = false
                    // Shut, it says how many notes it keeps
                    holder.side.text = if (item.open) "" else item.folder.count.toString()
                    // The folder a new note would go to stands out
                    view.background = UiKit.ripple(ctx)
                    view.alpha = 1f
                    view.setOnClickListener { toggle(dir) }
                    view.setOnLongClickListener { hold(view, dir, item.folder) }
                    dropOn(view) { dir }
                }
                is Row.NoteRow -> {
                    val note = item.note
                    view.setPadding((8 + item.depth * 22).dpToPx(), 0, 0, 0)
                    view.background = UiKit.ripple(ctx)
                    // Under a folder a note sits where the folder's name starts
                    holder.chevron.visibility = if (item.pinned) View.VISIBLE else View.INVISIBLE
                    holder.chevron.setImageResource(R.drawable.ic_m_star)
                    holder.icon.isVisible = false
                    holder.title.text = NotesStore.titleOf(note)
                    holder.title.setTypeface(null, Typeface.NORMAL)
                    holder.under.text = item.snippet
                    holder.under.isVisible = !item.snippet.isNullOrBlank()
                    holder.side.text = DateUtils.getRelativeTimeSpanString(note.lastModified(), System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS, DateUtils.FORMAT_ABBREV_ALL)
                    view.alpha = 1f
                    view.setOnClickListener { if (inBin) noteMenu(note) else startActivity(NoteEditorActivity.intent(ctx, note)) }
                    view.setOnLongClickListener { hold(view, note, null) }
                    // Dropped on a note: beside it, in its folder
                    dropOn(view) { note.parentFile }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_QUERY = "query"

        /** The notes opened on what a search finds in them (the launcher's own search). */
        fun searchIntent(ctx: Context, query: String): Intent = Intent(ctx, NotesActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_QUERY, query)
    }
}
