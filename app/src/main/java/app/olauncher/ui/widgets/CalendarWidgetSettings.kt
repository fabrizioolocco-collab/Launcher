package app.olauncher.ui.widgets

import android.app.Activity
import android.graphics.Typeface
import app.olauncher.R
import app.olauncher.data.CalendarText
import app.olauncher.data.CalendarTextStyle
import app.olauncher.data.CalendarWidgetConfig
import app.olauncher.helper.CalendarEvents
import app.olauncher.helper.FontManager
import app.olauncher.helper.MenuEntry
import app.olauncher.helper.showColorPickerDialog
import app.olauncher.helper.showMenuDialog
import app.olauncher.helper.showMultiSelectDialog
import app.olauncher.helper.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings of a calendar widget: calendars, past events, days, what to show, fonts and sizes. */
object CalendarWidgetSettings {

    private val SIZES = listOf(8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 22, 24, 26, 28, 32, 36, 40)
    private val DAYS = listOf(1, 2, 3, 4, 5, 7, 14)
    private val BACKGROUNDS = listOf(0, 15, 25, 40, 50, 65, 80, 100)
    private val STYLES = listOf(Typeface.NORMAL, Typeface.BOLD, Typeface.ITALIC, Typeface.BOLD_ITALIC)


    fun show(activity: Activity, view: CalendarWidgetView, scope: CoroutineScope) {
        val c = view.config
        val reopen = { show(activity, view, scope) }
        fun toggle(label: Int, value: Boolean, set: (CalendarWidgetConfig, Boolean) -> Unit) =
            MenuEntry(activity.getString(label), checked = value, onToggle = { v ->
                view.updateConfig { set(it, v) }
                true
            })

        val pastNames = listOf(R.string.ml_cal_past_show, R.string.ml_cal_past_dim, R.string.ml_cal_past_hide).map { activity.getString(it) }
        activity.showMenuDialog(
            activity.getString(R.string.ml_cal_settings),
            listOf(
                MenuEntry(activity.getString(R.string.ml_cal_calendars, calendarsLabel(activity, c))) {
                    pickCalendars(activity, view, scope, reopen)
                },
                MenuEntry(activity.getString(R.string.ml_cal_past, pastNames[c.pastMode.coerceIn(0, 2)])) {
                    choose(activity, activity.getString(R.string.ml_cal_past_title), pastNames, c.pastMode) { i ->
                        view.updateConfig { it.pastMode = i }
                        reopen()
                    }
                },
                MenuEntry(activity.getString(R.string.ml_cal_days, c.days)) {
                    choose(activity, activity.getString(R.string.ml_cal_days_title), DAYS.map { it.toString() }, DAYS.indexOf(c.days)) { i ->
                        view.updateConfig { it.days = DAYS[i] }
                        reopen()
                    }
                },
                MenuEntry(activity.getString(R.string.ml_cal_background, c.background)) {
                    choose(activity, activity.getString(R.string.ml_cal_background_title), BACKGROUNDS.map { "$it%" }, BACKGROUNDS.indexOf(c.background)) { i ->
                        view.updateConfig { it.background = BACKGROUNDS[i] }
                        reopen()
                    }
                },
                MenuEntry(activity.getString(R.string.ml_cal_progress_fill, colorLabel(activity, c.progressFill))) {
                    activity.showColorPickerDialog(activity.getString(R.string.ml_cal_progress_fill_title), c.progressFill, activity.getString(R.string.ml_default)) { color ->
                        view.updateConfig { it.progressFill = color }
                        reopen()
                    }
                },
                MenuEntry(activity.getString(R.string.ml_cal_progress_track, colorLabel(activity, c.progressTrack))) {
                    activity.showColorPickerDialog(activity.getString(R.string.ml_cal_progress_track_title), c.progressTrack, activity.getString(R.string.ml_default)) { color ->
                        view.updateConfig { it.progressTrack = color }
                        reopen()
                    }
                },
                MenuEntry(activity.getString(R.string.ml_cal_texts)) { showTexts(activity, view, reopen) },
                toggle(R.string.ml_cal_show_header, c.showHeader) { cfg, v -> cfg.showHeader = v },
                toggle(R.string.ml_cal_show_clock, c.showClock) { cfg, v -> cfg.showClock = v },
                toggle(R.string.ml_cal_short_date, c.shortDate) { cfg, v -> cfg.shortDate = v },
                toggle(R.string.ml_cal_show_week, c.showWeek) { cfg, v -> cfg.showWeek = v },
                toggle(R.string.ml_cal_swipe_days, c.swipeDays) { cfg, v -> cfg.swipeDays = v },
                toggle(R.string.ml_cal_show_arrows, c.showArrows) { cfg, v -> cfg.showArrows = v },
                toggle(R.string.ml_cal_show_progress, c.showProgress) { cfg, v -> cfg.showProgress = v },
                toggle(R.string.ml_cal_show_status, c.showStatus) { cfg, v -> cfg.showStatus = v },
                toggle(R.string.ml_cal_show_all_day, c.showAllDay) { cfg, v -> cfg.showAllDay = v },
                toggle(R.string.ml_cal_show_declined, c.showDeclined) { cfg, v -> cfg.showDeclined = v },
            )
        )
    }

    private fun colorLabel(activity: Activity, color: Int) =
        if (color == 0) activity.getString(R.string.ml_default) else "#%06X".format(color and 0xFFFFFF)

