package app.olauncher.ui.pdf

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import app.olauncher.R
import app.olauncher.helper.dpToPx
import app.olauncher.helper.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Creates the saved signature: drawn with the finger on a white box, or taken from an image
 * (light background made transparent). Saved as a PNG and reused in every PDF.
 */
class SignatureActivity : AppCompatActivity() {

    private lateinit var pad: PadView

    private val imagePicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }?.let { transparentBackground(it) }
                } catch (e: Exception) {
                    null
                }
            }
            if (bitmap == null) showToast(getString(R.string.ml_search_cannot_open)) else finishWith(bitmap)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dpToPx(), 8.dpToPx(), 8.dpToPx(), 8.dpToPx())
        }
        top.addView(button("←") { finish() })
        top.addView(TextView(this).apply {
            setText(R.string.ml_signature)
            setTextColor(Color.WHITE)
            textSize = 18f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 8.dpToPx() })
        top.addView(button(getString(R.string.ml_signature_image), 15f) {
            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        })
        root.addView(top)
        root.addView(TextView(this).apply {
            setText(R.string.ml_signature_hint)
            setTextColor(0xB3FFFFFF.toInt())
            textSize = 14f
            setPadding(20.dpToPx(), 4.dpToPx(), 20.dpToPx(), 8.dpToPx())
        })
        pad = PadView(this)
        root.addView(pad, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            setMargins(16.dpToPx(), 0, 16.dpToPx(), 0)
        })
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(8.dpToPx(), 12.dpToPx(), 8.dpToPx(), 16.dpToPx())
        }
        bottom.addView(button(getString(R.string.ml_signature_clear), 16f) { pad.clear() })
        bottom.addView(button(getString(R.string.ml_signature_use), 16f) {
            val bitmap = pad.result()
            if (bitmap == null) showToast(getString(R.string.ml_signature_hint)) else finishWith(bitmap)
        })
        root.addView(bottom)
        setContentView(root)
    }

    private fun button(label: String, size: Float = 26f, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = size
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(16.dpToPx(), 8.dpToPx(), 16.dpToPx(), 8.dpToPx())
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun finishWith(bitmap: Bitmap) {
        SavedSignature.save(this, bitmap)
        setResult(RESULT_OK)
        finish()
    }

    /** Makes near-white pixels transparent and the rest dark blue, so a photographed signature looks like ink. */
    private fun transparentBackground(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val lum = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
            pixels[i] = if (lum > 160) Color.TRANSPARENT else {
                val alpha = ((160 - lum) * 255 / 160).coerceIn(60, 255)
                Color.argb(alpha, 26, 26, 153)
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private inner class PadView(context: Context) : View(context) {
        private val paths = mutableListOf<Path>()
        private var current: Path? = null
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1A1A99.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 4f * resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val line = Paint().apply {
            color = 0xFFBBBBBB.toInt()
            strokeWidth = 2f
        }

        init {
            setBackgroundColor(Color.WHITE)
        }

        fun clear() {
            paths.clear()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val base = height * 0.72f
            canvas.drawLine(width * 0.08f, base, width * 0.92f, base, line)
            paths.forEach { canvas.drawPath(it, ink) }
            current?.let { canvas.drawPath(it, ink) }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> current = Path().apply { moveTo(e.x, e.y) }
                MotionEvent.ACTION_MOVE -> current?.lineTo(e.x, e.y)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    current?.let {
                        it.lineTo(e.x, e.y)
                        paths += it
                    }
                    current = null
                }
            }
            invalidate()
            return true
        }

        /** The strokes on a transparent bitmap, cropped to their bounds with a margin. */
        fun result(): Bitmap? {
            if (paths.isEmpty()) return null
            val full = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val c = Canvas(full)
            paths.forEach { c.drawPath(it, ink) }
            val bounds = android.graphics.RectF()
            paths.forEach { p ->
                val b = android.graphics.RectF()
                p.computeBounds(b, true)
                bounds.union(b)
            }
            val m = ink.strokeWidth * 2
            val l = (bounds.left - m).toInt().coerceAtLeast(0)
            val t = (bounds.top - m).toInt().coerceAtLeast(0)
            val r = (bounds.right + m).toInt().coerceAtMost(width)
            val b = (bounds.bottom + m).toInt().coerceAtMost(height)
            if (r <= l || b <= t) return null
            return Bitmap.createBitmap(full, l, t, r - l, b - t)
        }
    }

    companion object {
        fun intent(context: Context) = Intent(context, SignatureActivity::class.java)
    }
}
