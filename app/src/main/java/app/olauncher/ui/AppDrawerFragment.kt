package app.olauncher.ui

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.text.Spannable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.Recycler
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.DrawerTabsRepository
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.databinding.FragmentAppDrawerBinding
import app.olauncher.helper.DetoxManager
import app.olauncher.helper.FontManager
import app.olauncher.helper.IconChooser
import app.olauncher.helper.MenuEntry
import app.olauncher.helper.showMenuDialog
import app.olauncher.helper.showTextInputDialog
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import app.olauncher.helper.AppNotifications
import app.olauncher.helper.IconPackManager
import app.olauncher.helper.NotificationRepository
import app.olauncher.helper.TestNotifications
import app.olauncher.helper.deletePinnedShortcut
import app.olauncher.helper.dpToPx
import app.olauncher.helper.hideKeyboard
import app.olauncher.helper.isEinkDisplay
import app.olauncher.helper.isSystemAnimationsDisabled
import app.olauncher.helper.openAppInfo
import app.olauncher.helper.openSearch
import app.olauncher.helper.openUrl
import app.olauncher.helper.showKeyboard
import app.olauncher.helper.showToast
import app.olauncher.helper.uninstallApp
import app.olauncher.ui.widgets.HomeWidgetsController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppDrawerFragment : BaseFragment() {

    private lateinit var prefs: Prefs
    private lateinit var adapter: AppDrawerAdapter
    private lateinit var linearLayoutManager: LinearLayoutManager
    private var searchTextView: TextView? = null
    private var cachedIsCjkKeyboard: Boolean? = null

    private var flag = Constants.FLAG_LAUNCH_APP
    private var canRename = false
    private var currentAppList: List<AppModel>? = null
    private var currentPrivateSpaceApps: List<AppModel>? = null
    private var currentPrivateSpaceLocked: Boolean = true
    private var currentPrivateSpaceAvailable: Boolean = false
    private var uiScope = MainScope()
    private var tabs: DrawerTabsController? = null
    /** Whether the adapter's base list holds every app (searching) or only the selected tab's. */
    private var listBuiltForSearch = false
    private var currentHiddenApps: List<AppModel> = emptyList()
    private var notifications: Map<String, AppNotifications> = emptyMap()
    private val hideIndexRunnable = Runnable { _binding?.letterIndex?.isVisible = false }

    /** Keys of the apps of the tab shown, the ones that can be dragged into place or onto a tab. */
    private var draggableKeys: Set<String> = emptySet()
    private lateinit var dragHelper: ItemTouchHelper

    /** The app being dragged, where the finger is, how far it went and whether others moved. */
    private var dragged: AppModel.App? = null
    private var dragPosition = 0
    private var dragX = 0f
    private var dragY = 0f
    private var dragDistance = 0f
    private var dragReordered = false
    private var dropTarget: String? = null
    /** The list changed while dragging: rebuild it once the app is dropped. */
    private var listUpdatePending = false

    private val viewModel: MainViewModel by activityViewModels()
    private var _binding: FragmentAppDrawerBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAppDrawerBinding.inflate(inflater, container, false)
        uiScope = MainScope()
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        arguments?.let {
            flag = it.getInt(Constants.Key.FLAG, Constants.FLAG_LAUNCH_APP)
            canRename = it.getBoolean(Constants.Key.RENAME, false)
        }

        if (flag == Constants.FLAG_LAUNCH_APP) prefs.applyDrawerCardsOnce()
        initViews()
        initSearch()
        initAdapter()
        initObservers()
        initClickListeners()
        initLetterIndex()
        if (flag == Constants.FLAG_LAUNCH_APP) {
            tabs = DrawerTabsController(
                context = requireContext(),
                bar = binding.tabsBar,
                barContainer = binding.tabsScroll,
                searchRow = binding.searchRow,
                allApps = { currentAppList.orEmpty().filterIsInstance<AppModel.App>() },
                hiddenApps = { currentHiddenApps },
                onSelectionChanged = { updateCombinedAppList() },
                hideApp = { hide(it) },
                manage = { manageTabs() },
            ).also { it.render() }
            updateCombinedAppList()
        }
    }

    private fun initViews() {
        if (flag == Constants.FLAG_HIDDEN_APPS)
            binding.search.queryHint = getString(R.string.hidden_apps)
        else if (flag in Constants.FLAG_SET_HOME_APP_1..Constants.FLAG_SET_CALENDAR_APP)
            binding.search.queryHint = "Please select an app"
        try {
            searchTextView = binding.search.findViewById(R.id.search_src_text)
            searchTextView?.gravity = prefs.appLabelAlignment
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun initSearch() {
        // The keyboard never opens by itself: a tap anywhere on the search box opens it
        binding.search.setOnClickListener { binding.search.showKeyboard() }
        searchTextView?.setOnClickListener { binding.search.showKeyboard() }
        binding.search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                if (query?.startsWith("!") == true)
                    requireContext().openUrl(Constants.URL_DUCK_SEARCH + query.replace(" ", "%20"))
                else if (adapter.itemCount == 0)
                    requireContext().openSearch(query?.trim())
                else
                    adapter.launchFirstInList()
                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                try {
                    adapter.allowAutoLaunch = prefs.autoLaunchSingleResult && !isSearchComposing()
                    // Search looks through all apps: rebuild the base list when it was built for the other state
                    if (newText.isNotBlank() != listBuiltForSearch) updateCombinedAppList()
                    adapter.filter.filter(newText)
                    updateLetterIndex()
                    binding.appRename.visibility =
                        if (canRename && newText.isNotBlank()) View.VISIBLE else View.GONE
                    return true
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                return false
            }
        })
    }

    private fun isSearchComposing(): Boolean {
        val text = searchTextView?.text
        if (text !is Spannable) return false
        val start = BaseInputConnection.getComposingSpanStart(text)
        val end = BaseInputConnection.getComposingSpanEnd(text)
        if (start !in 0 until end) return false
        return isCjkKeyboard()
    }

    private fun isCjkKeyboard(): Boolean {
        cachedIsCjkKeyboard?.let { return it }
        val result = try {
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val subtype = imm.currentInputMethodSubtype
            val language = when {
                subtype == null -> ""
                subtype.languageTag.isNotEmpty() -> subtype.languageTag // e.g. "zh-CN", "ja-JP", "en-US"
                else -> subtype.locale // deprecated fallback, e.g. "zh_CN"
            }
            language.startsWith("zh") || language.startsWith("ja") || language.startsWith("ko")
        } catch (e: Exception) {
            false
        }
        cachedIsCjkKeyboard = result
        return result
    }

    private fun initAdapter() {
        adapter = AppDrawerAdapter(
            flag,
            prefs.appLabelAlignment,
            appClickListener = { appModel ->
                if (TestNotifications.isFake(appModel.appPackage))
                    TestNotifications.clearNotifications(requireContext(), appModel.appPackage)
                else
                    viewModel.selectedApp(appModel, flag)
                if (flag == Constants.FLAG_LAUNCH_APP || flag == Constants.FLAG_HIDDEN_APPS)
                    findNavController().popBackStack(R.id.mainFragment, false)
                else
                    findNavController().popBackStack()
            },
            appInfoListener = {
                openAppInfo(
                    requireContext(),
                    it.user,
                    it.appPackage
                )
                findNavController().popBackStack(R.id.mainFragment, false)
            },
            appDeleteListener = { appModel ->
                when (appModel) {
                    is AppModel.PrivateSpaceHeader, is AppModel.ListHeader -> {}
                    is AppModel.PinnedShortcut ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                            requireContext().deletePinnedShortcut(
                                packageName = appModel.appPackage,
                                shortcutIdToDelete = appModel.shortcutId,
                                user = appModel.user,
                            )
                        }

                    is AppModel.App -> requireContext().uninstallApp(appModel.appPackage, appModel.user)
                }
                viewModel.getAppList()
            },
            appHideListener = { appModel, position ->
                if (appModel is AppModel.PinnedShortcut) {
                    requireContext().showToast("Hiding pinned shortcuts is not supported")
                    return@AppDrawerAdapter
                }
                adapter.remove(appModel)

                val newSet = mutableSetOf<String>()
                newSet.addAll(prefs.hiddenApps)
                if (flag == Constants.FLAG_HIDDEN_APPS)
                    newSet.remove(DrawerTabsRepository.key(appModel))
                else
                    newSet.add(DrawerTabsRepository.key(appModel))

                prefs.hiddenApps = newSet
                if (newSet.isEmpty())
                    findNavController().popBackStack()
                if (prefs.firstHide) {
                    binding.search.hideKeyboard()
                    prefs.firstHide = false
                    viewModel.showDialog.postValue(Constants.Dialog.HIDDEN)
                    findNavController().navigate(R.id.action_appListFragment_to_settingsFragment2)
                }
                viewModel.getAppList()
                viewModel.getHiddenApps()
            },
            appRenameListener = { appModel, renameLabel ->
                val identifier = when (appModel) {
                    is AppModel.PinnedShortcut -> appModel.identity
                    is AppModel.App -> DrawerTabsRepository.renameId(appModel.appPackage, appModel.activityClassName)
                    else -> return@AppDrawerAdapter
                }
                prefs.setAppRenameLabel(identifier, renameLabel)
                viewModel.getAppList()
            },
            privateSpaceToggleListener = {
                viewModel.togglePrivateSpaceLock()
            },
            privateSpaceSettingsListener = {
                viewModel.openPrivateSpaceSettings()
                findNavController().popBackStack(R.id.mainFragment, false)
            },
            iconLoader = { appModel, view -> loadIcon(appModel, view) },
            titleStyler = { view -> FontManager.apply(view, prefs.drawerFont, prefs.drawerTextSize) },
            longPressMenu = { appModel, position -> onAppLongPress(appModel, position) },
            badgeTap = { appModel ->
                if (appModel is AppModel.App) NotificationBadge.showNotifications(requireContext(), appModel) {
                    viewModel.selectedApp(appModel, Constants.FLAG_LAUNCH_APP)
                    findNavController().popBackStack(R.id.mainFragment, false)
                }
            },
            badgeCount = { appModel ->
                if (appModel is AppModel.App) notifications[notificationKey(appModel)]?.count ?: 0 else 0
            },
        )

        setupLayout()
        binding.recyclerView.adapter = adapter
        initDrag()
        binding.recyclerView.addOnScrollListener(getRecyclerViewOnScrollListener())
        binding.recyclerView.itemAnimator = null
        if (requireContext().isEinkDisplay())
            binding.recyclerView.overScrollMode = View.OVER_SCROLL_NEVER
        else if (requireContext().isSystemAnimationsDisabled().not())
            binding.recyclerView.layoutAnimation =
                AnimationUtils.loadLayoutAnimation(requireContext(), R.anim.layout_anim_from_bottom)
    }

    /** List or grid (of icons or of cards), per settings; again when they change from the options. */
    private fun setupLayout() {
        // Pulling the list down past its top closes the drawer
        fun onOverScroll(dx: Int, scrollRange: Int) {
            val overScroll = dx - scrollRange
            if (overScroll < -10 && binding.recyclerView.scrollState == RecyclerView.SCROLL_STATE_DRAGGING)
                checkMessageAndExit()
        }
        linearLayoutManager = if (prefs.drawerGrid && flag == Constants.FLAG_LAUNCH_APP) {
            val columns = prefs.drawerColumns
            object : GridLayoutManager(requireContext(), columns) {
                override fun scrollVerticallyBy(dx: Int, recycler: Recycler, state: RecyclerView.State): Int {
                    val scrollRange = super.scrollVerticallyBy(dx, recycler, state)
                    onOverScroll(dx, scrollRange)
                    return scrollRange
                }
            }.apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int = if (adapter.spansRow(position)) columns else 1
                }
            }
        } else object : LinearLayoutManager(requireContext()) {
            override fun scrollVerticallyBy(dx: Int, recycler: Recycler, state: RecyclerView.State): Int {
                val scrollRange = super.scrollVerticallyBy(dx, recycler, state)
                onOverScroll(dx, scrollRange)
                return scrollRange
            }
        }
        adapter.showLabels = prefs.drawerLabels
        if (linearLayoutManager !is GridLayoutManager) adapter.grid = null
        val cards = linearLayoutManager is GridLayoutManager && prefs.drawerCards
        val side = if (cards) (16 - AppDrawerAdapter.GridViewHolder.CARD_GAP_DP / 2).dpToPx() else 0
        binding.recyclerView.setPadding(side, binding.recyclerView.paddingTop, side + indexRoom(), binding.recyclerView.paddingBottom)
        if (linearLayoutManager is GridLayoutManager) applyGridStyle()

        binding.recyclerView.layoutManager = linearLayoutManager
    }

    /** Room on the right for the letter index, when it's always there next to the cards. */
    private fun indexRoom(): Int =
        if (prefs.drawerGrid && prefs.drawerCards && flag == Constants.FLAG_LAUNCH_APP && prefs.letterIndexMode == Constants.LetterIndex.ALWAYS) 14.dpToPx() else 0

    /** The grid's options, from the sliders in the search pill. */
    private fun showDrawerOptions() {
        binding.search.hideKeyboard()
        fun reload() {
            binding.recyclerView.adapter = null
            setupLayout()
            binding.recyclerView.adapter = adapter
            updateCombinedAppList()
        }
        val view = getString(if (prefs.drawerGrid) R.string.ml_drawer_grid else R.string.ml_drawer_list)
        val entries = mutableListOf(
            MenuEntry("${getString(R.string.ml_drawer_view)}: $view") {
                requireContext().showMenuDialog(getString(R.string.ml_drawer_view), listOf(
                    // One grid, in the style it has (the cards, for most): no separate "cards" view to choose
                    MenuEntry(getString(R.string.ml_drawer_grid)) { prefs.drawerGrid = true; reload() },
                    MenuEntry(getString(R.string.ml_drawer_list)) { prefs.drawerGrid = false; reload() },
                ))
            },
        )
        if (prefs.drawerGrid) entries += MenuEntry("${getString(R.string.ml_drawer_columns_short)}: ${prefs.drawerColumns}") {
            requireContext().showMenuDialog(getString(R.string.ml_drawer_columns_short), (3..6).map { n ->
                MenuEntry(n.toString()) { prefs.drawerColumns = n; reload() }
            })
        }
        entries += MenuEntry(getString(R.string.ml_drawer_labels_short), checked = prefs.drawerLabels, onToggle = { v ->
            prefs.drawerLabels = v
            reload()
            true
        })
        if (tabs != null) entries += MenuEntry(getString(R.string.ml_tabs_manage)) { manageTabs() }
        requireContext().showMenuDialog(getString(R.string.ml_drawer_options), entries)
    }

    private var onImagePicked: ((android.net.Uri) -> Unit)? = null
    private val imagePicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        viewModel.keepCurrentScreen = false
        if (uri != null) onImagePicked?.invoke(uri)
        onImagePicked = null
    }

    private fun changeIconEntry(appModel: AppModel.App) = MenuEntry(getString(R.string.ml_change_icon)) {
        IconChooser.show(requireContext(), appModel, uiScope, { callback ->
            onImagePicked = callback
            viewModel.keepCurrentScreen = true
            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }) {
            adapter.refreshBadges()
        }
    }

    /** Pins the app as an icon on the home grid. */
    private fun addToHomeEntry(appModel: AppModel.App) = MenuEntry(getString(R.string.ml_add_to_home)) {
        requireContext().showToast(
            getString(if (HomeWidgetsController.addAppToHome(requireContext(), appModel)) R.string.ml_added_to_home else R.string.ml_already_on_home)
        )
    }

    /** Same long-press menu as on home: the app's notifications on top, then its actions. */
    private fun showAppMenu(appModel: AppModel, position: Int) {
        val ctx = requireContext()
        binding.search.hideKeyboard()
        val entries = mutableListOf<MenuEntry>()
        val hidden = appModel is AppModel.App && flag == Constants.FLAG_LAUNCH_APP && DrawerTabsRepository.key(appModel) in prefs.hiddenApps
        if (appModel is AppModel.App && (hidden || tabs?.showingHidden == true && binding.search.query.isNullOrBlank())) {
            // A hidden app, in the "Hidden" tab or found by the search: bring it back (hidden apps can still be placed on home)
            entries += addToHomeEntry(appModel)
            entries += MenuEntry(getString(R.string.adapter_show)) { unhide(appModel) }
            entries += changeIconEntry(appModel)
            entries += MenuEntry(getString(R.string.ml_app_info)) { adapter.openInfo(appModel) }
            entries += MenuEntry(getString(R.string.delete)) { adapter.delete(appModel) }
            ctx.showMenuDialog(appModel.appLabel, entries + DetoxController.whitelistEntry(ctx, appModel.appPackage), header = AppNotificationsView.create(ctx, appModel))
            return
        }
        if (appModel is AppModel.App) {
            entries += addToHomeEntry(appModel)
            if (!adapter.isHiddenList) {
                tabs?.let { t ->
                    entries += MenuEntry(getString(R.string.ml_move_to_tab)) { t.showMoveToTab(appModel) }
                    entries += MenuEntry(getString(R.string.ml_tabs_manage)) { manageTabs() }
                }
                entries += changeIconEntry(appModel)
                entries += MenuEntry(getString(R.string.rename)) { askRename(appModel) }
            } else entries += changeIconEntry(appModel)
            entries += MenuEntry(getString(if (adapter.isHiddenList) R.string.adapter_show else R.string.adapter_hide)) {
                adapter.toggleHidden(appModel, position)
            }
            entries += MenuEntry(getString(R.string.ml_app_info)) { adapter.openInfo(appModel) }
            entries += MenuEntry(getString(R.string.delete)) { adapter.delete(appModel) }
            ctx.showMenuDialog(appModel.appLabel, entries + DetoxController.whitelistEntry(ctx, appModel.appPackage), header = AppNotificationsView.create(ctx, appModel))
        } else if (appModel is AppModel.PinnedShortcut) {
            entries += MenuEntry(getString(R.string.rename)) { askRename(appModel) }
            entries += MenuEntry(getString(R.string.delete)) { adapter.delete(appModel) }
            ctx.showMenuDialog(appModel.appLabel, entries)
        }
    }

    // ---------------------------------------------------------------- drag and drop

    /**
     * A long press on an app of the tab shown picks it up: dragged among the others it changes
     * their order (saved for the tab), dropped on a tab (or "+") it moves there. Let go without
     * moving, it opens the app's menu as before.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun initDrag() {
        dragHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
            override fun isLongPressDragEnabled() = false

            override fun getMovementFlags(rv: RecyclerView, holder: RecyclerView.ViewHolder): Int {
                val all = ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
                return if (dragged != null) makeMovementFlags(all, 0) else 0
            }

            override fun canDropOver(rv: RecyclerView, current: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                // Every app is listed A-Z: dragged, an app only goes onto a tab
                if (tabs?.canReorder() != true) return false
                val item = adapter.getItem(target.bindingAdapterPosition.takeIf { it >= 0 } ?: return false)
                return item is AppModel.App && DrawerTabsRepository.key(item) in draggableKeys && !item.inNews
            }

            override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
                val a = from.bindingAdapterPosition
                val b = to.bindingAdapterPosition
                if (a < 0 || b < 0) return false
                adapter.moveItem(a, b)
                dragReordered = true
                return true
            }

            override fun onChildDraw(
                c: android.graphics.Canvas, rv: RecyclerView, holder: RecyclerView.ViewHolder,
                dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean,
            ) {
                super.onChildDraw(c, rv, holder, dX, dY, actionState, isCurrentlyActive)
                if (dragged == null || !isCurrentlyActive) return
                dragDistance = maxOf(dragDistance, kotlin.math.hypot(dX, dY))
                dropTarget = tabs?.targetAt(dragX, dragY)
                holder.itemView.alpha = if (dropTarget != null) 0.6f else 1f
            }

            override fun clearView(rv: RecyclerView, holder: RecyclerView.ViewHolder) {
                super.clearView(rv, holder)
                holder.itemView.alpha = 1f
                endDrag()
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
        })
        dragHelper.attachToRecyclerView(binding.recyclerView)
        // Where the finger is, for the drop targets above the grid (the grid gets every move while dragging)
        binding.recyclerView.setOnTouchListener { _, e ->
            dragX = e.rawX
            dragY = e.rawY
            false
        }
        // A long press on the empty part of the grid: "Manage tabs"
        val gestures = android.view.GestureDetector(requireContext(), object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: android.view.MotionEvent) {
                if (tabs == null || !binding.search.query.isNullOrBlank()) return
                val child = binding.recyclerView.findChildViewUnder(e.x, e.y)
                val item = child?.let { binding.recyclerView.getChildAdapterPosition(it) }?.takeIf { it >= 0 }?.let { adapter.getItem(it) }
                if (child == null || item?.appPackage?.isEmpty() == true) {
                    binding.recyclerView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    manageTabs()
                }
            }
        })
        binding.recyclerView.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: android.view.MotionEvent): Boolean {
                if (dragged == null) gestures.onTouchEvent(e)
                return false
            }
        })
    }

    private fun canDrag(appModel: AppModel): Boolean =
        tabs != null && appModel is AppModel.App && !appModel.inNews && binding.search.query.isNullOrBlank() &&
                tabs?.showingHidden != true && DrawerTabsRepository.key(appModel) in draggableKeys

    private fun onAppLongPress(appModel: AppModel, position: Int) {
        val holder = binding.recyclerView.findViewHolderForAdapterPosition(position)
        if (!canDrag(appModel) || holder == null) {
            showAppMenu(appModel, position)
            return
        }
        dragged = appModel as AppModel.App
        dragPosition = position
        dragDistance = 0f
        dragReordered = false
        dropTarget = null
        binding.search.hideKeyboard()
        adapter.beginDrag()
        // The app is drawn above the tabs when dragged up there
        binding.root.clipChildren = false
        tabs?.setDragging(true)
        binding.drawerHeader.text = getString(R.string.ml_tab_drop_hint)
        dragHelper.startDrag(holder)
    }

    private fun endDrag() {
        val app = dragged ?: return
        dragged = null
        tabs?.setDragging(false)
        binding.root.clipChildren = true
        val list = adapter.endDrag()
        val target = dropTarget
        dropTarget = null
        when {
            target != null -> tabs?.drop(app, target)
            dragReordered -> tabs?.saveSelectedOrder(list.filter { it is AppModel.App && DrawerTabsRepository.key(it) in draggableKeys })
            // Let go where it was picked up: the usual menu
            dragDistance < 12.dpToPx() -> showAppMenu(app, dragPosition)
        }
        listUpdatePending = false
        updateCombinedAppList()
    }

    private fun hide(app: AppModel.App) {
        prefs.hiddenApps = prefs.hiddenApps.toMutableSet().apply { add(DrawerTabsRepository.key(app)) }
        requireContext().showToast(getString(R.string.ml_tab_hidden_done, app.appLabel))
        viewModel.getAppList()
        viewModel.getHiddenApps()
    }

    /** "Manage tabs": every tab, shown or not, "All" and "Hidden" included. */
    private fun manageTabs() {
        binding.search.hideKeyboard()
        DrawerTabsManager.show(
            requireContext(),
            apps = currentAppList.orEmpty().filterIsInstance<AppModel.App>(),
            hidden = currentHiddenApps.filterIsInstance<AppModel.App>(),
            onChanged = {
                tabs?.reload()
                updateCombinedAppList()
            },
            onHiddenChanged = {
                viewModel.getAppList()
                viewModel.getHiddenApps()
            },
        )
    }

    private fun unhide(appModel: AppModel) {
        prefs.hiddenApps = prefs.hiddenApps.toMutableSet().apply { remove(DrawerTabsRepository.key(appModel)) }
        viewModel.getAppList()
        viewModel.getHiddenApps()
    }

    private fun askRename(appModel: AppModel) {
        requireContext().showTextInputDialog(
            title = getString(R.string.rename),
            initial = appModel.appLabel,
            hint = appModel.appLabel,
            action = getString(R.string.ml_save),
        ) { label -> adapter.rename(appModel, label) }
    }

    /**
     * Grid cells: icon size from the setting or the cell width, cell height from the rows setting
     * (so that many rows fill the screen) or the content.
     */
    private fun applyGridStyle() {
        val columns = prefs.drawerColumns
        val width = binding.recyclerView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val cellWidth = (width - binding.recyclerView.paddingLeft - binding.recyclerView.paddingRight) / columns
        val cards = prefs.drawerCards
        val iconSize = when {
            prefs.drawerIconSize > 0 -> prefs.drawerIconSize.dpToPx()
            cards -> (if (prefs.drawerLabels) 30 else 34).dpToPx()
            else -> (cellWidth * if (prefs.drawerLabels) 0.62f else 0.74f).toInt().coerceAtLeast(24.dpToPx())
        }
        val rows = prefs.drawerRows
        val height = binding.recyclerView.height
        val gap = if (cards) AppDrawerAdapter.GridViewHolder.CARD_GAP_DP.dpToPx() else 0
        val cellHeight = when {
            rows > 0 && height > 0 -> ((height - binding.recyclerView.paddingTop - binding.recyclerView.paddingBottom) / rows - gap).coerceAtLeast(48.dpToPx())
            // Square cards: as tall as they are wide
            cards && rows <= 0 -> (cellWidth - gap).coerceAtLeast(48.dpToPx())
            else -> 0
        }
        adapter.grid = AppDrawerAdapter.GridStyle(cellHeight, iconSize, prefs.drawerLabels, prefs.iconTiles, { appModel, view, size ->
            loadGridIcon(appModel, view, size)
        }, cards = cards)
        if ((rows > 0 && height == 0) || (cards && binding.recyclerView.width == 0)) {
            // Measure first, then size the cells to the rows
            binding.recyclerView.doOnLayout { if (_binding != null) applyGridStyle() }
            return
        }
        @Suppress("NotifyDataSetChanged")
        adapter.notifyDataSetChanged()
    }

    private fun loadGridIcon(appModel: AppModel, view: ImageView, size: Int) {
        val key = "${appModel.appPackage}|${appModel.user}|${(appModel as? AppModel.App)?.activityClassName}"
        view.tag = key
        val context = requireContext().applicationContext
        val iconPack = prefs.iconPack
        uiScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                IconPackManager.getIcon(
                    context, appModel.appPackage, (appModel as? AppModel.App)?.activityClassName,
                    appModel.user, iconPack, size
                )
            } ?: return@launch
            if (view.tag == key) view.setImageBitmap(bitmap)
        }
    }

    private fun loadIcon(appModel: AppModel, view: TextView) {
        if (!prefs.showIcons) return
        val size = (view.textSize * 1.15f).toInt()
        val key = "${appModel.appPackage}|${appModel.user}|${(appModel as? AppModel.App)?.activityClassName}"
        view.tag = key
        val notified = appModel is AppModel.App && (notifications[notificationKey(appModel)]?.count ?: 0) > 0
        view.compoundDrawablePadding = 14.dpToPx()
        view.setCompoundDrawablesRelative(ColorDrawable(0).apply { setBounds(0, 0, size, size) }, null, null, null)
        val context = requireContext().applicationContext
        val iconPack = prefs.iconPack
        uiScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                IconPackManager.getIcon(
                    context, appModel.appPackage, (appModel as? AppModel.App)?.activityClassName,
                    appModel.user, iconPack, size
                )
            } ?: return@launch
            // The row may have been recycled for another app meanwhile
            if (view.tag == key)
                view.setCompoundDrawablesRelative(NotificationBadge.tint(IconPackManager.toDrawable(context, bitmap, size), notified), null, null, null)
        }
    }

    private fun initObservers() {
        viewModel.firstOpen.observe(viewLifecycleOwner) {
        }
        if (flag == Constants.FLAG_HIDDEN_APPS) {
            viewModel.hiddenApps.observe(viewLifecycleOwner) {
                it?.let {
                    adapter.setAppList(it.toMutableList())
                }
            }
        } else {
            viewModel.appList.observe(viewLifecycleOwner) {
                currentAppList = it
                updateCombinedAppList()
            }
            if (flag == Constants.FLAG_LAUNCH_APP) {
                viewModel.hiddenApps.observe(viewLifecycleOwner) {
                    currentHiddenApps = it.orEmpty()
                    updateCombinedAppList()
                }
                viewModel.getHiddenApps()
                DetoxManager.state.observe(viewLifecycleOwner) { updateCombinedAppList() }
                NotificationRepository.state.observe(viewLifecycleOwner) {
                    notifications = it
                    updateCombinedAppList()
                    adapter.refreshBadges()
                }
                viewModel.privateSpaceAvailable.observe(viewLifecycleOwner) {
                    currentPrivateSpaceAvailable = it
                    updateCombinedAppList()
                }
                viewModel.privateSpaceLocked.observe(viewLifecycleOwner) {
                    currentPrivateSpaceLocked = it
                    updateCombinedAppList()
                }
                viewModel.privateSpaceApps.observe(viewLifecycleOwner) {
                    currentPrivateSpaceApps = it
                    updateCombinedAppList()
                }
            }
        }
    }

    private fun notificationKey(app: AppModel) = NotificationRepository.key(app.appPackage, app.user.toString())

    /**
     * "News": apps with notifications, hidden apps included, most recent first (or most
     * notifications first, per settings). Each one also stays in the A-Z list below.
     */
    private fun newsBlock(apps: List<AppModel>): List<AppModel> {
        if (flag != Constants.FLAG_LAUNCH_APP || notifications.isEmpty()) return emptyList()
        val pool = apps + currentHiddenApps +
                (if (TestNotifications.isEnabled(requireContext())) listOf(TestNotifications.fakeApp(requireContext())) else emptyList()) +
                TestNotifications.fakeApps(requireContext())
        val news = pool.filterIsInstance<AppModel.App>()
            .filter { notifications.containsKey(notificationKey(it)) && DetoxManager.isAllowed(requireContext(), it.appPackage) }
            .distinctBy { notificationKey(it) }
            .sortedWith(
                if (prefs.newsSortByCount)
                    compareByDescending<AppModel.App> { notifications[notificationKey(it)]?.count ?: 0 }
                        .thenByDescending { notifications[notificationKey(it)]?.lastPostTime ?: 0L }
                else compareByDescending<AppModel.App> { notifications[notificationKey(it)]?.lastPostTime ?: 0L }
            )
            .map { it.copy(inNews = true) }
        if (news.isEmpty()) return emptyList()
        return listOf(AppModel.ListHeader(getString(R.string.ml_news))) + news +
                AppModel.ListHeader(getString(R.string.ml_all_apps))
    }

    private fun updateCombinedAppList() {
        val ctx = context ?: return
        if (dragged != null) {
            listUpdatePending = true
            return
        }
        // During detox only allowed apps are listed (picking apps for settings is not affected)
        val detox = flag == Constants.FLAG_LAUNCH_APP && DetoxManager.isActive(ctx)
        val allowed = (currentAppList ?: return).filter { !detox || DetoxManager.isAllowed(ctx, it.appPackage) }
        val searching = !binding.search.query.isNullOrBlank()
        listBuiltForSearch = searching
        // Search looks through every app, the hidden ones included
        val searchPool = if (flag == Constants.FLAG_LAUNCH_APP)
            (allowed + currentHiddenApps).distinctBy { "${it.appPackage}|${it.user}|${(it as? AppModel.App)?.activityClassName}" }
        else allowed
        val apps = (if (searching) searchPool else tabs?.filter(allowed) ?: allowed)
            .filter { !detox || DetoxManager.isAllowed(ctx, it.appPackage) }
        draggableKeys = if (searching) emptySet() else apps.filterIsInstance<AppModel.App>().map { DrawerTabsRepository.key(it) }.toSet()
        // "News" block removed: apps with notifications are shown on home instead
        val combined = apps.toMutableList()

        if (flag == Constants.FLAG_LAUNCH_APP && currentPrivateSpaceAvailable && !detox) {
            combined.add(AppModel.PrivateSpaceHeader(isLocked = currentPrivateSpaceLocked))
            if (!currentPrivateSpaceLocked) {
                currentPrivateSpaceApps?.let { combined.addAll(it) }
            }
        }

        adapter.setAppList(combined)
        adapter.filter.filter(binding.search.query)
        updateLetterIndex()
        updateHeader(apps, searching)
    }

    /** "ALL · A-Z · 142" above the apps of a tab (not while searching or picking an app). */
    private fun updateHeader(apps: List<AppModel>, searching: Boolean) {
        val show = flag == Constants.FLAG_LAUNCH_APP && !searching
        binding.drawerHeader.isVisible = show
        if (!show) return
        val count = apps.count { it is AppModel.App || it is AppModel.PinnedShortcut }
        val custom = tabs?.selectedHasOrder() == true
        val order = getString(if (custom) R.string.ml_order_custom else R.string.ml_order_az)
        binding.drawerHeader.text = getString(R.string.ml_drawer_header, tabs?.selectedName() ?: getString(R.string.ml_all_apps), order, count)
        // Apps dragged into place: a tap here goes back to A-Z
        binding.drawerHeader.setOnClickListener(if (!custom) null else View.OnClickListener {
            requireContext().showMenuDialog(tabs?.selectedName().orEmpty(), listOf(
                MenuEntry(getString(R.string.ml_tab_az)) { tabs?.resetSelectedOrder() }
            ))
        })
        binding.drawerHeader.isClickable = custom
    }

    // ---------------------------------------------------------------- letter index

    private fun initLetterIndex() {
        binding.letterIndex.onLetter = { letter ->
            val position = adapter.appFilteredList.indexOfFirst { app ->
                (app is AppModel.App && !app.inNews || app is AppModel.PinnedShortcut) &&
                        app.appLabel.isNotBlank() && AlphabetIndexView.letterFor(app.appLabel) == letter
            }
            if (position >= 0) linearLayoutManager.scrollToPositionWithOffset(position, 0)
        }
        binding.letterIndex.onTouchStateChanged = { touching ->
            binding.letterIndex.removeCallbacks(hideIndexRunnable)
            if (!touching && prefs.letterIndexMode == Constants.LetterIndex.ON_SCROLL)
                binding.letterIndex.postDelayed(hideIndexRunnable, INDEX_HIDE_DELAY_MS)
        }
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (prefs.letterIndexMode != Constants.LetterIndex.ON_SCROLL || !indexAllowed()) return
                binding.letterIndex.removeCallbacks(hideIndexRunnable)
                if (newState == RecyclerView.SCROLL_STATE_IDLE)
                    binding.letterIndex.postDelayed(hideIndexRunnable, INDEX_HIDE_DELAY_MS)
                else binding.letterIndex.isVisible = true
            }
        })
    }

    /** The index makes sense on the full launch list, not while searching or picking an app. */
    private fun indexAllowed() =
        flag == Constants.FLAG_LAUNCH_APP && binding.search.query.isNullOrBlank() &&
                prefs.letterIndexMode != Constants.LetterIndex.NEVER

    private fun updateLetterIndex() {
        val letters = adapter.appsList
            .filter { (it is AppModel.App && !it.inNews || it is AppModel.PinnedShortcut) && it.appLabel.isNotBlank() }
            .map { AlphabetIndexView.letterFor(it.appLabel) }
            .distinct()
            .sortedWith(compareBy({ it == "#" }, { it }))
        binding.letterIndex.letters = letters
        binding.letterIndex.isVisible =
            indexAllowed() && prefs.letterIndexMode == Constants.LetterIndex.ALWAYS && letters.size > 1
    }

    private fun initClickListeners() {
        binding.drawerOptions.isVisible = flag == Constants.FLAG_LAUNCH_APP
        binding.drawerOptions.setOnClickListener { showDrawerOptions() }
        binding.appRename.setOnClickListener {
            val name = binding.search.query.toString().trim()
            if (name.isEmpty()) {
                requireContext().showToast(getString(R.string.type_a_new_app_name_first))
                binding.search.showKeyboard()
                return@setOnClickListener
            }

            when (flag) {
                Constants.FLAG_SET_HOME_APP_1 -> prefs.appName1 = name
                Constants.FLAG_SET_HOME_APP_2 -> prefs.appName2 = name
                Constants.FLAG_SET_HOME_APP_3 -> prefs.appName3 = name
                Constants.FLAG_SET_HOME_APP_4 -> prefs.appName4 = name
                Constants.FLAG_SET_HOME_APP_5 -> prefs.appName5 = name
                Constants.FLAG_SET_HOME_APP_6 -> prefs.appName6 = name
                Constants.FLAG_SET_HOME_APP_7 -> prefs.appName7 = name
                Constants.FLAG_SET_HOME_APP_8 -> prefs.appName8 = name
            }
            findNavController().popBackStack()
        }
    }

    private fun getRecyclerViewOnScrollListener(): RecyclerView.OnScrollListener {
        return object : RecyclerView.OnScrollListener() {

            var onTop = false

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                when (newState) {

                    RecyclerView.SCROLL_STATE_DRAGGING -> {
                        onTop = !recyclerView.canScrollVertically(-1)
                        if (onTop)
                            binding.search.hideKeyboard()
                    }

                    RecyclerView.SCROLL_STATE_IDLE -> {
                        if (!recyclerView.canScrollVertically(1))
                            binding.search.hideKeyboard()
                        // MinimaLauncher: never open the keyboard by itself
                    }
                }
            }
        }
    }

    private fun checkMessageAndExit() {
        findNavController().popBackStack()
        if (flag == Constants.FLAG_LAUNCH_APP)
            viewModel.checkForMessages.call()
    }

    override fun onStart() {
        super.onStart()
        cachedIsCjkKeyboard = null
        // The keyboard opens with the list only when asked in settings, otherwise on a tap on the search field
        if (flag == Constants.FLAG_LAUNCH_APP && prefs.autoShowKeyboard)
            binding.search.postDelayed({ _binding?.search?.showKeyboard() }, 120)
    }

    override fun onStop() {
        binding.search.hideKeyboard()
        super.onStop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        uiScope.cancel()
        _binding?.letterIndex?.removeCallbacks(hideIndexRunnable)
        searchTextView = null
        _binding = null
    }

    companion object {
        private const val INDEX_HIDE_DELAY_MS = 1500L
    }
}
