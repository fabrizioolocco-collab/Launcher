package app.olauncher.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import app.olauncher.R
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr

/**
 * The shared pieces of the launcher's own screens (file explorer, album, viewers, PDF tool, ToDo):
 * line icons (`R.drawable.ic_m_*`, tinted with the theme's text color), the bar at the top, the
 * bottom tool bar, file type tiles, bordered groups and fields, the empty state. Colors come from
 * the theme, so the same code works on the black viewers and on the light theme.
 */
object UiKit {

    fun primary(ctx: Context) = ctx.getColorFromAttr(R.attr.primaryColor)
    fun secondary(ctx: Context) = ctx.getColorFromAttr(R.attr.secondaryTextColor)
    fun line(ctx: Context) = ctx.getColorFromAttr(R.attr.cardLineColor)
    fun card(ctx: Context) = ctx.getColorFromAttr(R.attr.cardColor)
    fun stroke(ctx: Context) = ctx.getColorFromAttr(R.attr.cardStrokeColor)

    /** Borderless ripple for icon buttons. */
    fun roundRipple(ctx: Context): Drawable? = attrDrawable(ctx, android.R.attr.selectableItemBackgroundBorderless)

    /** Ripple for rows. */
    fun ripple(ctx: Context): Drawable? = attrDrawable(ctx, android.R.attr.selectableItemBackground)

    private fun attrDrawable(ctx: Context, attr: Int): Drawable? {
        val tv = TypedValue()
        return if (ctx.theme.resolveAttribute(attr, tv, true)) ContextCompat.getDrawable(ctx, tv.resourceId) else null
    }

