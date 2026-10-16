package app.olauncher.ui

import android.app.Activity
import app.olauncher.R
import app.olauncher.helper.showToast

/**
 * OneDrive search for the home search bar. Not connected yet: the Microsoft sign-in comes in a
 * later version, until then the bar tells the user it's coming.
 */
class OneDriveSearch(private val activity: Activity) {

    class Item(val name: String, val path: String?, val webUrl: String)

    fun isConnected(): Boolean = false

    /** Starts the Microsoft sign-in; true when it opened another screen (the browser). */
    fun connect(): Boolean {
        activity.showToast(activity.getString(R.string.ml_onedrive_soon))
        return false
    }

    /** Files matching [query], or null on errors (e.g. expired sign-in). */
    fun search(query: String): List<Item>? = null
}
