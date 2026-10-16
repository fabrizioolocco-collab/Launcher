package app.olauncher.ui

import android.content.Context
import android.content.pm.LauncherApps
import android.os.UserHandle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Filter
import android.widget.Filterable
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Constants
import app.olauncher.databinding.AdapterAppDrawerBinding
import app.olauncher.databinding.AdapterPrivateSpaceHeaderBinding
import app.olauncher.helper.dpToPx
import app.olauncher.helper.hideKeyboard
import app.olauncher.helper.isSystemApp
import app.olauncher.helper.showKeyboard
import java.text.Normalizer

class AppDrawerAdapter(
    private var flag: Int,
    private val appLabelGravity: Int,
    private val appClickListener: (AppModel) -> Unit,
    private val appInfoListener: (AppModel) -> Unit,
    private val appDeleteListener: (AppModel) -> Unit,
    private val appHideListener: (AppModel, Int) -> Unit,
    private val appRenameListener: (AppModel, String) -> Unit,
    private val privateSpaceToggleListener: () -> Unit = {},
    private val privateSpaceSettingsListener: () -> Unit = {},
    private val appSectionListener: (AppModel) -> Unit = {},
    private val iconLoader: ((AppModel, TextView) -> Unit)? = null,
    private val titleStyler: ((TextView) -> Unit)? = null,
    private val badgeCount: ((AppModel) -> Int)? = null,
    /** When set, a long press opens this menu instead of the inline action bar. */
    private val longPressMenu: ((AppModel, Int) -> Unit)? = null,
    /** Tap on the red notification pill of a row. */
    private val badgeTap: ((AppModel) -> Unit)? = null,
) : ListAdapter<AppModel, RecyclerView.ViewHolder>(DIFF_CALLBACK), Filterable {

    /**
     * Grid mode: apps are cells with the icon above the name. [cellHeightPx] 0 lets cells wrap
     * their content. [iconLoader] fills the cell's image.
     */
    class GridStyle(
        val cellHeightPx: Int,
        val iconSizePx: Int,
        val showLabels: Boolean,
        /** Rounded square behind the icon ([iconSizePx] is then the tile's edge). */
        val tiles: Boolean,
        val iconLoader: (AppModel, android.widget.ImageView, Int) -> Unit,
    )

    /** Set before the adapter is attached: null shows the list, otherwise the grid. */
    var grid: GridStyle? = null

    /** List mode: show the app names (icons only otherwise). */
    var showLabels = true

    /** True for items that take a whole grid row (headers and the trailing spacer). */
    fun spansRow(position: Int): Boolean {
        val item = getItem(position)
        return item is AppModel.ListHeader || item is AppModel.PrivateSpaceHeader || item.appPackage.isEmpty()
    }

    companion object {
        const val VIEW_TYPE_APP = 0
        const val VIEW_TYPE_PRIVATE_HEADER = 1
        const val VIEW_TYPE_LIST_HEADER = 2

        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<AppModel>() {
            override fun areItemsTheSame(oldItem: AppModel, newItem: AppModel): Boolean = when {
                oldItem is AppModel.App && newItem is AppModel.App ->
                    oldItem.appPackage == newItem.appPackage && oldItem.user == newItem.user &&
                            oldItem.inNews == newItem.inNews

                oldItem is AppModel.ListHeader && newItem is AppModel.ListHeader -> oldItem.title == newItem.title

                oldItem is AppModel.PinnedShortcut && newItem is AppModel.PinnedShortcut ->
                    oldItem.identity == newItem.identity

                oldItem is AppModel.PrivateSpaceHeader && newItem is AppModel.PrivateSpaceHeader -> true

                else -> false
            }

            override fun areContentsTheSame(oldItem: AppModel, newItem: AppModel): Boolean =
                oldItem == newItem
        }
    }

    private var autoLaunch = true
    private var isBangSearch = false
    var allowAutoLaunch = false
    private val diacriticsRegex = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val separatorsRegex = Regex("[-_+,.`'\\s\\p{Z}]")
    private val appFilter = createAppFilter()
    private val myUserHandle = android.os.Process.myUserHandle()

    var appsList: MutableList<AppModel> = mutableListOf()
    var appFilteredList: MutableList<AppModel> = mutableListOf()

    override fun getItemViewType(position: Int): Int {
        // The differ's list is what is on screen: always read items from it, never from a list that may be ahead of it
        return when (getItem(position)) {
            is AppModel.PrivateSpaceHeader -> VIEW_TYPE_PRIVATE_HEADER
            is AppModel.ListHeader -> VIEW_TYPE_LIST_HEADER
            else -> VIEW_TYPE_APP
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_LIST_HEADER -> ListHeaderViewHolder(
                TextView(parent.context, null, 0, R.style.TextSmall).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    setPadding(24.dpToPx(), 18.dpToPx(), 24.dpToPx(), 6.dpToPx())
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    letterSpacing = 0.06f
                    alpha = 0.6f
                }
            )

            VIEW_TYPE_PRIVATE_HEADER -> PrivateSpaceHeaderViewHolder(
                AdapterPrivateSpaceHeaderBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            else -> if (grid != null) GridViewHolder(parent.context) else ViewHolder(
                AdapterAppDrawerBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        try {
            if (position == RecyclerView.NO_POSITION) return
            val appModel = getItem(position)
            when (holder) {
                is ListHeaderViewHolder -> holder.bind(appModel as AppModel.ListHeader, appLabelGravity)

                is PrivateSpaceHeaderViewHolder -> {
                    holder.bind(
                        appLabelGravity,
                        privateSpaceToggleListener,
                        privateSpaceSettingsListener,
                    )
                }

                is GridViewHolder -> holder.bind(
                    grid ?: return,
                    myUserHandle,
                    appModel,
                    appClickListener,
                    titleStyler,
                    badgeCount?.invoke(appModel) ?: 0,
                    longPressMenu,
                    badgeTap,
                )

                is ViewHolder -> holder.bind(
                    showLabels,
                    flag,
                    appLabelGravity,
                    myUserHandle,
                    appModel,
                    appClickListener,
                    appDeleteListener,
                    appInfoListener,
                    appHideListener,
                    appRenameListener,
                    appSectionListener,
                    iconLoader,
                    titleStyler,
                    badgeCount?.invoke(appModel) ?: 0,
                    longPressMenu,
                    badgeTap,
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun getFilter(): Filter = this.appFilter

    private fun createAppFilter(): Filter {
        return object : Filter() {
            override fun performFiltering(charSearch: CharSequence?): FilterResults {
                isBangSearch = charSearch?.startsWith("!") ?: false
                autoLaunch = allowAutoLaunch && (charSearch?.startsWith(" ")?.not() ?: true)

                val appFilteredList = (if (charSearch.isNullOrBlank()) appsList
                else appsList.filter { app ->
                    // While searching, show only the A-Z apps: no headers, no "News" copies
                    app !is AppModel.PrivateSpaceHeader && app !is AppModel.ListHeader &&
                            !(app is AppModel.App && app.inNews) && appLabelMatches(app.appLabel, charSearch)
                } as MutableList<AppModel>)

                val filterResults = FilterResults()
                filterResults.values = appFilteredList
                return filterResults
            }

            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                results?.values?.let {
                    val items = it as MutableList<AppModel>
                    appFilteredList = items
                    submitList(items.toList()) {
                        autoLaunch()
                    }
                }
            }
        }
    }

    private fun autoLaunch() {
        try {
            if (itemCount == 1
                && autoLaunch
                && isBangSearch.not()
                && flag == Constants.FLAG_LAUNCH_APP
                && appFilteredList.isNotEmpty()
                && appFilteredList[0] !is AppModel.PrivateSpaceHeader
            ) appClickListener(appFilteredList[0])
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun appLabelMatches(appLabel: String, charSearch: CharSequence): Boolean {
        if (appLabel.contains(charSearch.trim(), true)) return true
        val query = charSearch.normalizeForSearch()
        return query.isNotEmpty() && appLabel.normalizeForSearch().contains(query, true)
    }

    private fun CharSequence.normalizeForSearch(): String =
        Normalizer.normalize(this, Normalizer.Form.NFD)
            .replace(diacriticsRegex, "")
            .replace(separatorsRegex, "")

    fun setAppList(appsList: MutableList<AppModel>) {
        // Add empty app for bottom padding in recyclerview and assign to list
        appsList.add(
            AppModel.App(
                appLabel = "",
                key = null,
                appPackage = "",
                activityClassName = "",
                isNew = false,
                user = android.os.Process.myUserHandle()
            )
        )
        this.appsList = appsList
        this.appFilteredList = appsList
        submitList(appsList.toList())
    }

    /** Takes an app out of the list on screen (hidden or uninstalled) without touching the differ's list in place. */
    fun remove(app: AppModel) {
        appsList.remove(app)
        appFilteredList = appFilteredList.filter { it !== app }.toMutableList()
        submitList(appFilteredList.toList())
    }

    fun launchFirstInList() {
        val first = appFilteredList.firstOrNull { it !is AppModel.PrivateSpaceHeader && it !is AppModel.ListHeader }
        if (first != null) appClickListener(first)
    }

    // Actions for the long-press menu, same behavior as the inline bar
    fun openInfo(app: AppModel) = appInfoListener(app)
    fun delete(app: AppModel) = appDeleteListener(app)
    fun toggleHidden(app: AppModel, position: Int) = appHideListener(app, position)
    fun rename(app: AppModel, label: String) = appRenameListener(app, label)
    fun addToSection(app: AppModel) = appSectionListener(app)
    val isHiddenList get() = flag == Constants.FLAG_HIDDEN_APPS

    /** Rebinds visible rows so notification badges reflect the latest counts. */
    fun refreshBadges() = notifyItemRangeChanged(0, itemCount)

    class ListHeaderViewHolder(private val view: TextView) : RecyclerView.ViewHolder(view) {
        fun bind(header: AppModel.ListHeader, gravity: Int) {
            view.text = header.title
            view.gravity = gravity
        }
    }

    class PrivateSpaceHeaderViewHolder(private val binding: AdapterPrivateSpaceHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(
            appLabelGravity: Int,
            toggleListener: () -> Unit,
            settingsListener: () -> Unit,
        ) = with(binding) {
            privateSpaceTitle.gravity = appLabelGravity
            privateSpaceTitle.setOnClickListener { toggleListener() }
            privateSpaceTitle.setOnLongClickListener {
                settingsListener()
                true
            }
        }
    }

    /** A grid cell: icon centered, name below (optional), a dot after the name for apps of another profile. */
    class GridViewHolder(context: Context) : RecyclerView.ViewHolder(
        android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(4.dpToPx(), 8.dpToPx(), 4.dpToPx(), 8.dpToPx())
            isClickable = true
            isLongClickable = true
            addView(android.widget.FrameLayout(context).apply {
                addView(android.widget.ImageView(context).apply {
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                }, android.widget.FrameLayout.LayoutParams(1, 1, android.view.Gravity.CENTER))
            }, android.widget.LinearLayout.LayoutParams(1, 1))
            addView(TextView(context, null, 0, R.style.TextSmall).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = android.view.Gravity.CENTER
                setPadding(0, 4.dpToPx(), 0, 0)
            }, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    ) {
        private val cell = itemView as android.widget.LinearLayout
        private val tile = cell.getChildAt(0) as android.widget.FrameLayout
        private val icon = tile.getChildAt(0) as android.widget.ImageView
        private val title = cell.getChildAt(1) as TextView
        private val defaultTitleColors = title.textColors

        /** The style is applied on every bind, so settings changes don't need new holders. */
        private fun applyStyle(style: GridStyle) {
            val cellHeight = if (style.cellHeightPx > 0) style.cellHeightPx else ViewGroup.LayoutParams.WRAP_CONTENT
            if (cell.layoutParams.height != cellHeight) cell.layoutParams = cell.layoutParams.apply { height = cellHeight }
            val tp = tile.layoutParams
            if (tp.width != style.iconSizePx) {
                tile.layoutParams = tp.apply { width = style.iconSizePx; height = style.iconSizePx }
                tile.background = if (style.tiles) app.olauncher.ui.widgets.IconTiles.drawable(cell.context, style.iconSizePx) else null
                val inner = if (style.tiles) (style.iconSizePx * app.olauncher.ui.widgets.IconTiles.ICON_FRACTION).toInt() else style.iconSizePx
                icon.layoutParams = icon.layoutParams.apply { width = inner; height = inner }
            }
            title.isVisible = style.showLabels
        }

        fun bind(
            style: GridStyle,
            myUserHandle: UserHandle,
            appModel: AppModel,
            clickListener: (AppModel) -> Unit,
            titleStyler: ((TextView) -> Unit)?,
            notificationCount: Int,
            longPressMenu: ((AppModel, Int) -> Unit)?,
            badgeTap: ((AppModel) -> Unit)?,
        ) {
            applyStyle(style)
            val spacer = appModel.appPackage.isEmpty()
            cell.visibility = if (spacer) View.INVISIBLE else View.VISIBLE
            val label = buildString {
                append(appModel.appLabel)
                if (appModel.isNew) append(" ✦")
                if (appModel.user != myUserHandle) append(" ●")
            }
            title.text = if (notificationCount > 0) HomeSectionsController.badgeText(appModel.appLabel, -1) else label
            if (notificationCount > 0) title.setTextColor(HomeSectionsController.NOTIFICATION_COLOR) else title.setTextColor(defaultTitleColors)
            titleStyler?.invoke(title)
            cell.contentDescription = appModel.appLabel
            icon.setImageDrawable(null)
            icon.colorFilter = if (notificationCount > 0) NotificationBadge.redFilter else null
            if (!spacer) style.iconLoader(appModel, icon, icon.layoutParams.width)
            cell.setOnClickListener {
                if (spacer) return@setOnClickListener
                if (notificationCount > 0 && badgeTap != null) badgeTap(appModel) else clickListener(appModel)
            }
            cell.setOnLongClickListener {
                if (!spacer && longPressMenu != null) longPressMenu(appModel, bindingAdapterPosition)
                true
            }
        }
    }

    class ViewHolder(private val binding: AdapterAppDrawerBinding) :
        RecyclerView.ViewHolder(binding.root) {
        private val defaultTitleColors = binding.appTitle.textColors

        fun bind(
            showLabels: Boolean,
            flag: Int,
            appLabelGravity: Int,
            myUserHandle: UserHandle,
            appModel: AppModel,
            clickListener: (AppModel) -> Unit,
            appDeleteListener: (AppModel) -> Unit,
            appInfoListener: (AppModel) -> Unit,
            appHideListener: (AppModel, Int) -> Unit,
            appRenameListener: (AppModel, String) -> Unit,
            appSectionListener: (AppModel) -> Unit,
            iconLoader: ((AppModel, TextView) -> Unit)?,
            titleStyler: ((TextView) -> Unit)?,
            notificationCount: Int,
            longPressMenu: ((AppModel, Int) -> Unit)?,
            badgeTap: ((AppModel) -> Unit)?,
        ) = with(binding) {
            appHideLayout.visibility = View.GONE
            renameLayout.visibility = View.GONE
            appTitle.visibility = View.VISIBLE

            // Show indicators in title based on app type and state
            if (notificationCount > 0) {
                appTitle.text = HomeSectionsController.badgeText(appModel.appLabel, -1)
                appTitle.setTextColor(HomeSectionsController.NOTIFICATION_COLOR)
            } else {
                appTitle.text = buildString {
                    append(appModel.appLabel)
                    if (appModel.isNew) append(" ✦")
                }
                appTitle.setTextColor(defaultTitleColors)
            }
            if (!showLabels) appTitle.text = ""
            appTitle.gravity = appLabelGravity
            otherProfileIndicator.isVisible = appModel.user != myUserHandle
            titleStyler?.invoke(appTitle)
            appTitle.setCompoundDrawablesRelative(null, null, null, null)
            if (appModel.appPackage.isNotEmpty()) iconLoader?.invoke(appModel, appTitle)

            // With notifications (red name and icon) a tap lists them instead of opening the app
            appTitle.setOnClickListener {
                if (notificationCount > 0 && badgeTap != null) badgeTap(appModel) else clickListener(appModel)
            }

            appTitle.setOnLongClickListener {
                if (longPressMenu != null) {
                    if (appModel.appPackage.isNotEmpty()) longPressMenu(appModel, bindingAdapterPosition)
                    return@setOnLongClickListener true
                }
                if (appModel.appPackage.isNotEmpty()) {
                    appDelete.alpha = when (
                        appModel is AppModel.PinnedShortcut || !root.context.isSystemApp(appModel.appPackage, appModel.user)
                    ) {
                        true -> 1.0f
                        false -> 0.5f
                    }
                    appHide.text = if (flag == Constants.FLAG_HIDDEN_APPS)
                        root.context.getString(R.string.adapter_show)
                    else
                        root.context.getString(R.string.adapter_hide)
                    appTitle.visibility = View.INVISIBLE
                    appHide.alpha = when (appModel is AppModel.PinnedShortcut) {
                        true -> 0.5f
                        false -> 1.0f
                    }
                    appHideLayout.visibility = View.VISIBLE
                    // Only allow renaming non hidden apps
                    appRename.isVisible = flag != Constants.FLAG_HIDDEN_APPS
                    // Home sections hold launchable apps only (not shortcuts); hidden apps are fine
                    appSection.isVisible = (flag == Constants.FLAG_LAUNCH_APP || flag == Constants.FLAG_HIDDEN_APPS) && appModel is AppModel.App
                }
                true
            }

            // Configure rename behavior
            appRename.setOnClickListener {
                if (appModel.appPackage.isNotEmpty()) {
                    etAppRename.hint = getAppName(etAppRename.context, appModel.appPackage, appModel.user)
                    etAppRename.setText(appModel.appLabel)
                    etAppRename.setSelectAllOnFocus(true)
                    renameLayout.visibility = View.VISIBLE
                    appHideLayout.visibility = View.GONE
                    etAppRename.showKeyboard()
                    etAppRename.imeOptions = EditorInfo.IME_ACTION_DONE
                }
            }
            etAppRename.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                appTitle.visibility = if (hasFocus) View.INVISIBLE else View.VISIBLE
            }
            etAppRename.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    etAppRename.hint = getAppName(etAppRename.context, appModel.appPackage, appModel.user)
                }

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {
                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    etAppRename.hint = ""
                }
            })
            etAppRename.setOnEditorActionListener { _, actionCode, _ ->
                if (actionCode == EditorInfo.IME_ACTION_DONE) {
                    val renameLabel = etAppRename.text.toString().trim()
                    if (renameLabel.isNotBlank() && appModel.appPackage.isNotBlank()) {
                        appRenameListener(appModel, renameLabel)
                        renameLayout.visibility = View.GONE
                    }
                    true
                }
                false
            }
            tvSaveRename.setOnClickListener {
                etAppRename.hideKeyboard()
                val renameLabel = etAppRename.text.toString().trim()
                if (renameLabel.isNotBlank() && appModel.appPackage.isNotBlank()) {
                    appRenameListener(appModel, renameLabel)
                    renameLayout.visibility = View.GONE
                } else {
                    appRenameListener(
                        appModel,
                        getAppName(etAppRename.context, appModel.appPackage, appModel.user)
                    )
                    renameLayout.visibility = View.GONE
                }
            }
            appInfo.setOnClickListener { appInfoListener(appModel) }
            appSection.setOnClickListener {
                appHideLayout.visibility = View.GONE
                appTitle.visibility = View.VISIBLE
                appSectionListener(appModel)
            }
            appDelete.setOnClickListener { appDeleteListener(appModel) }
            appMenuClose.setOnClickListener {
                appHideLayout.visibility = View.GONE
                appTitle.visibility = View.VISIBLE
            }
            appRenameClose.setOnClickListener {
                renameLayout.visibility = View.GONE
                appTitle.visibility = View.VISIBLE
            }
            appHide.setOnClickListener { appHideListener(appModel, bindingAdapterPosition) }
        }

        private fun getAppName(context: Context, appPackage: String, user: UserHandle): String {
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            return try {
                val activityList = launcherApps.getActivityList(appPackage, user)
                if (activityList.isNotEmpty()) {
                    activityList.first().label.toString()
                } else {
                    val packageManager = context.packageManager
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(appPackage, 0)
                    ).toString()
                }
            } catch (_: Exception) {
                "" // As a fallback, display an empty string.
            }
        }
    }
}
