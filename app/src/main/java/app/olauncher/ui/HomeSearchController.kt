package app.olauncher.ui

import android.Manifest
import app.olauncher.helper.CalendarEvents
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
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
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
import app.olauncher.data.BookmarkStore
import app.olauncher.helper.DetoxManager
import app.olauncher.helper.showKeyboard
import app.olauncher.ui.bookmarks.BookmarksUi
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
 * Search bar at the bottom of home. Tapping it, or swiping up on home, opens the search page over
 * the blurred wallpaper: bar on top, with the bookmarks and the app grid next to it, results below.
 * While typing: apps, contacts, files and folders on the phone, Android settings and the matching
 * bookmarks, in tabs. The search key and the globe in the bar search the web.
 * Files can be opened, or their folder (Solid Explorer when installed).
 */
class HomeSearchController(
    private val activity: Activity,
    private val column: LinearLayout,
    private val bar: View,
    private val input: EditText,
    private val clear: TextView,
    /** The globe in the bar: the text on the web, in one tap. */
    private val webButton: View,
    /** Next to the bar on the search page. */
    private val bookmarksButton: View,
    private val appsButton: View,
    private val resultsScroll: ScrollView,
    private val results: LinearLayout,
    /** Views replaced by the search page (home list, bottom area). */
    private val homeViews: List<View>,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val apps: () -> List<AppModel.App>,
    private val launchApp: (AppModel.App) -> Unit,
    /** Long press on an app found: its menu, as in the app list; [onChanged] after a change to it. */
    private val appMenu: (app: AppModel.App, onChanged: () -> Unit) -> Unit,
    /** Asks a runtime permission; the search refreshes when the user answers. */
    private val requestPermission: (String) -> Unit,
    /** Called before opening another screen that should come back here (permission settings, sign-in). */
    private val keepScreen: (Boolean) -> Unit,
    /** The app grid. */
    private val openAppList: () -> Unit,
) {
    private var job: Job? = null

    /** True while waiting for a permission or sign-in screen: keep the page when home stops. */
    private var waitingPermission = false

    private var pageOpen = false
    private val barMargins = (bar.layoutParams as ViewGroup.MarginLayoutParams).let { it.topMargin to it.bottomMargin }

    val isActive get() = pageOpen

    init {
        webButton.setOnClickListener { openWeb(query()) }
        bookmarksButton.setOnClickListener {
            leave()
            BookmarksUi.showPanel(activity, 16.dpToPx())
        }
        // A long press adds the copied address
        bookmarksButton.setOnLongClickListener {
            BookmarksUi.showForm(activity, BookmarksUi.fromClipboard(activity)) {}
            true
        }
        appsButton.setOnClickListener {
            close()
            openAppList()
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
        // OneDrive search was removed: forget its old sign-in
        activity.deleteSharedPreferences("app.minimalauncher.onedrive")
    }

    private fun query() = input.text.toString().trim()

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
        bookmarksButton.isVisible = true
        appsButton.isVisible = true
        setBlur(true)
        onQueryChanged()
    }

    /** Swipe up on home: the search page with the keyboard open. */
    fun openWithKeyboard() {
        openPage()
        input.requestFocus()
        input.showKeyboard()
    }

    /** The wallpaper behind the search page: blurred (Android 12+, where the phone allows it) and dimmed. */
    private fun setBlur(on: Boolean) {
        val window = activity.window ?: return
        val dim = androidx.core.graphics.ColorUtils.setAlphaComponent(UiKit.card(activity), 0x99)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(if (on) dim else android.graphics.Color.TRANSPARENT))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window.setBackgroundBlurRadius(if (on) 48.dpToPx() else 0)
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
        bookmarksButton.isVisible = false
        appsButton.isVisible = false
        setBlur(false)
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
        if (q.isEmpty()) {
            results.removeAllViews()
            if (pageOpen) results.addView(UiKit.emptyState(activity, R.drawable.ic_m_search, activity.getString(R.string.ml_search_page_hint)))
            return
        }
        job = scope.launch {
            delay(150)
            searchPhone(q)
        }
    }

    /** Search key: the web search, always (a result is a tap away). */
    private fun submit() {
        val q = query()
        if (q.isEmpty()) return
        openWeb(q)
    }

    /** The web search of [q] (Google's page when empty), in Samsung Internet when installed. */
    private fun openWeb(q: String) {
        val url = if (q.isEmpty()) "https://www.google.com" else "https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        // Samsung Internet when installed, otherwise the default browser
        try {
            activity.startActivity(Intent(intent).setPackage(SAMSUNG_INTERNET).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            leave()
        } catch (e: Exception) {
            open(intent)
        }
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

    /** What a result is, with the tile shown before it (apps show their own icon). */
    private enum class Kind(val tile: UiKit.FileKind?) {
        APP(null), CONTACT(UiKit.FileKind.CONTACT), SETTING(UiKit.FileKind.SETTINGS), FILE(UiKit.FileKind.DOC),
        FOLDER(UiKit.FileKind.FOLDER), ALBUM(UiKit.FileKind.WALLPAPER), PHOTO(UiKit.FileKind.IMAGE),
        VIDEO(UiKit.FileKind.VIDEO), MUSIC(UiKit.FileKind.AUDIO), BOOKMARK(null), WEB(null), EVENT(null)
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
        /** Contacts: their id and how to reach them (buttons on the row, long press menu). */
        val contactId: Long? = null,
        val ways: ContactWays? = null,
        /** Events: their calendar's color, on the tile. */
        val color: Int = 0,
        val onClick: () -> Unit,
    ) {
        val isDocument
            get() = mime != null && (mime.startsWith("text/") || DOCUMENT_MIMES.any { mime.contains(it) })
    }

    /** Result tabs, each with its count; only those with results are shown. */
    private enum class Tab(val label: Int, val icon: Int) {
        APPS(R.string.ml_search_group_apps, R.drawable.ic_m_grid), CONTACTS(R.string.ml_search_contacts, R.drawable.ic_m_contact),
        EVENTS(R.string.ml_search_tab_events, R.drawable.ic_m_event),
        PHOTOS(R.string.ml_search_tab_photos, R.drawable.ic_m_image), VIDEOS(R.string.ml_search_tab_videos, R.drawable.ic_m_video),
        MUSIC(R.string.ml_search_tab_music, R.drawable.ic_m_audio), DOCUMENTS(R.string.ml_search_tab_documents, R.drawable.ic_m_doc),
        /** The files that are none of the above (or the files access to ask). */
        FILES(R.string.ml_search_tab_other_files, R.drawable.ic_m_file),
        FOLDERS(R.string.ml_search_tab_folders, R.drawable.ic_m_folder), ALBUMS(R.string.ml_search_tab_albums, R.drawable.ic_m_image),
        SETTINGS(R.string.ml_search_settings, R.drawable.ic_m_settings),
        /** The matching bookmarks, only when there are some (the web search is the search key). */
        WEB(R.string.ml_search_tab_bookmarks, R.drawable.ic_m_star),
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
        val bookmarks: List<Row>,
        /** Null while the calendar can't be read (the tab asks for it). */
        val events: List<Row>?,
    ) {
        fun rows(tab: Tab): List<Row> = when (tab) {
            Tab.APPS -> apps
            Tab.CONTACTS -> contacts.orEmpty()
            Tab.EVENTS -> events.orEmpty()
            Tab.FILES -> files.orEmpty().filter { it.kind == Kind.FILE && !it.isDocument }
            Tab.FOLDERS -> folders
            Tab.ALBUMS -> albums
            Tab.PHOTOS -> files.orEmpty().filter { it.kind == Kind.PHOTO }
            Tab.VIDEOS -> files.orEmpty().filter { it.kind == Kind.VIDEO }
            Tab.MUSIC -> files.orEmpty().filter { it.kind == Kind.MUSIC }
            Tab.DOCUMENTS -> files.orEmpty().filter { it.isDocument }
            Tab.SETTINGS -> settings
            Tab.WEB -> bookmarks
        }

        val total get() = apps.size + contacts.orEmpty().size + files.orEmpty().size + folders.size + albums.size + settings.size + bookmarks.size + events.orEmpty().size
    }

    /** Calendar events whose title or place holds [q]: when (and where) in the subtitle; a tap opens them. */
    private fun events(q: String): List<Row> = CalendarEvents.search(activity.applicationContext, q, MAX_EVENTS).map { found ->
        val e = found.event
        val date = java.text.SimpleDateFormat(
            if (sameYear(e.begin)) "EEE d MMM" else "EEE d MMM yyyy", java.util.Locale.getDefault()
        ).format(java.util.Date(e.begin))
        val time = if (e.allDay) activity.getString(R.string.ml_search_event_all_day)
        else android.text.format.DateFormat.getTimeFormat(activity).format(java.util.Date(e.begin))
        val sub = listOf("$date · $time", found.location).filter { it.isNotBlank() }.joinToString(" · ")
        Row(Kind.EVENT, e.title.ifBlank { "-" }, sub, color = e.color) {
            CalendarEvents.openEvent(activity, e)
            leave()
        }
    }

    private fun sameYear(millis: Long): Boolean =
        java.util.Calendar.getInstance().let { now ->
            val y = now.get(java.util.Calendar.YEAR)
            now.timeInMillis = millis
            now.get(java.util.Calendar.YEAR) == y
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
        val bookmarkRows = withContext(Dispatchers.IO) { BookmarkStore.bookmarks(ctx) }
            .filter { normalize(it.title).contains(key) || it.url.lowercase().contains(key) }
            .map { b ->
                Row(Kind.BOOKMARK, b.title.ifBlank { b.url }, Uri.parse(b.url).host ?: b.url) {
                    BookmarksUi.open(activity, b, b.browser)
                    leave()
                }
            }
        val eventRows = if (granted(Manifest.permission.READ_CALENDAR)) withContext(Dispatchers.IO) { events(q) } else null
        render(q, Found(appRows, contactRows, fileRows, folderRows, albumRows, settingRows, bookmarkRows, eventRows))
    }

    private fun render(q: String, found: Found) {
        results.removeAllViews()
        val missingContacts = found.contacts == null
        val missingEvents = found.events == null
        val missingFiles = found.files == null
        // Tabs with results, plus Contacts / Files when their permission is missing (to ask it)
        val visible = Tab.entries.filter {
            found.rows(it).isNotEmpty() || (it == Tab.CONTACTS && missingContacts) || (it == Tab.FILES && missingFiles) ||
                    (it == Tab.EVENTS && missingEvents)
        }
        val current = tab?.takeIf { it in visible } ?: visible.firstOrNull { found.rows(it).isNotEmpty() } ?: visible.firstOrNull()
        if (current != null) tabBar(q, found, visible, current, missingFiles)
        val key = normalize(q)
        if (current != null) when {
            current == Tab.CONTACTS && missingContacts -> permissionRow(R.string.ml_search_allow_contacts) {
                requestPermission(Manifest.permission.READ_CONTACTS)
            }
            current == Tab.FILES && missingFiles -> permissionRow(R.string.ml_search_allow_files) { askFilesAccess() }
            current == Tab.EVENTS && missingEvents -> permissionRow(R.string.ml_search_allow_calendar) {
                requestPermission(Manifest.permission.READ_CALENDAR)
            }
            current == Tab.ALBUMS -> albumGrid(found.rows(current))
            current == Tab.APPS -> appGrid(found.rows(current), key)
            else -> card(found.rows(current), key)
        }
        // Nothing on the phone: the web is the search key, or this button
        if (found.total == 0) results.addView(UiKit.emptyState(
            activity, R.drawable.ic_m_globe, activity.getString(R.string.ml_search_no_results),
            activity.getString(R.string.ml_search_on_google, q),
        ) { openWeb(q) })
    }

    /** Rows in a card over the blurred wallpaper, a thin line between them. */
    private fun card(rows: List<Row>, key: String) {
        if (rows.isEmpty()) return
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = translucentCard()
            setPadding(12.dpToPx(), 4.dpToPx(), 12.dpToPx(), 4.dpToPx())
        }
        rows.forEachIndexed { i, row ->
            if (i > 0) box.addView(View(activity).apply { setBackgroundColor(UiKit.line(activity)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { marginStart = 54.dpToPx() })
            box.addView(rowView(row, key))
        }
        results.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 6.dpToPx()
            bottomMargin = 16.dpToPx()
        })
    }

    private fun translucentCard() = GradientDrawable().apply {
        cornerRadius = 20.dpToPx().toFloat()
        setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(UiKit.card(activity), 0xCC))
        setStroke(1.dpToPx(), UiKit.stroke(activity))
    }

    /** Apps tab: icons in four columns, name below, in a card. */
    private fun appGrid(rows: List<Row>, key: String) {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = translucentCard()
            setPadding(6.dpToPx(), 10.dpToPx(), 6.dpToPx(), 6.dpToPx())
        }
        val ctx = activity.applicationContext
        val iconPack = prefs.iconPack
        val size = 48.dpToPx()
        rows.chunked(APP_COLUMNS).forEach { line ->
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            line.forEach { r ->
                val app = r.app ?: return@forEach
                row.addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(2.dpToPx(), 6.dpToPx(), 2.dpToPx(), 8.dpToPx())
                    isClickable = true
                    isFocusable = true
                    background = UiKit.ripple(activity)
                    tag = RESULT
                    setOnClickListener { r.onClick() }
                    setOnLongClickListener {
                        input.hideKeyboard()
                        appMenu(app) { refresh() }
                        true
                    }
                    val icon = android.widget.ImageView(activity)
                    addView(icon, LinearLayout.LayoutParams(size, size))
                    scope.launch {
                        val bitmap = withContext(Dispatchers.IO) {
                            IconPackManager.getIcon(ctx, app.appPackage, app.activityClassName, app.user, iconPack, size)
                        } ?: return@launch
                        icon.setImageDrawable(IconPackManager.toDrawable(ctx, bitmap, size))
                    }
                    addView(TextView(activity, null, 0, R.style.TextSmall).apply {
                        text = highlight(r.title, key)
                        textSize = 12.5f
                        gravity = Gravity.CENTER
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, 6.dpToPx(), 0, 0)
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            // Keep the columns of a short last line
            repeat(APP_COLUMNS - line.size) { row.addView(View(activity), LinearLayout.LayoutParams(0, 0, 1f)) }
            box.addView(row)
        }
        results.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 6.dpToPx()
            bottomMargin = 16.dpToPx()
        })
    }

    /** [text] with the part matching [key] (accents ignored) in bold. */
    private fun highlight(text: String, key: String): CharSequence {
        if (key.isEmpty()) return text
        // The folded text, and where each of its characters comes from
        val folded = StringBuilder()
        val from = mutableListOf<Int>()
        text.forEachIndexed { i, c ->
            normalize(c.toString()).forEach { folded.append(it); from += i }
        }
        val start = folded.indexOf(key)
        if (start < 0) return text
        val end = from[start + key.length - 1] + 1
        return SpannableStringBuilder(text).apply {
            setSpan(StyleSpan(Typeface.BOLD), from[start], end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** Pills with icon, name and count: the chosen one filled, the others in the cards' style. */
    private fun tabBar(q: String, found: Found, visible: List<Tab>, current: Tab, missingFiles: Boolean) {
        // In rows, all in sight however many
        val chips = FlowLayout(activity)
        val primary = UiKit.primary(activity)
        val card = UiKit.card(activity)
        visible.forEach { t ->
            val count = found.rows(t).size
            val selected = t == current
            val color = if (selected) card else primary
            chips.addView(TextView(activity, null, 0, R.style.TextSmall).apply {
                // Without the files access the tab asks for all the files
                val label = activity.getString(if (t == Tab.FILES && missingFiles) R.string.ml_search_files else t.label)
                text = SpannableStringBuilder(label).apply {
                    if (count > 0) {
                        val start = length
                        append("  ").append(count.toString())
                        setSpan(RelativeSizeSpan(0.85f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setSpan(ForegroundColorSpan(androidx.core.graphics.ColorUtils.setAlphaComponent(color, 0xA0)),
                            start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                setTextColor(color)
                setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
                background = if (selected) GradientDrawable().apply {
                    cornerRadius = 18.dpToPx().toFloat()
                    setColor(primary)
                } else translucentCard().apply { cornerRadius = 18.dpToPx().toFloat() }
                ContextCompat.getDrawable(activity, t.icon)?.mutate()?.let { d ->
                    d.setBounds(0, 0, 16.dpToPx(), 16.dpToPx())
                    d.setTint(color)
                    setCompoundDrawablesRelative(d, null, null, null)
                    compoundDrawablePadding = 6.dpToPx()
                }
                gravity = Gravity.CENTER_VERTICAL
                setPadding(12.dpToPx(), 7.dpToPx(), 14.dpToPx(), 7.dpToPx())
                isClickable = true
                setOnClickListener {
                    tab = t
                    render(q, found)
                    resultsScroll.scrollTo(0, 0)
                }
            }, ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = 6.dpToPx()
                bottomMargin = 6.dpToPx()
            })
        }
        results.addView(chips, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
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
                    rows += Row(Kind.CONTACT, name, number, contactId = id) { open(Intent(Intent.ACTION_VIEW, contactUri)) }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (rows.isEmpty()) return rows
        val ways = contactWays(rows.mapNotNull { it.contactId })
        return rows.map { r ->
            Row(r.kind, r.title, r.subtitle, contactId = r.contactId, ways = ways[r.contactId], onClick = r.onClick)
        }
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
                        if (file != null && MediaThumbnails.isMedia(mime) && prefs.builtInViewer) {
                            app.olauncher.ui.media.MediaViewerActivity.open(activity, file)
                            leave()
                        } else if (file != null && mime == "application/pdf" && prefs.builtInPdf) {
                            app.olauncher.ui.pdf.PdfViewerActivity.open(activity, file)
                            leave()
                        } else open(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
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
                if (media != null) albums += Row(
                    Kind.ALBUM, dir.name, file = dir, cover = media.first(),
                    uri = MediaThumbnails.mediaUris(activity, dir)[media.first().absolutePath],
                ) { openAlbum(dir) }
                else folders += Row(Kind.FOLDER, dir.name, file = dir) { openLocalFolder(dir) }
            }
        return folders to albums
    }

    /** Photos and videos of [dir], newest first, when it holds nothing else; null otherwise. */
    private fun albumMedia(dir: File): List<File>? = MediaThumbnails.albumMedia(dir)


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
            text = activity.getString(title).uppercase()
            textSize = 12f
            letterSpacing = 0.08f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(UiKit.secondary(activity))
            setPadding(0, 16.dpToPx(), 0, 6.dpToPx())
        })
        rows.forEach { results.addView(rowView(it)) }
    }

    private fun rowView(row: Row, key: String = ""): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 8.dpToPx(), 0, 8.dpToPx())
        isClickable = true
        isFocusable = true
        background = UiKit.ripple(activity)
        tag = RESULT
        setOnClickListener { row.onClick() }
        if (row.file != null || row.ways != null) setOnLongClickListener {
            if (row.ways != null) showContactMenu(row) else showItemMenu(row)
            true
        }
        val thumb = row.file?.takeIf { row.kind == Kind.PHOTO || row.kind == Kind.VIDEO }
        when {
            thumb != null -> addView(roundedImage().also {
                MediaThumbnails.load(scope, it, thumb, 44.dpToPx(), row.uri)
            }, LinearLayout.LayoutParams(44.dpToPx(), 44.dpToPx()).apply { marginEnd = 12.dpToPx() })

            row.kind == Kind.CONTACT -> addView(avatar(row.title), LinearLayout.LayoutParams(40.dpToPx(), 40.dpToPx()).apply {
                marginStart = 2.dpToPx()
                marginEnd = 14.dpToPx()
            })

            row.kind == Kind.EVENT -> addView(
                UiKit.fileTile(activity, UiKit.FileKind.CLOUD, 40).apply {
                    (getChildAt(0) as android.widget.ImageView).apply {
                        setImageResource(R.drawable.ic_m_event)
                        if (row.color != 0) setColorFilter(row.color or 0xFF000000.toInt())
                    }
                },
                LinearLayout.LayoutParams(40.dpToPx(), 40.dpToPx()).apply {
                    marginStart = 2.dpToPx()
                    marginEnd = 14.dpToPx()
                }
            )

            row.kind == Kind.WEB || row.kind == Kind.BOOKMARK -> addView(
                UiKit.fileTile(activity, UiKit.FileKind.CLOUD, 40).apply {
                    (getChildAt(0) as android.widget.ImageView).setImageResource(
                        if (row.kind == Kind.WEB) R.drawable.ic_m_globe else R.drawable.ic_m_star
                    )
                },
                LinearLayout.LayoutParams(40.dpToPx(), 40.dpToPx()).apply {
                    marginStart = 2.dpToPx()
                    marginEnd = 14.dpToPx()
                }
            )

            row.kind != Kind.APP -> {
                // Files: the tile of their type (PDF, document, ...)
                val tile = if (row.kind == Kind.FILE) UiKit.kindOf(row.title, row.mime, false) else row.kind.tile ?: UiKit.FileKind.DOC
                addView(UiKit.fileTile(activity, tile, 40), LinearLayout.LayoutParams(40.dpToPx(), 40.dpToPx()).apply {
                    marginStart = 2.dpToPx()
                    marginEnd = 14.dpToPx()
                })
            }
        }
        val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(activity, null, 0, R.style.TextLarge).apply {
            text = highlight(row.title, key)
            textSize = 17f
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
        row.ways?.let { contactButtons(this, it) }
    }

    /** A contact: the initial in a circle, its color picked from the name. */
    private fun avatar(name: String) = TextView(activity).apply {
        val color = AVATAR_COLORS[Math.floorMod(name.hashCode(), AVATAR_COLORS.size)]
        text = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#"
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(color)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(color, 0x33))
        }
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
                        row.cover?.let { MediaThumbnails.load(scope, image, it, side, row.uri) }
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

    /** What a contact can be reached with: numbers, e-mails and its WhatsApp / Telegram chats. */
    private class ContactWays(
        val phones: List<String>,
        val emails: List<String>,
        /** The chat in WhatsApp / Telegram, when the contact has one. */
        val whatsApp: Intent?,
        val telegram: Intent?,
    )

    /** How each of the contacts can be reached, in one query. */
    private fun contactWays(ids: List<Long>): Map<Long, ContactWays> {
        class Found {
            val phones = linkedMapOf<String, String>()
            val emails = linkedSetOf<String>()
            var whatsApp: Pair<Long, String>? = null
            var telegram: Pair<Long, String>? = null
        }
        val found = ids.associateWith { Found() }
        val projection = arrayOf(
            ContactsContract.Data.CONTACT_ID, ContactsContract.Data._ID, ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1,
        )
        try {
            activity.contentResolver.query(
                ContactsContract.Data.CONTENT_URI, projection,
                "${ContactsContract.Data.CONTACT_ID} IN (${ids.joinToString { "?" }})", ids.map { it.toString() }.toTypedArray(), null
            )?.use { c ->
                while (c.moveToNext()) {
                    val f = found[c.getLong(0)] ?: continue
                    val dataId = c.getLong(1)
                    val mime = c.getString(2) ?: continue
                    val value = c.getString(3)?.trim().orEmpty()
                    when (mime) {
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> if (value.isNotEmpty())
                            f.phones.putIfAbsent(android.telephony.PhoneNumberUtils.normalizeNumber(value), value)
                        ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> if (value.isNotEmpty()) f.emails += value
                        in WHATSAPP_CHATS -> if (f.whatsApp == null || mime == WHATSAPP_CHATS[0]) f.whatsApp = dataId to mime
                        TELEGRAM_CHAT -> if (f.telegram == null) f.telegram = dataId to mime
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // Apps that don't add their rows to the contacts: their chat is opened by number
        val whatsAppByNumber = WHATSAPP_PACKAGES.takeIf { found.values.none { it.whatsApp != null } && !anyRow(WHATSAPP_CHATS) }
            ?.firstOrNull { installed(it) }
        val telegramByNumber = TELEGRAM_PACKAGES.takeIf { found.values.none { it.telegram != null } && !anyRow(listOf(TELEGRAM_CHAT)) }
            ?.firstOrNull { installed(it) }
        fun chat(row: Pair<Long, String>) =
            Intent(Intent.ACTION_VIEW).setDataAndType(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, row.first), row.second)
        return found.mapValues { (_, f) ->
            val digits = f.phones.values.firstOrNull()?.let { internationalDigits(it) }
            ContactWays(
                f.phones.values.toList(), f.emails.toList(),
                whatsApp = f.whatsApp?.let(::chat) ?: whatsAppByNumber?.let { pkg ->
                    digits?.let { Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$it")).setPackage(pkg) }
                },
                telegram = f.telegram?.let(::chat) ?: telegramByNumber?.let { pkg ->
                    digits?.let { Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?phone=$it")).setPackage(pkg) }
                },
            )
        }
    }

    private fun anyRow(mimes: List<String>) = try {
        activity.contentResolver.query(
            ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data._ID),
            "${ContactsContract.Data.MIMETYPE} IN (${mimes.joinToString { "?" }})", mimes.toTypedArray(), null
        )?.use { it.moveToFirst() } ?: false
    } catch (e: Exception) {
        false
    }

    private fun installed(pkg: String) = try {
        activity.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** The number with its country prefix and no "+", for the links of the chat apps; null if it can't tell. */
    private fun internationalDigits(number: String): String? {
        val iso = activity.getSystemService(android.telephony.TelephonyManager::class.java)
            ?.let { it.networkCountryIso.ifBlank { it.simCountryIso } }
            ?.uppercase().orEmpty().ifBlank { java.util.Locale.getDefault().country }
        val e164 = android.telephony.PhoneNumberUtils.formatNumberToE164(number, iso)
            ?: android.telephony.PhoneNumberUtils.normalizeNumber(number).takeIf { it.startsWith("+") }
        return e164?.removePrefix("+")?.takeIf { it.length > 6 }
    }

    /** A contact's row: a button for each way to reach it (the first number and address; the others in the long press menu). */
    private fun contactButtons(line: LinearLayout, ways: ContactWays) {
        val number = ways.phones.firstOrNull()
        val email = ways.emails.firstOrNull()
        listOfNotNull(
            number?.let { Triple(R.drawable.ic_m_call, activity.getString(R.string.ml_contact_call), Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", it, null))) },
            number?.let { Triple(R.drawable.ic_m_sms, activity.getString(R.string.ml_contact_sms), Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", it, null))) },
            ways.whatsApp?.let { Triple(R.drawable.ic_m_chat, WHATSAPP, it) },
            ways.telegram?.let { Triple(R.drawable.ic_m_send, TELEGRAM, it) },
            email?.let { Triple(R.drawable.ic_m_mail, activity.getString(R.string.ml_contact_email), Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", it, null))) },
        ).forEach { (icon, label, intent) ->
            line.addView(android.widget.ImageView(activity).apply {
                setImageResource(icon)
                imageTintList = android.content.res.ColorStateList.valueOf(activity.getColorFromAttr(R.attr.primaryColor))
                alpha = 0.85f
                contentDescription = label
                setPadding(7.dpToPx(), 7.dpToPx(), 7.dpToPx(), 7.dpToPx())
                val tv = android.util.TypedValue()
                if (activity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true))
                    background = ContextCompat.getDrawable(activity, tv.resourceId)
                setOnClickListener { open(intent) }
            }, LinearLayout.LayoutParams(34.dpToPx(), 34.dpToPx()).apply { marginStart = 2.dpToPx() })
        }
    }

    /** Long press on a contact: call, SMS, WhatsApp, Telegram and e-mail, only the ones it has. */
    private fun showContactMenu(row: Row) {
        val ways = row.ways ?: return
        val entries = mutableListOf<MenuEntry>()
        // With more numbers or addresses each shows its own on the right
        val manyPhones = ways.phones.size > 1
        ways.phones.forEach { number ->
            entries += MenuEntry(activity.getString(R.string.ml_contact_call), value = number.takeIf { manyPhones }, icon = R.drawable.ic_m_call) {
                open(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))
            }
        }
        ways.phones.forEach { number ->
            entries += MenuEntry(activity.getString(R.string.ml_contact_sms), value = number.takeIf { manyPhones }, icon = R.drawable.ic_m_sms) {
                open(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)))
            }
        }
        val first = ways.phones.firstOrNull()
        ways.whatsApp?.let { intent -> entries += MenuEntry(WHATSAPP, icon = R.drawable.ic_m_chat) { open(intent) } }
        ways.telegram?.let { intent -> entries += MenuEntry(TELEGRAM, icon = R.drawable.ic_m_send) { open(intent) } }
        val manyEmails = ways.emails.size > 1
        ways.emails.forEach { address ->
            entries += MenuEntry(activity.getString(R.string.ml_contact_email), value = address.takeIf { manyEmails }, icon = R.drawable.ic_m_mail) {
                open(Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", address, null)))
            }
        }
        entries += MenuEntry(activity.getString(R.string.ml_contact_open), icon = R.drawable.ic_m_contact) { row.onClick() }
        activity.showMenuDialog(row.title, entries, header = { dialog ->
            // The only number or address, under the name
            listOfNotNull(first?.takeIf { !manyPhones }, ways.emails.singleOrNull()).takeIf { it.isNotEmpty() }?.let { lines ->
                TextView(dialog.context, null, 0, R.style.TextSmall).apply {
                    text = lines.joinToString("\n")
                    alpha = 0.6f
                    setTextColor(dialog.context.getColorFromAttr(R.attr.primaryColor))
                    setPadding(0, 8.dpToPx(), 0, 4.dpToPx())
                }
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
            setTextColor(NotificationBadge.NOTIFICATION_COLOR)
            setPadding(0, 12.dpToPx(), 0, 12.dpToPx())
            isClickable = true
            setOnClickListener { onClick() }
        }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun normalize(s: CharSequence) = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "").lowercase()

    companion object {
        private const val RESULT = "result"
        private const val MAX_APPS = 30
        private const val MAX_EVENTS = 30
        private const val APP_COLUMNS = 4
        private val AVATAR_COLORS = intArrayOf(
            0xFF5B9BD5.toInt(), 0xFF9C7BD6.toInt(), 0xFFE09A4F.toInt(), 0xFFE0675E.toInt(), 0xFF4FB38A.toInt(), 0xFFD66BA0.toInt(),
        )
        private const val MAX_CONTACTS = 30
        private const val WHATSAPP = "WhatsApp"
        private const val TELEGRAM = "Telegram"
        /** Rows WhatsApp adds to the contacts: the chat first, then WhatsApp Business. */
        private val WHATSAPP_CHATS = listOf(
            "vnd.android.cursor.item/vnd.com.whatsapp.profile", "vnd.android.cursor.item/vnd.com.whatsapp.w4b.profile",
        )
        private const val TELEGRAM_CHAT = "vnd.android.cursor.item/vnd.org.telegram.messenger.android.profile"
        private val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")
        private val TELEGRAM_PACKAGES = listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram")
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
        private const val SAMSUNG_INTERNET = "com.sec.android.app.sbrowser"
        private const val EXTERNAL_STORAGE_DOCS = "com.android.externalstorage.documents"

        @Volatile
        private var cachedFolders: Pair<Long, List<File>>? = null
    }
}
