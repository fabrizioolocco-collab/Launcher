package app.olauncher.helper

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.core.view.isVisible
import androidx.core.widget.CompoundButtonCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.databinding.DialogBaseBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer

/** A menu entry for [showMenuDialog]. */
/**
 * A menu row. With [checked] set it's a switch instead: flipping it calls [onToggle] with the new
 * state and keeps the menu open; when [onToggle] returns false the switch flips back.
 */
class MenuEntry(
    val label: String,
    val checked: Boolean? = null,
    val onToggle: ((Boolean) -> Boolean)? = null,
    /** Starts a new group with this title (small capitals above it). */
    val section: String? = null,
    /** Current value, shown on the right ("Days shown: 1" is split like that by itself). */
    val value: String? = null,
    val icon: Int = 0,
    val destructive: Boolean = false,
    val onClick: () -> Unit = {},
)

private fun Context.baseDialog(title: String): Pair<OlDialog, DialogBaseBinding> {
    val dialog = OlDialog(this)
    val binding = DialogBaseBinding.inflate(LayoutInflater.from(dialog.context))
    binding.tvTitle.text = title
    styleTitle(binding.tvTitle)
    binding.ivClose.setOnClickListener { dialog.dismiss() }
    binding.tvAction.isVisible = false
    dialog.setView(binding.root)
    return dialog to binding
}

private fun Context.dialogTextView(styleRes: Int): TextView =
    TextView(this, null, 0, styleRes).apply {
        setTextColor(getColorFromAttr(R.attr.primaryColor))
        setShadowLayer(0f, 0f, 0f, 0)
    }

/** Wraps [content] in a ScrollView as tall as it needs, up to about 3/4 of the screen (then it scrolls). */
@Suppress("UNUSED_PARAMETER")
private fun Context.limitedHeight(content: View, itemCount: Int): View {
    val maxHeight = (getScreenDimensions(this).second * 0.75f).toInt() - 160.dpToPx()
    val scroll = object : ScrollView(this) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            var limit = maxHeight.coerceAtLeast(200.dpToPx())
            if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) limit = minOf(limit, MeasureSpec.getSize(heightMeasureSpec))
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST))
        }
    }
    scroll.isVerticalScrollBarEnabled = false
    scroll.addView(content)
    scroll.layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = 16.dpToPx()
        bottomMargin = 8.dpToPx()
    }
    return scroll
}

/**
 * Vertical list of actions in bordered groups (a [MenuEntry.section] starts a new one), each row
 * with its icon and, on the right, its value. Tapping one closes the dialog and runs it. App menus
 * get Info / Rename / Hide / Uninstall as tiles on top. Long lists get a search field.
 */
