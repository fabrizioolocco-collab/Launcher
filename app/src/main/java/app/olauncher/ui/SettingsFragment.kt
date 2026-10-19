package app.olauncher.ui

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import app.olauncher.BuildConfig
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.databinding.DialogTextSizeBinding
import app.olauncher.databinding.FragmentSettingsBinding
import app.olauncher.helper.appUsagePermissionGranted
import app.olauncher.helper.createDialog
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.hideStatusBar
import app.olauncher.helper.isAccessServiceEnabled
import app.olauncher.helper.isDarkThemeOn
import app.olauncher.helper.isEinkDisplay
import app.olauncher.helper.FontManager
import app.olauncher.helper.IconPackManager
import app.olauncher.helper.NotificationRepository
import app.olauncher.helper.TestNotifications
import app.olauncher.helper.DetoxManager
import app.olauncher.helper.getAppsList
import app.olauncher.helper.showMultiSelectDialog
import app.olauncher.helper.MenuEntry
import app.olauncher.helper.showMenuDialog
import app.olauncher.helper.showTextInputDialog
import app.olauncher.helper.showColorPickerDialog
import app.olauncher.data.AppModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.olauncher.helper.copyToClipboard
import app.olauncher.helper.showConfirmDialog
import app.olauncher.ui.widgets.WidgetLog
import app.olauncher.helper.isOlauncherDefault
import app.olauncher.helper.isTablet
import app.olauncher.helper.openAppInfo
import app.olauncher.helper.openUrl
import app.olauncher.helper.rateApp
import app.olauncher.helper.setPlainWallpaper
import app.olauncher.helper.shareApp
import app.olauncher.helper.OlDialog
import app.olauncher.helper.showPopupMenu
import app.olauncher.helper.showStatusBar
import app.olauncher.helper.showToast
import app.olauncher.listener.DeviceAdmin

class SettingsFragment : BaseFragment(), View.OnClickListener, View.OnLongClickListener {

    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel
    private lateinit var deviceManager: DevicePolicyManager
    private lateinit var componentName: ComponentName

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val showPentastic = System.currentTimeMillis() % 2 == 0L
    private var dialog: OlDialog? = null

