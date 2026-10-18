package app.olauncher.helper

import android.content.Context
import androidx.core.content.edit
import app.olauncher.data.AppModel
import app.olauncher.data.DrawerTabsRepository

/** The apps opened last from the launcher, newest first: shown on the search page before typing. */
object RecentApps {

    private const val PREFS = "app.minimalauncher.recent"
    private const val KEY = "KEYS"
    private const val MAX = 12

    fun record(context: Context, app: AppModel.App) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = DrawerTabsRepository.key(app)
        val keys = listOf(key) + load(context).filter { it != key }
        prefs.edit { putString(KEY, keys.take(MAX).joinToString("\n")) }
    }

    fun load(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?.split("\n")?.filter { it.isNotBlank() }.orEmpty()

    /** The recent ones among [apps], in the order they were opened. */
    fun <T : AppModel> of(context: Context, apps: List<T>): List<T> {
        val byKey = apps.associateBy { DrawerTabsRepository.key(it) }
        return load(context).mapNotNull { byKey[it] }
    }
}