fun Context.showMenuDialog(title: String, entries: List<MenuEntry>, header: ((OlDialog) -> View?)? = null): OlDialog {
    val (dialog, binding) = baseDialog(title)
    val ctx = dialog.context
    val primary = ctx.getColorFromAttr(R.attr.primaryColor)
    val secondary = ctx.getColorFromAttr(R.attr.secondaryTextColor)
    val line = ctx.getColorFromAttr(R.attr.cardLineColor)
    val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    // The dialog's padding is 24 at the start and 16 at the end: 8 more here keeps the groups centred
    val list = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 8.dpToPx(), 0)
    }
    val known = MenuIcons.known(ctx)

    fun run(entry: MenuEntry) {
        dialog.currentFocus?.hideKeyboard()
        dialog.dismiss()
        entry.onClick()
    }

    // App menus: the four usual actions as tiles
    val tiles = MenuIcons.tiles(ctx, entries)
    if (tiles.isNotEmpty()) {
        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        tiles.forEachIndexed { i, entry ->
            val look = known[entry.label]
            val color = if (look?.destructive == true) DESTRUCTIVE_COLOR else primary
            grid.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = groupBackground(ctx, line, 16)
                foreground = ripple(ctx)
                minimumHeight = 72.dpToPx()
                setPadding(2.dpToPx(), 10.dpToPx(), 2.dpToPx(), 10.dpToPx())
                isClickable = true
                contentDescription = entry.label
                setOnClickListener { run(entry) }
                addView(ImageView(ctx).apply {
                    setImageResource(look?.icon ?: 0)
                    imageTintList = ColorStateList.valueOf(color)
                }, LinearLayout.LayoutParams(22.dpToPx(), 22.dpToPx()))
                addView(TextView(ctx).apply {
                    text = look?.short ?: entry.label
                    setTextColor(color)
                    textSize = 12f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, 6.dpToPx(), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = 8.dpToPx()
            })
        }
        list.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 10.dpToPx()
        })
    }

    var group: LinearLayout? = null
    val dividers = mutableListOf<View>()
    fun newGroup(sectionTitle: String?) {
        if (!sectionTitle.isNullOrEmpty()) list.addView(TextView(ctx).apply {
            text = sectionTitle.uppercase()
            setTextColor(secondary)
            textSize = 12f
            letterSpacing = 0.08f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(8.dpToPx(), if (list.childCount == 0) 0 else 6.dpToPx(), 0, 6.dpToPx())
        })
        group = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = groupBackground(ctx, line, 16)
            clipToOutline = true
        }
        list.addView(group, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 10.dpToPx()
        })
    }

    val rows: List<View?> = entries.map { entry ->
        if (entry in tiles) return@map null
        if (group == null || entry.section != null) newGroup(entry.section)
        val g = group!!
        if (g.childCount > 0) View(ctx).apply { setBackgroundColor(line) }.also {
            dividers += it
            g.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1.dpToPx()))
        }
        val look = known[entry.label]
        // "Days shown: 1" → "Days shown" with "1" on the right
        var label = entry.label
        var value = entry.value
        if (value == null && entry.checked == null) {
            Regex("^(.{1,48}): (.{1,40})$").find(entry.label)?.let {
                label = it.groupValues[1]
                value = it.groupValues[2]
            }
        }
        val destructive = entry.destructive || look?.destructive == true
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 52.dpToPx()
            setPadding(14.dpToPx(), 6.dpToPx(), 12.dpToPx(), 6.dpToPx())
            foreground = ripple(ctx)
            isClickable = true
            isFocusable = true
        }
        val iconRes = if (entry.icon != 0) entry.icon else look?.icon ?: 0
        if (iconRes != 0) row.addView(ImageView(ctx).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(if (destructive) DESTRUCTIVE_COLOR else secondary)
        }, LinearLayout.LayoutParams(20.dpToPx(), 20.dpToPx()).apply { marginEnd = 14.dpToPx() })
        row.addView(TextView(ctx).apply {
            text = label
            textSize = 16f
            setTextColor(if (destructive) DESTRUCTIVE_COLOR else primary)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (entry.checked != null) {
            val switch = PillSwitch(ctx).apply {
                checked = entry.checked
                var reverting = false
                onChange = { isChecked ->
                    if (!reverting && entry.onToggle?.invoke(isChecked) == false) {
                        reverting = true
                        checked = !isChecked
                        reverting = false
                    }
                }
            }
            row.addView(switch, LinearLayout.LayoutParams(46.dpToPx(), 28.dpToPx()).apply { marginStart = 10.dpToPx() })
            row.setOnClickListener { switch.toggle() }
        } else {
            value?.let { v ->
                row.addView(TextView(ctx).apply {
                    text = v
                    textSize = 14f
                    setTextColor(secondary)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(10.dpToPx(), 0, 6.dpToPx(), 0)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 0f })
                row.addView(TextView(ctx).apply {
                    text = "›"
                    textSize = 18f
                    setTextColor(secondary)
                })
            }
            row.setOnClickListener { run(entry) }
        }
        g.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row
    }
    if (entries.size > SEARCH_THRESHOLD) {
        val filter = EditText(ctx).apply {
            hint = ctx.getString(R.string.ml_search)
            setSingleLine(true)
            textSize = 16f
            setTextColor(primary)
            setHintTextColor(secondary)
            background = groupBackground(ctx, line, 14)
            setPadding(14.dpToPx(), 10.dpToPx(), 14.dpToPx(), 10.dpToPx())
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        filter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString().orEmpty().trim()
                rows.forEachIndexed { i, row -> row?.isVisible = q.isEmpty() || entries[i].label.contains(q, true) }
                dividers.forEach { it.isVisible = q.isEmpty() }
            }
        })
        container.addView(filter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 12.dpToPx()
        })
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
    }
    header?.invoke(dialog)?.let { container.addView(it, 0) }
    container.addView(ctx.limitedHeight(list, entries.size))
    binding.contentContainer.addView(container)
    binding.contentContainer.isVisible = true
    dialog.showRespectingStatusBar()
    return dialog
}

