package app.olauncher.ui.widgets

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Build
import android.view.MotionEvent
import android.view.View
import app.olauncher.R
import app.olauncher.helper.showToast
import app.olauncher.ui.UiKit

/**
 * The launcher's battery widget: a thin outlined battery filled to the charge, with the percentage.
 * It fits whatever size it is given: small shows only the percentage, wider adds the battery,
 * wide adds the time to full while charging. Red below [LOW]; a bolt while charging. A tap opens
 * Android's battery usage; long press moves / resizes it like any widget.
 */
@SuppressLint("ViewConstructor")
class BatteryWidgetView(context: Context, val widgetId: Int) : View(context) {

    val touch = GridItemTouch(this) { openBatteryUsage() }

    private var level = -1
    private var charging = false
    private var fullInMs = -1L

    /** The card behind it (on by default); off, it sits bare on the wallpaper like the clock. */
    var framed: Boolean
        get() = prefs().getBoolean("FRAME_$widgetId", true)
        set(value) {
            prefs().edit().putBoolean("FRAME_$widgetId", value).apply()
            applyFrame()
        }

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = update(intent)
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val pctPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val bolt = Path()

    init {
        applyFrame()
        contentDescription = context.getString(R.string.ml_battery_widget)
    }

    private fun applyFrame() {
        background = if (framed) UiKit.cardBackground(context, 16) else null
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Sticky: the current state comes back right away
        val sticky = try {
            context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (e: Exception) {
            null
        }
        sticky?.let { update(it) }
    }

    override fun onDetachedFromWindow() {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        super.onDetachedFromWindow()
    }

    private fun update(intent: Intent) {
        val raw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        level = if (raw < 0) -1 else (raw * 100 / scale).coerceIn(0, 100)
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        fullInMs = if (charging && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                (context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).computeChargeTimeRemaining()
            } catch (e: Exception) {
                -1L
            }
        } else -1L
        contentDescription = "${context.getString(R.string.ml_battery_widget)} $level%"
        invalidate()
    }

    private fun statusText(): String? {
        if (!charging) return null
        if (level >= 100) return context.getString(R.string.ml_battery_full)
        if (fullInMs <= 0) return context.getString(R.string.ml_battery_charging)
        val minutes = (fullInMs / 60_000L).coerceAtLeast(1)
        val time = if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
        return context.getString(R.string.ml_battery_full_in, time)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (level < 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = minOf(h * 0.18f, w * 0.08f)
        val color = if (level <= LOW && !charging) LOW_COLOR else UiKit.primary(context)
        val text = "$level%"

        // Sizes follow the height; the layout drops the parts that don't fit the width
        pctPaint.textSize = h * 0.46f
        val gap = h * 0.16f
        val iconH = h * 0.36f
        val iconW = iconH * 2.0f
        var pctW = pctPaint.measureText(text)
        val status = statusText()
        subPaint.textSize = h * 0.24f
        val statusW = status?.let { subPaint.measureText(it) } ?: 0f
        val avail = w - 2 * pad
        val showIcon = iconW + gap + pctW <= avail
        val showStatus = showIcon && status != null && iconW + gap + pctW + gap * 1.5f + statusW <= avail
        if (!showIcon && pctW > avail) {
            // Only the percentage, shrunk to the width
            pctPaint.textSize *= avail / pctW
            pctW = pctPaint.measureText(text)
        }
        val total = (if (showIcon) iconW + gap else 0f) + pctW + (if (showStatus) gap * 1.5f + statusW else 0f)
        var x = (w - total) / 2f
        val cy = h / 2f

        if (showIcon) {
            drawBattery(canvas, x, cy - iconH / 2f, iconW, iconH, color)
            x += iconW + gap
        }
        pctPaint.color = color
        val fm = pctPaint.fontMetrics
        canvas.drawText(text, x, cy - (fm.ascent + fm.descent) / 2f, pctPaint)
        x += pctW
        if (showStatus) {
            subPaint.color = UiKit.secondary(context)
            val sm = subPaint.fontMetrics
            canvas.drawText(status!!, x + gap * 1.5f, cy - (sm.ascent + sm.descent) / 2f, subPaint)
        }
    }

    /** Thin outline with the tip on the right, filled to the level; the bolt is cut out of it. */
    private fun drawBattery(canvas: Canvas, left: Float, top: Float, width: Float, height: Float, color: Int) {
        val stroke = (height * 0.09f).coerceAtLeast(1f)
        val tipW = height * 0.14f
        val bodyW = width - tipW - stroke
        strokePaint.strokeWidth = stroke
        strokePaint.color = color
        fillPaint.color = color
        val layer = canvas.saveLayer(left - stroke, top - stroke, left + width + stroke, top + height + stroke, null)
        rect.set(left + stroke / 2, top + stroke / 2, left + bodyW, top + height - stroke / 2)
        val r = height * 0.24f
        canvas.drawRoundRect(rect, r, r, strokePaint)
        // Tip
        rect.set(left + bodyW + stroke * 0.6f, top + height * 0.32f, left + bodyW + stroke * 0.6f + tipW, top + height * 0.68f)
        canvas.drawRoundRect(rect, tipW / 2, tipW / 2, fillPaint)
        // Fill
        // Inside the outline, one stroke of gap all around
        val inset = stroke * 2f
        val innerW = bodyW - stroke * 3.5f
        val fillW = innerW * level / 100f
        if (fillW > 0f) {
            rect.set(left + inset, top + inset, left + inset + fillW, top + height - inset)
            val fr = (height - 2 * inset) * 0.2f
            canvas.drawRoundRect(rect, fr, fr, fillPaint)
        }
        if (charging) {
            val cx = left + bodyW / 2f
            val bh = height * 0.62f
            val bw = bh * 0.55f
            val by = top + (height - bh) / 2f
            bolt.reset()
            bolt.moveTo(cx + bw * 0.15f, by)
            bolt.lineTo(cx - bw * 0.5f, by + bh * 0.58f)
            bolt.lineTo(cx - bw * 0.02f, by + bh * 0.58f)
            bolt.lineTo(cx - bw * 0.15f, by + bh)
            bolt.lineTo(cx + bw * 0.5f, by + bh * 0.42f)
            bolt.lineTo(cx + bw * 0.02f, by + bh * 0.42f)
            bolt.close()
            // Outline of the bolt cut out, the bolt itself drawn: readable on the fill and on the empty part
            clearPaint.style = Paint.Style.STROKE
            clearPaint.strokeWidth = stroke * 1.6f
            canvas.drawPath(bolt, clearPaint)
            canvas.drawPath(bolt, fillPaint)
        }
        canvas.restoreToCount(layer)
    }

    private fun openBatteryUsage() {
        val intents = listOf(
            Intent(Intent.ACTION_POWER_USAGE_SUMMARY),
            Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS),
        )
        for (intent in intents) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
            }
        }
        context.showToast(context.getString(R.string.ml_battery_widget) + " $level%")
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = touch.touch(event)

    fun forget() = prefs().edit().remove("FRAME_$widgetId").apply()

    companion object {
        const val KIND = "battery"
        private const val PREFS = "app.minimalauncher.battery_widget"
        private const val LOW = 15
        private const val LOW_COLOR = 0xFFFF5A4E.toInt()
        /** Smallest side while resizing: it is meant to be tiny. */
        const val MIN_SIZE_DP = 22
    }
}
