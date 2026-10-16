package app.olauncher.ui

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.text.Spannable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.Recycler
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.AppModel
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
import app.olauncher.helper.isSystemApp
import app.olauncher.helper.openAppInfo
import app.olauncher.helper.openSearch
import app.olauncher.helper.openUrl
import app.olauncher.helper.showKeyboard
import app.olauncher.helper.showToast
import app.olauncher.helper.uninstall
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
    private var currentHiddenApps: List<AppModel> = emptyList()
    private var notifications: Map<String, AppNotifications> = emptyMap()
    private val hideIndexRunnable = Runnable { _binding?.letterIndex?.isVisible = false }

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

        initViews()
        initSearch()
        initAdapter()
        initObservers()
        initClickListeners()
        initLetterIndex()
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
                if (appModel.appPackage == TestNotifications.FAKE_PACKAGE)
                    TestNotifications.clearNotifications(requireContext())
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

                    is AppModel.App -> {
                        if (appModel.user != Process.myUserHandle()) {
                            openAppInfo(requireContext(), appModel.user, appModel.appPackage)
                        } else if (requireContext().isSystemApp(appModel.appPackage, appModel.user)) {
                            requireContext().showToast(getString(R.string.system_app_cannot_delete))
                            openAppInfo(requireContext(), appModel.user, appModel.appPackage)
                        } else {
                            requireContext().uninstall(appModel.appPackage)
                        }
                    }
                }
                viewModel.getAppList()
            },
            appHideListener = { appModel, position ->
                if (appModel is AppModel.PinnedShortcut) {
                    requireContext().showToast("Hiding pinned shortcuts is not supported")
                    return@AppDrawerAdapter
                }
                adapter.appFilteredList.removeAt(position)
                adapter.notifyItemRemoved(position)
                adapter.appsList.remove(appModel)

                val newSet = mutableSetOf<String>()
                newSet.addAll(prefs.hiddenApps)
                if (flag == Constants.FLAG_HIDDEN_APPS)
                    newSet.remove(appModel.appPackage + "|" + appModel.user.toString())
                else
                    newSet.add(appModel.appPackage + "|" + appModel.user.toString())

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
                    is AppModel.App -> appModel.appPackage
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
            appSectionListener = { appModel ->
                if (appModel is AppModel.App) {
                    binding.search.hideKeyboard()
                    HomeSectionsController.showAddToSectionDialog(requireContext(), appModel)
                }
            },
            iconLoader = { appModel, view -> loadIcon(appModel, view) },
            titleStyler = { view -> FontManager.apply(view, prefs.appFont, prefs.appTextSize) },
            longPressMenu = { appModel, position -> showAppMenu(appModel, position) },
            badgeCount = { appModel ->
                if (appModel is AppModel.App) notifications[notificationKey(appModel)]?.count ?: 0 else 0
            },
        )

        linearLayoutManager = object : LinearLayoutManager(requireContext()) {
            override fun scrollVerticallyBy(
                dx: Int,
                recycler: Recycler,
                state: RecyclerView.State,
            ): Int {
                val scrollRange = super.scrollVerticallyBy(dx, recycler, state)
                val overScroll = dx - scrollRange
                if (overScroll < -10 && binding.recyclerView.scrollState == RecyclerView.SCROLL_STATE_DRAGGING)
                    checkMessageAndExit()
                return scrollRange
            }
        }

        binding.recyclerView.layoutManager = linearLayoutManager
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addOnScrollListener(getRecyclerViewOnScrollListener())
        binding.recyclerView.itemAnimator = null
        if (requireContext().isEinkDisplay())
            binding.recyclerView.overScrollMode = View.OVER_SCROLL_NEVER
        else if (requireContext().isSystemAnimationsDisabled().not())
            binding.recyclerView.layoutAnimation =
                AnimationUtils.loadLayoutAnimation(requireContext(), R.anim.layout_anim_from_bottom)
    }

    private var onImagePicked: ((android.net.Uri) -> Unit)? = null
    private val imagePicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        viewModel.keepCurrentScreen = false
        if (uri != null) onImagePicked?.invoke(uri)
        onImagePicked = null
    }

    /** Same long-press menu as on home: the app's notifications on top, then its actions. */
    private fun showAppMenu(appModel: AppModel, position: Int) {
        val ctx = requireContext()
        binding.search.hideKeyboard()
        val entries = mutableListOf<MenuEntry>()
        if (appModel is AppModel.App) {
            if (!adapter.isHiddenList) {
                entries += MenuEntry(getString(R.string.ml_add_to_section)) { adapter.addToSection(appModel) }
                entries += MenuEntry(getString(R.string.ml_change_icon)) {
                    IconChooser.show(ctx, appModel, uiScope, { callback ->
                        onImagePicked = callback
                        viewModel.keepCurrentScreen = true
                        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) {
                        adapter.refreshBadges()
                    }
                }
                entries += MenuEntry(getString(R.string.rename)) { askRename(appModel) }
            }
            entries += MenuEntry(getString(if (adapter.isHiddenList) R.string.adapter_show else R.string.adapter_hide)) {
                adapter.toggleHidden(appModel, position)
            }
            entries += MenuEntry(getString(R.string.ml_app_info)) { adapter.openInfo(appModel) }
            entries += MenuEntry(getString(R.string.delete)) { adapter.delete(appModel) }
            ctx.showMenuDialog(appModel.appLabel, entries, header = AppNotificationsView.create(ctx, appModel))
        } else if (appModel is AppModel.PinnedShortcut) {
            entries += MenuEntry(getString(R.string.rename)) { askRename(appModel) }
            entries += MenuEntry(getString(R.string.delete)) { adapter.delete(appModel) }
            ctx.showMenuDialog(appModel.appLabel, entries)
        }
    }

    private fun askRename(appModel: AppModel) {
        requireContext().showTextInputDialog(
            title = getString(R.string.rename),
            initial = appModel.appLabel,
            hint = appModel.appLabel,
            action = getString(R.string.ml_save),
        ) { label -> adapter.rename(appModel, label) }
    }

    private fun loadIcon(appModel: AppModel, view: TextView) {
        if (!prefs.showIcons) return
        val size = (view.textSize * 1.15f).toInt()
        val key = "${appModel.appPackage}|${appModel.user}|${(appModel as? AppModel.App)?.activityClassName}"
        view.tag = key
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
                view.setCompoundDrawablesRelative(IconPackManager.toDrawable(context, bitmap, size), null, null, null)
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
                (if (TestNotifications.isEnabled(requireContext())) listOf(TestNotifications.fakeApp(requireContext())) else emptyList())
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
        // During detox only allowed apps are listed (picking apps for settings is not affected)
        val detox = flag == Constants.FLAG_LAUNCH_APP && DetoxManager.isActive(ctx)
        val apps = (currentAppList ?: return).filter { !detox || DetoxManager.isAllowed(ctx, it.appPackage) }
        val combined = (newsBlock(apps) + apps).toMutableList()

        if (flag == Constants.FLAG_LAUNCH_APP && currentPrivateSpaceAvailable && !detox) {
            combined.add(AppModel.PrivateSpaceHeader(isLocked = currentPrivateSpaceLocked))
            if (!currentPrivateSpaceLocked) {
                currentPrivateSpaceApps?.let { combined.addAll(it) }
            }
        }

        adapter.setAppList(combined)
        adapter.filter.filter(binding.search.query)
        updateLetterIndex()
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
        // MinimaLauncher: the keyboard opens only when the search field is tapped
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