/** Dialog titles: smaller and bold, like the cards' headers. */
fun styleTitle(title: TextView) {
    title.textSize = 19f
    title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
}

/** Red of the actions that delete or uninstall. */
const val DESTRUCTIVE_COLOR = 0xFFE5534B.toInt()

/** A group's border: thin line, rounded [radiusDp]. */
fun groupBackground(ctx: Context, line: Int, radiusDp: Int) = android.graphics.drawable.GradientDrawable().apply {
    cornerRadius = radiusDp.dpToPx().toFloat()
    setStroke(1.dpToPx(), line)
}

private fun ripple(ctx: Context): android.graphics.drawable.Drawable? {
    val tv = android.util.TypedValue()
    return if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true))
        androidx.core.content.ContextCompat.getDrawable(ctx, tv.resourceId) else null
}

/** Icons and looks of the entries every menu shares, found by their label. */
object MenuIcons {
    class Look(val icon: Int, val destructive: Boolean = false, val short: String? = null)

    private var cache: Pair<java.util.Locale, Map<String, Look>>? = null

    fun known(ctx: Context): Map<String, Look> {
        val locale = ctx.resources.configuration.locales[0]
        cache?.takeIf { it.first == locale }?.let { return it.second }
        fun s(id: Int) = ctx.getString(id)
        val map = mapOf(
            s(R.string.ml_app_info) to Look(R.drawable.ic_m_info, short = s(R.string.ml_tile_info)),
            s(R.string.rename) to Look(R.drawable.ic_m_rename),
            s(R.string.adapter_hide) to Look(R.drawable.ic_m_hide),
            s(R.string.adapter_show) to Look(R.drawable.ic_m_show),
            s(R.string.delete) to Look(R.drawable.ic_m_delete, destructive = true),
            s(R.string.ml_change_icon) to Look(R.drawable.ic_m_icon),
            s(R.string.ml_add_to_section) to Look(R.drawable.ic_m_add_section),
            s(R.string.ml_add_to_tab) to Look(R.drawable.ic_m_tab),
            s(R.string.ml_tab_back_to_all) to Look(R.drawable.ic_m_back),
            s(R.string.ml_move_up) to Look(R.drawable.ic_m_up),
            s(R.string.ml_move_down) to Look(R.drawable.ic_m_down),
            s(R.string.ml_app_move_to) to Look(R.drawable.ic_m_move),
            s(R.string.ml_app_copy_to) to Look(R.drawable.ic_m_copy),
            s(R.string.ml_app_remove_from_section) to Look(R.drawable.ic_m_remove),
            s(R.string.ml_add_app) to Look(R.drawable.ic_m_app),
            s(R.string.ml_add_widget) to Look(R.drawable.ic_m_widget),
            s(R.string.ml_section_new) to Look(R.drawable.ic_m_section),
            s(R.string.ml_folder_new_ellipsis) to Look(R.drawable.ic_m_folder),
            s(R.string.ml_parking_menu) to Look(R.drawable.ic_m_parking),
            s(R.string.ml_news_zone_show) to Look(R.drawable.ic_m_news),
            s(R.string.ml_explorer) to Look(R.drawable.ic_m_file),
            s(R.string.ml_detox) to Look(R.drawable.ic_m_detox),
            s(R.string.ml_settings) to Look(R.drawable.ic_m_settings),
            s(R.string.ml_section_rename) to Look(R.drawable.ic_m_rename),
            s(R.string.ml_section_edit_apps) to Look(R.drawable.ic_m_app),
            s(R.string.ml_section_delete) to Look(R.drawable.ic_m_delete, destructive = true),
            s(R.string.ml_tab_delete) to Look(R.drawable.ic_m_delete, destructive = true),
            s(R.string.ml_tab_edit_apps) to Look(R.drawable.ic_m_app),
            s(R.string.ml_open_app) to Look(R.drawable.ic_m_open),
            s(R.string.ml_cal_settings) to Look(R.drawable.ic_m_settings),
            s(R.string.ml_widget_remove) to Look(R.drawable.ic_m_remove, destructive = true),
            s(R.string.ml_cal_add_event) to Look(R.drawable.ic_m_event),
            s(R.string.ml_cal_add_todo) to Look(R.drawable.ic_m_todo),
            s(R.string.ml_cal_open_todo) to Look(R.drawable.ic_m_open),
            s(R.string.ml_todo_delete) to Look(R.drawable.ic_m_delete, destructive = true),
            s(R.string.ml_todo_category_delete) to Look(R.drawable.ic_m_delete, destructive = true),
            s(R.string.ml_todo_edit) to Look(R.drawable.ic_m_rename),
            s(R.string.ml_todo_duplicate) to Look(R.drawable.ic_m_copy),
        )
        cache = locale to map
        return map
    }

