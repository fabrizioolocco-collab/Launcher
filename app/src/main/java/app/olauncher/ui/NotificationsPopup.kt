package app.olauncher.ui

import android.annotation.SuppressLint
import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Prefs
import app.olauncher.helper.IconPackManager
import app.olauncher.helper.NotificationListener
import app.olauncher.helper.OlDialog
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.getScreenDimensions
import app.olauncher.helper.showToast
import java.util.Date
import kotlin.math.abs

/**
 * The notifications of an app, opened by tapping an app that has some: a card with the app's
 * icon, name and count, one row per notification (chat, time, message) and "Clear all" /
 * "Open app" at the bottom.
 *
 * Each row slides left: a little shows "Reply" underneath (releasing there opens the reply field
 * under the message, sent through the notification's own reply action); past half the row it
 * turns red and releasing clears the notification. A tap opens it.
 */
object NotificationsPopup {

    private const val REPLY_WIDTH_DP = 96
    private const val REPLY_AT_DP = 50
    private const val REPLY_COLOR = 0xFF2A5D8F.toInt()
    private const val DELETE_COLOR = 0xFFB3261E.toInt()
    private const val MAX_SHOWN = 8

    fun show(context: Context, app: AppModel.App, openApp: () -> Unit): Boolean {
        val listener = NotificationListener.instance ?: return false
        val items = listener.activeFor(app.appPackage, app.user.toString())
        if (items.isEmpty()) return false
        val dialog = OlDialog(context)
        val ctx = dialog.context
        val primary = ctx.getColorFromAttr(R.attr.primaryColor)
        val surface = ctx.getColorFromAttr(R.attr.cardColor)
        val line = ctx.getColorFromAttr(R.attr.cardLineColor)
        val secondary = ctx.getColorFromAttr(R.attr.secondaryTextColor)

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 24.dpToPx().toFloat()
                setColor(surface)
            }
            // The border on top: the rows' own backgrounds would hide it at the sides
            foreground = GradientDrawable().apply {
                cornerRadius = 24.dpToPx().toFloat()
                setStroke(1.dpToPx(), ctx.getColorFromAttr(R.attr.cardStrokeColor))
            }
            clipToOutline = true
        }

        // Header: icon, name, count, close
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18.dpToPx(), 12.dpToPx(), 6.dpToPx(), 12.dpToPx())
        }
        val iconSize = 34.dpToPx()
        val icon = ImageView(ctx).apply {
            background = GradientDrawable().apply {
                cornerRadius = 10.dpToPx().toFloat()
                setColor(ColorUtils.setAlphaComponent(primary, 0x14))
            }
            val p = 5.dpToPx()
            setPadding(p, p, p, p)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        header.addView(icon, LinearLayout.LayoutParams(iconSize, iconSize).apply { marginEnd = 12.dpToPx() })
        loadIcon(ctx, app, icon, iconSize)
        header.addView(TextView(ctx).apply {
            text = app.appLabel
            setTextColor(primary)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val count = TextView(ctx).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            minWidth = 22.dpToPx()
            setPadding(7.dpToPx(), 0, 7.dpToPx(), 0)
            background = GradientDrawable().apply {
                cornerRadius = 11.dpToPx().toFloat()
                setColor(HomeSectionsController.NOTIFICATION_COLOR)
            }
        }
        header.addView(count, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 22.dpToPx()))
        header.addView(UiKit.iconButton(ctx, R.drawable.ic_m_close, ctx.getString(R.string.close), secondary) { dialog.dismiss() },
            LinearLayout.LayoutParams(44.dpToPx(), 44.dpToPx()))
        card.addView(header)

        // The rows, scrolling when there are many
        val rows = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(rows)
        }
        card.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val shown = items.take(MAX_SHOWN).toMutableList()
        fun updateCount() {
            count.text = shown.size.toString()
            if (shown.isEmpty()) dialog.dismiss()
        }
        shown.toList().forEach { sbn ->
            rows.addView(row(ctx, dialog, app, sbn, listener, primary, surface, line, secondary) {
                shown.remove(sbn)
                updateCount()
            })
        }
        updateCount()

        // Footer: clear all | open app
        card.addView(divider(ctx, line))
        val footer = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        footer.addView(footerButton(ctx, R.string.ml_notif_clear_all, secondary, bold = false) {
            items.forEach { listener.dismiss(it.key) }
            dialog.dismiss()
        }, LinearLayout.LayoutParams(0, 54.dpToPx(), 1f))
        footer.addView(View(ctx).apply { setBackgroundColor(line) }, LinearLayout.LayoutParams(1.dpToPx(), ViewGroup.LayoutParams.MATCH_PARENT))
        footer.addView(footerButton(ctx, R.string.ml_open_app, primary, bold = true) {
            dialog.dismiss()
            openApp()
        }, LinearLayout.LayoutParams(0, 54.dpToPx(), 1f))
        card.addView(footer)

        val root = FrameLayout(ctx).apply { addView(card) }
        dialog.setView(root)
        // The whole phone behind goes out of focus (Android 12+, when the system allows blur)
        dialog.blurRadius = 28.dpToPx()
        dialog.showRespectingStatusBar()
        dialog.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(0))
            w.setLayout((getScreenDimensions(ctx).first - 40.dpToPx()).coerceAtLeast(200.dpToPx()), ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        // Not taller than about 60% of the screen: the rows scroll
        scroll.post {
            val max = (getScreenDimensions(ctx).second * 0.6f).toInt() - header.height - footer.height
            if (scroll.height > max) scroll.layoutParams = scroll.layoutParams.apply { height = max }
        }
        return true
    }

    private fun loadIcon(ctx: Context, app: AppModel.App, view: ImageView, size: Int) {
        val pack = Prefs(ctx).iconPack
        val main = Handler(Looper.getMainLooper())
        Thread {
            val bitmap = try {
                IconPackManager.getIcon(ctx, app.appPackage, app.activityClassName, app.user, pack, size)
            } catch (e: Exception) {
                null
            }
            if (bitmap != null) main.post { view.setImageBitmap(bitmap) }
        }.start()
    }

    private fun divider(ctx: Context, color: Int) = View(ctx).apply {
        setBackgroundColor(color)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1.dpToPx())
    }

    private fun footerButton(ctx: Context, text: Int, color: Int, bold: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
        setText(text)
        setTextColor(color)
        textSize = 15f
        if (bold) setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** The reply action of a notification (one with a text input), if it has one. */
    private fun replyAction(sbn: StatusBarNotification): Notification.Action? =
        sbn.notification.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }

    @SuppressLint("ClickableViewAccessibility")
    private fun row(
        ctx: Context, dialog: OlDialog, app: AppModel.App, sbn: StatusBarNotification, listener: NotificationListener,
        primary: Int, surface: Int, line: Int, secondary: Int, onGone: () -> Unit,
    ): View {
        val extras = sbn.notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) ?: extras?.getCharSequence(Notification.EXTRA_TITLE)
        val text = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras?.getCharSequence(Notification.EXTRA_TEXT)
        val reply = replyAction(sbn)

        val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(divider(ctx, line))
        val frame = FrameLayout(ctx)
        // Underneath: red "Clear" across the row, blue "Reply" at the end
        val white = 0xFFFFFFFF.toInt()
        val deleteLayer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(0, 0, 22.dpToPx(), 0)
            setBackgroundColor(DELETE_COLOR)
            isVisible = false
            addView(UiKit.icon(ctx, R.drawable.ic_m_delete, white, 18).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = 8.dpToPx()
            })
            addView(TextView(ctx).apply {
                this.text = ctx.getString(R.string.ml_notif_clear)
                setTextColor(white)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
            })
        }
        frame.addView(deleteLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val replyLayer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(REPLY_COLOR)
            isVisible = reply != null
            addView(UiKit.icon(ctx, R.drawable.ic_m_back, white, 20))
            addView(TextView(ctx).apply {
                this.text = ctx.getString(R.string.ml_notif_reply)
                setTextColor(white)
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, 4.dpToPx(), 0, 0)
            })
        }
        frame.addView(replyLayer, FrameLayout.LayoutParams(REPLY_WIDTH_DP.dpToPx(), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surface)
            setPadding(18.dpToPx(), 12.dpToPx(), 18.dpToPx(), 14.dpToPx())
        }
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(ctx).apply {
            this.text = (title ?: app.appLabel).toString().uppercase()
            setTextColor(secondary)
            textSize = 12.5f
            letterSpacing = 0.05f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(TextView(ctx).apply {
            this.text = android.text.format.DateFormat.getTimeFormat(ctx).format(Date(sbn.postTime))
            setTextColor(ColorUtils.setAlphaComponent(primary, 0x80))
            textSize = 12f
            setPadding(8.dpToPx(), 0, 0, 0)
        })
        content.addView(top)
        if (!text.isNullOrBlank()) content.addView(TextView(ctx).apply {
            this.text = text
            setTextColor(primary)
            textSize = 15f
            setLineSpacing(0f, 1.15f)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, 4.dpToPx(), 0, 0)
        })
        frame.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        wrap.addView(frame)

        // Reply field, under the message
        val replyBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dpToPx(), 0, 14.dpToPx(), 14.dpToPx())
            isVisible = false
        }
        val input = EditText(ctx).apply {
            hint = ctx.getString(R.string.ml_notif_reply_to, title ?: app.appLabel)
            setTextColor(primary)
            setHintTextColor(ColorUtils.setAlphaComponent(primary, 0x70))
            textSize = 14f
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 4
            background = GradientDrawable().apply {
                cornerRadius = 22.dpToPx().toFloat()
                setStroke(1.dpToPx(), line)
            }
            setPadding(16.dpToPx(), 10.dpToPx(), 16.dpToPx(), 10.dpToPx())
            minHeight = 44.dpToPx()
        }
        replyBox.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 8.dpToPx() })
        val send = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_m_send)
            imageTintList = android.content.res.ColorStateList.valueOf(surface)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val p = 11.dpToPx()
            setPadding(p, p, p, p)
            contentDescription = ctx.getString(R.string.ml_notif_send)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(primary)
            }
            isClickable = true
        }
        replyBox.addView(send, LinearLayout.LayoutParams(44.dpToPx(), 44.dpToPx()))
        wrap.addView(replyBox)

        fun submit() {
            val message = input.text.toString().trim()
            val action = reply ?: return
            if (message.isEmpty()) return
            if (sendReply(ctx, action, message)) {
                ctx.showToast(ctx.getString(R.string.ml_notif_sent))
                input.setText("")
                replyBox.isVisible = false
                hideKeyboard(input)
            } else ctx.showToast(ctx.getString(R.string.ml_notif_send_failed))
        }
        send.setOnClickListener { submit() }
        input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) {
                submit()
                true
            } else false
        }

        fun openReply() {
            replyBox.isVisible = true
            dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            input.requestFocus()
            input.post {
                (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }

        // Touch: slide left; a tap opens the notification
        val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        val replyAt = REPLY_AT_DP.dpToPx()
        var downX = 0f
        var downY = 0f
        var swiping = false
        content.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    swiping = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    if (!swiping && -dx > slop && abs(dx) > abs(e.rawY - downY)) {
                        swiping = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (swiping) {
                        val x = dx.coerceAtMost(0f)
                        v.translationX = x
                        val past = -x > v.width / 2f
                        deleteLayer.isVisible = past
                        replyLayer.isVisible = !past && reply != null
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val x = v.translationX
                    val up = e.actionMasked == MotionEvent.ACTION_UP
                    when {
                        swiping && up && -x > v.width / 2f -> {
                            v.animate().translationX(-v.width.toFloat()).setDuration(150).withEndAction {
                                listener.dismiss(sbn.key)
                                (wrap.parent as? ViewGroup)?.removeView(wrap)
                                onGone()
                            }.start()
                        }

                        else -> {
                            v.animate().translationX(0f).setDuration(150).withEndAction {
                                deleteLayer.isVisible = false
                                replyLayer.isVisible = reply != null
                            }.start()
                            // Released on "Reply": as if tapped
                            if (swiping && up && -x > replyAt && reply != null) openReply()
                            if (!swiping && up && abs(e.rawY - downY) < slop) {
                                dialog.dismiss()
                                AppNotificationsView.openNotification(ctx, app, sbn)
                                if (sbn.notification.flags and Notification.FLAG_AUTO_CANCEL != 0 || sbn.notification.contentIntent == null)
                                    listener.dismiss(sbn.key)
                            }
                        }
                    }
                    true
                }

                else -> false
            }
        }
        replyLayer.setOnClickListener { openReply() }
        return wrap
    }

    /** Sends [message] through the notification's reply action, as the shade does. */
    private fun sendReply(ctx: Context, action: Notification.Action, message: String): Boolean = try {
        val inputs = action.remoteInputs ?: emptyArray()
        val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, message) } }
        val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        RemoteInput.addResultsToIntent(inputs, intent, results)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
        action.actionIntent.send(ctx, 0, intent)
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }

    private fun hideKeyboard(v: View) {
        (v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
    }
}
