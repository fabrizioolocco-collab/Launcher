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
import app.olauncher.helper.getColorFromAttr
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
        /** Each app on its own card (icon and name inside, red with a count when it has notifications). */
        val cards: Boolean = false,
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
                            oldItem.activityClassName == newItem.activityClassName && oldItem.inNews == newItem.inNews

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

    /**
     * While an app is dragged: the list on screen, moved item by item (the differ's list only
     * changes through a diff, too late for the drag).
     */
    private var dragList: MutableList<AppModel>? = null

    public override fun getItem(position: Int): AppModel = dragList?.get(position) ?: super.getItem(position)

    override fun getItemCount(): Int = dragList?.size ?: super.getItemCount()

    fun beginDrag() {
        dragList = currentList.toMutableList()
    }

    fun moveItem(from: Int, to: Int) {
        val list = dragList ?: return
        list.add(to, list.removeAt(from))
        notifyItemMoved(from, to)
    }

    /**
     * The list as left by the drag. It stays on screen until the next list is in the differ
     * (see [show]): the moves the differ then reports already happened on screen.
     */
    fun endDrag(): List<AppModel> {
        val list = dragList ?: return currentList
        appsList = list.toMutableList()
        appFilteredList = appsList
        show(list.toList())
        return list
    }

    /** [submitList], and back to the differ's list after a drag. */
    @Suppress("NotifyDataSetChanged")
    private fun show(list: List<AppModel>, then: (() -> Unit)? = null) {
        submitList(list) {
            if (dragList != null) {
                dragList = null
                notifyDataSetChanged()
            }
            then?.invoke()
        }
    }

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
                    show(items.toList()) {
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
        show(appsList.toList())
    }

    /** Takes an app out of the list on screen (hidden or uninstalled) without touching the differ's list in place. */
    fun remove(app: AppModel) {
        appsList.remove(app)
        appFilteredList = appFilteredList.filter { it !== app }.toMutableList()
        show(appFilteredList.toList())
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

    /**
     * A grid cell: icon centered, name below (optional), a dot after the name for apps of another
     * profile. As a card the whole cell has the card's background and a count on the top right.
     */
    class GridViewHolder(context: Context) : RecyclerView.ViewHolder(
        android.widget.FrameLayout(context).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            isClickable = true
            isLongClickable = true
            addView(android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding(4.dpToPx(), 8.dpToPx(), 4.dpToPx(), 8.dpToPx())
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
            }, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // The count of a card with notifications
            addView(TextView(context).apply {
                textSize = 10.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(0xFFFFFFFF.toInt())
                gravity = android.view.Gravity.CENTER
                minWidth = 18.dpToPx()
                setPadding(5.dpToPx(), 0, 5.dpToPx(), 0)
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 9.dpToPx().toFloat()
                    setColor(BADGE_COLOR)
                }
                visibility = View.GONE
            }, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 18.dpToPx(), android.view.Gravity.TOP or android.view.Gravity.END).apply {
                topMargin = 6.dpToPx(); marginEnd = 6.dpToPx()
            })
        }
    ) {
        private val root = itemView as android.widget.FrameLayout
        private val cell = root.getChildAt(0) as android.widget.LinearLayout
        private val tile = cell.getChildAt(0) as android.widget.FrameLayout
        private val icon = tile.getChildAt(0) as android.widget.ImageView
        private val title = cell.getChildAt(1) as TextView
        private val badge = root.getChildAt(1) as TextView
        private val defaultTitleColors = title.textColors
        private var cards: Boolean? = null

        /** The style is applied on every bind, so settings changes don't need new holders. */
        private fun applyStyle(style: GridStyle) {
            val cellHeight = when {
                style.cellHeightPx > 0 -> style.cellHeightPx
                style.cards -> (if (style.showLabels) 92 else 72).dpToPx()
                else -> ViewGroup.LayoutParams.WRAP_CONTENT
            }
            val margin = if (style.cards) CARD_GAP_DP.dpToPx() / 2 else 0
            val lp = root.layoutParams as RecyclerView.LayoutParams
            if (lp.height != cellHeight || lp.topMargin != margin) root.layoutParams = lp.apply {
                height = cellHeight
                setMargins(margin, margin, margin, margin)
            }
            val tp = tile.layoutParams
            if (tp.width != style.iconSizePx || cards != style.cards) {
                cards = style.cards
                tile.layoutParams = tp.apply { width = style.iconSizePx; height = style.iconSizePx }
                val inner = if (style.tiles && !style.cards) (style.iconSizePx * app.olauncher.ui.widgets.IconTiles.ICON_FRACTION).toInt() else style.iconSizePx
                icon.layoutParams = icon.layoutParams.apply { width = inner; height = inner }
                if (style.cards) {
                    title.textSize = 11.5f
                    title.setPadding(0, 8.dpToPx(), 0, 0)
                } else {
                    title.setTextAppearance(R.style.TextSmall)
                    title.setPadding(0, 4.dpToPx(), 0, 0)
                }
            }
            title.isVisible = style.showLabels
        }

        private fun cardBackground(notified: Boolean) = android.graphics.drawable.GradientDrawable().apply {
            val ctx = root.context
            cornerRadius = 18.dpToPx().toFloat()
            setColor(if (notified) CARD_RED else ctx.getColorFromAttr(R.attr.cardColor))
            setStroke(1.dpToPx(), if (notified) CARD_RED_STROKE else ctx.getColorFromAttr(R.attr.cardStrokeColor))
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
            root.visibility = if (spacer) View.INVISIBLE else View.VISIBLE
            val notified = notificationCount > 0
            val label = buildString {
                append(appModel.appLabel)
                if (appModel.isNew) append(" ✦")
                if (appModel.user != myUserHandle) append(" ●")
            }
            title.text = if (notified && !style.cards) HomeSectionsController.badgeText(appModel.appLabel, -1) else label
            if (notified && !style.cards) title.setTextColor(HomeSectionsController.NOTIFICATION_COLOR) else title.setTextColor(defaultTitleColors)
            titleStyler?.invoke(title)
            // A card's name is a little quieter than the icon
            if (style.cards) {
                title.textSize = 11.5f
                title.alpha = 0.85f
            } else title.alpha = 1f
            root.contentDescription = appModel.appLabel
            icon.setImageDrawable(null)
            if (style.cards) {
                // The card turns red, the icon stays as it is
                icon.colorFilter = null
                tile.background = null
                root.background = if (spacer) null else cardBackground(notified)
                root.foreground = rippleOf(root.context)
                badge.isVisible = notified
                badge.text = if (notificationCount > 99) "99+" else notificationCount.toString()
            } else {
                icon.colorFilter = if (notified) NotificationBadge.redFilter else null
                tile.background = when {
                    notified -> NotificationBadge.background(style.iconSizePx)
                    style.tiles -> app.olauncher.ui.widgets.IconTiles.drawable(cell.context, style.iconSizePx)
                    else -> null
                }
                root.background = null
                root.foreground = null
                badge.isVisible = false
            }
            if (!spacer) style.iconLoader(appModel, icon, icon.layoutParams.width)
            root.setOnClickListener {
                if (spacer) return@setOnClickListener
                if (notified && badgeTap != null) badgeTap(appModel) else clickListener(appModel)
            }
            root.setOnLongClickListener {
                if (!spacer && longPressMenu != null) longPressMenu(appModel, bindingAdapterPosition)
                true
            }
        }

        private fun rippleOf(ctx: Context): android.graphics.drawable.Drawable? {
            val tv = android.util.TypedValue()
            return if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true))
                androidx.core.content.ContextCompat.getDrawable(ctx, tv.resourceId) else null
        }

        companion object {
            /** Space between two cards. */
            const val CARD_GAP_DP = 10
            private const val CARD_RED = 0xFF3A1715.toInt()
            private const val CARD_RED_STROKE = 0xFFB3261E.toInt()
            private const val BADGE_COLOR = 0xFFC62828.toInt()
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