    /** The tiles of an app menu (it has app info and uninstall): info, rename, hide/show, uninstall. */
    fun tiles(ctx: Context, entries: List<MenuEntry>): List<MenuEntry> {
        val labels = listOf(R.string.ml_app_info, R.string.rename, R.string.adapter_hide, R.string.adapter_show, R.string.delete).map { ctx.getString(it) }
        val info = ctx.getString(R.string.ml_app_info)
        val uninstall = ctx.getString(R.string.delete)
        if (entries.none { it.label == info } || entries.none { it.label == uninstall }) return emptyList()
        return labels.mapNotNull { l -> entries.firstOrNull { it.label == l && it.checked == null } }
    }
}

private const val SEARCH_THRESHOLD = 12

/** Single-line text input, e.g. a section name. [onSave] gets the trimmed, non-blank text. */
fun Context.showTextInputDialog(
    title: String,
    initial: String,
    hint: String,
    action: String,
    onSave: (String) -> Unit,
): OlDialog {
    val (dialog, binding) = baseDialog(title)
    val ctx = dialog.context
    val input = EditText(ctx).apply {
        setText(initial)
        setSelection(initial.length)
        this.hint = hint
        setSingleLine(true)
        imeOptions = EditorInfo.IME_ACTION_DONE
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setTextColor(ctx.getColorFromAttr(R.attr.primaryColor))
        setHintTextColor(ctx.getColorFromAttr(R.attr.primaryColorTrans50))
        backgroundTintList = ColorStateList.valueOf(ctx.getColorFromAttr(R.attr.primaryColor))
        textSize = 20f
        layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 16.dpToPx()
            bottomMargin = 8.dpToPx()
        }
    }
    fun submit() {
        val text = input.text.toString().trim()
        if (text.isBlank()) return
        input.hideKeyboard()
        dialog.dismiss()
        onSave(text)
    }
    input.setOnEditorActionListener { _, actionId, _ ->
        if (actionId == EditorInfo.IME_ACTION_DONE) {
            submit()
            true
        } else false
    }
    binding.contentContainer.addView(input)
    binding.contentContainer.isVisible = true
    binding.tvAction.text = action
    binding.tvAction.isVisible = true
    binding.tvAction.setOnClickListener { submit() }
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    dialog.showRespectingStatusBar()
    input.requestFocus()
    return dialog
}

/** A dialog whose message can be updated while something runs (e.g. waiting for a GPS fix). */
class StatusDialog(private val dialog: OlDialog, private val binding: DialogBaseBinding) {
    fun setMessage(message: String) {
        binding.tvMessage.text = message
    }

    fun setActionEnabled(enabled: Boolean) {
        binding.tvAction.isEnabled = enabled
        binding.tvAction.alpha = if (enabled) 1f else 0.4f
    }

    fun setOnDismiss(onDismiss: () -> Unit) = dialog.setOnDismissListener { onDismiss() }

    fun dismiss() = dialog.dismiss()
}

fun Context.showStatusDialog(title: String, message: String, action: String, onAction: () -> Unit): StatusDialog {
    val (dialog, binding) = baseDialog(title)
    binding.tvMessage.text = message
    binding.tvMessage.isVisible = true
    binding.tvAction.text = action
    binding.tvAction.isVisible = true
    binding.tvAction.setOnClickListener { onAction() }
    dialog.showRespectingStatusBar()
    return StatusDialog(dialog, binding)
}

