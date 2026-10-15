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
class MenuEntry(val label: String, val onClick: () -> Unit)

private fun Context.baseDialog(title: String): Pair<OlDialog, DialogBaseBinding> {
    val dialog = OlDialog(this)
    val binding = DialogBaseBinding.inflate(LayoutInflater.from(dialog.context))
    binding.tvTitle.text = title
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

/** Wraps [content] in a ScrollView no taller than about half the screen. */
private fun Context.limitedHeight(content: View, itemCount: Int): View {
    val scroll = ScrollView(this)
    scroll.addView(content)
    val maxHeight = (getScreenDimensions(this).second * 0.5f).toInt()
    val height = if (itemCount > 8) maxHeight else ViewGroup.LayoutParams.WRAP_CONTENT
    scroll.layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply {
        topMargin = 16.dpToPx()
        bottomMargin = 8.dpToPx()
    }
    return scroll
}

/** Simple vertical list of actions. Tapping one closes the dialog and runs it. Long lists get a search field. */
fun Context.showMenuDialog(title: String, entries: List<MenuEntry>): OlDialog {
    val (dialog, binding) = baseDialog(title)
    val ctx = dialog.context
    val primary = ctx.getColorFromAttr(R.attr.primaryColor)
    val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    val rows = entries.map { entry ->
        ctx.dialogTextView(R.style.TextSmall).apply {
            text = entry.label
            textSize = 18f
            setPadding(0, 12.dpToPx(), 0, 12.dpToPx())
            isClickable = true
            isFocusable = true
            setOnClickListener {
                hideKeyboard()
                dialog.dismiss()
                entry.onClick()
            }
        }.also {
            list.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }
    if (entries.size > SEARCH_THRESHOLD) {
        val filter = EditText(ctx).apply {
            hint = ctx.getString(R.string.ml_search)
            setSingleLine(true)
            textSize = 16f
            setTextColor(primary)
            setHintTextColor(ctx.getColorFromAttr(R.attr.primaryColorTrans50))
            backgroundTintList = ColorStateList.valueOf(primary)
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        filter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString().orEmpty().trim()
                rows.forEachIndexed { i, row -> row.isVisible = q.isEmpty() || entries[i].label.contains(q, true) }
            }
        })
        container.addView(filter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 12.dpToPx()
        })
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
    }
    container.addView(ctx.limitedHeight(list, entries.size))
    binding.contentContainer.addView(container)
    binding.contentContainer.isVisible = true
    dialog.showRespectingStatusBar()
    return dialog
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

/** Confirmation with a message and one action. */
fun Context.showConfirmDialog(title: String, message: String, action: String, onConfirm: () -> Unit): OlDialog {
    val (dialog, binding) = baseDialog(title)
    binding.tvMessage.text = message
    binding.tvMessage.isVisible = true
    binding.tvAction.text = action
    binding.tvAction.isVisible = true
    binding.tvAction.setOnClickListener {
        dialog.dismiss()
        onConfirm()
    }
    dialog.showRespectingStatusBar()
    return dialog
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
    binding.tvAction.text = action
    binding.tvAction.isVisible = true
    binding.tvAction.setOnClickListener {
        filter.hideKeyboard()
        dialog.dismiss()
        onConfirm(checked)
    }
    // Keep the keyboard closed until the search field is tapped
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
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
