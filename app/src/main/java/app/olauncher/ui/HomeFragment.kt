package app.olauncher.ui

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.DrawerTabsRepository
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.databinding.FragmentHomeBinding
import app.olauncher.helper.DetoxManager
import app.olauncher.helper.SwipeActions
import app.olauncher.helper.FolderShortcuts
import app.olauncher.helper.AppRename
import app.olauncher.helper.ParkingShortcut
import app.olauncher.helper.MenuEntry
import app.olauncher.helper.showMenuDialog
import app.olauncher.helper.showTextInputDialog
import app.olauncher.helper.NotificationRepository
import app.olauncher.helper.TestNotifications
import app.olauncher.helper.appUsagePermissionGranted
import app.olauncher.helper.dpToPx
import app.olauncher.helper.expandNotificationDrawer
import app.olauncher.helper.getChangedAppTheme
import app.olauncher.helper.getUserHandleFromString
import app.olauncher.helper.openAlarmApp
import app.olauncher.helper.openCalendar
import app.olauncher.helper.openCameraApp
import app.olauncher.helper.openDialerApp
import app.olauncher.helper.setPlainWallpaperByTheme
import app.olauncher.helper.IconChooser
import app.olauncher.helper.openAppInfo
import app.olauncher.helper.showToast
import app.olauncher.helper.uninstallEntry
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import app.olauncher.ui.widgets.HomeWidgetsController
import app.olauncher.ui.widgets.WidgetGridLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeFragment : BaseFragment(), View.OnClickListener, View.OnLongClickListener {

    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel
    private lateinit var deviceManager: DevicePolicyManager

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private var uiScope = MainScope()
    private lateinit var news: HomeNewsController
    private lateinit var dock: DockController
    private lateinit var widgets: HomeWidgetsController
    private lateinit var detox: DetoxController
    private lateinit var detoxPage: DetoxPage
    private lateinit var search: HomeSearchController

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (::search.isInitialized) search.refresh()
    }

    private var onImagePicked: ((Uri) -> Unit)? = null
    private val imagePicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onImagePicked?.invoke(uri)
        onImagePicked = null
    }

    private var onFolderPicked: ((String) -> Unit)? = null
    private val folderPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringExtra(FileExplorerActivity.EXTRA_LOCAL_PATH)?.let { onFolderPicked?.invoke(it) }
        onFolderPicked = null
    }

    private var onFilePicked: ((Uri) -> Unit)? = null
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onFilePicked?.invoke(uri)
        onFilePicked = null
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        uiScope = MainScope()
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        viewModel = activity?.run {
            ViewModelProvider(this)[MainViewModel::class.java]
        } ?: throw Exception("Invalid Activity")

        deviceManager = context?.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

        // The main grid: widgets, app icons and the news strip share its cells
        widgets = HomeWidgetsController(
            activity = requireActivity(),
            grid = binding.widgetsLayout,
            free = binding.widgetsFree,
            scrollView = binding.homeScroll,
            prefs = prefs,
            scope = uiScope,
            keepScreen = { viewModel.keepCurrentScreen = it },
            onLaunchApp = { app -> launchHomeApp(app) },
            pickImage = { callback ->
                onImagePicked = callback
                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
        )
        viewModel.homeApps.value?.let { widgets.setApps(it) }
        widgets.render()

        dock = DockController(
            context = requireContext(),
            container = binding.dock,
            prefs = prefs,
            scope = uiScope,
            onLaunch = { app -> launchHomeApp(app) },
            pickImage = { callback ->
                onImagePicked = callback
                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onChanged = { news.render() },
        )
        viewModel.homeApps.value?.let { dock.setApps(it) }

        news = HomeNewsController(
            context = requireContext(),
            grid = widgets,
            emptyHint = binding.emptyHomeHint,
            prefs = prefs,
            scope = uiScope,
            gridAppKeys = { widgets.appKeys() + dock.appKeys() },
            dock = dock,
            pickImage = { callback ->
                onImagePicked = callback
                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
        )
        viewModel.homeApps.value?.let { news.setApps(it) }

        // Swipe actions on home apps (set from their long-press menu)
        binding.homeScroll.appSwipe = object : HomeScrollView.AppSwipeHandler {
            override fun hasAction(app: AppModel.App, up: Boolean) = SwipeActions.get(requireContext(), app, up) != null
            override fun run(app: AppModel.App, up: Boolean) {
                SwipeActions.run(requireContext(), app, up)
            }
        }
        SwipeActions.folderPicker = { callback -> pickFolder(callback) }
        SwipeActions.onFoldersChanged = { viewModel.loadHomeApps() }
        AppRename.onRenamed = {
            viewModel.loadHomeApps()
            viewModel.getAppList()
        }
        SwipeActions.filePicker = { callback ->
            onFilePicked = callback
            filePicker.launch(arrayOf("*/*"))
        }

        search = HomeSearchController(
            activity = requireActivity(),
            column = binding.homeColumn,
            bar = binding.searchRow,
            input = binding.searchInput,
            clear = binding.searchClear,
            webButton = binding.searchWeb,
            bookmarksButton = binding.bookmarksButton,
            appsButton = binding.appsButton,
            resultsScroll = binding.searchResultsScroll,
            results = binding.searchResults,
            homeViews = listOf(binding.homeScroll, binding.dockSlot, binding.detoxButton),
            prefs = prefs,
            scope = uiScope,
            apps = {
                val hidden = prefs.hiddenApps
                viewModel.homeApps.value.orEmpty().filter { DrawerTabsRepository.key(it) !in hidden }
            },
            launchApp = { app -> launchHomeApp(app) },
            appMenu = { app, onChanged -> showSearchAppMenu(app, onChanged) },
            requestPermission = { permissionRequest.launch(it) },
            keepScreen = { viewModel.keepCurrentScreen = it },
            openAppList = { showAppList(Constants.FLAG_LAUNCH_APP) },
        )
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (search.isActive) search.close()
            }
        })
        // Keep the search bar above the keyboard
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.homeColumn) { v, insets ->
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, ime)
            insets
        }

        detoxPage = DetoxPage(
            page = binding.detoxPage,
            prefs = prefs,
            scope = uiScope,
            onLaunch = { app -> launchHomeApp(app) },
            onEnd = { detox.openSessionMenu() },
        )
        viewModel.homeApps.value?.let { detoxPage.setApps(it) }
        detox = DetoxController(
            activity = requireActivity(),
            page = detoxPage,
            onPageShown = { if (search.isActive) search.close() },
            setDefaultLauncher = { viewModel.resetLauncherLiveData.call() },
            keepScreen = { viewModel.keepCurrentScreen = it },
        )

        initObservers()
        setHomeAlignment(prefs.homeAlignment)
        initSwipeTouchListener()
        initClickListeners()
    }

    override fun onStart() {
        super.onStart()
        HomeWidgetsController.startListening(requireContext())
    }

    override fun onPause() {
        detox.onPause()
        super.onPause()
    }

    override fun onStop() {
        search.onHomeStopped()
        HomeWidgetsController.stopListening(requireContext())
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        populateHomeScreen(false)
        widgets.reloadIfChanged()
        widgets.refreshIfNeeded()
        widgets.onHomeResumed()
        detox.onResume()
        search.onHomeResumed()
        if (!prefs.welcomeShown) {
            prefs.welcomeShown = true
            detox.showPermissions()
        }
        viewModel.loadHomeApps()
        binding.notificationAccessHint.isVisible = !NotificationRepository.isAccessGranted(requireContext())
        viewModel.isOlauncherDefault()
        // The battery lives in the system status bar: show it once by default (can be turned off in Settings)
        if (!prefs.statusBarDefaultApplied) {
            prefs.statusBarDefaultApplied = true
            prefs.showStatusBar = true
        }
        if (prefs.showStatusBar) showStatusBar()
        else hideStatusBar()
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.lock -> {}
            // Home button for recents feature disabled
            // R.id.recents -> {}
            R.id.clock -> openClockApp()
            R.id.date -> openCalendarApp()
            R.id.setDefaultLauncher -> viewModel.resetLauncherLiveData.call()
            R.id.tvScreenTime -> openScreenTimeDigitalWellbeing()
        }
    }

    private fun openClockApp() {
        if (prefs.clockAppPackage.isBlank())
            openAlarmApp(requireContext())
        else
            launchApp(
                "Clock",
                prefs.clockAppPackage,
                prefs.clockAppClassName,
                prefs.clockAppUser
            )
    }

    private fun openCalendarApp() {
        if (prefs.calendarAppPackage.isBlank())
            openCalendar(requireContext())
        else
            launchApp(
                "Calendar",
                prefs.calendarAppPackage,
                prefs.calendarAppClassName,
                prefs.calendarAppUser
            )
    }

    override fun onLongClick(view: View): Boolean {
        when (view.id) {
            R.id.clock -> {
                showAppList(Constants.FLAG_SET_CLOCK_APP)
                prefs.clockAppPackage = ""
                prefs.clockAppClassName = ""
                prefs.clockAppUser = ""
            }

            R.id.date -> {
                showAppList(Constants.FLAG_SET_CALENDAR_APP)
                prefs.calendarAppPackage = ""
                prefs.calendarAppClassName = ""
                prefs.calendarAppUser = ""
            }

            R.id.tvScreenTime -> {
                showAppList(Constants.FLAG_SET_SCREEN_TIME_APP)
                prefs.screenTimeAppPackage = ""
                prefs.screenTimeAppClassName = ""
                prefs.screenTimeAppUser = ""
            }

            R.id.setDefaultLauncher -> {
                prefs.hideSetDefaultLauncher = true
                binding.setDefaultLauncher.visibility = View.GONE
                if (viewModel.isOlauncherDefault.value != true) {
                    requireContext().showToast(R.string.set_as_default_launcher)
                    findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
                }
            }
        }
        return true
    }

    private fun initObservers() {
        if (prefs.firstSettingsOpen) {
            binding.firstRunTips.visibility = View.VISIBLE
            binding.setDefaultLauncher.visibility = View.GONE
        } else binding.firstRunTips.visibility = View.GONE

        viewModel.refreshHome.observe(viewLifecycleOwner) {
            populateHomeScreen(it)
        }
        viewModel.homeApps.observe(viewLifecycleOwner) {
            widgets.setApps(it)
            dock.setApps(it)
            news.setApps(it)
            detoxPage.setApps(it)
        }
        NotificationRepository.state.observe(viewLifecycleOwner) {
            widgets.setNotifications(it)
            dock.setNotifications(it)
            news.setNotifications(it)
            detoxPage.setNotifications(it)
        }
        DetoxManager.state.observe(viewLifecycleOwner) {
            widgets.render()
            dock.render()
            news.render()
            detoxPage.refreshApps()
            detox.updateBar()
        }
        DetoxManager.pausedCount.observe(viewLifecycleOwner) { detox.setPausedCount(it) }
        viewModel.showPermissions.observe(viewLifecycleOwner) { detox.showPermissions() }
        viewModel.homePressed.observe(viewLifecycleOwner) { search.onHomePressed() }
        viewModel.isOlauncherDefault.observe(viewLifecycleOwner, Observer {
            if (it != true) {
                if (prefs.dailyWallpaper && prefs.appTheme == AppCompatDelegate.MODE_NIGHT_YES) {
                    prefs.dailyWallpaper = false
                    viewModel.cancelWallpaperWorker()
                }
                prefs.homeBottomAlignment = false
                setHomeAlignment()
            }
            if (binding.firstRunTips.isVisible) return@Observer
            binding.setDefaultLauncher.isVisible = it.not() && prefs.hideSetDefaultLauncher.not()
        })
        viewModel.homeAppAlignment.observe(viewLifecycleOwner) {
            setHomeAlignment(it)
        }
        viewModel.toggleDateTime.observe(viewLifecycleOwner) {
            populateDateTime()
        }
        viewModel.screenTimeValue.observe(viewLifecycleOwner) {
            it?.let { binding.tvScreenTime.text = it }
        }
        // Home button for recents feature disabled
        // viewModel.showRecentApps.observe(viewLifecycleOwner) {
        //     binding.recents.performClick()
        // }
    }

    private fun initSwipeTouchListener() {
        binding.homeScroll.callbacks = object : HomeScrollView.GestureCallbacks {
            // The search page (over the blurred wallpaper), or the app list, per settings
            override fun onSwipeUp() = if (prefs.swipeUpSearch) search.openWithKeyboard() else showAppList(Constants.FLAG_LAUNCH_APP)
            override fun onSwipeDown() = expandNotificationDrawer(requireContext())
            override fun onSwipeLeft() = openSwipeLeftApp()
            override fun onSwipeRight() = openSwipeRightApp()
            override fun onEmptyTap() = widgets.endEditing()

            override fun onEmptyLongPress(rawX: Float, rawY: Float) {
                // New items land on the pressed cell when they fit there
                val cell = binding.widgetsLayout.cellAt(rawX, rawY)
                news.showHomeMenu(
                    openSettings = {
                        try {
                            findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
                            viewModel.firstOpen(false)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    },
                    addApp = {
                        widgets.setPreferredCell(cell?.first, cell?.second)
                        widgets.showAddAppDialog()
                    },
                    addWidget = {
                        widgets.setPreferredCell(cell?.first, cell?.second)
                        widgets.showAddWidgetDialog()
                    },
                    addFolder = { createFolderShortcut() },
                    parking = { showParkingMenu() },
                    detox = { detox.open() },
                )
            }

            override fun onEmptyDoubleTap() {
                if (!prefs.lockModeOn) return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    binding.lock.performClick()
                else
                    lockPhone()
            }
        }
    }

    private fun initClickListeners() {
        binding.lock.setOnClickListener(this)
        binding.detoxButton.setOnClickListener { detox.open() }
        // Home button for recents feature disabled
        // binding.recents.setOnClickListener(this)
        binding.clock.setOnClickListener(this)
        binding.date.setOnClickListener(this)
        binding.clock.setOnLongClickListener(this)
        binding.date.setOnLongClickListener(this)
        binding.setDefaultLauncher.setOnClickListener(this)
        binding.setDefaultLauncher.setOnLongClickListener(this)
        binding.tvScreenTime.setOnClickListener(this)
        binding.tvScreenTime.setOnLongClickListener(this)
        binding.notificationAccessHint.setOnClickListener {
            NotificationRepository.openAccessSettings(requireContext())
        }

    }

    private fun setHomeAlignment(horizontalGravity: Int = prefs.homeAlignment) {
        binding.dateTimeLayout.gravity = horizontalGravity
    }

    private fun populateDateTime() {
        binding.dateTimeLayout.isVisible = prefs.dateTimeVisibility != Constants.DateTime.OFF
        binding.clock.isVisible = Constants.DateTime.isTimeVisible(prefs.dateTimeVisibility)
        binding.date.isVisible = Constants.DateTime.isDateVisible(prefs.dateTimeVisibility)
        // The date sits on the right of the clock, or at the start when the clock is hidden
        (binding.dateColumn.layoutParams as ViewGroup.MarginLayoutParams).marginStart =
            if (binding.clock.isVisible) 16.dpToPx() else 0

        // "ven 16 ott · W42": ISO week (starts on Monday, week 1 holds the first Thursday)
        val dateFormat = SimpleDateFormat("EEE d MMM", Locale.getDefault())
        val week = java.util.Calendar.getInstance().apply {
            firstDayOfWeek = java.util.Calendar.MONDAY
            minimalDaysInFirstWeek = 4
        }.get(java.util.Calendar.WEEK_OF_YEAR)
        binding.date.text = getString(R.string.ml_date_week, dateFormat.format(Date()).replace(".", ""), week)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun populateScreenTime() {
        if (requireContext().appUsagePermissionGranted().not()) return
        viewModel.getTodaysScreenTime()
        binding.tvScreenTime.visibility = View.VISIBLE
    }

    private fun populateHomeScreen(@Suppress("UNUSED_PARAMETER") appCountUpdated: Boolean) {
        populateDateTime()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            populateScreenTime()

        news.render()
    }

    private fun launchAppOrShortcut(
        appName: String,
        packageName: String,
        activityClassName: String?,
        shortcutId: String?,
        isShortcut: Boolean,
        userString: String,
        fallback: (() -> Unit)? = null,
    ) {
        if (appName.isEmpty()) {
            showLongPressToast()
            return
        }
        if (isShortcut && !shortcutId.isNullOrEmpty()) {
            launchShortcut(
                packageName = packageName,
                shortcutId = shortcutId,
                shortcutLabel = appName,
                userString = userString
            )
        } else if (packageName.isNotEmpty()) {
            launchApp(
                appName = appName,
                packageName = packageName,
                activityClassName = activityClassName,
                userString = userString
            )
        } else {
            fallback?.invoke()
        }
    }

    private fun launchShortcut(shortcutId: String, packageName: String, shortcutLabel: String, userString: String) {
        viewModel.selectedApp(
            AppModel.PinnedShortcut(
                shortcutId = shortcutId,
                appLabel = shortcutLabel,
                user = getUserHandleFromString(requireContext(), userString),
                key = null,
                appPackage = packageName,
                isNew = false,
            ),
            Constants.FLAG_LAUNCH_APP
        )
    }

    private fun launchApp(appName: String, packageName: String, activityClassName: String?, userString: String) {
        viewModel.selectedApp(
            AppModel.App(
                appLabel = appName,
                key = null,
                appPackage = packageName,
                activityClassName = activityClassName,
                isNew = false,
                user = getUserHandleFromString(requireContext(), userString)
            ),
            Constants.FLAG_LAUNCH_APP
        )
    }

    private fun openSwipeRightApp() {
        if (!prefs.swipeRightEnabled) return
        launchAppOrShortcut(
            appName = prefs.appNameSwipeRight,
            packageName = prefs.appPackageSwipeRight,
            activityClassName = prefs.appActivityClassNameRight,
            shortcutId = prefs.shortcutIdSwipeRight,
            isShortcut = prefs.isShortcutSwipeRight,
            userString = prefs.appUserSwipeRight,
            fallback = { openDialerApp(requireContext()) }
        )
    }

    private fun openSwipeLeftApp() {
        if (!prefs.swipeLeftEnabled) return
        launchAppOrShortcut(
            appName = prefs.appNameSwipeLeft,
            packageName = prefs.appPackageSwipeLeft,
            activityClassName = prefs.appActivityClassNameSwipeLeft,
            shortcutId = prefs.shortcutIdSwipeLeft,
            isShortcut = prefs.isShortcutSwipeLeft,
            userString = prefs.appUserSwipeLeft,
            fallback = { openCameraApp(requireContext()) }
        )
    }

    private fun showAppList(flag: Int, rename: Boolean = false, includeHiddenApps: Boolean = false) {
        viewModel.getAppList(includeHiddenApps)
        try {
            findNavController().navigate(
                R.id.action_mainFragment_to_appListFragment,
                bundleOf(
                    Constants.Key.FLAG to flag,
                    Constants.Key.RENAME to rename
                )
            )
        } catch (e: Exception) {
            findNavController().navigate(
                R.id.appListFragment,
                bundleOf(
                    Constants.Key.FLAG to flag,
                    Constants.Key.RENAME to rename
                )
            )
            e.printStackTrace()
        }
    }

    private fun lockPhone() {
        requireActivity().runOnUiThread {
            try {
                deviceManager.lockNow()
            } catch (e: SecurityException) {
                requireContext().showToast(getString(R.string.please_turn_on_double_tap_to_unlock), Toast.LENGTH_LONG)
                findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
            } catch (e: Exception) {
                requireContext().showToast(getString(R.string.launcher_failed_to_lock_device), Toast.LENGTH_LONG)
                prefs.lockModeOn = false
            }
        }
    }

    private fun showStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireActivity().window.insetsController?.show(WindowInsets.Type.statusBars())
        else
            @Suppress("DEPRECATION", "InlinedApi")
            requireActivity().window.decorView.apply {
                systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            }
    }

    private fun hideStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireActivity().window.insetsController?.hide(WindowInsets.Type.statusBars())
        else {
            @Suppress("DEPRECATION")
            requireActivity().window.decorView.apply {
                systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE or View.SYSTEM_UI_FLAG_FULLSCREEN
            }
        }
    }

    private fun changeAppTheme() {
        if (prefs.dailyWallpaper.not()) return
        val changedAppTheme = getChangedAppTheme(requireContext(), prefs.appTheme)
        prefs.appTheme = changedAppTheme
        if (prefs.dailyWallpaper) {
            setPlainWallpaperByTheme(requireContext(), changedAppTheme)
            viewModel.setWallpaperWorker()
        }
        requireActivity().recreate()
    }

    private fun openScreenTimeDigitalWellbeing() {
        if (prefs.screenTimeAppPackage.isNotBlank()) {
            launchApp(
                "Screen Time",
                prefs.screenTimeAppPackage,
                prefs.screenTimeAppClassName,
                prefs.screenTimeAppUser
            )
            return
        }
        val intent = Intent()
        try {
            intent.setClassName(
                Constants.DIGITAL_WELLBEING_PACKAGE_NAME,
                Constants.DIGITAL_WELLBEING_ACTIVITY
            )
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                intent.setClassName(
                    Constants.DIGITAL_WELLBEING_SAMSUNG_PACKAGE_NAME,
                    Constants.DIGITAL_WELLBEING_SAMSUNG_ACTIVITY
                )
                startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /** Home menu > New folder: pick a folder, name it, choose what opens it; it lands on the home grid. */
    private fun createFolderShortcut() {
        pickFolder { path ->
            val ctx = requireContext()
            ctx.showTextInputDialog(getString(R.string.ml_folder_new), FileExplorerActivity.folderName(ctx, path), getString(R.string.ml_explorer_folder_name), getString(R.string.ml_save)) { name ->
                fun add(inExplorer: Boolean) {
                    val folder = FolderShortcuts.add(ctx, path, name, inExplorer)
                    widgets.addApp(FolderShortcuts.toApp(folder))
                    viewModel.loadHomeApps()
                    news.render()
                }
                // A OneDrive folder opens only in the built-in explorer: nothing to choose
                if (FileExplorerActivity.isCloudPath(path)) return@showTextInputDialog add(true)
                ctx.showMenuDialog(getString(R.string.ml_folder_open_with), listOf(true, false).map { inExplorer ->
                    MenuEntry(getString(if (inExplorer) R.string.ml_explorer else R.string.ml_file_manager)) { add(inExplorer) }
                })
            }
        }
    }

    /** Home menu > Parking: save or open the position, and put the button on home. */
    private fun showParkingMenu() {
        val ctx = requireContext()
        val entries = ParkingShortcut.menuEntries(requireActivity()) +
                MenuEntry(getString(R.string.ml_parking_add_button)) {
                    ParkingShortcut.setEnabled(ctx, true)
                    if (!widgets.addApp(ParkingShortcut.app(ctx))) ctx.showToast(getString(R.string.ml_parking_already))
                    viewModel.loadHomeApps()
                }
        ctx.showMenuDialog(ParkingShortcut.describe(ctx), entries)
    }

    /** Our own folder picker (the system one refuses Download and the storage root). */
    private fun pickFolder(onPicked: (String) -> Unit) {
        onFolderPicked = onPicked
        folderPicker.launch(Intent(requireContext(), FolderPickerActivity::class.java).putExtra(FileExplorerActivity.EXTRA_ALLOW_CLOUD, true))
    }

    /**
     * Long press on an app found by the search: the app list's menu (add to home or dock, change
     * icon, rename, hide, info, uninstall, allowed in detox), its notifications on top.
     */
    private fun showSearchAppMenu(app: AppModel.App, onChanged: () -> Unit) {
        val ctx = requireContext()
        val entries = mutableListOf<MenuEntry>()
        entries += MenuEntry(getString(R.string.ml_add_to_home)) {
            val added = HomeWidgetsController.addAppToHome(ctx, app)
            ctx.showToast(getString(if (added) R.string.ml_added_to_home else R.string.ml_already_on_home))
            if (added) widgets.reloadIfChanged()
        }
        if (!dock.contains(app) && !dock.isFull()) entries += MenuEntry(getString(R.string.ml_dock_add_app)) { dock.add(app) }
        entries += MenuEntry(getString(R.string.ml_change_icon)) {
            IconChooser.show(ctx, app, uiScope, { callback ->
                onImagePicked = callback
                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { onChanged() }
        }
        AppRename.menuEntry(ctx, app)?.let { entries += it }
        entries += MenuEntry(getString(R.string.adapter_hide)) {
            prefs.hiddenApps = prefs.hiddenApps.toMutableSet().apply { add(DrawerTabsRepository.key(app)) }
            ctx.showToast(getString(R.string.ml_tab_hidden_done, app.appLabel))
            viewModel.getAppList()
            onChanged()
        }
        entries += MenuEntry(getString(R.string.ml_app_info)) { openAppInfo(ctx, app.user, app.appPackage) }
        ctx.uninstallEntry(app.appPackage, app.user)?.let { entries += it }
        ctx.showMenuDialog(
            app.appLabel,
            entries + DetoxController.whitelistEntry(ctx, app.appPackage),
            header = AppNotificationsView.create(ctx, app),
        )
    }

    /** Launches an app shown on home; the fake test app "opens" by clearing its notifications. */
    private fun launchHomeApp(app: AppModel.App) {
        if (FolderShortcuts.isFolder(app.appPackage)) FolderShortcuts.open(requireContext(), app.appPackage)
        else if (ParkingShortcut.isParking(app.appPackage)) ParkingShortcut.save(requireActivity())
        else if (TestNotifications.isFake(app.appPackage)) TestNotifications.clearNotifications(requireContext(), app.appPackage)
        else viewModel.launch(app)
    }

    private fun showLongPressToast() = requireContext().showToast(getString(R.string.long_press_to_select_app))

    override fun onDestroyView() {
        // The search page dims and blurs the window: never leave it so
        if (::search.isInitialized) search.close()
        super.onDestroyView()
        uiScope.cancel()
        widgets.release()
        detox.release()
        _binding = null
    }
}