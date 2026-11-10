package app.olauncher.ui.shopping

import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.data.ShoppingItem
import app.olauncher.data.ShoppingStore
import app.olauncher.helper.MenuEntry
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.showConfirmDialog
import app.olauncher.helper.showMenuDialog
import app.olauncher.helper.showTextInputDialog
import app.olauncher.helper.showToast
import app.olauncher.ui.UiKit

/**
 * The launcher's shopping lists ("Spesa" in the app list). Lists as chips on top (+ for a new one,
 * long press to rename / move / delete); a field to add ("2 latte"), which stays open for the next
 * one, with suggestions from what was bought before; tap a thing to tick it (it goes down among the
 * bought ones), long press to drag it elsewhere (or, without moving, to edit / delete it), swipe it
 * away to delete it.
 */
class ShoppingActivity : AppCompatActivity() {

    private var primary = 0
    private var secondary = 0
    private lateinit var bar: UiKit.AppBar
    private lateinit var listChips: LinearLayout
    private lateinit var input: EditText
    private lateinit var suggestionRow: LinearLayout
    private lateinit var suggestionScroll: HorizontalScrollView
    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: View
    private val adapter = ItemsAdapter()

    private sealed class Row {
        class ToBuy(val item: ShoppingItem) : Row()
        class DoneHeader(val count: Int) : Row()
        class Done(val item: ShoppingItem) : Row()
    }

