package app.olauncher.data

import android.content.Context
import android.content.SharedPreferences
import android.view.Gravity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit

class Prefs(context: Context) {
    private val PREFS_FILENAME = "app.olauncher"

    private val FIRST_OPEN = "FIRST_OPEN"
    private val FIRST_OPEN_TIME = "FIRST_OPEN_TIME"
    private val FIRST_SETTINGS_OPEN = "FIRST_SETTINGS_OPEN"
    private val FIRST_HIDE = "FIRST_HIDE"
    private val USER_STATE = "USER_STATE"
    private val LOCK_MODE = "LOCK_MODE"
    private val HOME_APPS_NUM = "HOME_APPS_NUM"
    private val AUTO_SHOW_KEYBOARD = "AUTO_SHOW_KEYBOARD"
    private val SWIPE_UP_SEARCH = "SWIPE_UP_SEARCH"
    private val SWIPE_UP_OFF = "SWIPE_UP_OFF"
    private val HOME_LOCKED = "HOME_LOCKED"
    private val KEYBOARD_MESSAGE = "KEYBOARD_MESSAGE"
    private val DAILY_WALLPAPER = "DAILY_WALLPAPER"
    private val DAILY_WALLPAPER_URL = "DAILY_WALLPAPER_URL"
    private val HOME_ALIGNMENT = "HOME_ALIGNMENT"
    private val HOME_BOTTOM_ALIGNMENT = "HOME_BOTTOM_ALIGNMENT"
    private val APP_LABEL_ALIGNMENT = "APP_LABEL_ALIGNMENT"
    private val STATUS_BAR = "STATUS_BAR"
    private val DATE_TIME_VISIBILITY = "DATE_TIME_VISIBILITY"
    private val SWIPE_LEFT_ENABLED = "SWIPE_LEFT_ENABLED"
    private val SWIPE_RIGHT_ENABLED = "SWIPE_RIGHT_ENABLED"
    private val HIDDEN_APPS = "HIDDEN_APPS"
    private val HIDDEN_APPS_UPDATED = "HIDDEN_APPS_UPDATED"
    private val SHOW_HINT_COUNTER = "SHOW_HINT_COUNTER"
    private val APP_THEME = "APP_THEME"
    private val ABOUT_CLICKED = "ABOUT_CLICKED"
    private val RATE_CLICKED = "RATE_CLICKED"
    private val WALLPAPER_MSG_SHOWN = "WALLPAPER_MSG_SHOWN"
    private val SHARE_SHOWN_TIME = "SHARE_SHOWN_TIME"
    private val TEXT_SIZE_SCALE = "TEXT_SIZE_SCALE"
    private val BOLD_FONT = "BOLD_FONT"
    private val PRO_MESSAGE_SHOWN = "PRO_MESSAGE_SHOWN"
    private val HIDE_SET_DEFAULT_LAUNCHER = "HIDE_SET_DEFAULT_LAUNCHER"
    private val SCREEN_TIME_LAST_UPDATED = "SCREEN_TIME_LAST_UPDATED"
    private val LAUNCHER_RESTART_TIMESTAMP = "LAUNCHER_RECREATE_TIMESTAMP"
    private val SHOWN_ON_DAY_OF_YEAR = "SHOWN_ON_DAY_OF_YEAR"
    // Home button for recents feature disabled
    // private val HOME_BUTTON_SHOW_RECENTS = "HOME_BUTTON_SHOW_RECENTS"

    private val APP_NAME_1 = "APP_NAME_1"
    private val APP_NAME_2 = "APP_NAME_2"
    private val APP_NAME_3 = "APP_NAME_3"
    private val APP_NAME_4 = "APP_NAME_4"
    private val APP_NAME_5 = "APP_NAME_5"
    private val APP_NAME_6 = "APP_NAME_6"
    private val APP_NAME_7 = "APP_NAME_7"
    private val APP_NAME_8 = "APP_NAME_8"
    private val APP_PACKAGE_1 = "APP_PACKAGE_1"
    private val APP_PACKAGE_2 = "APP_PACKAGE_2"
    private val APP_PACKAGE_3 = "APP_PACKAGE_3"
    private val APP_PACKAGE_4 = "APP_PACKAGE_4"
    private val APP_PACKAGE_5 = "APP_PACKAGE_5"
    private val APP_PACKAGE_6 = "APP_PACKAGE_6"
    private val APP_PACKAGE_7 = "APP_PACKAGE_7"
    private val APP_PACKAGE_8 = "APP_PACKAGE_8"
    private val APP_ACTIVITY_CLASS_NAME_1 = "APP_ACTIVITY_CLASS_NAME_1"
    private val APP_ACTIVITY_CLASS_NAME_2 = "APP_ACTIVITY_CLASS_NAME_2"
    private val APP_ACTIVITY_CLASS_NAME_3 = "APP_ACTIVITY_CLASS_NAME_3"
    private val APP_ACTIVITY_CLASS_NAME_4 = "APP_ACTIVITY_CLASS_NAME_4"
    private val APP_ACTIVITY_CLASS_NAME_5 = "APP_ACTIVITY_CLASS_NAME_5"
    private val APP_ACTIVITY_CLASS_NAME_6 = "APP_ACTIVITY_CLASS_NAME_6"
    private val APP_ACTIVITY_CLASS_NAME_7 = "APP_ACTIVITY_CLASS_NAME_7"
    private val APP_ACTIVITY_CLASS_NAME_8 = "APP_ACTIVITY_CLASS_NAME_8"
    private val APP_USER_1 = "APP_USER_1"
    private val APP_USER_2 = "APP_USER_2"
    private val APP_USER_3 = "APP_USER_3"
    private val APP_USER_4 = "APP_USER_4"
    private val APP_USER_5 = "APP_USER_5"
    private val APP_USER_6 = "APP_USER_6"
    private val APP_USER_7 = "APP_USER_7"
    private val APP_USER_8 = "APP_USER_8"

