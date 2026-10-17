package app.olauncher.ui

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.Drawable
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.helper.MenuEntry
import app.olauncher.helper.showMenuDialog

/**
 * Apps with notifications show their icon on red (instead of a count): tapping such an app lists
 * its notifications, and the icon goes back to normal once they're all gone.
 */
object NotificationBadge {

    /**
     * Recolors by brightness: the dark background becomes the notification red, white parts
     * stay white (made for white-on-black icon packs, so the whole shape stands out; colored
     * icons turn into shades between red and white). Transparent parts stay transparent.
     */
    val redFilter: ColorMatrixColorFilter by lazy {
        val c = HomeSectionsController.NOTIFICATION_COLOR
        val r = ((c shr 16) and 0xFF) / 255f
        val g = ((c shr 8) and 0xFF) / 255f
        val b = (c and 0xFF) / 255f
        // out = red + (white - red) * brightness
        fun row(base: Float) = (1f - base).let { k -> floatArrayOf(0.299f * k, 0.587f * k, 0.114f * k, 0f, base * 255f) }
        ColorMatrixColorFilter(ColorMatrix(row(r) + row(g) + row(b) + floatArrayOf(0f, 0f, 0f, 1f, 0f)))
    }

    /** Red icon when [notified], normal otherwise. */
    fun tint(drawable: Drawable, notified: Boolean): Drawable = drawable.mutate().apply {
        colorFilter = if (notified) redFilter else null
    }

    /** Notifications of [app] (tap opens, swipe dismisses) plus "Open app". */
    fun showNotifications(context: Context, app: AppModel.App, openApp: () -> Unit) {
        context.showMenuDialog(
            app.appLabel,
            listOf(MenuEntry(context.getString(R.string.ml_open_app)) { openApp() }),
            header = AppNotificationsView.create(context, app, closeWhenEmpty = true),
        )
    }
}
