package app.olauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import app.olauncher.R
import app.olauncher.helper.getColorFromAttr

/**
 * Vertical strip of letters for the app drawer. Touching or dragging over it reports the letter
 * under the finger; the touched letter is drawn bigger.
 */
class AlphabetIndexView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var letters: List<String> = emptyList()
        set(value) {
            field = value
            selected = -1
            invalidate()
        }

    var onLetter: ((String) -> Unit)? = null
    var onTouchStateChanged: ((Boolean) -> Unit)? = null

    private var selected = -1
    private val normalSize = sp(12f)
    private val selectedSize = sp(26f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = context.getColorFromAttr(R.attr.primaryColor)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun sp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (letters.isEmpty()) return
        val slot = height.toFloat() / letters.size
        letters.forEachIndexed { i, letter ->
            val isSelected = i == selected
            paint.textSize = if (isSelected) selectedSize else minOf(normalSize, slot * 0.8f)
            paint.alpha = if (isSelected) 255 else 170
            // Selected letter floats to the left of the strip so the finger doesn't cover it
            val column = width - sp(12f)
            val x = if (isSelected) column - sp(36f) else column
            val y = slot * i + slot / 2 - (paint.descent() + paint.ascent()) / 2
            canvas.drawText(letter, x, y, paint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (letters.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    onTouchStateChanged?.invoke(true)
                }
                val index = (event.y / height * letters.size).toInt().coerceIn(0, letters.lastIndex)
                if (index != selected) {
                    selected = index
                    invalidate()
                    onLetter?.invoke(letters[index])
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                selected = -1
                invalidate()
                onTouchStateChanged?.invoke(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    companion object {
        /** Index letter for a label: A-Z ignoring accents, "#" for anything else. */
        fun letterFor(label: String): String {
            val first = java.text.Normalizer.normalize(label.trim().take(1), java.text.Normalizer.Form.NFD)
                .firstOrNull()?.uppercaseChar() ?: return "#"
            return if (first in 'A'..'Z') first.toString() else "#"
        }
    }
}