    private val APP_NAME_SWIPE_LEFT = "APP_NAME_SWIPE_LEFT"
    private val APP_NAME_SWIPE_RIGHT = "APP_NAME_SWIPE_RIGHT"
    private val APP_PACKAGE_SWIPE_LEFT = "APP_PACKAGE_SWIPE_LEFT"
    private val APP_PACKAGE_SWIPE_RIGHT = "APP_PACKAGE_SWIPE_RIGHT"
    private val APP_ACTIVITY_CLASS_NAME_SWIPE_LEFT = "APP_ACTIVITY_CLASS_NAME_SWIPE_LEFT"
    private val APP_ACTIVITY_CLASS_NAME_SWIPE_RIGHT = "APP_ACTIVITY_CLASS_NAME_SWIPE_RIGHT"
    private val APP_USER_SWIPE_LEFT = "APP_USER_SWIPE_LEFT"
    private val APP_USER_SWIPE_RIGHT = "APP_USER_SWIPE_RIGHT"
    private val CLOCK_APP_PACKAGE = "CLOCK_APP_PACKAGE"
    private val CLOCK_APP_USER = "CLOCK_APP_USER"
    private val CLOCK_APP_CLASS_NAME = "CLOCK_APP_CLASS_NAME"
    private val CALENDAR_APP_PACKAGE = "CALENDAR_APP_PACKAGE"
    private val CALENDAR_APP_USER = "CALENDAR_APP_USER"
    private val CALENDAR_APP_CLASS_NAME = "CALENDAR_APP_CLASS_NAME"
    private val SCREEN_TIME_APP_PACKAGE = "SCREEN_TIME_APP_PACKAGE"
    private val SCREEN_TIME_APP_USER = "SCREEN_TIME_APP_USER"
    private val SCREEN_TIME_APP_CLASS_NAME = "SCREEN_TIME_APP_CLASS_NAME"

    private val IS_SHORTCUT_1 = "IS_SHORTCUT_1"
    private val SHORTCUT_ID_1 = "SHORTCUT_ID_1"
    private val IS_SHORTCUT_2 = "IS_SHORTCUT_2"
    private val SHORTCUT_ID_2 = "SHORTCUT_ID_2"
    private val IS_SHORTCUT_3 = "IS_SHORTCUT_3"
    private val SHORTCUT_ID_3 = "SHORTCUT_ID_3"
    private val IS_SHORTCUT_4 = "IS_SHORTCUT_4"
    private val SHORTCUT_ID_4 = "SHORTCUT_ID_4"
    private val IS_SHORTCUT_5 = "IS_SHORTCUT_5"
    private val SHORTCUT_ID_5 = "SHORTCUT_ID_5"
    private val IS_SHORTCUT_6 = "IS_SHORTCUT_6"
    private val SHORTCUT_ID_6 = "SHORTCUT_ID_6"
    private val IS_SHORTCUT_7 = "IS_SHORTCUT_7"
    private val SHORTCUT_ID_7 = "SHORTCUT_ID_7"
    private val IS_SHORTCUT_8 = "IS_SHORTCUT_8"
    private val SHORTCUT_ID_8 = "SHORTCUT_ID_8"

    private val SHORTCUT_ID_SWIPE_LEFT = "SHORTCUT_ID_SWIPE_LEFT"
    private val IS_SHORTCUT_SWIPE_LEFT = "IS_SHORTCUT_SWIPE_LEFT"
    private val SHORTCUT_ID_SWIPE_RIGHT = "SHORTCUT_ID_SWIPE_RIGHT"
    private val IS_SHORTCUT_SWIPE_RIGHT = "IS_SHORTCUT_SWIPE_RIGHT"