    private var rows: List<Row> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        primary = getColorFromAttr(R.attr.primaryColor)
        secondary = UiKit.secondary(this)
        setContentView(buildLayout())
        refresh()
    }

    private val listId get() = ShoppingStore.currentListId(this)

    // ---------------------------------------------------------------- layout

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8.dpToPx(), 0, 8.dpToPx(), 0)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(v.paddingLeft, bars.top + 4.dpToPx(), v.paddingRight, maxOf(bars.bottom, ime.bottom) + 8.dpToPx())
            insets
        }
        bar = UiKit.appBar(this, getString(R.string.ml_shop_app), onBack = { finish() }, actions = listOf(
            UiKit.iconButton(this, R.drawable.ic_m_share, getString(R.string.ml_shop_share)) { share() },
            UiKit.iconButton(this, R.drawable.ic_m_more, getString(R.string.ml_explorer_more)) { showMenu() },
        ))
        root.addView(bar.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // The lists
        listChips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(12.dpToPx(), 0, 12.dpToPx(), 0)
            addView(listChips)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 6.dpToPx(); bottomMargin = 10.dpToPx()
        })

        // The field to add, with its button
        val addRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dpToPx(), 0, 6.dpToPx(), 0)
        }
        input = EditText(this).apply {
            hint = getString(R.string.ml_shop_add_hint)
            textSize = 16f
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            UiKit.styleInput(this)
            // Enter adds and keeps the keyboard open for the next one
            setOnEditorActionListener { _, _, _ ->
                addTyped()
                true
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = showSuggestions()
            })
        }
        addRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addRow.addView(UiKit.iconButton(this, R.drawable.ic_m_add, getString(R.string.ml_shop_add)) { addTyped() })
        root.addView(addRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Suggestions from what was bought before
        suggestionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        suggestionScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(12.dpToPx(), 0, 12.dpToPx(), 0)
            addView(suggestionRow)
        }
        root.addView(suggestionScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 8.dpToPx()
        })

        // The things to buy, then the bought ones
        val content = FrameLayout(this)
        recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ShoppingActivity)
            adapter = this@ShoppingActivity.adapter
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(12.dpToPx(), 6.dpToPx(), 12.dpToPx(), 24.dpToPx())
        }
        touchHelper.attachToRecyclerView(recycler)
        content.addView(recycler, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        emptyView = UiKit.emptyState(this, R.drawable.ic_m_cart, getString(R.string.ml_shop_empty)).apply { isVisible = false }
        content.addView(emptyView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            topMargin = 24.dpToPx()
        })
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun chip(label: String, selected: Boolean, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) =
        TextView(this, null, 0, R.style.TextSmall).apply {
            text = label
            setTextColor(primary)
            setShadowLayer(0f, 0f, 0f, 0)
            setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
            setPadding(14.dpToPx(), 7.dpToPx(), 14.dpToPx(), 7.dpToPx())
            background = GradientDrawable().apply {
                cornerRadius = 20.dpToPx().toFloat()
                if (selected) setColor(ColorUtils.setAlphaComponent(primary, 0x30))
                setStroke(1.dpToPx(), if (selected) ColorUtils.setAlphaComponent(primary, 0xA0) else UiKit.line(this@ShoppingActivity))
            }
            isClickable = true
            setOnClickListener { onClick() }
            if (onLongClick != null) setOnLongClickListener {
                onLongClick()
                true
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = 8.dpToPx() }
        }

    // ---------------------------------------------------------------- data

    private fun refresh() {
        val lists = ShoppingStore.lists(this)
        val current = listId
        listChips.removeAllViews()
        lists.forEach { list ->
            listChips.addView(chip(list.name, list.id == current, onLongClick = { showListMenu(list.id) }) {
                ShoppingStore.setCurrentListId(this, list.id)
                refresh()
            })
        }
        listChips.addView(chip("+", false) { askNewList() })

        val items = ShoppingStore.items(this, current)
        val toBuy = items.filter { !it.done }
        val done = items.filter { it.done }
        rows = toBuy.map { Row.ToBuy(it) } +
                (if (done.isNotEmpty()) listOf(Row.DoneHeader(done.size)) else emptyList()) +
                done.map { Row.Done(it) }
        adapter.notifyDataSetChanged()
        emptyView.isVisible = items.isEmpty()
        UiKit.setSubtitle(bar, lists.firstOrNull { it.id == current }?.name?.let { name ->
            if (toBuy.isEmpty()) name else "$name · " + getString(R.string.ml_shop_to_buy, toBuy.size)
        })
        showSuggestions()
    }

    private fun showSuggestions() {
        val suggestions = ShoppingStore.suggestions(this, listId, input.text.toString())
        suggestionRow.removeAllViews()
        suggestions.forEach { text ->
            suggestionRow.addView(chip("+ $text", false, onLongClick = {
                ShoppingStore.forget(this, text)
                showToast(getString(R.string.ml_shop_forgotten, text))
                showSuggestions()
            }) {
                ShoppingStore.add(this, listId, text)
                input.setText("")
                refresh()
            })
        }
        suggestionScroll.isVisible = suggestions.isNotEmpty()
    }

    private fun addTyped() {
        val text = input.text.toString()
        if (text.isBlank()) return
        ShoppingStore.add(this, listId, text)
        input.setText("")
        refresh()
        recycler.scrollToPosition(0)
    }

    // ---------------------------------------------------------------- lists

    private fun askNewList() {
        showTextInputDialog(getString(R.string.ml_shop_new_list), "", getString(R.string.ml_shop_list_name), getString(R.string.ml_save)) { name ->
            if (name.isBlank()) return@showTextInputDialog
            ShoppingStore.setCurrentListId(this, ShoppingStore.addList(this, name.trim()).id)
            refresh()
        }
    }

    private fun askRenameList(id: Long) {
        val list = ShoppingStore.lists(this).firstOrNull { it.id == id } ?: return
        showTextInputDialog(getString(R.string.ml_shop_rename_list), list.name, getString(R.string.ml_shop_list_name), getString(R.string.ml_save)) { name ->
            if (name.isBlank()) return@showTextInputDialog
            ShoppingStore.renameList(this, id, name.trim())
            refresh()
        }
    }

    private fun askDeleteList(id: Long) {
        val list = ShoppingStore.lists(this).firstOrNull { it.id == id } ?: return
        showConfirmDialog(getString(R.string.ml_shop_delete_list), getString(R.string.ml_shop_delete_list_confirm, list.name), getString(R.string.ml_explorer_delete)) {
            ShoppingStore.removeList(this, id)
            refresh()
        }
    }

    private fun showListMenu(id: Long) {
        val lists = ShoppingStore.lists(this)
        val index = lists.indexOfFirst { it.id == id }
        val list = lists.getOrNull(index) ?: return
        showMenuDialog(list.name, listOfNotNull(
            MenuEntry(getString(R.string.ml_shop_rename_list), icon = R.drawable.ic_m_rename) { askRenameList(id) },
            if (index > 0) MenuEntry(getString(R.string.ml_shop_move_left), icon = R.drawable.ic_m_left) {
                ShoppingStore.moveList(this, id, -1)
                refresh()
            } else null,
            if (index < lists.lastIndex) MenuEntry(getString(R.string.ml_shop_move_right), icon = R.drawable.ic_m_right) {
                ShoppingStore.moveList(this, id, 1)
                refresh()
            } else null,
            MenuEntry(getString(R.string.ml_shop_delete_list), icon = R.drawable.ic_m_delete, destructive = true) { askDeleteList(id) },
        ))
    }

    private fun showMenu() {
        val id = listId
        val hasDone = ShoppingStore.items(this, id).any { it.done }
        showMenuDialog(getString(R.string.ml_shop_app), listOfNotNull(
            MenuEntry(getString(R.string.ml_shop_new_list), icon = R.drawable.ic_m_add) { askNewList() },
            MenuEntry(getString(R.string.ml_shop_rename_list), icon = R.drawable.ic_m_rename) { askRenameList(id) },
            if (hasDone) MenuEntry(getString(R.string.ml_shop_clear_done), icon = R.drawable.ic_m_check) {
                ShoppingStore.clearDone(this, id)
                refresh()
            } else null,
            MenuEntry(getString(R.string.ml_shop_delete_list), icon = R.drawable.ic_m_delete, destructive = true) { askDeleteList(id) },
        ))
    }

    /** The things to buy as text (WhatsApp, a note...). */
    private fun share() {
        val list = ShoppingStore.lists(this).firstOrNull { it.id == listId } ?: return
        val toBuy = ShoppingStore.items(this, list.id).filter { !it.done }
        if (toBuy.isEmpty()) {
            showToast(getString(R.string.ml_shop_nothing_to_share))
            return
        }
        val text = list.name + "\n" + toBuy.joinToString("\n") { "• " + (if (it.qty > 1) "${it.qty} × " else "") + it.text }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), list.name))
    }

    // ---------------------------------------------------------------- items

    private fun showItemMenu(item: ShoppingItem) {
        showMenuDialog(item.text, listOf(
            MenuEntry(getString(R.string.ml_shop_edit), icon = R.drawable.ic_m_edit) { askEdit(item) },
            MenuEntry(getString(R.string.ml_explorer_delete), icon = R.drawable.ic_m_delete, destructive = true) {
                ShoppingStore.remove(this, item.id)
                refresh()
            },
        ))
    }

    private fun askEdit(item: ShoppingItem) {
        val current = (if (item.qty > 1) "${item.qty} " else "") + item.text
        showTextInputDialog(getString(R.string.ml_shop_edit), current, getString(R.string.ml_shop_add_hint), getString(R.string.ml_save)) { text ->
            if (text.isBlank()) return@showTextInputDialog
            ShoppingStore.update(this, item.id, text)
            refresh()
        }
    }

    // ---------------------------------------------------------------- drag and swipe

    /** Where the long-pressed row was picked up; it moved if it lands elsewhere. */
    private var dragFrom = -1
    private var dragMoved = false

    private val touchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
            val row = rows.getOrNull(viewHolder.bindingAdapterPosition) ?: return 0
            return when (row) {
                is Row.ToBuy -> makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START or ItemTouchHelper.END)
                is Row.Done -> makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START or ItemTouchHelper.END)
                else -> 0
            }
        }

        override fun onMove(recyclerView: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
            val a = from.bindingAdapterPosition
            val b = to.bindingAdapterPosition
            // Only among the things to buy
            if (rows.getOrNull(a) !is Row.ToBuy || rows.getOrNull(b) !is Row.ToBuy) return false
            rows = rows.toMutableList().apply { add(b, removeAt(a)) }
            adapter.notifyItemMoved(a, b)
            dragMoved = true
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val item = when (val row = rows.getOrNull(viewHolder.bindingAdapterPosition)) {
                is Row.ToBuy -> row.item
                is Row.Done -> row.item
                else -> null
            }
            if (item != null) ShoppingStore.remove(this@ShoppingActivity, item.id)
            refresh()
        }

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                dragFrom = viewHolder.bindingAdapterPosition
                dragMoved = false
                viewHolder.itemView.alpha = 0.85f
            }
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            viewHolder.itemView.alpha = 1f
            if (dragFrom < 0) return
            val from = dragFrom
            dragFrom = -1
            if (dragMoved) {
                ShoppingStore.reorder(this@ShoppingActivity, listId, rows.filterIsInstance<Row.ToBuy>().map { it.item.id })
                refresh()
            } else {
                // A long press without moving: the row's menu
                when (val row = rows.getOrNull(from)) {
                    is Row.ToBuy -> showItemMenu(row.item)
                    is Row.Done -> showItemMenu(row.item)
                    else -> {}
                }
            }
        }
    })

    // ---------------------------------------------------------------- rows

    private inner class ItemsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) = if (rows[position] is Row.DoneHeader) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view = if (viewType == 1) LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(6.dpToPx(), 18.dpToPx(), 0, 6.dpToPx())
                addView(TextView(parent.context).apply {
                    textSize = 12f
                    letterSpacing = 0.08f
                    setTextColor(secondary)
                    setTypeface(typeface, Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(parent.context).apply {
                    text = getString(R.string.ml_shop_clear_done)
                    textSize = 13f
                    setTextColor(primary)
                    setPadding(12.dpToPx(), 6.dpToPx(), 12.dpToPx(), 6.dpToPx())
                    background = UiKit.ripple(parent.context)
                    setOnClickListener {
                        ShoppingStore.clearDone(this@ShoppingActivity, listId)
                        refresh()
                    }
                })
            } else LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = 52.dpToPx()
                setPadding(6.dpToPx(), 0, 6.dpToPx(), 0)
                background = UiKit.ripple(parent.context)
                isClickable = true
                addView(ImageView(parent.context), LinearLayout.LayoutParams(22.dpToPx(), 22.dpToPx()).apply { marginEnd = 14.dpToPx() })
                addView(TextView(parent.context).apply {
                    textSize = 16f
                    maxLines = 2
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(parent.context).apply {
                    textSize = 14f
                    setTextColor(secondary)
                    setPadding(10.dpToPx(), 0, 4.dpToPx(), 0)
                })
            }
            view.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val row = rows[position]
            val view = holder.itemView as LinearLayout
            if (row is Row.DoneHeader) {
                (view.getChildAt(0) as TextView).text = getString(R.string.ml_shop_done_section, row.count).uppercase()
                return
            }
            val item = (row as? Row.ToBuy)?.item ?: (row as Row.Done).item
            val done = row is Row.Done
            val box = view.getChildAt(0) as ImageView
            val text = view.getChildAt(1) as TextView
            val qty = view.getChildAt(2) as TextView
            box.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (done) setColor(ColorUtils.setAlphaComponent(primary, 0x60))
                setStroke(2.dpToPx(), if (done) ColorUtils.setAlphaComponent(primary, 0x60) else secondary)
            }
            box.setImageResource(if (done) R.drawable.ic_m_check else 0)
            box.imageTintList = android.content.res.ColorStateList.valueOf(getColorFromAttr(R.attr.primaryInverseColor))
            box.setPadding(3.dpToPx(), 3.dpToPx(), 3.dpToPx(), 3.dpToPx())
            text.text = item.text
            text.setTextColor(if (done) secondary else primary)
            text.paintFlags = if (done) text.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG else text.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            qty.text = if (item.qty > 1) "× ${item.qty}" else ""
            qty.isVisible = item.qty > 1
            view.alpha = if (done) 0.7f else 1f
            view.setOnClickListener {
                ShoppingStore.setDone(this@ShoppingActivity, item.id, !done)
                refresh()
            }
        }
    }
}