/** Confirmation with a message and one action. */
fun Context.showConfirmDialog(title: String, message: String, action: String, onConfirm: () -> Unit): OlDialog {
    val (dialog, binding) = baseDialog(title)
    val ctx = dialog.context
    binding.tvMessage.text = message
    binding.tvMessage.isVisible = true
    binding.tvMessage.setTextColor(ctx.getColorFromAttr(R.attr.secondaryTextColor))
    // Cancel / action as two buttons; the action is red when it deletes or throws something away
    val destructive = MenuIcons.known(ctx)[action]?.destructive == true ||
            action in listOf(R.string.ml_todo_delete, R.string.ml_explorer_delete, R.string.ml_edit_discard_action).map { ctx.getString(it) }
    val primary = ctx.getColorFromAttr(R.attr.primaryColor)
    val buttons = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, 8.dpToPx(), 8.dpToPx(), 0)
    }
    buttons.addView(pillButton(ctx, ctx.getString(R.string.ml_todo_cancel), filled = false, color = primary) { dialog.dismiss() },
        LinearLayout.LayoutParams(0, 48.dpToPx(), 1f).apply { marginEnd = 10.dpToPx() })
    buttons.addView(pillButton(ctx, action, filled = true, color = if (destructive) DESTRUCTIVE_COLOR else primary) {
        dialog.dismiss()
        onConfirm()
    }, LinearLayout.LayoutParams(0, 48.dpToPx(), 1f))
    binding.contentContainer.addView(buttons)
    binding.contentContainer.isVisible = true
    dialog.showRespectingStatusBar()
    return dialog
}

/** Rounded button: outlined, or filled with [color] (text then in the card's color, white on red). */
fun pillButton(ctx: Context, label: String, filled: Boolean, color: Int, onClick: () -> Unit) = TextView(ctx).apply {
    text = label
    textSize = 15f
    gravity = Gravity.CENTER
    maxLines = 1
    setTypeface(typeface, android.graphics.Typeface.BOLD)
    setTextColor(
        when {
            !filled -> color
            color == DESTRUCTIVE_COLOR -> 0xFFFFFFFF.toInt()
            else -> ctx.getColorFromAttr(R.attr.cardColor)
        }
    )
    background = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = 24.dpToPx().toFloat()
        if (filled) setColor(color) else setStroke(1.dpToPx(), ctx.getColorFromAttr(R.attr.cardStrokeColor))
    }
    isClickable = true
    isFocusable = true
    setOnClickListener { onClick() }
}

/**
 * Checkbox list with a filter field on top (the keyboard opens only when the field is tapped).
 * [onConfirm] receives the indexes of the checked [labels].
 */
fun Context.showMultiSelectDialog(
    title: String,
    labels: List<String>,
    initiallyChecked: Set<Int>,
    action: String,
    onConfirm: (Set<Int>) -> Unit,
): OlDialog {
    val (dialog, binding) = baseDialog(title)
    val ctx = dialog.context
    val checked = initiallyChecked.toMutableSet()
    val primary = ctx.getColorFromAttr(R.attr.primaryColor)

    val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    val filter = EditText(ctx).apply {
        hint = ctx.getString(R.string.ml_search)
        setSingleLine(true)
        textSize = 16f
        setTextColor(primary)
        setHintTextColor(ctx.getColorFromAttr(R.attr.primaryColorTrans50))
        backgroundTintList = ColorStateList.valueOf(primary)
        imeOptions = EditorInfo.IME_ACTION_DONE
    }
    container.addView(filter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = 12.dpToPx()
    })

    val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    val rows = labels.mapIndexed { index, label ->
        AppCompatCheckBox(ctx).apply {
            text = label
            textSize = 17f
            setTextColor(primary)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dpToPx(), 10.dpToPx(), 0, 10.dpToPx())
            CompoundButtonCompat.setButtonTintList(this, ColorStateList.valueOf(primary))
            isChecked = index in checked
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) checked.add(index) else checked.remove(index)
            }
        }.also { list.addView(it) }
    }
    container.addView(ctx.limitedHeight(list, labels.size))

    fun normalize(s: CharSequence) = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "").lowercase()
    filter.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            val q = normalize(s?.toString().orEmpty().trim())
            rows.forEachIndexed { i, row -> row.isVisible = q.isEmpty() || normalize(labels[i]).contains(q) }
        }
    })

    binding.contentContainer.addView(container)
    binding.contentContainer.isVisible = true
    // The action sits in the title row, next to the close icon: a long list or the keyboard can
    // push the bottom row off screen
    binding.tvAction.isVisible = false
    val titleRow = binding.tvTitle.parent as android.widget.FrameLayout
    (binding.tvTitle.layoutParams as ViewGroup.MarginLayoutParams).marginEnd = 120.dpToPx()
    titleRow.addView(ctx.dialogTextView(R.style.TextSmallBold).apply {
        text = action
        textSize = 18f
        setPadding(8.dpToPx(), 6.dpToPx(), 8.dpToPx(), 6.dpToPx())
        isClickable = true
        isFocusable = true
        setOnClickListener {
            filter.hideKeyboard()
            dialog.dismiss()
            onConfirm(checked)
        }
    }, android.widget.FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL
    ).apply { marginEnd = 40.dpToPx() })
    // Keep the keyboard closed until the search field is tapped; then shrink to stay above it
    dialog.window?.setSoftInputMode(
        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    )
    dialog.showRespectingStatusBar()
    return dialog
}