    private fun calendarsLabel(activity: Activity, c: CalendarWidgetConfig) =
        if (c.calendars.isEmpty()) activity.getString(R.string.ml_cal_calendars_all)
        else activity.getString(R.string.ml_cal_calendars_count, c.calendars.size)

    /** A list of options; the current one is marked. */
    private fun choose(activity: Activity, title: String, options: List<String>, current: Int, onPick: (Int) -> Unit) {
        activity.showMenuDialog(title, options.mapIndexed { i, label ->
            MenuEntry(if (i == current) "✓  $label" else label) { onPick(i) }
        })
    }

    private fun pickCalendars(activity: Activity, view: CalendarWidgetView, scope: CoroutineScope, then: () -> Unit) {
        if (!CalendarEvents.hasPermission(activity)) {
            activity.showToast(activity.getString(R.string.ml_cal_permission))
            return
        }
        scope.launch {
            val calendars = withContext(Dispatchers.IO) { CalendarEvents.calendars(activity.applicationContext) }
            if (calendars.isEmpty()) {
                activity.showToast(activity.getString(R.string.ml_cal_no_calendars))
                return@launch
            }
            val chosen = view.config.calendars
            val checked = calendars.indices.filter { i ->
                if (chosen.isEmpty()) calendars[i].visible else calendars[i].id in chosen
            }.toSet()
            val labels = calendars.map { if (it.account.isBlank() || it.account == it.name) it.name else "${it.name}  ·  ${it.account}" }
            activity.showMultiSelectDialog(
                activity.getString(R.string.ml_cal_calendars_title), labels, checked, activity.getString(R.string.ml_done)
            ) { selected ->
                // None ticked: back to the calendars visible in the calendar app
                view.updateConfig { cfg -> cfg.calendars = selected.map { calendars[it].id }.toSet() }
                then()
            }
        }
    }

    private fun textName(t: CalendarText) = when (t) {
        CalendarText.CLOCK -> R.string.ml_cal_text_clock
        CalendarText.HEADER -> R.string.ml_cal_text_header
        CalendarText.WEEK -> R.string.ml_cal_text_week
        CalendarText.DAY -> R.string.ml_cal_text_day
        CalendarText.TIME -> R.string.ml_cal_text_time
        CalendarText.TITLE -> R.string.ml_cal_text_title
        CalendarText.STATUS -> R.string.ml_cal_text_status
    }

    private fun showTexts(activity: Activity, view: CalendarWidgetView, back: () -> Unit) {
        activity.showMenuDialog(
            activity.getString(R.string.ml_cal_texts),
            CalendarText.entries.map { t ->
                MenuEntry(activity.getString(textName(t))) { showText(activity, view, t) { showTexts(activity, view, back) } }
            } + MenuEntry(activity.getString(R.string.ml_cal_reset_texts)) {
                view.updateConfig { cfg -> CalendarText.entries.forEach { cfg.texts[it] = CalendarTextStyle(sizeSp = it.defaultSize, style = it.defaultStyle) } }
                back()
            }
        )
    }

    private fun showText(activity: Activity, view: CalendarWidgetView, t: CalendarText, back: () -> Unit) {
        val s = view.config.text(t)
        val reopen = { showText(activity, view, t, back) }
        val styleNames = listOf(R.string.ml_cal_style_normal, R.string.ml_cal_style_bold, R.string.ml_cal_style_italic, R.string.ml_cal_style_bold_italic)
            .map { activity.getString(it) }
        activity.showMenuDialog(
            activity.getString(textName(t)),
            listOf(
                MenuEntry(activity.getString(R.string.ml_cal_font, FontManager.label(activity, s.font))) {
                    val fonts = FontManager.options(activity)
                    val ids = listOf("") + fonts.map { it.id }
                    val labels = listOf(activity.getString(R.string.ml_default)) + fonts.map { it.label }
                    choose(activity, activity.getString(R.string.ml_cal_font_title), labels, ids.indexOf(s.font)) { i ->
                        view.updateConfig { it.text(t).font = ids[i] }
                        reopen()
                    }
                },
                MenuEntry(activity.getString(R.string.ml_cal_size, s.sizeSp)) {
                    choose(activity, activity.getString(R.string.ml_cal_size_title), SIZES.map { "$it sp" }, SIZES.indexOf(s.sizeSp)) { i ->
                        view.updateConfig { it.text(t).sizeSp = SIZES[i] }
                        reopen()
                    }
                },
                MenuEntry(activity.getString(R.string.ml_cal_style, styleNames[STYLES.indexOf(s.style).coerceAtLeast(0)])) {
                    choose(activity, activity.getString(R.string.ml_cal_style_title), styleNames, STYLES.indexOf(s.style)) { i ->
                        view.updateConfig { it.text(t).style = STYLES[i] }
                        reopen()
                    }
                },
                MenuEntry(activity.getString(R.string.ml_cal_color, colorLabel(activity, s.color))) {
                    activity.showColorPickerDialog(activity.getString(R.string.ml_cal_color_title), s.color, activity.getString(R.string.ml_default)) { color ->
                        view.updateConfig { it.text(t).color = color }
                        reopen()
                    }
                },
                MenuEntry(activity.getString(R.string.ml_cal_reset_text)) {
                    view.updateConfig { it.texts[t] = CalendarTextStyle(sizeSp = t.defaultSize, style = t.defaultStyle) }
                    reopen()
                },
                MenuEntry(activity.getString(R.string.ml_cal_back)) { back() },
            )
        )
    }
}
