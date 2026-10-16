package app.olauncher.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import androidx.core.view.isVisible
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.helper.MenuEntry
import app.olauncher.helper.dpToPx
import app.olauncher.helper.showMenuDialog

/** Red pill with a notification count; tapping it lists the app's notifications. */
object NotificationBadge {

    fun create(context: Context, textSp: Float = 11f): TextView = TextView(context).apply {
        setTextColor(0xFFFFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        includeFontPadding = false
        minWidth = 20.dpToPx()
        minHeight = 20.dpToPx()
        setPadding(6.dpToPx(), 2.dpToPx(), 6.dpToPx(), 2.dpToPx())
        background = GradientDrawable().apply {
            cornerRadius = 10.dpToPx().toFloat()
            setColor(HomeSectionsController.NOTIFICATION_COLOR)
        }
        isClickable = true
        isVisible = false
    }

    fun bind(badge: TextView, count: Int, onTap: (() -> Unit)?) {
        badge.isVisible = count > 0
        badge.text = if (count > 99) "99+" else count.toString()
        badge.setOnClickListener { onTap?.invoke() }
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
