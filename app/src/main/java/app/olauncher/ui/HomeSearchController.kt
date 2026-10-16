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
import android.provider.ContactsContract
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
import app.olauncher.helper.showPopupMenu
import app.olauncher.helper.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.text.Normalizer

/**
 * Search bar at the bottom of home. The selector on the right picks where to search:
 * - Google: opens the results in the browser;
 * - Phone: apps, contacts, files and Android settings, listed above the bar while typing;
 * - OneDrive: the user's OneDrive (see [OneDriveSearch]).
 */
class HomeSearchController(
    private val activity: Activity,
    private val input: EditText,
    private val clear: TextView,
    private val modeButton: TextView,
    private val resultsScroll: ScrollView,
    private val results: LinearLayout,
    /** Views replaced by the results while searching (home list, bottom area). */
    private val homeViews: List<View>,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val apps: () -> List<AppModel.App>,
    private val launchApp: (AppModel.App) -> Unit,
    /** Asks a runtime permission; the search refreshes when the user answers. */
    private val requestPermission: (String) -> Unit,
    /** Called before opening another screen that should come back here (permission settings). */
    private val keepScreen: (Boolean) -> Unit,
) {
    enum class Mode { WEB, PHONE, ONEDRIVE }

    private var mode = Mode.entries.getOrElse(prefs.searchMode) { Mode.WEB }
    private var job: Job? = null

    /** True while waiting for a permission screen: keep the query when home stops. */
    private var waitingPermission = false

    val isActive get() = input.text.isNotEmpty() || resultsScroll.isVisible

    init {
        modeButton.setOnClickListener { showModeMenu() }
        clear.setOnClickListener { close() }
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
    }

    private fun query() = input.text.toString().trim()

    // ---------------------------------------------------------------- mode

    private fun label(m: Mode) = activity.getString(
        when (m) {
            Mode.WEB -> R.string.ml_search_web
            Mode.PHONE -> R.string.ml_search_phone
            Mode.ONEDRIVE -> R.string.ml_search_onedrive
        }
    )

    private fun showModeMenu() {
        modeButton.showPopupMenu(configure = { menu ->
            Mode.entries.forEachIndexed { i, m -> menu.add(0, i, i, label(m)) }
        }) { item ->
            mode = Mode.entries[item.itemId]
            prefs.searchMode = mode.ordinal
            updateMode()
            onQueryChanged()
        }
    }

    private fun updateMode() {
        modeButton.text = activity.getString(R.string.ml_search_mode_format, label(mode))
        input.setHint(
            when (mode) {
                Mode.WEB -> R.string.ml_search_hint_web
                Mode.PHONE -> R.string.ml_search_hint_phone
                Mode.ONEDRIVE -> R.string.ml_search_hint_onedrive
            }
        )
    }

    // ---------------------------------------------------------------- state

    private fun showResults(show: Boolean) {
        resultsScroll.isVisible = show
        homeViews.forEach { it.isVisible = !show }
    }

    /** Clears the query and brings home back. */
    fun close() {
        job?.cancel()
        input.setText("")
        input.clearFocus()
        input.hideKeyboard()
        results.removeAllViews()
        showResults(false)
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
        clear.isVisible = input.text.isNotEmpty()
        job?.cancel()
        if (q.isEmpty() || mode == Mode.WEB) {
            results.removeAllViews()
            showResults(false)
            return
        }
        job = scope.launch {
            delay(150)
            when (mode) {
                Mode.PHONE -> searchPhone(q)
                Mode.ONEDRIVE -> searchOneDrive(q)
                Mode.WEB -> Unit
            }
        }
    }

    /** Search key: Google opens the browser, the other modes open the first result. */
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

    private class Row(val title: String, val subtitle: String? = null, val app: AppModel.App? = null, val onClick: () -> Unit)

    private suspend fun searchPhone(q: String) {
        val ctx = activity.applicationContext
        val key = normalize(q)
        val appRows = apps()
            .filter { DetoxManager.isAllowed(ctx, it.appPackage) }
            .filter { normalize(it.appLabel).contains(key) }
            .distinctBy { "${it.appPackage}|${it.activityClassName}|${it.user}" }
            .sortedWith(compareBy({ !normalize(it.appLabel).startsWith(key) }, { it.appLabel.lowercase() }))
            .take(MAX_APPS)
            .map { app -> Row(app.appLabel, app = app) { launchApp(app); close() } }
        val contactsGranted = granted(Manifest.permission.READ_CONTACTS)
        val contactRows = if (contactsGranted) withContext(Dispatchers.IO) { contacts(q) } else emptyList()
        val filesGranted = filesGranted()
        val fileRows = if (filesGranted) withContext(Dispatchers.IO) { files(q) } else emptyList()
        val settingRows = settings().filter { normalize(it.title).contains(key) }

        results.removeAllViews()
        group(R.string.ml_search_group_apps, appRows)
        group(R.string.ml_search_contacts, contactRows)
        if (!contactsGranted) permissionRow(R.string.ml_search_allow_contacts) {
            requestPermission(Manifest.permission.READ_CONTACTS)
        }
        group(R.string.ml_search_files, fileRows)
        if (!filesGranted) permissionRow(R.string.ml_search_allow_files) { askFilesAccess() }
        group(R.string.ml_search_settings, settingRows)
        footer(activity.getString(R.string.ml_search_on_google, q)) { openWeb(q) }
        showResults(true)
        resultsScroll.post { resultsScroll.fullScroll(View.FOCUS_DOWN) }
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
                    rows += Row(name, number) { open(Intent(Intent.ACTION_VIEW, contactUri)) }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return rows
    }

    private fun files(q: String): List<Row> {
        val base = MediaStore.Files.getContentUri("external")
        val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.MediaColumns.RELATIVE_PATH
        else @Suppress("DEPRECATION") MediaStore.MediaColumns.DATA
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            pathColumn,
        )
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND ${MediaStore.MediaColumns.MIME_TYPE} IS NOT NULL"
        val args = arrayOf("%$q%")
        val rows = mutableListOf<Row>()
        try {
            activity.contentResolver.query(base, projection, selection, args, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { c ->
                while (c.moveToNext() && rows.size < MAX_FILES) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    val path = c.getString(3)?.let {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) it.trimEnd('/') else it.substringBeforeLast('/').substringAfter("/0/")
                    }
                    val uri = ContentUris.withAppendedId(base, id)
                    rows += Row(name, path) {
                        open(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return rows
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
            Row(activity.getString(label)) { open(Intent(action)) }
        }
    }

    // ---------------------------------------------------------------- OneDrive

    private suspend fun searchOneDrive(q: String) {
        results.removeAllViews()
        val search = OneDriveSearch(activity)
        if (!search.isConnected()) {
            permissionRow(R.string.ml_onedrive_connect) {
                connectOneDrive(search)
            }
            showResults(true)
            return
        }
        val found = withContext(Dispatchers.IO) { search.search(q) }
        results.removeAllViews()
        if (found == null) {
            permissionRow(R.string.ml_onedrive_error) {
                connectOneDrive(search)
            }
        } else {
            group(R.string.ml_search_onedrive, found.map { item ->
                Row(item.name, item.path) { open(Intent(Intent.ACTION_VIEW, Uri.parse(item.webUrl))) }
            })
            if (found.isEmpty()) emptyRow()
        }
        showResults(true)
        resultsScroll.post { resultsScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun connectOneDrive(search: OneDriveSearch) {
        keepScreen(true)
        if (search.connect()) waitingPermission = true else keepScreen(false)
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
        orientation = LinearLayout.VERTICAL
        setPadding(0, 8.dpToPx(), 0, 8.dpToPx())
        isClickable = true
        isFocusable = true
        tag = RESULT
        setOnClickListener { row.onClick() }
        val title = TextView(activity, null, 0, R.style.TextLarge).apply {
            text = row.title
            textSize = 18f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
        }
        addView(title)
        row.subtitle?.takeIf { it.isNotBlank() }?.let { sub ->
            addView(TextView(activity, null, 0, R.style.TextSmall).apply {
                text = sub
                alpha = 0.6f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.START
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

    private fun emptyRow() {
        results.addView(TextView(activity, null, 0, R.style.TextSmall).apply {
            setText(R.string.ml_search_no_results)
            alpha = 0.6f
            setPadding(0, 12.dpToPx(), 0, 12.dpToPx())
        })
    }

    private fun normalize(s: CharSequence) = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "").lowercase()

    companion object {
        private const val RESULT = "result"
        private const val MAX_APPS = 8
        private const val MAX_CONTACTS = 6
        private const val MAX_FILES = 12
    }
}