/**
 * Grid of icons with a search field, e.g. all icons of an icon pack.
 * [loadIcon] is called off the main thread; [onPick] receives the chosen name.
 */
fun Context.showIconGridDialog(
    title: String,
    names: List<String>,
    scope: CoroutineScope,
    loadIcon: (String, Int) -> Bitmap?,
    onPick: (String) -> Unit,
): OlDialog {
    val (dialog, binding) = baseDialog(title)
    val ctx = dialog.context
    val primary = ctx.getColorFromAttr(R.attr.primaryColor)
    val cellSize = 56.dpToPx()
    val iconSize = 44.dpToPx()

    val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    val filter = EditText(ctx).apply {
        hint = ctx.getString(R.string.ml_search)
        setSingleLine(true)
        textSize = 16f
        setTextColor(primary)
        setHintTextColor(ctx.getColorFromAttr(R.attr.primaryColorTrans50))
        backgroundTintList = ColorStateList.valueOf(primary)
        imeOptions = EditorInfo.IME_ACTION_DONE
    }
    container.addView(filter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = 12.dpToPx()
    })

    var shown: List<String> = names
    val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = shown.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val image = ImageView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cellSize)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                isClickable = true
                isFocusable = true
            }
            return object : RecyclerView.ViewHolder(image) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val image = holder.itemView as ImageView
            val name = shown[position]
            image.tag = name
            image.setImageDrawable(null)
            image.contentDescription = name
            image.setOnClickListener {
                filter.hideKeyboard()
                dialog.dismiss()
                onPick(name)
            }
            scope.launch {
                val bitmap = withContext(Dispatchers.IO) { loadIcon(name, iconSize) } ?: return@launch
                if (image.tag == name) image.setImageBitmap(bitmap)
            }
        }
    }
    val grid = RecyclerView(ctx).apply {
        layoutManager = GridLayoutManager(ctx, 5)
        this.adapter = adapter
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (getScreenDimensions(ctx).second * 0.55f).toInt()
        ).apply { topMargin = 12.dpToPx() }
    }
    container.addView(grid)

    filter.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

        @SuppressLint("NotifyDataSetChanged")
        override fun afterTextChanged(s: Editable?) {
            // Icon names use underscores, e.g. "google_maps": match "google maps" too
            val q = s?.toString().orEmpty().trim().lowercase().replace(" ", "_")
            shown = if (q.isEmpty()) names else names.filter { it.lowercase().contains(q) }
            adapter.notifyDataSetChanged()
        }
    })

    binding.contentContainer.addView(container)
    binding.contentContainer.isVisible = true
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
    dialog.showRespectingStatusBar()
    return dialog
}

/** Quick choices at the top of [showColorPickerDialog]. */
private val PRESET_COLORS = listOf(
    0xFFFFFFFF, 0xFFBDBDBD, 0xFF757575, 0xFF000000, 0xFFFFC107, 0xFFFF9800, 0xFFF44336,
    0xFFE91E63, 0xFF9C27B0, 0xFF3F51B5, 0xFF2196F3, 0xFF03A9F4, 0xFF009688, 0xFF4CAF50,
).map { it.toInt() }

/**
 * Color picker: quick choices, hue / saturation / brightness sliders and a #RRGGBB field.
 * [onPick] receives the color, or 0 when "default" ([defaultLabel]) is tapped.
 */
