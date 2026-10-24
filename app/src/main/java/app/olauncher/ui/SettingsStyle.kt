package app.olauncher.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import app.olauncher.R
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr

/**
 * The settings screen in the cards' style, applied over its layout: each group a card with a
 * thin border, its title in small grey capitals, the rows split by thin lines, the values on
 * the right in grey. The cards with a title start collapsed and open with a tap on it; the
 * search field above them filters the rows by their text.
 */
object SettingsStyle {

    private class Section(val group: LinearLayout, val title: TextView?, val body: LinearLayout, var expanded: Boolean = false)

    fun apply(scrollLayout: LinearLayout, search: EditText) {
        val ctx = scrollLayout.context
        val card = ctx.getColorFromAttr(R.attr.cardColor)
        val stroke = ctx.getColorFromAttr(R.attr.cardStrokeColor)
        val line = ctx.getColorFromAttr(R.attr.cardLineColor)
        val secondary = ctx.getColorFromAttr(R.attr.secondaryTextColor)
        val sections = mutableListOf<Section>()
        for (i in 0 until scrollLayout.childCount) {
            val group = scrollLayout.getChildAt(i) as? LinearLayout ?: continue
            // Only the groups (the search field is no LinearLayout)
            if (group.background == null) continue
            group.background = GradientDrawable().apply {
                cornerRadius = 20.dpToPx().toFloat()
                setColor(card)
                setStroke(1.dpToPx(), stroke)
            }
            group.setPadding(6.dpToPx(), 10.dpToPx(), 6.dpToPx(), 6.dpToPx())
            for (j in 0 until group.childCount) {
                val child = group.getChildAt(j)
                if (j == 0 && child is TextView) {
                    sectionTitle(child, secondary)
                    continue
                }
                if (j == 0) continue
                // A line above every row after the title (it follows the row when that is hidden)
                if (child.background == null) child.background = LayerDrawable(arrayOf(ColorDrawable(line), ColorDrawable(card))).apply {
                    setLayerInset(1, 0, 1.dpToPx(), 0, 0)
                }
                child.minimumHeight = 52.dpToPx()
                (child.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                    it.topMargin = 0
                    child.layoutParams = it
                }
                if (child is FrameLayout) row(child, secondary)
                else if (child is TextView) child.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            }
            sections += section(group, secondary)
        }
        search(scrollLayout, search, sections, card, stroke, secondary)
    }

    /** Moves the rows under the title into a body that the title opens and closes. */
    private fun section(group: LinearLayout, secondary: Int): Section {
        val title = group.getChildAt(0) as? TextView
        val body = LinearLayout(group.context).apply { orientation = LinearLayout.VERTICAL }
        val first = if (title != null) 1 else 0
        while (group.childCount > first) {
            val child = group.getChildAt(first)
            group.removeViewAt(first)
            body.addView(child)
        }
        group.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val section = Section(group, title, body, expanded = title == null)
        if (title != null) {
            title.layoutParams = (title.layoutParams as LinearLayout.LayoutParams).apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                topMargin = 0
                bottomMargin = 0
            }
            title.setPadding(title.paddingLeft, 10.dpToPx(), 12.dpToPx(), 10.dpToPx())
            title.compoundDrawablePadding = 8.dpToPx()
            title.compoundDrawableTintList = ColorStateList.valueOf(secondary)
            title.setOnClickListener {
                section.expanded = !section.expanded
                showExpanded(section)
            }
            showExpanded(section)
        }
        return section
    }

    private fun showExpanded(section: Section) {
        val title = section.title ?: return
        section.body.visibility = if (section.expanded) View.VISIBLE else View.GONE
        val icon = ContextCompat.getDrawable(
            title.context,
            if (section.expanded) R.drawable.ic_m_chevron_up else R.drawable.ic_m_chevron_down
        )?.mutate()?.apply { setBounds(0, 0, 18.dpToPx(), 18.dpToPx()) }
        title.setCompoundDrawablesRelative(null, null, icon, null)
    }

    private fun search(
        scrollLayout: LinearLayout, search: EditText, sections: List<Section>,
        card: Int, stroke: Int, secondary: Int,
    ) {
        val ctx = scrollLayout.context
        search.background = GradientDrawable().apply {
            cornerRadius = 24.dpToPx().toFloat()
            setColor(card)
            setStroke(1.dpToPx(), stroke)
        }
        val empty = TextView(ctx).apply {
            text = ctx.getString(R.string.ml_settings_no_results)
            setTextColor(secondary)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 24.dpToPx(), 0, 24.dpToPx())
            visibility = View.GONE
        }
        scrollLayout.addView(empty, scrollLayout.indexOfChild(search) + 1)
        // The field must not take the focus (and the keyboard) when the screen opens
        scrollLayout.isFocusableInTouchMode = true
        scrollLayout.requestFocus()

        // The rows hidden by the search, given back when it changes (others hide on their own)
        val hidden = mutableSetOf<View>()
        search.doAfterTextChanged { text ->
            val query = normalize(text?.toString().orEmpty().trim())
            hidden.forEach { it.visibility = View.VISIBLE }
            hidden.clear()
            var found = false
            for (section in sections) {
                if (query.isEmpty()) {
                    section.group.visibility = View.VISIBLE
                    if (section.title != null) showExpanded(section)
                    continue
                }
                val titleMatch = section.title?.let { normalize(it.text.toString()).contains(query) } == true
                var any = titleMatch
                if (!titleMatch) for (i in 0 until section.body.childCount) {
                    val row = section.body.getChildAt(i)
                    if (row.visibility != View.VISIBLE) continue
                    if (normalize(texts(row)).contains(query)) any = true
                    else {
                        row.visibility = View.GONE
                        hidden += row
                    }
                }
                section.group.visibility = if (any) View.VISIBLE else View.GONE
                if (any) section.body.visibility = View.VISIBLE
                found = found || any
            }
            empty.visibility = if (query.isNotEmpty() && !found) View.VISIBLE else View.GONE
        }
        search.setOnEditorActionListener { v, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            (v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(v.windowToken, 0)
            true
        }
    }

    /** All the text of a row: its label and its value. */
    private fun texts(view: View): String = when (view) {
        is TextView -> view.text.toString()
        is ViewGroup -> (0 until view.childCount).joinToString(" ") { texts(view.getChildAt(it)) }
        else -> ""
    }

    private fun normalize(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")

    private fun sectionTitle(title: TextView, color: Int) {
        title.textSize = 12f
        title.isAllCaps = true
        title.letterSpacing = 0.08f
        title.setTextColor(color)
        title.typeface = Typeface.create(title.typeface, Typeface.BOLD)
        (title.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.topMargin = 4.dpToPx()
            it.bottomMargin = 10.dpToPx()
            title.layoutParams = it
        }
    }

    /** Label on the left, value on the right, both centred in the row; the value grey and plain. */
    private fun row(row: FrameLayout, secondary: Int) {
        for (k in 0 until row.childCount) {
            val v = row.getChildAt(k)
            val lp = v.layoutParams as? FrameLayout.LayoutParams ?: continue
            val end = lp.gravity != FrameLayout.LayoutParams.UNSPECIFIED_GRAVITY &&
                    (lp.gravity and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK).let { it == Gravity.END || it == Gravity.RIGHT }
            lp.gravity = (if (end) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
            v.layoutParams = lp
            if (end && v is TextView) {
                v.setTextColor(secondary)
                v.typeface = Typeface.create(v.typeface, Typeface.NORMAL)
                v.textSize = 14f
            }
        }
    }
}
