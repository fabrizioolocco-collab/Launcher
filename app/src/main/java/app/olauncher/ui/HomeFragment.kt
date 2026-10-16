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
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.databinding.FragmentHomeBinding
import app.olauncher.helper.DetoxManager
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
import app.olauncher.helper.showToast
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
    private lateinit var sections: HomeSectionsController
    private lateinit var widgets: HomeWidgetsController
    private lateinit var bottomArea: HomeWidgetsController
    private lateinit var detox: DetoxController
    private lateinit var search: HomeSearchController

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (::search.isInitialized) search.refresh()
    }

    private var onImagePicked: ((Uri) -> Unit)? = null
    private val imagePicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onImagePicked?.invoke(uri)
        onImagePicked = null
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

        sections = HomeSectionsController(
            context = requireContext(),
            container = binding.sectionsLayout,
            emptyHint = binding.noSectionsHint,
            prefs = prefs,
            scope = uiScope,
            onLaunch = { app -> launchHomeApp(app) },
            bottomAppKeys = { if (::bottomArea.isInitialized) bottomArea.appKeys() else emptySet() },
            pickImage = { callback ->
                onImagePicked = callback
                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
        )
        viewModel.homeApps.value?.let { sections.setApps(it) }

        widgets = HomeWidgetsController(
            activity = requireActivity(),
            grid = binding.widgetsLayout,
            scrollView = binding.homeScroll,
            prefs = prefs,
            scope = uiScope,
            keepScreen = { viewModel.keepCurrentScreen = it },
        )
        widgets.render()

        bottomArea = HomeWidgetsController(
            activity = requireActivity(),
            grid = binding.bottomGrid,
            scrollView = binding.homeScroll,
            prefs = prefs,
            scope = uiScope,
            keepScreen = { viewModel.keepCurrentScreen = it },
            bottom = true,
            onLaunchApp = { app -> launchHomeApp(app) },
            pickImage = { callback ->
                onImagePicked = callback
                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
        )
        viewModel.homeApps.value?.let { bottomArea.setApps(it) }
        bottomArea.render()
        binding.bottomGrid.homeGestures = object : WidgetGridLayout.HomeGestures {
            override fun onEmptyLongPress(col: Int, row: Int) = bottomArea.showAddMenu(col, row)
            override fun onSwipeUp() = showAppList(Constants.FLAG_LAUNCH_APP)
            override fun onSwipeDown() = expandNotificationDrawer(requireContext())
        }

        search = HomeSearchController(
            activity = requireActivity(),
            column = binding.homeColumn,
            bar = binding.searchBar,
            input = binding.searchInput,
            clear = binding.searchClear,
            modeButton = binding.searchMode,
            resultsScroll = binding.searchResultsScroll,
            results = binding.searchResults,
            homeViews = listOf(binding.homeScroll, binding.bottomGrid),
            prefs = prefs,
            scope = uiScope,
            apps = { viewModel.homeApps.value.orEmpty() },
            launchApp = { app -> launchHomeApp(app) },
            requestPermission = { permissionRequest.launch(it) },
            keepScreen = { viewModel.keepCurrentScreen = it },
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

        detox = DetoxController(
            activity = requireActivity(),
            bar = binding.detoxBar,
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
        widgets.refreshIfNeeded()
        widgets.onHomeResumed()
        bottomArea.refreshIfNeeded()
        bottomArea.onHomeResumed()
        detox.onResume()
        search.onHomeResumed()
        if (!prefs.welcomeShown) {
            prefs.welcomeShown = true
            detox.showPermissions()
        }
        viewModel.loadHomeApps()
        viewModel.getHiddenApps()
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
            sections.setApps(it)
            bottomArea.setApps(it)
        }
        viewModel.hiddenApps.observe(viewLifecycleOwner) {
            sections.setHiddenApps(it.orEmpty().filterIsInstance<AppModel.App>())
        }
        NotificationRepository.state.observe(viewLifecycleOwner) {
            sections.setNotifications(it)
            bottomArea.setNotifications(it)
        }
        DetoxManager.state.observe(viewLifecycleOwner) {
            sections.render()
            widgets.render()
                bottomArea.render()
            detox.updateBar()
        }
        DetoxManager.pausedCount.observe(viewLifecycleOwner) { detox.setPausedCount(it) }
        viewModel.showPermissions.observe(viewLifecycleOwner) { detox.showPermissions() }
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
            override fun onSwipeUp() = showAppList(Constants.FLAG_LAUNCH_APP)
            override fun onSwipeDown() = expandNotificationDrawer(requireContext())
            override fun onSwipeLeft() = openSwipeLeftApp()
            override fun onSwipeRight() = openSwipeRightApp()
            override fun onEmptyTap() {
                widgets.endEditing()
                bottomArea.endEditing()
                sections.collapseAll()
            }

            override fun onEmptyLongPress() {
                sections.showHomeMenu(
                    openSettings = {
                        try {
                            findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
                            viewModel.firstOpen(false)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    },
                    addWidget = { widgets.showAddWidgetDialog() },
                    addToBottom = { bottomArea.showAddMenu() },
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
        (binding.bottomAlignSpace.layoutParams as LinearLayout.LayoutParams).weight =
            if (prefs.homeBottomAlignment) 1f else 0f
        binding.bottomAlignSpace.requestLayout()
        sections.render()
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

        sections.reload()
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

    /** Launches an app shown on home; the fake test app "opens" by clearing its notifications. */
    private fun launchHomeApp(app: AppModel.App) {
        if (app.appPackage == TestNotifications.FAKE_PACKAGE) TestNotifications.clearNotifications(requireContext())
        else viewModel.launch(app)
    }

    private fun showLongPressToast() = requireContext().showToast(getString(R.string.long_press_to_select_app))

    override fun onDestroyView() {
        super.onDestroyView()
        uiScope.cancel()
        widgets.release()
        bottomArea.release()
        detox.release()
        _binding = null
    }
}