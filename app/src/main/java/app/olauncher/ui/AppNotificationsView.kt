package app.olauncher.ui

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Typeface
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.helper.NotificationListener
import app.olauncher.helper.OlDialog
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import kotlin.math.abs

/**
 * Notifications of an app shown at the top of its long-press menu, like Nova: tap to open,
 * swipe sideways to dismiss.
 */
object AppNotificationsView {

    /** Header factory for [app.olauncher.helper.showMenuDialog]; null when the app has no notifications. */
    fun create(context: Context, app: AppModel.App): ((OlDialog) -> View?)? {
        val listener = NotificationListener.instance ?: return null
        val items = listener.activeFor(app.appPackage, app.user.toString())
        if (items.isEmpty()) return null
        return { dialog -> build(dialog, items, listener) }
    }

    private fun build(dialog: OlDialog, items: List<StatusBarNotification>, listener: NotificationListener): View {
        val ctx = dialog.context
        val primary = ctx.getColorFromAttr(R.attr.primaryColor)
        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12.dpToPx(), 0, 4.dpToPx())
        }
        items.take(MAX_SHOWN).forEach { sbn ->
            val extras = sbn.notification.extras
            val title = extras?.getCharSequence(Notification.EXTRA_TITLE)
            val text = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras?.getCharSequence(Notification.EXTRA_TEXT)
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.rounded_rect_shade_color)
                setPadding(14.dpToPx(), 10.dpToPx(), 14.dpToPx(), 10.dpToPx())
            }
            card.addView(TextView(ctx).apply {
                this.text = title ?: ""
                setTextColor(primary)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            if (!text.isNullOrBlank()) card.addView(TextView(ctx).apply {
                this.text = text
                setTextColor(primary)
                alpha = 0.8f
                textSize = 14f
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
            })
            list.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 8.dpToPx()
            })
            attachSwipe(card,
                onTap = {
                    try {
                        sbn.notification.contentIntent?.send()
                    } catch (e: PendingIntent.CanceledException) {
                        e.printStackTrace()
                    }
                    if (sbn.notification.flags and Notification.FLAG_AUTO_CANCEL != 0) listener.dismiss(sbn.key)
                    dialog.dismiss()
                },
                onDismissed = {
                    listener.dismiss(sbn.key)
                    list.removeView(card)
                    if (list.childCount == 0) list.visibility = View.GONE
                },
            )
        }
        return list
    }

    /** Horizontal swipe beyond a third of the width dismisses; a tap opens. */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachSwipe(view: View, onTap: () -> Unit, onDismissed: () -> Unit) {
        val slop = ViewConfiguration.get(view.context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var swiping = false
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    swiping = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    if (!swiping && abs(dx) > slop && abs(dx) > abs(e.rawY - downY)) {
                        swiping = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (swiping) {
                        v.translationX = dx
                        v.alpha = 1f - (abs(dx) / v.width).coerceIn(0f, 0.9f)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = e.rawX - downX
                    if (swiping && abs(dx) > v.width / 3f && e.actionMasked == MotionEvent.ACTION_UP) {
                        v.animate().translationX(if (dx > 0) v.width.toFloat() else -v.width.toFloat()).alpha(0f)
                            .setDuration(150).withEndAction { onDismissed() }.start()
                    } else {
                        v.animate().translationX(0f).alpha(1f).setDuration(150).start()
                        if (!swiping && e.actionMasked == MotionEvent.ACTION_UP && abs(e.rawY - downY) < slop) onTap()
                    }
                    true
                }

                else -> false
            }
        }
    }

    private const val MAX_SHOWN = 6
}