    private var pendingFontTarget: ((String) -> Unit)? = null
    private val fontPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.keepCurrentScreen = false
        val target = pendingFontTarget
        pendingFontTarget = null
        if (uri == null || target == null) return@registerForActivityResult
        val ctx = requireContext().applicationContext
        val id = try {
            FontManager.importFont(ctx, uri)
        } catch (e: Exception) {
            null
        }
        if (id == null) {
            ctx.showToast(getString(R.string.ml_font_error))
            return@registerForActivityResult
        }
        target(id)
        if (_binding != null) populateTextStyle()
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) sendTestNotification(pendingTestMany)
        else requireContext().showToast(getString(R.string.ml_notifications_blocked), Toast.LENGTH_LONG)
    }

    /** Which test the notification permission request was for. */
    private var pendingTestMany = false

    /** One more notification of the test app, or ([many]) one from each of ten fake apps. */
    private fun sendTestNotification(many: Boolean = false) {
        val ctx = requireContext()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingTestMany = many
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        if (!NotificationRepository.isAccessGranted(ctx))
            ctx.showToast(getString(R.string.ml_grant_access_first), Toast.LENGTH_LONG)
        if (many) {
            val appContext = ctx.applicationContext
            val iconPack = prefs.iconPack
            // In the view model scope: they keep arriving after going back to home to watch them
            viewModel.viewModelScope.launch {
                val sent = withContext(Dispatchers.IO) {
                    TestNotifications.sendMany(appContext, iconPack) {
                        withContext(Dispatchers.Main) { viewModel.loadHomeApps() }
                    }
                }
                if (!sent && isAdded) testNotificationsBlocked()
            }
            return
        }
        if (!TestNotifications.send(ctx)) {
            testNotificationsBlocked()
            return
        }
        viewModel.loadHomeApps()
    }

    private fun testNotificationsBlocked() {
        val ctx = requireContext()
        ctx.showToast(getString(R.string.ml_notifications_blocked), Toast.LENGTH_LONG)
        try {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        viewModel = activity?.run {
            ViewModelProvider(this)[MainViewModel::class.java]
        } ?: throw Exception("Invalid Activity")
        viewModel.isOlauncherDefault()

        deviceManager = requireContext().getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        componentName = ComponentName(requireContext(), DeviceAdmin::class.java)
        checkAdminPermission()

        SettingsStyle.apply(binding.scrollLayout)
        populateIcons()
        populateTextStyle()
        populateProMessage()
        populateKeyboardText()
        populateScreenTimeOnOff()
        populateLockSettings()
        // Home button for recents feature disabled
        // populateHomeButtonRecents()
        populateWallpaperText()
        populateAppThemeText()
        populateTextSize()
        populateBoldFont()
        populateAlignment()
        populateStatusBar()
        populateDateTime()
        populateSwipeApps()
        populateActionHints()
        initClickListeners()
        initObservers()

        if (showPentastic)
            binding.footer.text = getText(R.string.new_app_minimal_todo_lists)
    }

    override fun onResume() {
        super.onResume()
        // Notification access may have just been granted in system settings
        if (_binding != null) populateTextStyle()
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.olauncherHiddenApps -> showHiddenApps()
            R.id.moreFeatures -> viewModel.showDialog.postValue(Constants.Dialog.PRO_MESSAGE)
            R.id.screenTimeOnOff -> viewModel.showDialog.postValue(Constants.Dialog.DIGITAL_WELLBEING)
            R.id.appInfo -> openAppInfo(requireContext(), Process.myUserHandle(), BuildConfig.APPLICATION_ID)
            R.id.setLauncher -> viewModel.resetLauncherLiveData.call()
            R.id.toggleLock -> toggleLockMode()
            // Home button for recents feature disabled
            // R.id.homeButtonRecents -> toggleHomeButtonRecents()
            R.id.autoShowKeyboard -> toggleKeyboardText()
            R.id.swipeUpAction -> {
                prefs.swipeUpSearch = !prefs.swipeUpSearch
                populateKeyboardText()
            }
            R.id.showIcons -> toggleShowIcons()
            R.id.iconTiles -> {
                prefs.iconTiles = !prefs.iconTiles
                populateTextStyle()
            }
            R.id.iconPack -> showIconPackMenu(view)
            R.id.appFont -> showFontMenu(view, prefs.appFont) { prefs.appFont = it }
            R.id.appTextSize -> showTextSizeMenu(view) { prefs.appTextSize = it }
            R.id.notificationAccess -> NotificationRepository.openAccessSettings(requireContext())
            R.id.newsOrder -> showNewsOrderMenu(view)
            R.id.letterIndex -> showLetterIndexMenu(view)
            R.id.drawerLayout -> view.showPopupMenu(
                configure = { menu ->
                    menu.add(Menu.NONE, 1, 0, getString(R.string.ml_drawer_grid))
                    menu.add(Menu.NONE, 0, 1, getString(R.string.ml_drawer_list))
                }
            ) { item ->
                prefs.drawerGrid = item.itemId != 0
                populateTextStyle()
            }
            R.id.drawerColumns -> view.showPopupMenu(
                configure = { menu -> (2..8).forEach { menu.add(Menu.NONE, it, it, it.toString()) } }
            ) { item ->
                prefs.drawerColumns = item.itemId
                populateTextStyle()
            }
            R.id.drawerRows -> view.showPopupMenu(
                configure = { menu ->
                    menu.add(Menu.NONE, 0, 0, getString(R.string.ml_default))
                    (3..12).forEach { menu.add(Menu.NONE, it, it, it.toString()) }
                }
            ) { item ->
                prefs.drawerRows = item.itemId
                populateTextStyle()
            }
            R.id.drawerIconSize -> view.showPopupMenu(
                configure = { menu ->
                    menu.add(Menu.NONE, 0, 0, getString(R.string.ml_default))
                    ICON_SIZES.forEachIndexed { i, size -> menu.add(Menu.NONE, size, i + 1, size.toString()) }
                }
            ) { item ->
                prefs.drawerIconSize = item.itemId
                populateTextStyle()
            }
            R.id.drawerFont -> showFontMenu(view, prefs.drawerFont) { prefs.drawerFont = it }
            R.id.drawerTextSize -> showTextSizeMenu(view) { prefs.drawerTextSize = it }
            R.id.drawerLabels -> {
                prefs.drawerLabels = !prefs.drawerLabels
                populateTextStyle()
            }
            R.id.homeLabels -> {
                prefs.homeShowLabels = !prefs.homeShowLabels
                populateTextStyle()
            }
            R.id.homeIconSize -> view.showPopupMenu(
                configure = { menu ->
                    menu.add(Menu.NONE, 0, 0, getString(R.string.ml_default))
                    ICON_SIZES.forEachIndexed { i, size -> menu.add(Menu.NONE, size, i + 1, size.toString()) }
                }
            ) { item ->
                prefs.homeIconSize = item.itemId
                populateTextStyle()
            }
            R.id.tabsSettings -> viewModel.viewModelScope.launch {
                val ctx = context ?: return@launch
                val apps = getAppsList(ctx, prefs, includeRegularApps = true, includeHiddenApps = false).filterIsInstance<AppModel.App>()
                val hidden = getAppsList(ctx, prefs, includeRegularApps = false, includeHiddenApps = true).filterIsInstance<AppModel.App>()
                if (!isAdded) return@launch
                DrawerTabsManager.show(requireContext(), apps, hidden, onChanged = {}, onHiddenChanged = {
                    viewModel.getAppList()
                    viewModel.getHiddenApps()
                })
            }
            R.id.detoxWhitelist -> showDetoxWhitelist()
            R.id.detoxLock -> {
                prefs.detoxLock = !prefs.detoxLock
                populateTextStyle()
            }
            R.id.detoxLockSeconds -> askNumber(R.string.ml_detox_lock_seconds, prefs.detoxLockSeconds, 3..600) {
                prefs.detoxLockSeconds = it
            }
            R.id.detoxLockoutAfter -> askNumber(R.string.ml_detox_lockout_after_hint, prefs.detoxLockoutAfter, 0..50) {
                prefs.detoxLockoutAfter = it
            }
            R.id.detoxLockoutReset -> askNumber(R.string.ml_detox_lockout_reset, prefs.detoxLockoutReset, 1..1440) {
                prefs.detoxLockoutReset = it
            }
            R.id.detoxLockoutMinutes -> askNumber(R.string.ml_detox_lockout_minutes, prefs.detoxLockoutMinutes, 1..240) {
                prefs.detoxLockoutMinutes = it
            }
            R.id.detoxLockColor -> requireContext().showColorPickerDialog(
                getString(R.string.ml_detox_lock_color), prefs.detoxLockColor, getString(R.string.ml_default),
            ) { color ->
                prefs.detoxLockColor = color
                if (_binding != null) populateTextStyle()
            }
            R.id.phoneFolders -> {
                prefs.phoneFoldersInExplorer = !prefs.phoneFoldersInExplorer
                populateTextStyle()
            }
            R.id.mediaViewer -> {
                prefs.builtInViewer = !prefs.builtInViewer
                populateTextStyle()
            }
            R.id.pdfViewer -> {
                prefs.builtInPdf = !prefs.builtInPdf
                populateTextStyle()
            }
            R.id.permissionsScreen -> {
                findNavController().popBackStack(R.id.mainFragment, false)
                viewModel.showPermissions.call()
            }
            R.id.widgetLog -> {
                val ctx = requireContext()
                val text = WidgetLog.read(ctx).lines().takeLast(25).joinToString("\n").ifBlank { "-" }
                ctx.showConfirmDialog(getString(R.string.ml_widget_log), text, getString(R.string.ml_copy)) {
                    ctx.copyToClipboard(text)
                    ctx.showToast(getString(R.string.ml_copied))
                }
            }
            R.id.widgetColumns -> view.showPopupMenu(
                configure = { menu -> (2..8).forEach { menu.add(Menu.NONE, it, it, it.toString()) } }
            ) { item ->
                prefs.widgetColumns = item.itemId
                populateTextStyle()
            }
            R.id.autoLaunchSingle -> {
                prefs.autoLaunchSingleResult = !prefs.autoLaunchSingleResult
                populateTextStyle()
            }
            R.id.testNotificationSend -> sendTestNotification()
            R.id.testNotificationMany -> sendTestNotification(many = true)
            R.id.testNotificationRemove -> {
                TestNotifications.disable(requireContext())
                viewModel.loadHomeApps()
                requireContext().showToast(getString(R.string.ml_test_removed))
            }
            R.id.dailyWallpaperUrl -> requireContext().openUrl(prefs.dailyWallpaperUrl)
            R.id.dailyWallpaper -> toggleDailyWallpaperUpdate()
            R.id.alignment -> showAlignmentMenu(view)
            R.id.statusBar -> toggleStatusBar()
            R.id.dateTime -> showDateTimeMenu(view)
            R.id.appThemeText -> showAppThemeMenu(view, showSystem = false)
            R.id.textSizeValue -> showTextSizeDialog()
            R.id.boldFont -> toggleBoldFont()

            R.id.swipeLeftApp -> showSwipeAppMenu(view, left = true)
            R.id.swipeRightApp -> showSwipeAppMenu(view, left = false)

            R.id.aboutOlauncher -> {
                prefs.aboutClicked = true
                requireContext().openUrl(Constants.URL_ABOUT_OLAUNCHER)
            }

            R.id.share -> requireActivity().shareApp()
            R.id.rate -> {
                prefs.rateClicked = true
                requireActivity().rateApp()
            }

            R.id.twitter -> requireContext().openUrl(Constants.URL_TWITTER_TANUJ)
            R.id.github -> requireContext().openUrl(Constants.URL_OLAUNCHER_GITHUB)
            R.id.privacy -> requireContext().openUrl(Constants.URL_OLAUNCHER_PRIVACY)
            R.id.footer -> {
                requireContext().openUrl(
                    if (showPentastic) Constants.URL_PENTASTIC else Constants.URL_NTS
                )
            }
        }
    }

    override fun onLongClick(view: View): Boolean {
        when (view.id) {
            R.id.alignment -> {
                prefs.appLabelAlignment = prefs.homeAlignment
                findNavController().navigate(R.id.action_settingsFragment_to_appListFragment)
                requireContext().showToast(getString(R.string.alignment_changed))
            }

            R.id.dailyWallpaper -> removeWallpaper()
            R.id.appThemeText -> showAppThemeMenu(view, showSystem = true)
            R.id.swipeLeftApp -> toggleSwipeLeft()
            R.id.swipeRightApp -> toggleSwipeRight()
            R.id.toggleLock -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        return true
    }

    private fun initClickListeners() {
        binding.olauncherHiddenApps.setOnClickListener(this)
        binding.appInfo.setOnClickListener(this)
        binding.setLauncher.setOnClickListener(this)
        binding.aboutOlauncher.setOnClickListener(this)
        binding.moreFeatures.setOnClickListener(this)
        binding.autoShowKeyboard.setOnClickListener(this)
        binding.swipeUpAction.setOnClickListener(this)
        binding.toggleLock.setOnClickListener(this)
        // Home button for recents feature disabled
        // binding.homeButtonRecents.setOnClickListener(this)
        binding.showIcons.setOnClickListener(this)
        binding.iconTiles.setOnClickListener(this)
        binding.iconPack.setOnClickListener(this)
        binding.appFont.setOnClickListener(this)
        binding.appTextSize.setOnClickListener(this)
        binding.notificationAccess.setOnClickListener(this)
        binding.newsOrder.setOnClickListener(this)
        binding.letterIndex.setOnClickListener(this)
        binding.drawerLayout.setOnClickListener(this)
        binding.drawerColumns.setOnClickListener(this)
        binding.drawerRows.setOnClickListener(this)
        binding.drawerIconSize.setOnClickListener(this)
        binding.drawerFont.setOnClickListener(this)
        binding.drawerTextSize.setOnClickListener(this)
        binding.drawerLabels.setOnClickListener(this)
        binding.homeLabels.setOnClickListener(this)
        binding.homeIconSize.setOnClickListener(this)
        binding.tabsSettings.setOnClickListener(this)
        binding.detoxWhitelist.setOnClickListener(this)
        binding.detoxLock.setOnClickListener(this)
        binding.detoxLockSeconds.setOnClickListener(this)
        binding.detoxLockoutAfter.setOnClickListener(this)
        binding.detoxLockoutMinutes.setOnClickListener(this)
        binding.detoxLockoutReset.setOnClickListener(this)
        binding.detoxLockColor.setOnClickListener(this)
        binding.phoneFolders?.setOnClickListener(this)
        binding.mediaViewer?.setOnClickListener(this)
        binding.pdfViewer?.setOnClickListener(this)
        binding.permissionsScreen.setOnClickListener(this)
        binding.widgetColumns.setOnClickListener(this)
        binding.widgetLog.setOnClickListener(this)
        binding.autoLaunchSingle.setOnClickListener(this)
        binding.testNotificationSend.setOnClickListener(this)
        binding.testNotificationMany.setOnClickListener(this)
        binding.testNotificationRemove.setOnClickListener(this)
        binding.screenTimeOnOff.setOnClickListener(this)
        binding.dailyWallpaperUrl.setOnClickListener(this)
        binding.dailyWallpaper.setOnClickListener(this)
        binding.alignment.setOnClickListener(this)
        binding.statusBar.setOnClickListener(this)
        binding.dateTime.setOnClickListener(this)
        binding.swipeLeftApp.setOnClickListener(this)
        binding.swipeRightApp.setOnClickListener(this)
        binding.appThemeText.setOnClickListener(this)
        binding.textSizeValue.setOnClickListener(this)
        binding.boldFont.setOnClickListener(this)

        binding.share.setOnClickListener(this)
        binding.rate.setOnClickListener(this)
        binding.twitter.setOnClickListener(this)
        binding.github.setOnClickListener(this)
        binding.privacy.setOnClickListener(this)
        binding.footer.setOnClickListener(this)

        binding.dailyWallpaper.setOnLongClickListener(this)
        binding.alignment.setOnLongClickListener(this)
        binding.appThemeText.setOnLongClickListener(this)
        binding.swipeLeftApp.setOnLongClickListener(this)
        binding.swipeRightApp.setOnLongClickListener(this)
        binding.toggleLock.setOnLongClickListener(this)
    }

    private fun initObservers() {
        if (prefs.firstSettingsOpen) {
            viewModel.showDialog.postValue(Constants.Dialog.ABOUT)
            prefs.firstSettingsOpen = false
        }
        viewModel.isOlauncherDefault.observe(viewLifecycleOwner) {
            if (it) {
                binding.setLauncher.text = getString(R.string.change_default_launcher)
                prefs.toShowHintCounter += 1
            }
        }
        viewModel.homeAppAlignment.observe(viewLifecycleOwner) {
            populateAlignment()
        }
        viewModel.updateSwipeApps.observe(viewLifecycleOwner) {
            populateSwipeApps()
        }
    }

    // Popup menus

    private fun populateIcons() {
        binding.showIcons.setText(if (prefs.showIcons) R.string.on else R.string.off)
        val packs = IconPackManager.installedPacks(requireContext())
        binding.iconPack.text = packs.firstOrNull { it.packageName == prefs.iconPack }?.label
            ?: getString(R.string.ml_system_icons)
    }

    private fun populateTextStyle() {
        val ctx = requireContext()
        binding.appFont.text = FontManager.label(ctx, prefs.appFont)
        binding.appTextSize.text = sizeLabel(prefs.appTextSize)
        binding.widgetColumns.text = prefs.widgetColumns.toString()
        binding.homeLabels.setText(if (prefs.homeShowLabels) R.string.on else R.string.off)
        binding.homeIconSize.text = if (prefs.homeIconSize > 0) prefs.homeIconSize.toString() else getString(R.string.ml_default)
        binding.phoneFolders?.setText(if (prefs.phoneFoldersInExplorer) R.string.ml_explorer else R.string.ml_file_manager)
        binding.mediaViewer?.setText(if (prefs.builtInViewer) R.string.ml_viewer_builtin else R.string.ml_viewer_gallery)
        binding.pdfViewer?.setText(if (prefs.builtInPdf) R.string.ml_pdf_tool else R.string.ml_pdf_external)
        binding.autoLaunchSingle.setText(if (prefs.autoLaunchSingleResult) R.string.on else R.string.off)
        binding.detoxWhitelist.text = DetoxManager.whitelist(ctx).size.toString()
        binding.detoxLock.setText(if (prefs.detoxLock) R.string.on else R.string.off)
        binding.detoxLockSeconds.text = getString(R.string.ml_detox_seconds, prefs.detoxLockSeconds)
        binding.detoxLockoutAfter.text = if (prefs.detoxLockoutAfter > 0) prefs.detoxLockoutAfter.toString() else getString(R.string.off)
        binding.detoxLockoutMinutes.text = getString(R.string.ml_detox_minutes, prefs.detoxLockoutMinutes)
        binding.detoxLockoutReset.text = getString(R.string.ml_detox_minutes, prefs.detoxLockoutReset)
        binding.detoxLockColor.text = if (prefs.detoxLockColor == 0) getString(R.string.ml_default)
        else "#%06X".format(prefs.detoxLockColor and 0xFFFFFF)
        binding.newsOrder.setText(if (prefs.newsSortByCount) R.string.ml_news_count else R.string.ml_news_recent)
        binding.iconTiles.setText(if (prefs.iconTiles) R.string.on else R.string.off)
        binding.drawerLayout.setText(
            when {
                !prefs.drawerGrid -> R.string.ml_drawer_list
                else -> R.string.ml_drawer_grid
            }
        )
        binding.drawerColumns.text = prefs.drawerColumns.toString()
        binding.drawerRows.text = if (prefs.drawerRows > 0) prefs.drawerRows.toString() else getString(R.string.ml_default)
        binding.drawerIconSize.text = if (prefs.drawerIconSize > 0) prefs.drawerIconSize.toString() else getString(R.string.ml_default)
        binding.drawerFont.text = FontManager.label(ctx, prefs.drawerFont)
        binding.drawerTextSize.text = sizeLabel(prefs.drawerTextSize)
        binding.drawerLabels.setText(if (prefs.drawerLabels) R.string.on else R.string.off)
        // Grid-only settings are dimmed while the drawer is a list
        listOf(binding.drawerColumns, binding.drawerRows, binding.drawerIconSize).forEach { it.alpha = if (prefs.drawerGrid) 1f else 0.5f }
        binding.letterIndex.setText(
            when (prefs.letterIndexMode) {
                Constants.LetterIndex.ON_SCROLL -> R.string.ml_index_on_scroll
                Constants.LetterIndex.NEVER -> R.string.ml_index_never
                else -> R.string.ml_index_always
            }
        )
        binding.notificationAccess.setText(
            if (NotificationRepository.isAccessGranted(ctx)) R.string.ml_enabled else R.string.ml_disabled
        )
    }

    private fun sizeLabel(size: Int) = if (size <= 0) getString(R.string.ml_default) else size.toString()

    private fun showFontMenu(anchor: View, current: String, onPick: (String) -> Unit) {
        val ctx = requireContext()
        val options = FontManager.options(ctx)
        anchor.showPopupMenu(
            configure = { menu ->
                menu.add(Menu.NONE, 0, 0, getString(R.string.ml_default))
                options.forEachIndexed { i, font ->
                    // The current font is marked by the menu's own check
                    menu.add(Menu.NONE, i + 1, i + 1, FontManager.preview(ctx, font.id, font.label)).apply {
                        if (font.id == current) setCheckable(true).setChecked(true)
                    }
                }
                menu.add(Menu.NONE, LOAD_FONT_ID, options.size + 1, getString(R.string.ml_load_font))
            }
        ) { item ->
            when (item.itemId) {
                0 -> onPick("")
                LOAD_FONT_ID -> {
                    pendingFontTarget = onPick
                    viewModel.keepCurrentScreen = true
                    fontPicker.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream", "*/*"))
                    return@showPopupMenu
                }

                else -> onPick(options[item.itemId - 1].id)
            }
            populateTextStyle()
        }
    }

    /** A whole number typed in, kept within [range]. */
    private fun askNumber(title: Int, current: Int, range: IntRange, onSet: (Int) -> Unit) {
        requireContext().showTextInputDialog(
            getString(title), current.toString(), "${range.first}–${range.last}", getString(R.string.ml_save),
            inputType = android.text.InputType.TYPE_CLASS_NUMBER,
        ) { text ->
            val value = text.toIntOrNull() ?: return@showTextInputDialog
            onSet(value.coerceIn(range))
            if (_binding != null) populateTextStyle()
        }
    }

    private fun showDetoxWhitelist() {
        val ctx = requireContext()
        if (DetoxManager.isActive(ctx) && DetoxManager.isStrict(ctx)) {
            ctx.showToast(getString(R.string.ml_detox_whitelist_locked))
            return
        }
        viewModel.viewModelScope.launch {
            // The launcher's own apps one by one, and the folders made as shortcuts on home
            val apps = (getAppsList(ctx, prefs, includeRegularApps = true, includeHiddenApps = true)
                .filterIsInstance<AppModel.App>() + app.olauncher.helper.FolderShortcuts.asApps(ctx))
                .distinctBy { DetoxManager.whitelistKey(it) }
                .sortedBy { it.appLabel.lowercase() }
            val current = DetoxManager.whitelist(ctx)
            val labels = apps.map { it.appLabel }
            val checked = apps.indices.filter { DetoxManager.whitelistKey(apps[it]) in current }.toSet()
            ctx.showMultiSelectDialog(getString(R.string.ml_detox_whitelist), labels, checked, getString(R.string.ml_done)) { selected ->
                if (!DetoxManager.setWhitelist(ctx, selected.map { DetoxManager.whitelistKey(apps[it]) }.toSet()))
                    ctx.showToast(getString(R.string.ml_detox_whitelist_locked))
                if (_binding != null) populateTextStyle()
            }
        }
    }

    private fun showNewsOrderMenu(anchor: View) {
        anchor.showPopupMenu(
            configure = { menu ->
                menu.add(Menu.NONE, 0, 0, getString(R.string.ml_news_recent))
                menu.add(Menu.NONE, 1, 1, getString(R.string.ml_news_count))
            }
        ) { item ->
            prefs.newsSortByCount = item.itemId == 1
            populateTextStyle()
        }
    }

    private fun showLetterIndexMenu(anchor: View) {
        anchor.showPopupMenu(
            configure = { menu ->
                menu.add(Menu.NONE, Constants.LetterIndex.ALWAYS, 0, getString(R.string.ml_index_always))
                menu.add(Menu.NONE, Constants.LetterIndex.ON_SCROLL, 1, getString(R.string.ml_index_on_scroll))
                menu.add(Menu.NONE, Constants.LetterIndex.NEVER, 2, getString(R.string.ml_index_never))
            }
        ) { item ->
            prefs.letterIndexMode = item.itemId
            populateTextStyle()
        }
    }

    private fun showTextSizeMenu(anchor: View, onPick: (Int) -> Unit) {
        anchor.showPopupMenu(
            configure = { menu ->
                menu.add(Menu.NONE, 0, 0, getString(R.string.ml_default))
                TEXT_SIZES.forEachIndexed { i, size -> menu.add(Menu.NONE, size, i + 1, size.toString()) }
            }
        ) { item ->
            onPick(item.itemId)
            populateTextStyle()
        }
    }

    private fun toggleShowIcons() {
        prefs.showIcons = !prefs.showIcons
        populateIcons()
    }

    private fun showIconPackMenu(anchor: View) {
        val packs = IconPackManager.installedPacks(requireContext())
        anchor.showPopupMenu(
            configure = { menu ->
                menu.add(Menu.NONE, 0, 0, getString(R.string.ml_system_icons))
                packs.forEachIndexed { i, pack -> menu.add(Menu.NONE, i + 1, i + 1, pack.label) }
            }
        ) { item ->
            prefs.iconPack = if (item.itemId == 0) "" else packs[item.itemId - 1].packageName
            IconPackManager.clearCache()
            populateIcons()
        }
    }

    private fun showDateTimeMenu(anchor: View) {
        anchor.showPopupMenu(R.menu.date_time) { item ->
            when (item.itemId) {
                R.id.dateTimeOn -> toggleDateTime(Constants.DateTime.ON)
                R.id.dateTimeOff -> toggleDateTime(Constants.DateTime.OFF)
                R.id.dateOnly -> toggleDateTime(Constants.DateTime.DATE_ONLY)
            }
        }
    }

    private fun showAlignmentMenu(anchor: View) {
        anchor.showPopupMenu(
            R.menu.alignment,
            configure = { menu ->
                menu.findItem(R.id.alignmentBottom).setTitle(
                    if (prefs.homeBottomAlignment) R.string.bottom_on else R.string.bottom_off
                )
            }
        ) { item ->
            when (item.itemId) {
                R.id.alignmentLeft -> viewModel.updateHomeAlignment(Gravity.START)
                R.id.alignmentCenter -> viewModel.updateHomeAlignment(Gravity.CENTER)
                R.id.alignmentRight -> viewModel.updateHomeAlignment(Gravity.END)
                R.id.alignmentBottom -> updateHomeBottomAlignment()
            }
        }
    }

    // "System" stays hidden unless the row is long pressed
    private fun showAppThemeMenu(anchor: View, showSystem: Boolean) {
        anchor.showPopupMenu(
            R.menu.app_theme,
            configure = { menu -> menu.findItem(R.id.themeSystem).isVisible = showSystem }
        ) { item ->
            when (item.itemId) {
                R.id.themeLight -> updateTheme(AppCompatDelegate.MODE_NIGHT_NO)
                R.id.themeDark -> updateTheme(AppCompatDelegate.MODE_NIGHT_YES)
                R.id.themeSystem -> updateTheme(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }
        }
    }

    // Dialogs

    private fun showDialog(newDialog: OlDialog) {
        dialog?.dismiss()
        dialog = newDialog
        newDialog.showRespectingStatusBar()
    }

    private fun showTextSizeDialog() {
        var stepper: DialogTextSizeBinding? = null
        val dialog = requireContext().createDialog(R.string.text_size, R.string.okay) { container ->
            DialogTextSizeBinding.inflate(layoutInflater, container, false).also { stepper = it }.root
        }
        stepper?.apply {
            textSizeCurrent.text = formatScale(pendingOrCurrentTextSizeScale())
            textSizeMinus.setOnClickListener { adjustTextSizePreview(-0.1f, this) }
            textSizePlus.setOnClickListener { adjustTextSizePreview(0.1f, this) }
        }
        dialog.setOnDismissListener { applyTextSizeScale() }
        showDialog(dialog)
    }

    // Prominent disclosure before sending the user to accessibility settings
    private fun showAccessibilityDialog() {
        val serviceEnabled = isAccessServiceEnabled(requireContext())
        showDialog(
            requireContext().createDialog(
                title = R.string.gestures,
                action = if (serviceEnabled) R.string.disable else R.string.enable,
                message = R.string.accessibility_disclosure,
                neutral = R.string.not_working,
                onNeutral = { requireContext().openUrl(Constants.URL_DOUBLE_TAP) },
                onAction = { openAccessibilityService() },
            )
        )
    }

    private fun toggleSwipeLeft() {
        prefs.swipeLeftEnabled = !prefs.swipeLeftEnabled
        if (prefs.swipeLeftEnabled) {
            binding.swipeLeftApp.setTextColor(requireContext().getColorFromAttr(R.attr.primaryColor))
            requireContext().showToast(getString(R.string.swipe_left_app_enabled))
        } else {
            binding.swipeLeftApp.setTextColor(requireContext().getColorFromAttr(R.attr.primaryColorTrans50))
            requireContext().showToast(getString(R.string.swipe_left_app_disabled))
        }
        populateSwipeApps()
    }

    private fun toggleSwipeRight() {
        prefs.swipeRightEnabled = !prefs.swipeRightEnabled
        if (prefs.swipeRightEnabled) {
            binding.swipeRightApp.setTextColor(requireContext().getColorFromAttr(R.attr.primaryColor))
            requireContext().showToast(getString(R.string.swipe_right_app_enabled))
        } else {
            binding.swipeRightApp.setTextColor(requireContext().getColorFromAttr(R.attr.primaryColorTrans50))
            requireContext().showToast(getString(R.string.swipe_right_app_disabled))
        }
        populateSwipeApps()
    }

    private fun toggleStatusBar() {
        prefs.showStatusBar = !prefs.showStatusBar
        populateStatusBar()
    }

    private fun populateStatusBar() {
        if (prefs.showStatusBar) {
            requireActivity().window.showStatusBar()
            binding.statusBar.text = getString(R.string.on)
        } else {
            requireActivity().window.hideStatusBar()
            binding.statusBar.text = getString(R.string.off)
        }
    }

    private fun toggleDateTime(selected: Int) {
        prefs.dateTimeVisibility = selected
        populateDateTime()
        viewModel.toggleDateTime()
    }

    private fun populateDateTime() {
        binding.dateTime.text = getString(
            when (prefs.dateTimeVisibility) {
                Constants.DateTime.DATE_ONLY -> R.string.date
                Constants.DateTime.ON -> R.string.on
                else -> R.string.off
            }
        )
    }

    private fun showHiddenApps() {
        if (prefs.hiddenApps.isEmpty()) {
            requireContext().showToast(getString(R.string.no_hidden_apps))
            return
        }
        viewModel.getHiddenApps()
        findNavController().navigate(
            R.id.action_settingsFragment_to_appListFragment,
            bundleOf(Constants.Key.FLAG to Constants.FLAG_HIDDEN_APPS)
        )
    }

    private fun checkAdminPermission() {
        val isAdmin: Boolean = deviceManager.isAdminActive(componentName)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P)
            prefs.lockModeOn = isAdmin
    }

    private fun openAccessibilityService() {
        // prefs.lockModeOn = true
        populateLockSettings()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun toggleLockMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (!prefs.lockModeOn && !isAccessServiceEnabled(requireContext())) {
                showAccessibilityDialog()
                return
            }
            prefs.lockModeOn = !prefs.lockModeOn
        } else {
            val isAdmin: Boolean = deviceManager.isAdminActive(componentName)
            if (isAdmin) {
                removeActiveAdmin("Admin permission removed.")
                prefs.lockModeOn = false
            } else {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                intent.putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    getString(R.string.admin_permission_message)
                )
                requireActivity().startActivityForResult(intent, Constants.REQUEST_CODE_ENABLE_ADMIN)
            }
        }
        populateLockSettings()
    }

    private fun removeActiveAdmin(toastMessage: String? = null) {
        try {
            deviceManager.removeActiveAdmin(componentName) // for backward compatibility
            requireContext().showToast(toastMessage)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeWallpaper() {
        if (requireContext().isEinkDisplay()) {
            prefs.appTheme = AppCompatDelegate.MODE_NIGHT_NO
            setPlainWallpaper(requireContext(), android.R.color.white)
        } else {
            prefs.appTheme = AppCompatDelegate.MODE_NIGHT_YES
            setPlainWallpaper(requireContext(), android.R.color.black)
        }
        if (!prefs.dailyWallpaper) return
        prefs.dailyWallpaper = false
        populateWallpaperText()
        viewModel.cancelWallpaperWorker()
    }

    private fun toggleDailyWallpaperUpdate() {
        if (prefs.dailyWallpaper.not() && prefs.appTheme == AppCompatDelegate.MODE_NIGHT_YES && viewModel.isOlauncherDefault.value == false) {
            requireContext().showToast(R.string.set_as_default_launcher_first)
            return
        }
        prefs.dailyWallpaper = !prefs.dailyWallpaper
        populateWallpaperText()
        if (prefs.dailyWallpaper) {
            viewModel.setWallpaperWorker()
            showWallpaperToasts()
        } else viewModel.cancelWallpaperWorker()
    }

    private fun showWallpaperToasts() {
        if (isOlauncherDefault(requireContext()))
            requireContext().showToast(getString(R.string.your_wallpaper_will_update_shortly))
        else
            requireContext().showToast(getString(R.string.olauncher_is_not_default_launcher), Toast.LENGTH_LONG)
    }

    private var pendingTextSizeScale: Float = -1f

    private fun pendingOrCurrentTextSizeScale(): Float =
        if (pendingTextSizeScale > 0) pendingTextSizeScale else prefs.textSizeScale

    private fun formatScale(scale: Float): String = String.format("%.1f", scale)

    private fun adjustTextSizePreview(delta: Float, dialogBinding: DialogTextSizeBinding) {
        val maxScale = if (isTablet(requireContext())) 2.0f else 1.5f
        val current = pendingOrCurrentTextSizeScale()
        val newScale = Math.round((current + delta) * 10f) / 10f
        val clamped = newScale.coerceIn(0.5f, maxScale)
        if (clamped == current) return
        pendingTextSizeScale = clamped
        val formatted = formatScale(clamped)
        binding.textSizeValue.text = formatted
        dialogBinding.textSizeCurrent.text = formatted
    }

    private fun applyTextSizeScale() {
        if (pendingTextSizeScale < 0 || prefs.textSizeScale == pendingTextSizeScale) {
            pendingTextSizeScale = -1f
            return
        }
        prefs.textSizeScale = pendingTextSizeScale
        pendingTextSizeScale = -1f
        val activity = activity ?: return
        if (activity.isChangingConfigurations.not())
            activity.recreate()
    }

    /** Whether the keyboard opens with the app list (off: only when the search field is tapped). */
    private fun toggleKeyboardText() {
        prefs.autoShowKeyboard = !prefs.autoShowKeyboard
        populateKeyboardText()
    }

    private fun updateTheme(appTheme: Int) {
        if (AppCompatDelegate.getDefaultNightMode() == appTheme) return
        prefs.appTheme = appTheme
        populateAppThemeText(appTheme)
        setAppTheme(appTheme)
    }

    private fun setAppTheme(theme: Int) {
        if (AppCompatDelegate.getDefaultNightMode() == theme) return
        if (prefs.dailyWallpaper) {
            setPlainWallpaper(theme)
            viewModel.setWallpaperWorker()
        }
        requireActivity().recreate()
    }

    private fun setPlainWallpaper(appTheme: Int) {
        when (appTheme) {
            AppCompatDelegate.MODE_NIGHT_YES -> setPlainWallpaper(requireContext(), android.R.color.black)
            AppCompatDelegate.MODE_NIGHT_NO -> setPlainWallpaper(requireContext(), android.R.color.white)
            else -> {
                if (requireContext().isDarkThemeOn())
                    setPlainWallpaper(requireContext(), android.R.color.black)
                else setPlainWallpaper(requireContext(), android.R.color.white)
            }
        }
    }

    private fun populateAppThemeText(appTheme: Int = prefs.appTheme) {
        when (appTheme) {
            AppCompatDelegate.MODE_NIGHT_YES -> binding.appThemeText.text = getString(R.string.dark)
            AppCompatDelegate.MODE_NIGHT_NO -> binding.appThemeText.text = getString(R.string.light)
            else -> binding.appThemeText.text = getString(R.string.system_default)
        }
    }

    private fun populateTextSize() {
        binding.textSizeValue.text = formatScale(prefs.textSizeScale)
    }

    private fun toggleBoldFont() {
        prefs.boldFont = !prefs.boldFont
        populateBoldFont()
        requireActivity().recreate()
    }

    private fun populateBoldFont() {
        binding.boldFont.text = getString(if (prefs.boldFont) R.string.on else R.string.off)
    }

    private fun populateScreenTimeOnOff() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (requireContext().appUsagePermissionGranted()) binding.screenTimeOnOff.text = getString(R.string.on)
            else binding.screenTimeOnOff.text = getString(R.string.off)
        } else binding.screenTimeLayout.visibility = View.GONE
    }

    private fun populateKeyboardText() {
        if (prefs.autoShowKeyboard) binding.autoShowKeyboard.text = getString(R.string.on)
        else binding.autoShowKeyboard.text = getString(R.string.off)
        binding.swipeUpAction.setText(if (prefs.swipeUpSearch) R.string.ml_swipe_up_search else R.string.ml_swipe_up_apps)
    }

    private fun populateWallpaperText() {
        if (prefs.dailyWallpaper) binding.dailyWallpaper.text = getString(R.string.on)
        else binding.dailyWallpaper.text = getString(R.string.off)
    }

    private fun updateHomeBottomAlignment() {
        if (viewModel.isOlauncherDefault.value != true) {
            requireContext().showToast(getString(R.string.please_set_olauncher_as_default_first), Toast.LENGTH_LONG)
            return
        }
        prefs.homeBottomAlignment = !prefs.homeBottomAlignment
        populateAlignment()
        viewModel.updateHomeAlignment(prefs.homeAlignment)
    }

    private fun populateAlignment() {
        when (prefs.homeAlignment) {
            Gravity.START -> binding.alignment.text = getString(R.string.left)
            Gravity.CENTER -> binding.alignment.text = getString(R.string.center)
            Gravity.END -> binding.alignment.text = getString(R.string.right)
        }
    }

    // Home button for recents feature disabled
    // private fun toggleHomeButtonRecents() {
    //     if (!prefs.homeButtonShowRecents && !isAccessServiceEnabled(requireContext())) {
    //         showAccessibilityDialog()
    //         return
    //     }
    //     prefs.homeButtonShowRecents = !prefs.homeButtonShowRecents
    //     populateHomeButtonRecents()
    // }

    // private fun populateHomeButtonRecents() {
    //     binding.homeButtonRecents.text = getString(
    //         if (prefs.homeButtonShowRecents && isAccessServiceEnabled(requireContext())) R.string.on
    //         else R.string.off
    //     )
    // }

    private fun populateLockSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            binding.toggleLock.text = getString(
                if (prefs.lockModeOn && isAccessServiceEnabled(requireContext())) R.string.on
                else R.string.off
            )
        } else {
            binding.toggleLock.text = getString(
                if (prefs.lockModeOn) R.string.on
                else R.string.off
            )
        }
    }

    /** Swipe left/right: pick an app, or "No app" to turn the gesture off. */
    private fun showSwipeAppMenu(anchor: View, left: Boolean) {
        anchor.showPopupMenu(
            configure = { menu ->
                menu.add(Menu.NONE, 1, 1, getString(R.string.ml_choose_app))
                menu.add(Menu.NONE, 2, 2, getString(R.string.ml_no_app))
            }
        ) { item ->
            if (item.itemId == 1) {
                if (left) prefs.swipeLeftEnabled = true else prefs.swipeRightEnabled = true
                showAppListIfEnabled(if (left) Constants.FLAG_SET_SWIPE_LEFT_APP else Constants.FLAG_SET_SWIPE_RIGHT_APP)
            } else {
                if (left) prefs.swipeLeftEnabled = false else prefs.swipeRightEnabled = false
                populateSwipeApps()
            }
        }
    }

    private fun populateSwipeApps() {
        val primary = requireContext().getColorFromAttr(R.attr.primaryColor)
        binding.swipeLeftApp.setTextColor(primary)
        binding.swipeRightApp.setTextColor(primary)
        binding.swipeLeftApp.text = if (prefs.swipeLeftEnabled) prefs.appNameSwipeLeft else getString(R.string.ml_no_app)
        binding.swipeRightApp.text = if (prefs.swipeRightEnabled) prefs.appNameSwipeRight else getString(R.string.ml_no_app)
        if (!prefs.swipeLeftEnabled)
            binding.swipeLeftApp.setTextColor(requireContext().getColorFromAttr(R.attr.primaryColorTrans50))
        if (!prefs.swipeRightEnabled)
            binding.swipeRightApp.setTextColor(requireContext().getColorFromAttr(R.attr.primaryColorTrans50))
    }

//    private fun populateDigitalWellbeing() {
//        binding.digitalWellbeing.isVisible = requireContext().isPackageInstalled(Constants.DIGITAL_WELLBEING_PACKAGE_NAME).not()
//                && requireContext().isPackageInstalled(Constants.DIGITAL_WELLBEING_SAMSUNG_PACKAGE_NAME).not()
//                && prefs.hideDigitalWellbeing.not()
//    }

    private fun showAppListIfEnabled(flag: Int) {
        if ((flag == Constants.FLAG_SET_SWIPE_LEFT_APP) and !prefs.swipeLeftEnabled) {
            requireContext().showToast(getString(R.string.long_press_to_enable))
            return
        }
        if ((flag == Constants.FLAG_SET_SWIPE_RIGHT_APP) and !prefs.swipeRightEnabled) {
            requireContext().showToast(getString(R.string.long_press_to_enable))
            return
        }
        viewModel.getAppList(true)
        findNavController().navigate(
            R.id.action_settingsFragment_to_appListFragment,
            bundleOf(Constants.Key.FLAG to flag)
        )
    }

    private fun populateActionHints() {
        if (prefs.aboutClicked.not())
            binding.aboutOlauncher.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_info, 0)
        if (viewModel.isOlauncherDefault.value != true) return
        if (prefs.rateClicked.not() && prefs.toShowHintCounter > Constants.HINT_RATE_US && prefs.toShowHintCounter < Constants.HINT_RATE_US + 100)
            binding.rate.setCompoundDrawablesWithIntrinsicBounds(0, android.R.drawable.arrow_down_float, 0, 0)
    }

    private fun populateProMessage() {
        if (prefs.proMessageShown.not() && prefs.userState == Constants.UserState.SHARE) {
            prefs.proMessageShown = true
            viewModel.showDialog.postValue(Constants.Dialog.PRO_MESSAGE)
        }
    }

    override fun onDestroyView() {
        // Dismissing the text size dialog applies any pending scale via its dismiss listener
        dialog?.dismiss()
        dialog = null
        applyTextSizeScale()
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        viewModel.checkForMessages.call()
        super.onDestroy()
    }

    companion object {
        private const val LOAD_FONT_ID = 10_000
        private val TEXT_SIZES = listOf(12, 14, 16, 18, 20, 22, 24, 26, 28, 30, 32, 36, 40)
        private val ICON_SIZES = listOf(24, 32, 40, 48, 56, 64, 72, 80, 96, 112, 128)
    }
}