fun Context.showColorPickerDialog(title: String, initial: Int, defaultLabel: String, onPick: (Int) -> Unit): OlDialog {
    val (dialog, binding) = baseDialog(title)
    val ctx = dialog.context
    val primary = ctx.getColorFromAttr(R.attr.primaryColor)
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(if (initial != 0) initial else android.graphics.Color.WHITE, hsv)
    var color = android.graphics.Color.HSVToColor(hsv)
    var syncing = false

    val container = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 16.dpToPx(), 0, 8.dpToPx())
    }
    val swatch = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 12.dpToPx().toFloat() }
    container.addView(View(ctx).apply { background = swatch }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 44.dpToPx()))

    val presets = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    container.addView(presets, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = 12.dpToPx()
    })

    fun slider(max: Int, value: Int, track: android.graphics.drawable.Drawable?, onChange: (Int) -> Unit) =
        android.widget.SeekBar(ctx).apply {
            this.max = max
            progress = value
            thumbTintList = ColorStateList.valueOf(primary)
            if (track != null) {
                progressDrawable = track
                minimumHeight = 10.dpToPx()
            } else progressTintList = ColorStateList.valueOf(primary)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) onChange(progress)
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
            })
        }

    val rainbow = android.graphics.drawable.GradientDrawable(
        android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(0xFFFF0000.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FFFF.toInt(), 0xFF0000FF.toInt(), 0xFFFF00FF.toInt(), 0xFFFF0000.toInt())
    ).apply { cornerRadius = 5.dpToPx().toFloat() }
    val hex = EditText(ctx).apply {
        setSingleLine(true)
        textSize = 18f
        setTextColor(primary)
        backgroundTintList = ColorStateList.valueOf(primary)
        imeOptions = EditorInfo.IME_ACTION_DONE
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
    }
    lateinit var hue: android.widget.SeekBar
    lateinit var sat: android.widget.SeekBar
    lateinit var bright: android.widget.SeekBar

    fun refresh(fromHex: Boolean = false) {
        color = android.graphics.Color.HSVToColor(hsv)
        swatch.setColor(color)
        swatch.setStroke(1.dpToPx(), ctx.getColorFromAttr(R.attr.primaryColorTrans50))
        syncing = true
        hue.progress = hsv[0].toInt()
        sat.progress = (hsv[1] * 100).toInt()
        bright.progress = (hsv[2] * 100).toInt()
        if (!fromHex) hex.setText("#%06X".format(color and 0xFFFFFF))
        syncing = false
    }

    hue = slider(360, hsv[0].toInt(), rainbow) { hsv[0] = it.toFloat(); refresh() }
    sat = slider(100, (hsv[1] * 100).toInt(), null) { hsv[1] = it / 100f; refresh() }
    bright = slider(100, (hsv[2] * 100).toInt(), null) { hsv[2] = it / 100f; refresh() }
    listOf(hue, sat, bright).forEach {
        container.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 36.dpToPx()).apply { topMargin = 8.dpToPx() })
    }
    container.addView(hex, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = 8.dpToPx()
    })
    hex.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (syncing) return
            val text = s?.toString()?.trim()?.removePrefix("#") ?: return
            if (text.length != 6) return
            val parsed = text.toLongOrNull(16) ?: return
            android.graphics.Color.colorToHSV((parsed or 0xFF000000).toInt(), hsv)
            refresh(fromHex = true)
        }
    })

    PRESET_COLORS.chunked(7).forEach { rowColors ->
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        rowColors.forEach { c ->
            row.addView(View(ctx).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(c)
                    setStroke(1.dpToPx(), ctx.getColorFromAttr(R.attr.primaryColorTrans50))
                }
                isClickable = true
                setOnClickListener {
                    android.graphics.Color.colorToHSV(c, hsv)
                    refresh()
                }
            }, LinearLayout.LayoutParams(0, 30.dpToPx(), 1f).apply { setMargins(4.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx()) })
        }
        presets.addView(row)
    }

    container.addView(ctx.dialogTextView(R.style.TextSmall).apply {
        text = defaultLabel
        textSize = 16f
        setPadding(0, 14.dpToPx(), 0, 6.dpToPx())
        isClickable = true
        setOnClickListener {
            hex.hideKeyboard()
            dialog.dismiss()
            onPick(0)
        }
    })
    refresh()

    binding.contentContainer.addView(ScrollView(ctx).apply { addView(container) })
    binding.contentContainer.isVisible = true
    binding.tvAction.text = ctx.getString(R.string.ml_done)
    binding.tvAction.isVisible = true
    binding.tvAction.setOnClickListener {
        hex.hideKeyboard()
        dialog.dismiss()
        onPick(color)
    }
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    dialog.showRespectingStatusBar()
    return dialog
}