    private val SHOW_ICONS = "SHOW_ICONS"
    private val NEWS_SORT_BY_COUNT = "NEWS_SORT_BY_COUNT"
    private val AUTO_LAUNCH_SINGLE = "AUTO_LAUNCH_SINGLE"
    private val DETOX_LOCK = "DETOX_LOCK"
    private val DETOX_LOCK_SECONDS = "DETOX_LOCK_SECONDS"
    private val DETOX_LOCK_COLOR = "DETOX_LOCK_COLOR"
    private val DETOX_LOCKOUT_AFTER = "DETOX_LOCKOUT_AFTER"
    private val DETOX_LOCKOUT_MINUTES = "DETOX_LOCKOUT_MINUTES"
    private val DETOX_LOCKOUT_RESET = "DETOX_LOCKOUT_RESET"
    private val ALARM_VIBRATE_SECONDS = "ALARM_VIBRATE_SECONDS"
    private val HOME_DETOX = "HOME_DETOX"
    private val HOME_DETOX_SECONDS = "HOME_DETOX_SECONDS"
    private val HOME_DETOX_LOCKOUT_AFTER = "HOME_DETOX_LOCKOUT_AFTER"
    private val HOME_DETOX_LOCKOUT_MINUTES = "HOME_DETOX_LOCKOUT_MINUTES"
    private val HOME_DETOX_LOCKOUT_RESET = "HOME_DETOX_LOCKOUT_RESET"
    private val HOME_DETOX_SEARCH = "HOME_DETOX_SEARCH"
    private val HOME_DETOX_SEARCH_SECONDS = "HOME_DETOX_SEARCH_SECONDS"
    private val ALARM_RAMP_SECONDS = "ALARM_RAMP_SECONDS"
    private val WIDGET_COLUMNS = "WIDGET_COLUMNS"
    private val WELCOME_SHOWN = "WELCOME_SHOWN"
    private val BOTTOM_LABELS = "BOTTOM_LABELS"
    private val HOME_SHOW_LABELS = "HOME_SHOW_LABELS"
    private val HOME_ICON_SIZE = "HOME_ICON_SIZE"
    private val LETTER_INDEX_MODE = "LETTER_INDEX_MODE"
    private val APP_FONT = "APP_FONT"
    private val APP_TEXT_SIZE = "APP_TEXT_SIZE"
    private val ICON_PACK = "ICON_PACK"

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_FILENAME, 0)

    var showIcons: Boolean
        get() = prefs.getBoolean(SHOW_ICONS, true)
        set(value) = prefs.edit { putBoolean(SHOW_ICONS, value) }

    /** Font ids from FontManager; "" keeps the default style. Sizes in sp, 0 keeps the default size. */
    var appFont: String
        get() = prefs.getString(APP_FONT, "").orEmpty()
        set(value) = prefs.edit { putString(APP_FONT, value) }

    var appTextSize: Int
        get() = prefs.getInt(APP_TEXT_SIZE, 0)
        set(value) = prefs.edit { putInt(APP_TEXT_SIZE, value) }

    var homeShowLabels: Boolean
        get() = prefs.getBoolean(HOME_SHOW_LABELS, true)
        set(value) = prefs.edit { putBoolean(HOME_SHOW_LABELS, value) }

    /** Icon size in dp for home apps, 0 = automatic. */
    var homeIconSize: Int
        get() = prefs.getInt(HOME_ICON_SIZE, 0)
        set(value) = prefs.edit { putInt(HOME_ICON_SIZE, value) }

    /** Show app names under the icons of the bottom area. */
    var bottomLabels: Boolean
        get() = prefs.getBoolean(BOTTOM_LABELS, false)
        set(value) = prefs.edit { putBoolean(BOTTOM_LABELS, value) }

    var welcomeShown: Boolean
        get() = prefs.getBoolean(WELCOME_SHOWN, false)
        set(value) = prefs.edit { putBoolean(WELCOME_SHOWN, value) }

    /** Columns of the home widget grid (2-8). */
    var widgetColumns: Int
        get() = prefs.getInt(WIDGET_COLUMNS, 6).coerceIn(2, 8)
        set(value) = prefs.edit { putInt(WIDGET_COLUMNS, value.coerceIn(2, 8)) }

    /** Drawer search: open the app by itself when only one result is left. */
    var autoLaunchSingleResult: Boolean
        get() = prefs.getBoolean(AUTO_LAUNCH_SINGLE, false)
        set(value) = prefs.edit { putBoolean(AUTO_LAUNCH_SINGLE, value) }

    /** Detox page: lock the phone when no app is opened within [detoxLockSeconds]. */
    var detoxLock: Boolean
        get() = prefs.getBoolean(DETOX_LOCK, false)
        set(value) = prefs.edit { putBoolean(DETOX_LOCK, value) }

    var detoxLockSeconds: Int
        get() = prefs.getInt(DETOX_LOCK_SECONDS, 15)
        set(value) = prefs.edit { putInt(DETOX_LOCK_SECONDS, value) }

    /** Detox lock bars run out in a row (no app opened) before the phone is locked out; 0 = never. */
    var detoxLockoutAfter: Int
        get() = prefs.getInt(DETOX_LOCKOUT_AFTER, 3)
        set(value) = prefs.edit { putInt(DETOX_LOCKOUT_AFTER, value) }

    /** Minutes of a detox lockout: only calls and the wallet can be used. */
    var detoxLockoutMinutes: Int
        get() = prefs.getInt(DETOX_LOCKOUT_MINUTES, 2)
        set(value) = prefs.edit { putInt(DETOX_LOCKOUT_MINUTES, value) }

    /** Minutes after the last missed lock bar when the count starts again from zero. */
    var detoxLockoutReset: Int
        get() = prefs.getInt(DETOX_LOCKOUT_RESET, 10)
        set(value) = prefs.edit { putInt(DETOX_LOCKOUT_RESET, value) }

    /** Detox home: a bar on home locks the phone when no app is opened within [homeDetoxSeconds]. */
    var homeDetox: Boolean
        get() = prefs.getBoolean(HOME_DETOX, false)
        set(value) = prefs.edit { putBoolean(HOME_DETOX, value) }

    var homeDetoxSeconds: Int
        get() = prefs.getInt(HOME_DETOX_SECONDS, 30)
        set(value) = prefs.edit { putInt(HOME_DETOX_SECONDS, value) }

    /** Detox home bars run out in a row before a lockout; 0 = never. */
    var homeDetoxLockoutAfter: Int
        get() = prefs.getInt(HOME_DETOX_LOCKOUT_AFTER, 3)
        set(value) = prefs.edit { putInt(HOME_DETOX_LOCKOUT_AFTER, value) }

    var homeDetoxLockoutMinutes: Int
        get() = prefs.getInt(HOME_DETOX_LOCKOUT_MINUTES, 2)
        set(value) = prefs.edit { putInt(HOME_DETOX_LOCKOUT_MINUTES, value) }

    /** Minutes after the last missed detox home bar when the count starts again from zero. */
    var homeDetoxLockoutReset: Int
        get() = prefs.getInt(HOME_DETOX_LOCKOUT_RESET, 10)
        set(value) = prefs.edit { putInt(HOME_DETOX_LOCKOUT_RESET, value) }

    /** Detox home on the search page: one of the HOME_DETOX_SEARCH_ values. */
    var homeDetoxSearch: Int
        get() = prefs.getInt(HOME_DETOX_SEARCH, HOME_DETOX_SEARCH_UNLIMITED)
        set(value) = prefs.edit { putInt(HOME_DETOX_SEARCH, value) }

    /** Detox home: the search page's own countdown, when limited. */
    var homeDetoxSearchSeconds: Int
        get() = prefs.getInt(HOME_DETOX_SEARCH_SECONDS, 40)
        set(value) = prefs.edit { putInt(HOME_DETOX_SEARCH_SECONDS, value) }

    /** ToDo alarms: seconds of vibration alone before the sound starts. */
    var alarmVibrateSeconds: Int
        get() = prefs.getInt(ALARM_VIBRATE_SECONDS, 10)
        set(value) = prefs.edit { putInt(ALARM_VIBRATE_SECONDS, value) }

    /** ToDo alarms: seconds for the sound to grow from very low to full volume; 0 = full at once. */
    var alarmRampSeconds: Int
        get() = prefs.getInt(ALARM_RAMP_SECONDS, 30)
        set(value) = prefs.edit { putInt(ALARM_RAMP_SECONDS, value) }

    /** Color of the detox lock countdown bar; 0 = default. */
    var detoxLockColor: Int
        get() = prefs.getInt(DETOX_LOCK_COLOR, 0)
        set(value) = prefs.edit { putInt(DETOX_LOCK_COLOR, value) }

    /** Drawer "News": false = most recent first, true = most notifications first. */
    /** PDFs: true = built-in viewer/editor, false = an external app. */
    var builtInPdf: Boolean
        get() = prefs.getBoolean("BUILT_IN_PDF", true)
        set(value) = prefs.edit { putBoolean("BUILT_IN_PDF", value) }

    /** Photos and videos: true = built-in viewer, false = the gallery app (Fossify). */
    var builtInViewer: Boolean
        get() = prefs.getBoolean("BUILT_IN_VIEWER", true)
        set(value) = prefs.edit { putBoolean("BUILT_IN_VIEWER", value) }

    /** Phone folders from the search: true = built-in explorer, false = the file manager (Solid Explorer). */
    var phoneFoldersInExplorer: Boolean
        get() = prefs.getBoolean("PHONE_FOLDERS_IN_EXPLORER", true)
        set(value) = prefs.edit { putBoolean("PHONE_FOLDERS_IN_EXPLORER", value) }

    /** Home search bar target: 0 Google, 1 phone (2 was OneDrive, now phone). */
    var searchMode: Int
        get() = prefs.getInt("SEARCH_MODE", 0)
        set(value) = prefs.edit { putInt("SEARCH_MODE", value) }

    var newsSortByCount: Boolean
        get() = prefs.getBoolean(NEWS_SORT_BY_COUNT, false)
        set(value) = prefs.edit { putBoolean(NEWS_SORT_BY_COUNT, value) }

    /** Drawer letter index: see Constants.LetterIndex. */
    var letterIndexMode: Int
        get() = prefs.getInt(LETTER_INDEX_MODE, Constants.LetterIndex.ALWAYS)
        set(value) = prefs.edit { putInt(LETTER_INDEX_MODE, value) }

    /** Grid row of the "news" strip (apps with notifications), -1 until placed. */
    var newsRow: Int
        get() = prefs.getInt("NEWS_ROW", -1)
        set(value) = prefs.edit { putInt("NEWS_ROW", value) }

    /** Rounded squares behind the icons (home grid and app list grid). */
    var iconTiles: Boolean
        get() = prefs.getBoolean("ICON_TILES", true)
        set(value) = prefs.edit { putBoolean("ICON_TILES", value) }

    // ---- App drawer layout: list (default) or a grid of icons

    var drawerGrid: Boolean
        get() = prefs.getBoolean("DRAWER_GRID", false)
        set(value) = prefs.edit { putBoolean("DRAWER_GRID", value) }

    /** In the grid, each app on its own card (with the name inside). */
    var drawerCards: Boolean
        get() = prefs.getBoolean("DRAWER_CARDS", true)
        set(value) = prefs.edit { putBoolean("DRAWER_CARDS", value) }

    /** The app grid on cards became the drawer's look: switched on once, then up to the user. */
    fun applyDrawerCardsOnce() {
        if (prefs.getBoolean("DRAWER_CARDS_APPLIED", false)) return
        prefs.edit {
            putBoolean("DRAWER_CARDS_APPLIED", true)
            putBoolean("DRAWER_GRID", true)
            putBoolean("DRAWER_CARDS", true)
        }
    }

    /** Grid columns (2-8). */
    var drawerColumns: Int
        get() = prefs.getInt("DRAWER_COLUMNS", 4).coerceIn(2, 8)
        set(value) = prefs.edit { putInt("DRAWER_COLUMNS", value.coerceIn(2, 8)) }

    /** Grid rows that fit the screen (cells are as tall as needed), 0 = automatic (as tall as their content). */
    var drawerRows: Int
        get() = prefs.getInt("DRAWER_ROWS", 0).coerceIn(0, 12)
        set(value) = prefs.edit { putInt("DRAWER_ROWS", value.coerceIn(0, 12)) }

    /** Icon size in dp in the grid, 0 = automatic (from the cell width). */
    var drawerIconSize: Int
        get() = prefs.getInt("DRAWER_ICON_SIZE", 0)
        set(value) = prefs.edit { putInt("DRAWER_ICON_SIZE", value) }

    /** Show app names in the drawer (grid and list). */
    var drawerLabels: Boolean
        get() = prefs.getBoolean("DRAWER_LABELS", true)
        set(value) = prefs.edit { putBoolean("DRAWER_LABELS", value) }

    /** Drawer font and size; until set, the drawer follows the home app names. */
    var drawerFont: String
        get() = prefs.getString("DRAWER_FONT", null) ?: appFont
        set(value) = prefs.edit { putString("DRAWER_FONT", value) }

    var drawerTextSize: Int
        get() = prefs.getInt("DRAWER_TEXT_SIZE", -1).let { if (it < 0) appTextSize else it }
        set(value) = prefs.edit { putInt("DRAWER_TEXT_SIZE", value) }

    /** Package of the selected icon pack, empty for the system icons. */
    var iconPack: String
        get() = prefs.getString(ICON_PACK, "").orEmpty()
        set(value) = prefs.edit { putString(ICON_PACK, value) }

    var firstOpen: Boolean
        get() = prefs.getBoolean(FIRST_OPEN, true)
        set(value) = prefs.edit { putBoolean(FIRST_OPEN, value).apply() }

    var firstOpenTime: Long
        get() = prefs.getLong(FIRST_OPEN_TIME, 0L)
        set(value) = prefs.edit { putLong(FIRST_OPEN_TIME, value).apply() }

    var firstSettingsOpen: Boolean
        get() = prefs.getBoolean(FIRST_SETTINGS_OPEN, true)
        set(value) = prefs.edit { putBoolean(FIRST_SETTINGS_OPEN, value).apply() }

    var firstHide: Boolean
        get() = prefs.getBoolean(FIRST_HIDE, true)
        set(value) = prefs.edit { putBoolean(FIRST_HIDE, value).apply() }

    var userState: String
        get() = prefs.getString(USER_STATE, Constants.UserState.START).toString()
        set(value) = prefs.edit { putString(USER_STATE, value).apply() }

    var lockModeOn: Boolean
        get() = prefs.getBoolean(LOCK_MODE, false)
        set(value) = prefs.edit { putBoolean(LOCK_MODE, value).apply() }

    /** Swipe up on home: the search page (true) or the app list. */
    var swipeUpSearch: Boolean
        get() = prefs.getBoolean(SWIPE_UP_SEARCH, true)
        set(value) = prefs.edit { putBoolean(SWIPE_UP_SEARCH, value).apply() }

    /** Swipe up on home does nothing: the search opens only from the search bar. */
    var swipeUpOff: Boolean
        get() = prefs.getBoolean(SWIPE_UP_OFF, false)
        set(value) = prefs.edit { putBoolean(SWIPE_UP_OFF, value).apply() }

    /** Home layout locked: icons, widgets and the dock can't be moved, resized, added or removed. */
    var homeLocked: Boolean
        get() = prefs.getBoolean(HOME_LOCKED, false)
        set(value) = prefs.edit { putBoolean(HOME_LOCKED, value).apply() }

    var autoShowKeyboard: Boolean
        get() = prefs.getBoolean(AUTO_SHOW_KEYBOARD, false)
        set(value) = prefs.edit { putBoolean(AUTO_SHOW_KEYBOARD, value).apply() }

    var keyboardMessageShown: Boolean
        get() = prefs.getBoolean(KEYBOARD_MESSAGE, false)
        set(value) = prefs.edit { putBoolean(KEYBOARD_MESSAGE, value).apply() }

    var dailyWallpaper: Boolean
        get() = prefs.getBoolean(DAILY_WALLPAPER, false)
        set(value) = prefs.edit { putBoolean(DAILY_WALLPAPER, value).apply() }

    var dailyWallpaperUrl: String
        get() = prefs.getString(DAILY_WALLPAPER_URL, "").toString()
        set(value) = prefs.edit { putString(DAILY_WALLPAPER_URL, value).apply() }

    var homeAppsNum: Int
        get() = prefs.getInt(HOME_APPS_NUM, 4)
        set(value) = prefs.edit { putInt(HOME_APPS_NUM, value).apply() }

    var homeAlignment: Int
        get() = prefs.getInt(HOME_ALIGNMENT, Gravity.START)
        set(value) = prefs.edit { putInt(HOME_ALIGNMENT, value).apply() }

    var homeBottomAlignment: Boolean
        get() = prefs.getBoolean(HOME_BOTTOM_ALIGNMENT, false)
        set(value) = prefs.edit { putBoolean(HOME_BOTTOM_ALIGNMENT, value).apply() }

    var appLabelAlignment: Int
        get() = prefs.getInt(APP_LABEL_ALIGNMENT, Gravity.START)
        set(value) = prefs.edit { putInt(APP_LABEL_ALIGNMENT, value).apply() }

    /** True once the status bar has been turned on by default (it shows the battery). */
    var statusBarDefaultApplied: Boolean
        get() = prefs.getBoolean("STATUS_BAR_DEFAULT_APPLIED", false)
        set(value) = prefs.edit { putBoolean("STATUS_BAR_DEFAULT_APPLIED", value) }

    var showStatusBar: Boolean
        get() = prefs.getBoolean(STATUS_BAR, false)
        set(value) = prefs.edit { putBoolean(STATUS_BAR, value).apply() }

    var dateTimeVisibility: Int
        get() = prefs.getInt(DATE_TIME_VISIBILITY, Constants.DateTime.ON)
        set(value) = prefs.edit { putInt(DATE_TIME_VISIBILITY, value).apply() }

    var swipeLeftEnabled: Boolean
        get() = prefs.getBoolean(SWIPE_LEFT_ENABLED, true)
        set(value) = prefs.edit { putBoolean(SWIPE_LEFT_ENABLED, value).apply() }

    var swipeRightEnabled: Boolean
        get() = prefs.getBoolean(SWIPE_RIGHT_ENABLED, true)
        set(value) = prefs.edit { putBoolean(SWIPE_RIGHT_ENABLED, value).apply() }

    var appTheme: Int
        get() = prefs.getInt(APP_THEME, AppCompatDelegate.MODE_NIGHT_YES)
        set(value) = prefs.edit { putInt(APP_THEME, value).apply() }

    var textSizeScale: Float
        get() = prefs.getFloat(TEXT_SIZE_SCALE, 1.0f)
        set(value) = prefs.edit { putFloat(TEXT_SIZE_SCALE, value).apply() }

    var boldFont: Boolean
        get() = prefs.getBoolean(BOLD_FONT, false)
        set(value) = prefs.edit { putBoolean(BOLD_FONT, value).apply() }

    var proMessageShown: Boolean
        get() = prefs.getBoolean(PRO_MESSAGE_SHOWN, false)
        set(value) = prefs.edit { putBoolean(PRO_MESSAGE_SHOWN, value).apply() }

    var hideSetDefaultLauncher: Boolean
        get() = prefs.getBoolean(HIDE_SET_DEFAULT_LAUNCHER, false)
        set(value) = prefs.edit { putBoolean(HIDE_SET_DEFAULT_LAUNCHER, value).apply() }

    var screenTimeLastUpdated: Long
        get() = prefs.getLong(SCREEN_TIME_LAST_UPDATED, 0L)
        set(value) = prefs.edit { putLong(SCREEN_TIME_LAST_UPDATED, value).apply() }

    var launcherRestartTimestamp: Long
        get() = prefs.getLong(LAUNCHER_RESTART_TIMESTAMP, 0L)
        set(value) = prefs.edit { putLong(LAUNCHER_RESTART_TIMESTAMP, value).apply() }

    var shownOnDayOfYear: Int
        get() = prefs.getInt(SHOWN_ON_DAY_OF_YEAR, 0)
        set(value) = prefs.edit { putInt(SHOWN_ON_DAY_OF_YEAR, value).apply() }

    // Home button for recents feature disabled
    // var homeButtonShowRecents: Boolean
    //     get() = prefs.getBoolean(HOME_BUTTON_SHOW_RECENTS, false)
    //     set(value) = prefs.edit { putBoolean(HOME_BUTTON_SHOW_RECENTS, value).apply() }

    var hiddenApps: MutableSet<String>
        get() = prefs.getStringSet(HIDDEN_APPS, mutableSetOf()) as MutableSet<String>
        set(value) = prefs.edit { putStringSet(HIDDEN_APPS, value).apply() }

    var hiddenAppsUpdated: Boolean
        get() = prefs.getBoolean(HIDDEN_APPS_UPDATED, false)
        set(value) = prefs.edit { putBoolean(HIDDEN_APPS_UPDATED, value).apply() }

    var toShowHintCounter: Int
        get() = prefs.getInt(SHOW_HINT_COUNTER, 1)
        set(value) = prefs.edit { putInt(SHOW_HINT_COUNTER, value).apply() }

    var aboutClicked: Boolean
        get() = prefs.getBoolean(ABOUT_CLICKED, false)
        set(value) = prefs.edit { putBoolean(ABOUT_CLICKED, value).apply() }

    var rateClicked: Boolean
        get() = prefs.getBoolean(RATE_CLICKED, false)
        set(value) = prefs.edit { putBoolean(RATE_CLICKED, value).apply() }

    var wallpaperMsgShown: Boolean
        get() = prefs.getBoolean(WALLPAPER_MSG_SHOWN, false)
        set(value) = prefs.edit { putBoolean(WALLPAPER_MSG_SHOWN, value).apply() }

    var shareShownTime: Long
        get() = prefs.getLong(SHARE_SHOWN_TIME, 0L)
        set(value) = prefs.edit { putLong(SHARE_SHOWN_TIME, value).apply() }

    var appName1: String
        get() = prefs.getString(APP_NAME_1, "").toString()
        set(value) = prefs.edit { putString(APP_NAME_1, value).apply() }

    var appName2: String
        get() = prefs.getString(APP_NAME_2, "").toString()
        set(value) = prefs.edit { putString(APP_NAME_2, value).apply() }

    var appName3: String
        get() = prefs.getString(APP_NAME_3, "").toString()
        set(value) = prefs.edit { putString(APP_NAME_3, value).apply() }

    var appName4: String
        get() = prefs.getString(APP_NAME_4, "").toString()
        set(value) = prefs.edit { putString(APP_NAME_4, value).apply() }

    var appName5: String
        get() = prefs.getString(APP_NAME_5, "").toString()
        set(value) = prefs.edit { putString(APP_NAME_5, value).apply() }

    var appName6: String
        get() = prefs.getString(APP_NAME_6, "").toString()
        set(value) = prefs.edit { putString(APP_NAME_6, value).apply() }

    var appName7: String
        get() = prefs.getString(APP_NAME_7, "").toString()
        set(value) = prefs.edit { putString(APP_NAME_7, value).apply() }

    var appName8: String
        get() = prefs.getString(APP_NAME_8, "").toString()
        set(value) = prefs.edit { putString(APP_NAME_8, value).apply() }

    var appPackage1: String
        get() = prefs.getString(APP_PACKAGE_1, "").toString()
        set(value) = prefs.edit { putString(APP_PACKAGE_1, value).apply() }

    var appPackage2: String
        get() = prefs.getString(APP_PACKAGE_2, "").toString()
        set(value) = prefs.edit { putString(APP_PACKAGE_2, value).apply() }

    var appPackage3: String
        get() = prefs.getString(APP_PACKAGE_3, "").toString()
        set(value) = prefs.edit { putString(APP_PACKAGE_3, value).apply() }

    var appPackage4: String
        get() = prefs.getString(APP_PACKAGE_4, "").toString()
        set(value) = prefs.edit { putString(APP_PACKAGE_4, value).apply() }

    var appPackage5: String
        get() = prefs.getString(APP_PACKAGE_5, "").toString()
        set(value) = prefs.edit { putString(APP_PACKAGE_5, value).apply() }

    var appPackage6: String
        get() = prefs.getString(APP_PACKAGE_6, "").toString()
        set(value) = prefs.edit { putString(APP_PACKAGE_6, value).apply() }

    var appPackage7: String
        get() = prefs.getString(APP_PACKAGE_7, "").toString()
        set(value) = prefs.edit { putString(APP_PACKAGE_7, value).apply() }

    var appPackage8: String
        get() = prefs.getString(APP_PACKAGE_8, "").toString()
        set(value) = prefs.edit { putString(APP_PACKAGE_8, value).apply() }

    var appActivityClassName1: String?
        get() = prefs.getString(APP_ACTIVITY_CLASS_NAME_1, "").toString()
        set(value) = prefs.edit { putString(APP_ACTIVITY_CLASS_NAME_1, value).apply() }

    var appActivityClassName2: String?
        get() = prefs.getString(APP_ACTIVITY_CLASS_NAME_2, "").toString()
        set(value) = prefs.edit { putString(APP_ACTIVITY_CLASS_NAME_2, value).apply() }

    var appActivityClassName3: String?
        get() = prefs.getString(APP_ACTIVITY_CLASS_NAME_3, "").toString()
        set(value) = prefs.edit { putString(APP_ACTIVITY_CLASS_NAME_3, value).apply() }

    var appActivityClassName4: String?
        get() = prefs.getString(APP_ACTIVITY_CLASS_NAME_4, "").toString()
        set(value) = prefs.edit { putString(APP_ACTIVITY_CLASS_NAME_4, value).apply() }

    var appActivityClassName5: String?
        get() = prefs.getString(APP_ACTIVITY_CLASS_NAME_5, "").toString()
        set(value) = prefs.edit { putString(APP_ACTIVITY_CLASS_NAME_5, value).apply() }

    var appActivityClassName6: String?
        get() = prefs.getString(APP_ACTIVITY_CLASS_NAME_6, "").toString()
        set(value) = prefs.edit { putString(APP_ACTIVITY_CLASS_NAME_6, value).apply() }

    var appActivityClassName7: String?
        get() = prefs.getString(APP_ACTIVITY_CLASS_NAME_7, "").toString()
        set(value) = prefs.edit { putString(APP_ACTIVITY_CLASS_NAME_7, value).apply() }

    var appActivityClassName8: String?
        get() = prefs.getString(APP_ACTIVITY_CLASS_NAME_8, "").toString()
        set(value) = prefs.edit { putString(APP_ACTIVITY_CLASS_NAME_8, value).apply() }

    var appUser1: String
        get() = prefs.getString(APP_USER_1, "").toString()
        set(value) = prefs.edit { putString(APP_USER_1, value).apply() }

    var appUser2: String
        get() = prefs.getString(APP_USER_2, "").toString()
        set(value) = prefs.edit { putString(APP_USER_2, value).apply() }

    var appUser3: String
        get() = prefs.getString(APP_USER_3, "").toString()
        set(value) = prefs.edit { putString(APP_USER_3, value).apply() }

    var appUser4: String
        get() = prefs.getString(APP_USER_4, "").toString()
        set(value) = prefs.edit { putString(APP_USER_4, value).apply() }

    var appUser5: String
        get() = prefs.getString(APP_USER_5, "").toString()
        set(value) = prefs.edit { putString(APP_USER_5, value).apply() }

    var appUser6: String
        get() = prefs.getString(APP_USER_6, "").toString()
        set(value) = prefs.edit { putString(APP_USER_6, value).apply() }

    var appUser7: String
        get() = prefs.getString(APP_USER_7, "").toString()
        set(value) = prefs.edit { putString(APP_USER_7, value).apply() }

    var appUser8: String
        get() = prefs.getString(APP_USER_8, "").toString()
        set(value) = prefs.edit { putString(APP_USER_8, value).apply() }

    var appNameSwipeLeft: String
        get() = prefs.getString(APP_NAME_SWIPE_LEFT, "Camera").toString()
        set(value) = prefs.edit { putString(APP_NAME_SWIPE_LEFT, value).apply() }

    var appNameSwipeRight: String
        get() = prefs.getString(APP_NAME_SWIPE_RIGHT, "Phone").toString()
        set(value) = prefs.edit { putString(APP_NAME_SWIPE_RIGHT, value).apply() }

    var appPackageSwipeLeft: String
        get() = prefs.getString(APP_PACKAGE_SWIPE_LEFT, "").toString()
        set(value) = prefs.edit { putString(APP_PACKAGE_SWIPE_LEFT, value).apply() }

    var appActivityClassNameSwipeLeft: String?
        get() = prefs.getString(APP_ACTIVITY_CLASS_NAME_SWIPE_LEFT, "").toString()
        set(value) = prefs.edit { putString(APP_ACTIVITY_CLASS_NAME_SWIPE_LEFT, value).apply() }

    var appPackageSwipeRight: String
        get() = prefs.getString(APP_PACKAGE_SWIPE_RIGHT, "").toString()
        set(value) = prefs.edit { putString(APP_PACKAGE_SWIPE_RIGHT, value).apply() }

    var appActivityClassNameRight: String?
        get() = prefs.getString(APP_ACTIVITY_CLASS_NAME_SWIPE_RIGHT, "").toString()
        set(value) = prefs.edit { putString(APP_ACTIVITY_CLASS_NAME_SWIPE_RIGHT, value).apply() }

    var appUserSwipeLeft: String
        get() = prefs.getString(APP_USER_SWIPE_LEFT, "").toString()
        set(value) = prefs.edit { putString(APP_USER_SWIPE_LEFT, value).apply() }

    var appUserSwipeRight: String
        get() = prefs.getString(APP_USER_SWIPE_RIGHT, "").toString()
        set(value) = prefs.edit { putString(APP_USER_SWIPE_RIGHT, value).apply() }

    var clockAppPackage: String
        get() = prefs.getString(CLOCK_APP_PACKAGE, "").toString()
        set(value) = prefs.edit { putString(CLOCK_APP_PACKAGE, value).apply() }

    var clockAppUser: String
        get() = prefs.getString(CLOCK_APP_USER, "").toString()
        set(value) = prefs.edit { putString(CLOCK_APP_USER, value).apply() }

    var clockAppClassName: String?
        get() = prefs.getString(CLOCK_APP_CLASS_NAME, "").toString()
        set(value) = prefs.edit { putString(CLOCK_APP_CLASS_NAME, value).apply() }

    var calendarAppPackage: String
        get() = prefs.getString(CALENDAR_APP_PACKAGE, "").toString()
        set(value) = prefs.edit { putString(CALENDAR_APP_PACKAGE, value).apply() }

    var calendarAppUser: String
        get() = prefs.getString(CALENDAR_APP_USER, "").toString()
        set(value) = prefs.edit { putString(CALENDAR_APP_USER, value).apply() }

    var calendarAppClassName: String?
        get() = prefs.getString(CALENDAR_APP_CLASS_NAME, "").toString()
        set(value) = prefs.edit { putString(CALENDAR_APP_CLASS_NAME, value).apply() }

    var screenTimeAppPackage: String
        get() = prefs.getString(SCREEN_TIME_APP_PACKAGE, "").toString()
        set(value) = prefs.edit { putString(SCREEN_TIME_APP_PACKAGE, value).apply() }

    var screenTimeAppUser: String
        get() = prefs.getString(SCREEN_TIME_APP_USER, "").toString()
        set(value) = prefs.edit { putString(SCREEN_TIME_APP_USER, value).apply() }

    var screenTimeAppClassName: String?
        get() = prefs.getString(SCREEN_TIME_APP_CLASS_NAME, "").toString()
        set(value) = prefs.edit { putString(SCREEN_TIME_APP_CLASS_NAME, value).apply() }

    var isShortcut1: Boolean
        get() = prefs.getBoolean(IS_SHORTCUT_1, false)
        set(value) = prefs.edit { putBoolean(IS_SHORTCUT_1, value) }

    var shortcutId1: String
        get() = prefs.getString(SHORTCUT_ID_1, "").toString()
        set(value) = prefs.edit { putString(SHORTCUT_ID_1, value) }

    var isShortcut2: Boolean
        get() = prefs.getBoolean(IS_SHORTCUT_2, false)
        set(value) = prefs.edit { putBoolean(IS_SHORTCUT_2, value) }

    var shortcutId2: String
        get() = prefs.getString(SHORTCUT_ID_2, "").toString()
        set(value) = prefs.edit { putString(SHORTCUT_ID_2, value) }

    var isShortcut3: Boolean
        get() = prefs.getBoolean(IS_SHORTCUT_3, false)
        set(value) = prefs.edit { putBoolean(IS_SHORTCUT_3, value) }

    var shortcutId3: String
        get() = prefs.getString(SHORTCUT_ID_3, "").toString()
        set(value) = prefs.edit { putString(SHORTCUT_ID_3, value) }

    var isShortcut4: Boolean
        get() = prefs.getBoolean(IS_SHORTCUT_4, false)
        set(value) = prefs.edit { putBoolean(IS_SHORTCUT_4, value) }

    var shortcutId4: String
        get() = prefs.getString(SHORTCUT_ID_4, "").toString()
        set(value) = prefs.edit { putString(SHORTCUT_ID_4, value) }

    var isShortcut5: Boolean
        get() = prefs.getBoolean(IS_SHORTCUT_5, false)
        set(value) = prefs.edit { putBoolean(IS_SHORTCUT_5, value) }

    var shortcutId5: String
        get() = prefs.getString(SHORTCUT_ID_5, "").toString()
        set(value) = prefs.edit { putString(SHORTCUT_ID_5, value) }

    var isShortcut6: Boolean
        get() = prefs.getBoolean(IS_SHORTCUT_6, false)
        set(value) = prefs.edit { putBoolean(IS_SHORTCUT_6, value) }

    var shortcutId6: String
        get() = prefs.getString(SHORTCUT_ID_6, "").toString()
        set(value) = prefs.edit { putString(SHORTCUT_ID_6, value) }

    var isShortcut7: Boolean
        get() = prefs.getBoolean(IS_SHORTCUT_7, false)
        set(value) = prefs.edit { putBoolean(IS_SHORTCUT_7, value) }

    var shortcutId7: String
        get() = prefs.getString(SHORTCUT_ID_7, "").toString()
        set(value) = prefs.edit { putString(SHORTCUT_ID_7, value) }

    var isShortcut8: Boolean
        get() = prefs.getBoolean(IS_SHORTCUT_8, false)
        set(value) = prefs.edit { putBoolean(IS_SHORTCUT_8, value) }

    var shortcutId8: String
        get() = prefs.getString(SHORTCUT_ID_8, "").toString()
        set(value) = prefs.edit { putString(SHORTCUT_ID_8, value) }

    var shortcutIdSwipeLeft: String
        get() = prefs.getString(SHORTCUT_ID_SWIPE_LEFT, "").toString()
        set(value) = prefs.edit { putString(SHORTCUT_ID_SWIPE_LEFT, value) }

    var isShortcutSwipeLeft: Boolean
        get() = prefs.getBoolean(IS_SHORTCUT_SWIPE_LEFT, false)
        set(value) = prefs.edit { putBoolean(IS_SHORTCUT_SWIPE_LEFT, value) }

    var shortcutIdSwipeRight: String
        get() = prefs.getString(SHORTCUT_ID_SWIPE_RIGHT, "").toString()
        set(value) = prefs.edit { putString(SHORTCUT_ID_SWIPE_RIGHT, value) }

    var isShortcutSwipeRight: Boolean
        get() = prefs.getBoolean(IS_SHORTCUT_SWIPE_RIGHT, false)
        set(value) = prefs.edit { putBoolean(IS_SHORTCUT_SWIPE_RIGHT, value) }

    fun getAppName(location: Int): String {
        return when (location) {
            1 -> prefs.getString(APP_NAME_1, "").toString()
            2 -> prefs.getString(APP_NAME_2, "").toString()
            3 -> prefs.getString(APP_NAME_3, "").toString()
            4 -> prefs.getString(APP_NAME_4, "").toString()
            5 -> prefs.getString(APP_NAME_5, "").toString()
            6 -> prefs.getString(APP_NAME_6, "").toString()
            7 -> prefs.getString(APP_NAME_7, "").toString()
            8 -> prefs.getString(APP_NAME_8, "").toString()
            else -> ""
        }
    }

    fun getAppPackage(location: Int): String {
        return when (location) {
            1 -> prefs.getString(APP_PACKAGE_1, "").toString()
            2 -> prefs.getString(APP_PACKAGE_2, "").toString()
            3 -> prefs.getString(APP_PACKAGE_3, "").toString()
            4 -> prefs.getString(APP_PACKAGE_4, "").toString()
            5 -> prefs.getString(APP_PACKAGE_5, "").toString()
            6 -> prefs.getString(APP_PACKAGE_6, "").toString()
            7 -> prefs.getString(APP_PACKAGE_7, "").toString()
            8 -> prefs.getString(APP_PACKAGE_8, "").toString()
            else -> ""
        }
    }

    fun getAppActivityClassName(location: Int): String {
        return when (location) {
            1 -> prefs.getString(APP_ACTIVITY_CLASS_NAME_1, "").toString()
            2 -> prefs.getString(APP_ACTIVITY_CLASS_NAME_2, "").toString()
            3 -> prefs.getString(APP_ACTIVITY_CLASS_NAME_3, "").toString()
            4 -> prefs.getString(APP_ACTIVITY_CLASS_NAME_4, "").toString()
            5 -> prefs.getString(APP_ACTIVITY_CLASS_NAME_5, "").toString()
            6 -> prefs.getString(APP_ACTIVITY_CLASS_NAME_6, "").toString()
            7 -> prefs.getString(APP_ACTIVITY_CLASS_NAME_7, "").toString()
            8 -> prefs.getString(APP_ACTIVITY_CLASS_NAME_8, "").toString()
            else -> ""
        }
    }

    fun getAppUser(location: Int): String {
        return when (location) {
            1 -> prefs.getString(APP_USER_1, "").toString()
            2 -> prefs.getString(APP_USER_2, "").toString()
            3 -> prefs.getString(APP_USER_3, "").toString()
            4 -> prefs.getString(APP_USER_4, "").toString()
            5 -> prefs.getString(APP_USER_5, "").toString()
            6 -> prefs.getString(APP_USER_6, "").toString()
            7 -> prefs.getString(APP_USER_7, "").toString()
            8 -> prefs.getString(APP_USER_8, "").toString()
            else -> ""
        }
    }

    fun getShortcutId(location: Int): String {
        return when (location) {
            1 -> shortcutId1
            2 -> shortcutId2
            3 -> shortcutId3
            4 -> shortcutId4
            5 -> shortcutId5
            6 -> shortcutId6
            7 -> shortcutId7
            8 -> shortcutId8
            else -> ""
        }
    }

    fun getIsShortcut(location: Int): Boolean {
        return when (location) {
            1 -> isShortcut1
            2 -> isShortcut2
            3 -> isShortcut3
            4 -> isShortcut4
            5 -> isShortcut5
            6 -> isShortcut6
            7 -> isShortcut7
            8 -> isShortcut8
            else -> false
        }
    }

    fun setAppActivityClassName(location: Int, activityClassName: String) {
        when (location) {
            1 -> appActivityClassName1 = activityClassName
            2 -> appActivityClassName2 = activityClassName
            3 -> appActivityClassName3 = activityClassName
            4 -> appActivityClassName4 = activityClassName
            5 -> appActivityClassName5 = activityClassName
            6 -> appActivityClassName6 = activityClassName
            7 -> appActivityClassName7 = activityClassName
            8 -> appActivityClassName8 = activityClassName
        }
    }

    fun updateAppActivityClassName(packageName: String, activityClassName: String) {
        for (i in 1..8) {
            if (getAppPackage(i) == packageName) setAppActivityClassName(i, activityClassName)
        }
        if (clockAppPackage == packageName) clockAppClassName = activityClassName
        if (calendarAppPackage == packageName) calendarAppClassName = activityClassName
        if (screenTimeAppPackage == packageName) screenTimeAppClassName = activityClassName
        if (appPackageSwipeLeft == packageName) appActivityClassNameSwipeLeft = activityClassName
        if (appPackageSwipeRight == packageName) appActivityClassNameRight = activityClassName
    }

    fun getAppRenameLabel(appPackage: String): String = prefs.getString(appPackage, "").toString()

    fun setAppRenameLabel(appPackage: String, renameLabel: String) = prefs.edit { putString(appPackage, renameLabel) }

    companion object {
        /** Detox home on the search page: the countdown goes on from home's. */
        const val HOME_DETOX_SEARCH_SHARED = 0
        /** Detox home on the search page: the countdown waits. */
        const val HOME_DETOX_SEARCH_UNLIMITED = 1
        /** Detox home on the search page: a countdown of its own ([homeDetoxSearchSeconds]). */
        const val HOME_DETOX_SEARCH_LIMITED = 2
    }
}