    /** A line icon, [sizeDp] square, tinted [tint] (default: the text color). */
    fun icon(ctx: Context, res: Int, tint: Int = primary(ctx), sizeDp: Int = 24) = ImageView(ctx).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = LinearLayout.LayoutParams(sizeDp.dpToPx(), sizeDp.dpToPx())
    }

    /** A 44 dp touch target holding a 24 dp line icon. */
    fun iconButton(ctx: Context, res: Int, description: String, tint: Int = primary(ctx), onClick: () -> Unit) = ImageView(ctx).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        contentDescription = description
        val p = 10.dpToPx()
        setPadding(p, p, p, p)
        background = roundRipple(ctx)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(44.dpToPx(), 44.dpToPx())
    }

    /** The bar at the top of a screen: back, a title with an optional grey line under it, actions on the right. */
    class AppBar(val view: LinearLayout, val title: TextView, val subtitle: TextView, val back: ImageView, val actions: LinearLayout)

    fun appBar(ctx: Context, title: String, onBack: () -> Unit, actions: List<View> = emptyList()): AppBar {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 56.dpToPx()
        }
        val back = iconButton(ctx, R.drawable.ic_m_arrow_back, ctx.getString(R.string.ml_cal_back)) { onBack() }
        bar.addView(back)
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(6.dpToPx(), 0, 6.dpToPx(), 0)
        }
        val titleView = TextView(ctx).apply {
            text = title
            textSize = 18f
            setTextColor(primary(ctx))
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val subtitleView = TextView(ctx).apply {
            textSize = 12.5f
            setTextColor(secondary(ctx))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
            isVisible = false
        }
        texts.addView(titleView)
        texts.addView(subtitleView)
        bar.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val actionRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.forEach { actionRow.addView(it) }
        bar.addView(actionRow)
        return AppBar(bar, titleView, subtitleView, back, actionRow)
    }

    fun setSubtitle(bar: AppBar, text: CharSequence?) {
        bar.subtitle.text = text
        bar.subtitle.isVisible = !text.isNullOrBlank()
    }

    /** Thin rounded border (groups, fields), optional fill. */
    fun border(ctx: Context, radiusDp: Int = 16, fill: Int = 0) = GradientDrawable().apply {
        cornerRadius = radiusDp.dpToPx().toFloat()
        if (fill != 0) setColor(fill)
        setStroke(1.dpToPx(), line(ctx))
    }

    /** A card: the card color with its border. */
    fun cardBackground(ctx: Context, radiusDp: Int = 20) = GradientDrawable().apply {
        cornerRadius = radiusDp.dpToPx().toFloat()
        setColor(card(ctx))
        setStroke(1.dpToPx(), stroke(ctx))
    }

    /** A text field with a rounded border. */
    fun styleInput(input: EditText) {
        val ctx = input.context
        input.background = border(ctx, 14)
        input.setTextColor(primary(ctx))
        input.setHintTextColor(secondary(ctx))
        input.setPadding(14.dpToPx(), 10.dpToPx(), 14.dpToPx(), 10.dpToPx())
        input.minHeight = 44.dpToPx()
    }

    /** A tool of a bottom bar: icon over a short label; [active] fills it as a pill. */
    fun toolButton(ctx: Context, res: Int, label: String, onClick: () -> Unit) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(2.dpToPx(), 8.dpToPx(), 2.dpToPx(), 6.dpToPx())
        isClickable = true
        isFocusable = true
        contentDescription = label
        background = ripple(ctx)
        addView(icon(ctx, res, sizeDp = 22))
        addView(TextView(ctx).apply {
            text = label
            textSize = 11.5f
            setTextColor(primary(ctx))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(0, 4.dpToPx(), 0, 0)
        })
        setOnClickListener { onClick() }
    }

    /** Marks a [toolButton] as the active one (filled pill) or not. */
    fun setToolActive(tool: View, active: Boolean) {
        val ctx = tool.context
        tool.background = if (active) GradientDrawable().apply {
            cornerRadius = 14.dpToPx().toFloat()
            setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(primary(ctx), 0x26))
        } else ripple(ctx)
    }

    /** The bottom bar of tools: a row in a bordered card. */
    fun toolBar(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = cardBackground(ctx, 18)
        setPadding(4.dpToPx(), 2.dpToPx(), 4.dpToPx(), 2.dpToPx())
    }

    /** Kinds of file, for [fileTile]. */
    enum class FileKind(val icon: Int, val tint: Int) {
        FOLDER(R.drawable.ic_m_folder, 0),
        IMAGE(R.drawable.ic_m_image, 0xFF5B9BD5.toInt()),
        VIDEO(R.drawable.ic_m_video, 0xFF9C7BD6.toInt()),
        AUDIO(R.drawable.ic_m_audio, 0xFFE09A4F.toInt()),
        PDF(R.drawable.ic_m_pdf, 0xFFE0675E.toInt()),
        DOC(R.drawable.ic_m_doc, 0),
        PHONE(R.drawable.ic_m_phone, 0),
        SDCARD(R.drawable.ic_m_sdcard, 0),
        CONTACT(R.drawable.ic_m_contact, 0),
        SETTINGS(R.drawable.ic_m_settings, 0),
        WALLPAPER(R.drawable.ic_m_image, 0xFF5B9BD5.toInt()),
    }

    fun kindOf(name: String, mime: String?, isFolder: Boolean): FileKind {
        if (isFolder) return FileKind.FOLDER
        val m = mime.orEmpty()
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            m.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "bmp") -> FileKind.IMAGE
            m.startsWith("video/") || ext in setOf("mp4", "mkv", "webm", "mov", "3gp", "avi") -> FileKind.VIDEO
            m.startsWith("audio/") || ext in setOf("mp3", "m4a", "ogg", "wav", "flac", "aac", "opus") -> FileKind.AUDIO
            m == "application/pdf" || ext == "pdf" -> FileKind.PDF
            else -> FileKind.DOC
        }
    }

    /** A rounded tile with the kind's line icon, lightly tinted by kind. */
    fun fileTile(ctx: Context, kind: FileKind, sizeDp: Int = 40): FrameLayout {
        val tint = if (kind.tint != 0) kind.tint else secondary(ctx)
        return FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                cornerRadius = (sizeDp * 0.3f).toInt().dpToPx().toFloat()
                setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(tint, 0x24))
            }
            addView(ImageView(ctx).apply {
                setImageResource(kind.icon)
                imageTintList = ColorStateList.valueOf(tint)
            }, FrameLayout.LayoutParams((sizeDp * 0.55f).toInt().dpToPx(), (sizeDp * 0.55f).toInt().dpToPx(), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(sizeDp.dpToPx(), sizeDp.dpToPx())
        }
    }

    /** Big line icon, one line of text and an optional action, centred. */
    fun emptyState(ctx: Context, res: Int, text: String, action: String? = null, onAction: (() -> Unit)? = null) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(24.dpToPx(), 48.dpToPx(), 24.dpToPx(), 48.dpToPx())
        addView(icon(ctx, res, secondary(ctx), 44))
        addView(TextView(ctx).apply {
            this.text = text
            textSize = 15f
            setTextColor(secondary(ctx))
            gravity = Gravity.CENTER
            setPadding(0, 14.dpToPx(), 0, 0)
        })
        if (action != null && onAction != null) addView(app.olauncher.helper.pillButton(ctx, action, filled = false, color = primary(ctx)) { onAction() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 44.dpToPx()).apply { topMargin = 16.dpToPx() })
    }

    /** Selection mark on a grid item: a round badge with a check (filled when [selected]). */
    fun checkBadge(ctx: Context, selected: Boolean, sizeDp: Int = 24) = FrameLayout(ctx).apply {
        val p = primary(ctx)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (selected) setColor(p) else setColor(0x55000000)
            setStroke(2.dpToPx(), if (selected) p else 0xFFFFFFFF.toInt())
        }
        if (selected) addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_m_check)
            imageTintList = ColorStateList.valueOf(card(ctx))
        }, FrameLayout.LayoutParams((sizeDp * 0.7f).toInt().dpToPx(), (sizeDp * 0.7f).toInt().dpToPx(), Gravity.CENTER))
        layoutParams = FrameLayout.LayoutParams(sizeDp.dpToPx(), sizeDp.dpToPx())
    }
}