/**
 * A small drop-down card under this view (end-aligned), one row per entry with its icon.
 * Tapping one closes it and runs it.
 */
fun View.showDropdown(entries: List<MenuEntry>): android.widget.PopupWindow {
    val ctx = context
    val primary = ctx.getColorFromAttr(R.attr.primaryColor)
    val secondary = ctx.getColorFromAttr(R.attr.secondaryTextColor)
    val known = MenuIcons.known(ctx)
    val card = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(6.dpToPx(), 6.dpToPx(), 6.dpToPx(), 6.dpToPx())
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 16.dpToPx().toFloat()
            setColor(ctx.getColorFromAttr(R.attr.cardColor))
            setStroke(1.dpToPx(), ctx.getColorFromAttr(R.attr.cardStrokeColor))
        }
    }
    val popup = android.widget.PopupWindow(card, 220.dpToPx(), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
        elevation = 8.dpToPx().toFloat()
        setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        isOutsideTouchable = true
    }
    entries.forEach { entry ->
        val look = known[entry.label]
        card.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 46.dpToPx()
            setPadding(10.dpToPx(), 0, 10.dpToPx(), 0)
            background = ripple(ctx)
            isClickable = true
            val iconRes = if (entry.icon != 0) entry.icon else look?.icon ?: 0
            if (iconRes != 0) addView(ImageView(ctx).apply {
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(secondary)
            }, LinearLayout.LayoutParams(18.dpToPx(), 18.dpToPx()).apply { marginEnd = 12.dpToPx() })
            addView(TextView(ctx).apply {
                text = entry.label
                textSize = 15f
                setTextColor(primary)
            })
            setOnClickListener {
                popup.dismiss()
                entry.onClick()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    popup.showAsDropDown(this, width - 220.dpToPx(), 4.dpToPx())
    return popup
}

/**
 * On/off switch of the menus: a pill track with a round knob. On: light track, dark knob on the
 * right; off: dark track with a border, grey knob on the left. Tapping it (or its row) toggles it.
 */
class PillSwitch(context: Context) : View(context) {
    var onChange: ((Boolean) -> Unit)? = null
    var checked = false
        set(value) {
            if (field == value) return
            field = value
            animateKnob()
        }
    private var position = 0f
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val rect = android.graphics.RectF()
    private val onTrack = context.getColorFromAttr(R.attr.primaryColor)
    private val onKnob = context.getColorFromAttr(R.attr.cardColor)
    private val offTrack = context.getColorFromAttr(R.attr.cardLineColor)
    private val offBorder = context.getColorFromAttr(R.attr.cardStrokeColor)
    private val offKnob = context.getColorFromAttr(R.attr.secondaryTextColor)
    private var animator: android.animation.ValueAnimator? = null

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener { toggle() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        position = if (checked) 1f else 0f
    }

    fun toggle() {
        checked = !checked
        onChange?.invoke(checked)
    }

    private fun animateKnob() {
        animator?.cancel()
        if (!isAttachedToWindow) {
            position = if (checked) 1f else 0f
            invalidate()
            return
        }
        animator = android.animation.ValueAnimator.ofFloat(position, if (checked) 1f else 0f).apply {
            duration = 150
            addUpdateListener {
                position = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        val r = h / 2f
        rect.set(0f, 0f, w, h)
        paint.style = android.graphics.Paint.Style.FILL
        paint.color = androidx.core.graphics.ColorUtils.blendARGB(offTrack, onTrack, position)
        canvas.drawRoundRect(rect, r, r, paint)
        if (position < 1f) {
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = 1.dpToPx().toFloat()
            paint.color = androidx.core.graphics.ColorUtils.setAlphaComponent(offBorder, ((1f - position) * 255).toInt())
            rect.inset(0.5f.dpToPxF(), 0.5f.dpToPxF())
            canvas.drawRoundRect(rect, r, r, paint)
        }
        val pad = 3.dpToPx().toFloat()
        val knobR = r - pad
        val cx = pad + knobR + (w - 2 * (pad + knobR)) * position
        paint.style = android.graphics.Paint.Style.FILL
        paint.color = androidx.core.graphics.ColorUtils.blendARGB(offKnob, onKnob, position)
        canvas.drawCircle(cx, r, knobR, paint)
    }

    private fun Float.dpToPxF() = this * resources.displayMetrics.density
}
