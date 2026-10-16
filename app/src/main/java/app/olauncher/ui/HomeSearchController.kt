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

    fun onHomeStopped() {
        if (!waitingPermission) close()
    }

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
            close()
        } catch (e: Exception) {
            activity.showToast(activity.getString(R.string.ml_search_cannot_open))
        }
    }

    // ---------------------------------------------------------------- phone

    private enum class Kind(val glyph: String) {
        APP(""), CONTACT("👤"), SETTING("⚙️"), FILE("📄"), FOLDER("📁")
    }

    private class Row(
        val kind: Kind,
        val title: String,
        val subtitle: String? = null,
        val app: AppModel.App? = null,
        /** For files: opens the folder holding them. */
        val openFolder: (() -> Unit)? = null,
        val onClick: () -> Unit,
    )

    private class Found(
        val apps: List<Row>,
        val contacts: List<Row>?,
        val files: List<Row>?,
        val settings: List<Row>,
    )

    private suspend fun searchPhone(q: String) {
        val ctx = activity.applicationContext
        val key = normalize(q)
        val appRows = apps()
            .filter { DetoxManager.isAllowed(ctx, it.appPackage) }
            .filter { normalize(it.appLabel).contains(key) }
            .distinctBy { "${it.appPackage}|${it.activityClassName}|${it.user}" }
            .sortedWith(compareBy({ !normalize(it.appLabel).startsWith(key) }, { it.appLabel.lowercase() }))
            .take(MAX_APPS)
            .map { app -> Row(Kind.APP, app.appLabel, app = app) { launchApp(app); close() } }
        val contactRows = if (granted(Manifest.permission.READ_CONTACTS)) withContext(Dispatchers.IO) { contacts(q) } else null
        val fileRows = if (filesGranted()) withContext(Dispatchers.IO) { folders(key) + files(q) } else null
        val settingRows = settings().filter { normalize(it.title).contains(key) }
        val found = Found(appRows, contactRows, fileRows, settingRows)
        render(q, found)
    }

    private fun render(q: String, found: Found) {
        results.removeAllViews()
        group(R.string.ml_search_group_apps, found.apps)
        if (found.contacts == null) permissionRow(R.string.ml_search_allow_contacts) {
            requestPermission(Manifest.permission.READ_CONTACTS)
        } else group(R.string.ml_search_contacts, found.contacts)
        group(R.string.ml_search_files, found.files.orEmpty())
        if (found.files == null) permissionRow(R.string.ml_search_allow_files) { askFilesAccess() }
        group(R.string.ml_search_settings, found.settings)
        footer(activity.getString(R.string.ml_search_on_google, q)) { openWeb(q) }
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
                    val dir = c.getString(3)?.let { File(it).parentFile }
                    val uri = ContentUris.withAppendedId(base, id)
                    rows += Row(
                        Kind.FILE, name, dir?.let { relative(it) },
                        openFolder = dir?.let { d -> { openLocalFolder(d) } },
                    ) {
                        open(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return rows
    }

    private fun folders(key: String): List<Row> =
        localFolders().asSequence()
            .filter { normalize(it.name).contains(key) }
            .take(MAX_FOLDERS)
            .map { dir ->
                Row(Kind.FOLDER, dir.name, activity.getString(R.string.ml_search_folder, relative(dir.parentFile ?: dir))) {
                    openLocalFolder(dir)
                }
            }.toList()

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
            close()
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
                    close()
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
        if (row.kind != Kind.APP) addView(TextView(activity, null, 0, R.style.TextLarge).apply {
            text = row.kind.glyph
            textSize = 18f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(36.dpToPx(), ViewGroup.LayoutParams.WRAP_CONTENT))
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
                ellipsize = TextUtils.TruncateAt.START
            })
        }
        addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.openFolder?.let { openFolder ->
            addView(TextView(activity, null, 0, R.style.TextLarge).apply {
                text = "📂"
                textSize = 20f
                gravity = Gravity.CENTER
                contentDescription = activity.getString(R.string.ml_search_open_folder)
                setPadding(12.dpToPx(), 8.dpToPx(), 4.dpToPx(), 8.dpToPx())
                isClickable = true
                setOnClickListener { openFolder() }
            })
        }
        row.app?.let { app -> loadIcon(title, app) }
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
        private const val MAX_APPS = 8
        private const val MAX_CONTACTS = 6
        private const val MAX_FILES = 12
        private const val MAX_FOLDERS = 6
        private const val MAX_DEPTH = 7
        private const val MAX_SCANNED = 30_000
        private const val FOLDER_CACHE_MS = 10 * 60_000L
        private const val SOLID_EXPLORER = "pl.solidexplorer2"
        private const val EXTERNAL_STORAGE_DOCS = "com.android.externalstorage.documents"

        @Volatile
        private var cachedFolders: Pair<Long, List<File>>? = null
    }
}
