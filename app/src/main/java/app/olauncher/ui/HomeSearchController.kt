package app.olauncher.ui

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StrictMode
import android.provider.ContactsContract
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Prefs
import app.olauncher.helper.DetoxManager
import app.olauncher.helper.IconPackManager
import app.olauncher.helper.MediaThumbnails
import app.olauncher.helper.MenuEntry
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.showMenuDialog
import app.olauncher.helper.dpToPx
import app.olauncher.helper.hideKeyboard
import app.olauncher.helper.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import java.text.Normalizer

/**
 * Search bar at the bottom of home. Tapping it opens the search page: bar on top, results below.
 * The button on the right switches between:
 * - Google: the search key opens the results in the browser;
 * - Phone: apps, contacts, files and folders on the phone and Android settings, listed
 *   while typing. Files can be opened, or their folder (Solid Explorer when installed).
 */
class HomeSearchController(
    private val activity: Activity,
    private val column: LinearLayout,
    private val bar: View,
    private val input: EditText,
    private val clear: TextView,
    private val modeButton: TextView,
    private val resultsScroll: ScrollView,
    private val results: LinearLayout,
    /** Views replaced by the search page (home list, bottom area). */
    private val homeViews: List<View>,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val apps: () -> List<AppModel.App>,
    private val launchApp: (AppModel.App) -> Unit,
    /** Asks a runtime permission; the search refreshes when the user answers. */
    private val requestPermission: (String) -> Unit,
    /** Called before opening another screen that should come back here (permission settings, sign-in). */
    private val keepScreen: (Boolean) -> Unit,
) {
    enum class Mode { WEB, PHONE }

    // Older versions had a separate OneDrive mode (2)
    private var mode = if (prefs.searchMode == 0) Mode.WEB else Mode.PHONE
    private var job: Job? = null

    /** True while waiting for a permission or sign-in screen: keep the page when home stops. */
    private var waitingPermission = false

    private var pageOpen = false
    private val barMargins = (bar.layoutParams as ViewGroup.MarginLayoutParams).let { it.topMargin to it.bottomMargin }

    val isActive get() = pageOpen

    init {
        modeButton.setOnClickListener {
            mode = if (mode == Mode.WEB) Mode.PHONE else Mode.WEB
            prefs.searchMode = mode.ordinal
            updateMode()
            onQueryChanged()
        }
        clear.setOnClickListener { close() }
        input.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) openPage() }
        input.setOnClickListener { openPage() }
        input.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                submit()
                true
            } else false
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = onQueryChanged()
        })
        updateMode()
        // OneDrive search was removed: forget its old sign-in
        activity.deleteSharedPreferences("app.minimalauncher.onedrive")
    }

    private fun query() = input.text.toString().trim()

    // ---------------------------------------------------------------- mode

    private fun updateMode() {
        modeButton.text = activity.getString(
            R.string.ml_search_mode_format,
            activity.getString(if (mode == Mode.WEB) R.string.ml_search_web else R.string.ml_search_phone)
        )
        input.setHint(if (mode == Mode.WEB) R.string.ml_search_hint_web else R.string.ml_search_hint_phone)
    }

    // ---------------------------------------------------------------- page

    /** Search page: home hidden, bar moved to the top, results below it. */
    private fun openPage() {
        if (pageOpen) return
        pageOpen = true
        homeViews.forEach { it.isVisible = false }
        // Move the results below the bar (the bar itself keeps focus and keyboard)
        column.removeView(resultsScroll)
        column.addView(resultsScroll)
        (bar.layoutParams as ViewGroup.MarginLayoutParams).apply {
            topMargin = 44.dpToPx()
            bottomMargin = 8.dpToPx()
        }
        bar.requestLayout()
        results.gravity = Gravity.TOP
        resultsScroll.isVisible = true
        clear.isVisible = true
        onQueryChanged()
    }

    /** Clears the query and brings home back. */
    fun close() {
        job?.cancel()
        tab = null
        input.setText("")
        input.clearFocus()
        input.hideKeyboard()
        results.removeAllViews()
        if (!pageOpen) return
        pageOpen = false
        resultsScroll.isVisible = false
        column.removeView(resultsScroll)
        column.addView(resultsScroll, column.indexOfChild(bar))
        (bar.layoutParams as ViewGroup.MarginLayoutParams).apply {
            topMargin = barMargins.first
            bottomMargin = barMargins.second
        }
        bar.requestLayout()
        clear.isVisible = false
        homeViews.forEach { it.isVisible = true }
    }

    /**
     * Something was opened from the search: keep the page (back from there returns to it), just
     * drop the keyboard. Pressing Home closes the search instead, see [onHomePressed].
     */
    private fun leave() {
        input.clearFocus()
        input.hideKeyboard()
    }

    fun onHomeStopped() = leave()

    /** The Home button brings back the plain home screen. */
    fun onHomePressed() = close()

    fun onHomeResumed() {
        if (waitingPermission) {
            waitingPermission = false
            keepScreen(false)
            onQueryChanged()
        }
    }

    /** A permission dialog was answered. */
    fun refresh() = onQueryChanged()

    private fun onQueryChanged() {
        val q = query()
        job?.cancel()
        if (q.isEmpty() || mode == Mode.WEB) {
            results.removeAllViews()
            return
        }
        job = scope.launch {
            delay(150)
            searchPhone(q)
        }
    }

    /** Search key: Google opens the browser, Phone opens the first result. */
    private fun submit() {
        val q = query()
        if (q.isEmpty()) return
        if (mode == Mode.WEB) {
            openWeb(q)
            return
        }
        (0 until results.childCount).map { results.getChildAt(it) }
            .firstOrNull { it.tag == RESULT }?.performClick()
    }

    private fun openWeb(q: String) {
        val url = "https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8")
        open(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun open(intent: Intent) {
        try {
            activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            leave()
        } catch (e: Exception) {
            activity.showToast(activity.getString(R.string.ml_search_cannot_open))
        }
    }

    // ---------------------------------------------------------------- phone

    private enum class Kind(val glyph: String) {
        APP(""), CONTACT("👤"), SETTING("⚙️"), FILE("📄"), FOLDER("📁"), ALBUM("🌄"), PHOTO("🖼️"), VIDEO("🎬"), MUSIC("🎵")
    }

    private class Row(
        val kind: Kind,
        val title: String,
        val subtitle: String? = null,
        val app: AppModel.App? = null,
        val mime: String? = null,
        /** Files, folders and albums: their path (long press menu). */
        val file: File? = null,
        /** Files: MediaStore address, to open or share. */
        val uri: Uri? = null,
        /** Albums: the photo or video shown as cover. */
        val cover: File? = null,
        val onClick: () -> Unit,
    ) {
        val isDocument
            get() = mime != null && (mime.startsWith("text/") || DOCUMENT_MIMES.any { mime.contains(it) })
    }

    /** Result tabs, each with its count; only those with results are shown. */
    private enum class Tab(val label: Int) {
        APPS(R.string.ml_search_group_apps), CONTACTS(R.string.ml_search_contacts),
        FILES(R.string.ml_search_files), FOLDERS(R.string.ml_search_tab_folders), ALBUMS(R.string.ml_search_tab_albums),
        PHOTOS(R.string.ml_search_tab_photos), VIDEOS(R.string.ml_search_tab_videos), MUSIC(R.string.ml_search_tab_music),
        DOCUMENTS(R.string.ml_search_tab_documents), SETTINGS(R.string.ml_search_settings),
    }

    /** Tab chosen by the user; kept while typing as long as it has results. */
    private var tab: Tab? = null

    private class Found(
        val apps: List<Row>,
        val contacts: List<Row>?,
        val files: List<Row>?,
        val folders: List<Row>,
        val albums: List<Row>,
        val settings: List<Row>,
    ) {
        fun rows(tab: Tab): List<Row> = when (tab) {
            Tab.APPS -> apps
            Tab.CONTACTS -> contacts.orEmpty()
            Tab.FILES -> files.orEmpty()
            Tab.FOLDERS -> folders
            Tab.ALBUMS -> albums
            Tab.PHOTOS -> files.orEmpty().filter { it.kind == Kind.PHOTO }
            Tab.VIDEOS -> files.orEmpty().filter { it.kind == Kind.VIDEO }
            Tab.MUSIC -> files.orEmpty().filter { it.kind == Kind.MUSIC }
            Tab.DOCUMENTS -> files.orEmpty().filter { it.isDocument }
            Tab.SETTINGS -> settings
        }

        val total get() = apps.size + contacts.orEmpty().size + files.orEmpty().size + folders.size + albums.size + settings.size
    }

    private suspend fun searchPhone(q: String) {
        val ctx = activity.applicationContext
        val key = normalize(q)
        val appRows = apps()
            .filter { DetoxManager.isAllowed(ctx, it.appPackage) }
            .filter { normalize(it.appLabel).contains(key) }
            .distinctBy { "${it.appPackage}|${it.activityClassName}|${it.user}" }
            .sortedWith(compareBy({ !normalize(it.appLabel).startsWith(key) }, { it.appLabel.lowercase() }))
            .take(MAX_APPS)
            .map { app -> Row(Kind.APP, app.appLabel, app = app) { launchApp(app); leave() } }
        val contactRows = if (granted(Manifest.permission.READ_CONTACTS)) withContext(Dispatchers.IO) { contacts(q) } else null
        val filesOk = filesGranted()
        val fileRows = if (filesOk) withContext(Dispatchers.IO) { files(q) } else null
        val (folderRows, albumRows) = if (filesOk) withContext(Dispatchers.IO) { folders(key) } else emptyList<Row>() to emptyList()
        val settingRows = settings().filter { normalize(it.title).contains(key) }
        render(q, Found(appRows, contactRows, fileRows, folderRows, albumRows, settingRows))
    }

    private fun render(q: String, found: Found) {
        results.removeAllViews()
        val missingContacts = found.contacts == null
        val missingFiles = found.files == null
        // Tabs with results, plus Contacts / Files when their permission is missing (to ask it)
        val visible = Tab.entries.filter {
            found.rows(it).isNotEmpty() || (it == Tab.CONTACTS && missingContacts) || (it == Tab.FILES && missingFiles)
        }
        val current = tab?.takeIf { it in visible } ?: visible.firstOrNull { found.rows(it).isNotEmpty() } ?: visible.firstOrNull()
        if (current != null) tabBar(q, found, visible, current)
        when {
            current == Tab.CONTACTS && missingContacts -> permissionRow(R.string.ml_search_allow_contacts) {
                requestPermission(Manifest.permission.READ_CONTACTS)
            }
            current == Tab.FILES && missingFiles -> permissionRow(R.string.ml_search_allow_files) { askFilesAccess() }
            current == Tab.ALBUMS -> albumGrid(found.rows(current))
            current != null -> found.rows(current).forEach { results.addView(rowView(it)) }
        }
        if (current == null || found.total == 0) results.addView(TextView(activity, null, 0, R.style.TextSmall).apply {
            setText(R.string.ml_search_no_results)
            alpha = 0.6f
            setPadding(0, 16.dpToPx(), 0, 4.dpToPx())
        })
        footer(activity.getString(R.string.ml_search_on_google, q)) { openWeb(q) }
    }

    /** Chips "Apps 3 · Files 12 …". */
    private fun tabBar(q: String, found: Found, visible: List<Tab>, current: Tab) {
        val chips = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        visible.forEach { t ->
            val count = found.rows(t).size
            chips.addView(TextView(activity, null, 0, R.style.TextSmall).apply {
                text = activity.getString(R.string.ml_search_tab_format, activity.getString(t.label), count)
                setTypeface(typeface, if (t == current) Typeface.BOLD else Typeface.NORMAL)
                alpha = if (t == current) 1f else 0.6f
                if (t == current) setBackgroundResource(R.drawable.rounded_rect_shade_color)
                setPadding(12.dpToPx(), 6.dpToPx(), 12.dpToPx(), 6.dpToPx())
                isClickable = true
                setOnClickListener {
                    tab = t
                    render(q, found)
                    resultsScroll.scrollTo(0, 0)
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = 4.dpToPx()
            })
        }
        results.addView(android.widget.HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(chips)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 4.dpToPx()
        })
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED

    private fun filesGranted() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else granted(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun askFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            waitingPermission = true
            keepScreen(true)
            try {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${activity.packageName}"))
                )
            } catch (e: Exception) {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else requestPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun contacts(q: String): List<Row> {
        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(q))
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val rows = mutableListOf<Row>()
        val seen = mutableSetOf<Long>()
        try {
            activity.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                while (c.moveToNext() && rows.size < MAX_CONTACTS) {
                    val id = c.getLong(0)
                    if (!seen.add(id)) continue
                    val lookup = c.getString(1)
                    val name = c.getString(2) ?: continue
                    val number = c.getString(3)
                    val contactUri = ContactsContract.Contacts.getLookupUri(id, lookup)
                    rows += Row(Kind.CONTACT, name, number) { open(Intent(Intent.ACTION_VIEW, contactUri)) }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return rows
    }

    private val storageRoot: File get() = Environment.getExternalStorageDirectory()

    /** Path shown under a result, relative to the storage root. */
    private fun relative(dir: File) = dir.absolutePath.removePrefix(storageRoot.absolutePath).trimStart('/')

    private fun files(q: String): List<Row> {
        val base = MediaStore.Files.getContentUri("external")
        @Suppress("DEPRECATION")
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATA,
        )
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND ${MediaStore.MediaColumns.MIME_TYPE} IS NOT NULL"
        val rows = mutableListOf<Row>()
        try {
            activity.contentResolver.query(base, projection, selection, arrayOf("%$q%"), "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { c ->
                while (c.moveToNext() && rows.size < MAX_FILES) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    val file = c.getString(3)?.let { File(it) }
                    val uri = ContentUris.withAppendedId(base, id)
                    val kind = when {
                        mime?.startsWith("image/") == true -> Kind.PHOTO
                        mime?.startsWith("video/") == true -> Kind.VIDEO
                        mime?.startsWith("audio/") == true -> Kind.MUSIC
                        else -> Kind.FILE
                    }
                    rows += Row(kind, name, mime = mime, file = file, uri = uri) {
                        open(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return rows
    }

    /** Matching folders, split into plain folders and albums (only photos and videos inside). */
    private fun folders(key: String): Pair<List<Row>, List<Row>> {
        val folders = mutableListOf<Row>()
        val albums = mutableListOf<Row>()
        localFolders().asSequence()
            .filter { normalize(it.name).contains(key) }
            .take(MAX_FOLDERS)
            .forEach { dir ->
                val media = albumMedia(dir)
                if (media != null) albums += Row(Kind.ALBUM, dir.name, file = dir, cover = media.first()) { openAlbum(dir) }
                else folders += Row(Kind.FOLDER, dir.name, file = dir) { openLocalFolder(dir) }
            }
        return folders to albums
    }

    /** Photos and videos of [dir], newest first, when it holds nothing else; null otherwise. */
    private fun albumMedia(dir: File): List<File>? {
        val files = dir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") } ?: return null
        if (files.isEmpty() || !files.all { isMedia(mimeOf(it.name)) }) return null
        return files.sortedByDescending { it.lastModified() }
    }

    private fun isMedia(mime: String?) = mime != null && (mime.startsWith("image/") || mime.startsWith("video/"))

    private fun mimeOf(name: String) =
        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())

    /**
     * Fossify Gallery shortcut for [dir], if the user created one (Fossify: long press on the
     * album > "Create shortcut"; its id is the folder path). It opens the album's grid, which
     * Fossify doesn't let other apps open directly.
     */
    private fun albumShortcut(dir: File): android.content.pm.ShortcutInfo? {
        val launcherApps = activity.getSystemService(android.content.pm.LauncherApps::class.java) ?: return null
        if (!launcherApps.hasShortcutHostPermission()) return null
        val path = dir.absolutePath.trimEnd('/')
        return GALLERIES.firstNotNullOfOrNull { gallery ->
            try {
                val query = android.content.pm.LauncherApps.ShortcutQuery()
                    .setPackage(gallery)
                    .setQueryFlags(android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
                launcherApps.getShortcuts(query, android.os.Process.myUserHandle())
                    ?.firstOrNull { it.id.trimEnd('/') == path }
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Opens an album: Fossify's own grid through its shortcut when there is one, else our grid. */
    private fun openAlbum(dir: File) {
        albumShortcut(dir)?.let { shortcut ->
            try {
                activity.getSystemService(android.content.pm.LauncherApps::class.java)
                    .startShortcut(shortcut, null, null)
                leave()
                return
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        // Otherwise our own grid of the album
        AlbumActivity.open(activity, dir)
        leave()
    }

    /** All folders on the phone storage (cached for a while; hidden folders and Android/ skipped). */
    private fun localFolders(): List<File> {
        cachedFolders?.let { (time, list) -> if (System.currentTimeMillis() - time < FOLDER_CACHE_MS) return list }
        val list = mutableListOf<File>()
        fun walk(dir: File, depth: Int) {
            if (depth > MAX_DEPTH || list.size >= MAX_SCANNED) return
            dir.listFiles()?.forEach { child ->
                if (!child.isDirectory || child.name.startsWith(".")) return@forEach
                if (depth == 0 && child.name == "Android") return@forEach
                list += child
                walk(child, depth + 1)
            }
        }
        walk(storageRoot, 0)
        cachedFolders = System.currentTimeMillis() to list
        return list
    }

    /**
     * Opens a phone folder, preferably in Solid Explorer. File managers don't share a standard way
     * to receive a folder, so the usual variants are tried in turn.
     */
    private fun openLocalFolder(dir: File) {
        if (prefs.phoneFoldersInExplorer) {
            FileExplorerActivity.openLocal(activity, dir)
            leave()
            return
        }
        val fileUri = Uri.fromFile(dir)
        val docUri = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_DOCS, "primary:" + relative(dir))
        val dirMime = DocumentsContract.Document.MIME_TYPE_DIR
        val attempts = listOf(
            Intent(Intent.ACTION_VIEW).setDataAndType(fileUri, "resource/folder").setPackage(SOLID_EXPLORER),
            Intent(Intent.ACTION_VIEW).setDataAndType(fileUri, dirMime).setPackage(SOLID_EXPLORER),
            Intent(Intent.ACTION_VIEW).setDataAndType(docUri, dirMime).setPackage(SOLID_EXPLORER),
            Intent(Intent.ACTION_VIEW).setDataAndType(fileUri, "resource/folder"),
            Intent(Intent.ACTION_VIEW).setDataAndType(docUri, dirMime),
        )
        // file:// URIs to other apps are refused by default; folders have no content:// equivalent
        val policy = StrictMode.getVmPolicy()
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
        try {
            for (intent in attempts) {
                try {
                    activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    leave()
                    return
                } catch (e: Exception) {
                    // try the next variant
                }
            }
        } finally {
            StrictMode.setVmPolicy(policy)
        }
        activity.showToast(activity.getString(R.string.ml_search_cannot_open))
    }

    private fun settings(): List<Row> {
        val entries = mutableListOf(
            R.string.ml_set_wifi to Settings.ACTION_WIFI_SETTINGS,
            R.string.ml_set_bluetooth to Settings.ACTION_BLUETOOTH_SETTINGS,
            R.string.ml_set_battery to Intent.ACTION_POWER_USAGE_SUMMARY,
            R.string.ml_set_display to Settings.ACTION_DISPLAY_SETTINGS,
            R.string.ml_set_sound to Settings.ACTION_SOUND_SETTINGS,
            R.string.ml_set_notifications to "android.settings.NOTIFICATION_SETTINGS",
            R.string.ml_set_apps to Settings.ACTION_APPLICATION_SETTINGS,
            R.string.ml_set_storage to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
            R.string.ml_set_location to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
            R.string.ml_set_security to Settings.ACTION_SECURITY_SETTINGS,
            R.string.ml_set_accounts to Settings.ACTION_SYNC_SETTINGS,
            R.string.ml_set_date to Settings.ACTION_DATE_SETTINGS,
            R.string.ml_set_language to Settings.ACTION_LOCALE_SETTINGS,
            R.string.ml_set_accessibility to Settings.ACTION_ACCESSIBILITY_SETTINGS,
            R.string.ml_set_network to Settings.ACTION_WIRELESS_SETTINGS,
            R.string.ml_set_hotspot to Settings.ACTION_WIRELESS_SETTINGS,
            R.string.ml_set_mobile_data to Settings.ACTION_DATA_ROAMING_SETTINGS,
            R.string.ml_set_airplane to Settings.ACTION_AIRPLANE_MODE_SETTINGS,
            R.string.ml_set_nfc to Settings.ACTION_NFC_SETTINGS,
            R.string.ml_set_developer to Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
            R.string.ml_set_about to Settings.ACTION_DEVICE_INFO_SETTINGS,
            R.string.ml_set_all to Settings.ACTION_SETTINGS,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            entries += R.string.ml_set_data_usage to Settings.ACTION_DATA_USAGE_SETTINGS
        return entries.map { (label, action) ->
            Row(Kind.SETTING, activity.getString(label)) { open(Intent(action)) }
        }
    }

    // ---------------------------------------------------------------- views

    private fun group(title: Int, rows: List<Row>) {
        if (rows.isEmpty()) return
        results.addView(TextView(activity, null, 0, R.style.TextSmall).apply {
            setText(title)
            setTypeface(typeface, Typeface.BOLD)
            alpha = 0.6f
            setPadding(0, 16.dpToPx(), 0, 4.dpToPx())
        })
        rows.forEach { results.addView(rowView(it)) }
    }

    private fun rowView(row: Row): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 6.dpToPx(), 0, 6.dpToPx())
        isClickable = true
        isFocusable = true
        tag = RESULT
        setOnClickListener { row.onClick() }
        if (row.file != null) setOnLongClickListener {
            showItemMenu(row)
            true
        }
        val thumb = row.file?.takeIf { row.kind == Kind.PHOTO || row.kind == Kind.VIDEO }
        when {
            thumb != null -> addView(roundedImage().also {
                MediaThumbnails.load(scope, it, thumb, 44.dpToPx())
            }, LinearLayout.LayoutParams(44.dpToPx(), 44.dpToPx()).apply { marginEnd = 12.dpToPx() })

            row.kind != Kind.APP -> addView(TextView(activity, null, 0, R.style.TextLarge).apply {
                text = row.kind.glyph
                textSize = 18f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(44.dpToPx(), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = 12.dpToPx() })
        }
        val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(activity, null, 0, R.style.TextLarge).apply {
            text = row.title
            textSize = 18f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
        }
        texts.addView(title)
        row.subtitle?.takeIf { it.isNotBlank() }?.let { sub ->
            texts.addView(TextView(activity, null, 0, R.style.TextSmall).apply {
                text = sub
                alpha = 0.6f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.app?.let { app -> loadIcon(title, app) }
    }

    private fun roundedImage() = android.widget.ImageView(activity).apply {
        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(0x22888888)
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, 10.dpToPx().toFloat())
        }
    }

    /** Albums tab: covers in two columns, name below. */
    private fun albumGrid(rows: List<Row>) {
        val width = (resultsScroll.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels) -
                results.paddingLeft - results.paddingRight
        val gap = 12.dpToPx()
        val side = (width - gap) / 2
        rows.chunked(2).forEach { pair ->
            val line = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 6.dpToPx(), 0, 6.dpToPx())
            }
            pair.forEachIndexed { index, row ->
                line.addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    isClickable = true
                    tag = RESULT
                    setOnClickListener { row.onClick() }
                    setOnLongClickListener {
                        showItemMenu(row)
                        true
                    }
                    addView(roundedImage().also { image ->
                        row.cover?.let { MediaThumbnails.load(scope, image, it, side) }
                    }, LinearLayout.LayoutParams(side, side))
                    addView(TextView(activity, null, 0, R.style.TextSmall).apply {
                        text = row.title
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(2.dpToPx(), 6.dpToPx(), 2.dpToPx(), 0)
                    })
                }, LinearLayout.LayoutParams(side, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (index == 0) marginEnd = gap
                })
            }
            results.addView(line)
        }
    }

    /** Long press on a file, folder or album: its path, then the actions. */
    private fun showItemMenu(row: Row) {
        val file = row.file ?: return
        val entries = mutableListOf(MenuEntry(activity.getString(R.string.ml_search_open)) { row.onClick() })
        if (!file.isDirectory) {
            val uri = row.uri ?: androidx.core.content.FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
            val mime = row.mime ?: mimeOf(file.name) ?: "*/*"
            entries += MenuEntry(activity.getString(R.string.ml_explorer_open_with)) {
                val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                open(Intent.createChooser(intent, row.title))
            }
            entries += MenuEntry(activity.getString(R.string.ml_explorer_share)) {
                val intent = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                open(Intent.createChooser(intent, row.title))
            }
            file.parentFile?.let { dir -> entries += MenuEntry(activity.getString(R.string.ml_search_open_folder)) { openLocalFolder(dir) } }
        } else if (row.kind == Kind.ALBUM) {
            entries += MenuEntry(activity.getString(R.string.ml_search_open_folder)) { openLocalFolder(file) }
        }
        entries += MenuEntry(activity.getString(R.string.ml_search_copy_path)) {
            val clipboard = activity.getSystemService(android.content.ClipboardManager::class.java)
            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText(row.title, file.absolutePath))
            activity.showToast(activity.getString(R.string.ml_search_path_copied))
        }
        activity.showMenuDialog(row.title, entries, header = { dialog ->
            TextView(dialog.context, null, 0, R.style.TextSmall).apply {
                text = "/" + relative(file)
                alpha = 0.6f
                setTextColor(dialog.context.getColorFromAttr(R.attr.primaryColor))
                setPadding(0, 8.dpToPx(), 0, 4.dpToPx())
            }
        })
    }

    private fun loadIcon(view: TextView, app: AppModel.App) {
        val size = (view.textSize * 1.3f).toInt()
        view.compoundDrawablePadding = 14.dpToPx()
        val iconPack = prefs.iconPack
        val ctx = activity.applicationContext
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                IconPackManager.getIcon(ctx, app.appPackage, app.activityClassName, app.user, iconPack, size)
            } ?: return@launch
            view.setCompoundDrawablesRelative(IconPackManager.toDrawable(ctx, bitmap, size), null, null, null)
        }
    }

    private fun permissionRow(text: Int, onClick: () -> Unit) {
        results.addView(TextView(activity, null, 0, R.style.TextSmall).apply {
            setText(text)
            setTextColor(HomeSectionsController.NOTIFICATION_COLOR)
            setPadding(0, 12.dpToPx(), 0, 12.dpToPx())
            isClickable = true
            setOnClickListener { onClick() }
        }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun footer(text: String, onClick: () -> Unit) {
        results.addView(TextView(activity, null, 0, R.style.TextSmall).apply {
            this.text = text
            alpha = 0.8f
            setPadding(0, 20.dpToPx(), 0, 8.dpToPx())
            isClickable = true
            setOnClickListener { onClick() }
        })
    }

    private fun normalize(s: CharSequence) = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "").lowercase()

    companion object {
        private const val RESULT = "result"
        private const val MAX_APPS = 30
        private const val MAX_CONTACTS = 30
        private const val MAX_FILES = 300
        private const val MAX_FOLDERS = 80
        private val GALLERIES = listOf("org.fossify.gallery", "com.simplemobiletools.gallery.pro")
        private val DOCUMENT_MIMES = listOf(
            "pdf", "msword", "wordprocessing", "excel", "spreadsheet", "powerpoint", "presentation", "opendocument", "rtf",
        )
        private const val MAX_DEPTH = 7
        private const val MAX_SCANNED = 30_000
        private const val FOLDER_CACHE_MS = 10 * 60_000L
        private const val SOLID_EXPLORER = "pl.solidexplorer2"
        private const val EXTERNAL_STORAGE_DOCS = "com.android.externalstorage.documents"

        @Volatile
        private var cachedFolders: Pair<Long, List<File>>? = null
    }
}
