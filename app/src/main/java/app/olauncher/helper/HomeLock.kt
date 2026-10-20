package app.olauncher.helper

import android.content.Context
import android.view.View
import app.olauncher.R
import app.olauncher.data.Prefs

/**
 * The home lock (from the home's long-press menu): while on, icons, widgets, the news strip and
 * the dock can't be moved, resized, added or removed, so a stray touch changes nothing.
 */
object HomeLock {

    fun isOn(context: Context) = Prefs(context).homeLocked

    /** The menu's entries without those that change the home's layout, while locked. */
    fun filter(context: Context, entries: List<MenuEntry>): List<MenuEntry> {
        if (!isOn(context)) return entries
        val editing = listOf(
            R.string.ml_widget_resize, R.string.ml_widget_remove, R.string.ml_remove_from_home,
            R.string.ml_dock_move_left, R.string.ml_dock_move_right, R.string.ml_dock_add, R.string.ml_dock_remove,
        ).map { context.getString(it) }.toSet()
        return entries.filter { it.label !in editing }
    }

    fun showLockedToast(context: Context) = context.showToast(context.getString(R.string.ml_home_locked))

    /** [showMenuDialog] for an item on home: without the layout entries while locked. */
    fun Context.showHomeItemMenu(title: String, entries: List<MenuEntry>, header: ((OlDialog) -> View?)? = null) {
        val shown = filter(this, entries)
        if (shown.isEmpty()) showLockedToast(this) else showMenuDialog(title, shown, header)
    }
}
