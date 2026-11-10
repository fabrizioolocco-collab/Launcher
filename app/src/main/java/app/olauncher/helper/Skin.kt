package app.olauncher.helper

import android.app.Activity
import android.app.Application
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import app.olauncher.R
import app.olauncher.data.Prefs
import java.io.File

/**
 * The launcher's looks (Settings > Appearance > Style), each always dark and with a wallpaper of its own:
 *  - MILITARY: olive and sand on a faint camouflage, panels with two cut corners, stencil clock,
 *    military date and countdowns in the calendar widget;
 *  - TACTICAL: the same shapes and texts on graphite and black, steel instead of sand;
 *  - ELEGANT: translucent rounded "glass" panels on a deep wallpaper with soft colours, thin clock.
 * NONE leaves the launcher as it is. The colours come from a theme overlay applied to every
 * activity; the shapes from [panel]; the wallpaper is set (and the previous one put back) by
 * [syncWallpaper].
 */
object Skin {

    enum class Kind(val id: String, val label: Int) {
        NONE("", R.string.ml_skin_none),
        MILITARY("military", R.string.ml_skin_military),
        TACTICAL("tactical", R.string.ml_skin_tactical),
        ELEGANT("elegant", R.string.ml_skin_elegant),
    }

    class Palette(
        val text: Int, val dim: Int, val accent: Int, val panel: Int, val panelStroke: Int,
        val ground: Int, val alert: Int,
        /** Multiplied over the home icons (white line icons take this colour); 0 = as they are. */
        val iconTint: Int,
    )

    private val MILITARY = Palette(
        text = 0xFFD9D2AE.toInt(), dim = 0xFF8E8A6C.toInt(), accent = 0xFFC8AE6E.toInt(),
        panel = 0xFF1F2318.toInt(), panelStroke = 0xFF343A28.toInt(), ground = 0xFF121410.toInt(),
        alert = 0xFFFF7A1A.toInt(), iconTint = 0xFFC8AE6E.toInt(),
    )
    private val TACTICAL = Palette(
        text = 0xFFD5D8DA.toInt(), dim = 0xFF80868B.toInt(), accent = 0xFFB8BFC5.toInt(),
        panel = 0xFF15171A.toInt(), panelStroke = 0xFF2B2F33.toInt(), ground = 0xFF0A0B0C.toInt(),
        alert = 0xFFFF7A1A.toInt(), iconTint = 0,
    )
    private val ELEGANT = Palette(
        text = 0xFFFFFFFF.toInt(), dim = 0xFFB4B8CC.toInt(), accent = 0xFFE7C9A1.toInt(),
        panel = 0x1FFFFFFF, panelStroke = 0x38FFFFFF, ground = 0xFF15172B.toInt(),
        alert = 0xFFFFB0A6.toInt(), iconTint = 0,
    )

    fun kind(context: Context): Kind = Prefs(context).skin.let { id -> Kind.values().firstOrNull { it.id == id } ?: Kind.NONE }

    fun isOn(context: Context) = kind(context) != Kind.NONE

    /** Cut corners, stencil and condensed capitals, military date and countdowns. */
    fun cut(context: Context) = kind(context).let { it == Kind.MILITARY || it == Kind.TACTICAL }

    fun palette(context: Context): Palette = palette(kind(context))

    private fun palette(kind: Kind) = when (kind) {
        Kind.TACTICAL -> TACTICAL
        Kind.ELEGANT -> ELEGANT
        else -> MILITARY
    }

    private fun overlay(kind: Kind) = when (kind) {
        Kind.MILITARY -> R.style.SkinMilitary
        Kind.TACTICAL -> R.style.SkinTactical
        Kind.ELEGANT -> R.style.SkinElegant
        Kind.NONE -> 0
    }

    /** Every activity of the launcher (home, gallery, files, ToDo, viewers...) gets the look's colours. */
    fun register(app: Application) {
        if (isOn(app)) AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) = applyTo(activity)
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                // Before Android 10 there is no "pre created": this still runs before the content is set
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) applyTo(activity)
                solidWindow(activity)
            }
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /**
     * The launcher's own screens (gallery, files, ToDo...) get the look's ground as their window: the
     * theme shows the wallpaper, which then showed in the bands under the status bar and the navigation
     * bar in another colour. The home keeps the wallpaper; see-through screens stay as they are.
     */
    private fun solidWindow(activity: Activity) {
        if (!isOn(activity) || activity is app.olauncher.MainActivity) return
        val a = activity.theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowIsTranslucent))
        val translucent = a.getBoolean(0, false)
        a.recycle()
        if (translucent) return
        val window = activity.window
        val ground = palette(activity).ground
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(ground))
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        if (Build.VERSION.SDK_INT < 35) {
            // Before Android 15 the bars can still have a colour of their own
            @Suppress("DEPRECATION")
            window.statusBarColor = ground
            @Suppress("DEPRECATION")
            window.navigationBarColor = ground
        }
    }

    private fun applyTo(activity: Activity) {
        val style = overlay(kind(activity))
        if (style != 0) activity.theme.applyStyle(style, true)
    }

    // ---------------------------------------------------------------- type

    private fun font(context: Context, res: Int): Typeface? = try {
        ResourcesCompat.getFont(context, res)
    } catch (e: Exception) {
        null
    }

    /** The clock's face: stencil on the cut looks, thin on the elegant one. */
    fun clockFace(context: Context): Typeface? = when (kind(context)) {
        Kind.MILITARY, Kind.TACTICAL -> font(context, R.font.ml_saira_stencil)
        Kind.ELEGANT -> Typeface.create("sans-serif-thin", Typeface.NORMAL)
        Kind.NONE -> null
    }

    /** The face of labels and lists: condensed on the cut looks. */
    fun bodyFace(context: Context): Typeface? = if (cut(context)) font(context, R.font.ml_saira_condensed) else null

    // ---------------------------------------------------------------- shapes

    /**
     * A panel in the look's shape: two cut corners ([cutDp]) or, on the elegant look, a rounded
     * ([radiusDp]) translucent sheet. [fill] / [stroke] default to the look's panel colours.
     */
    fun panel(context: Context, fill: Int? = null, stroke: Int? = null, cutDp: Int = 10, radiusDp: Int = 20): Drawable {
        val p = palette(context)
        val f = fill ?: p.panel
        val s = stroke ?: p.panelStroke
        return if (cut(context)) NotchedDrawable(f, s, cutDp.dpToPx().toFloat())
        else GradientDrawable().apply {
            cornerRadius = radiusDp.dpToPx().toFloat()
            setColor(f)
            if (s ushr 24 > 0) setStroke(1.dpToPx(), s)
        }
    }

    private class NotchedDrawable(private val fill: Int, private val stroke: Int, private val cut: Float) : Drawable() {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = fill }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = stroke
            strokeWidth = 1.dpToPx().toFloat()
        }
        private val path = Path()

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            val half = strokePaint.strokeWidth / 2
            val l = bounds.left + half
            val t = bounds.top + half
            val r = bounds.right - half
            val b = bounds.bottom - half
            val c = cut.coerceAtMost(minOf(r - l, b - t) / 3)
            path.reset()
            path.moveTo(l + c, t)
            path.lineTo(r, t)
            path.lineTo(r, b - c)
            path.lineTo(r - c, b)
            path.lineTo(l, b)
            path.lineTo(l, t + c)
            path.close()
        }

        override fun draw(canvas: Canvas) {
            if (fill ushr 24 > 0) canvas.drawPath(path, fillPaint)
            if (stroke ushr 24 > 0) canvas.drawPath(path, strokePaint)
        }

        override fun getOutline(outline: android.graphics.Outline) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= 30) outline.setPath(path) else outline.setConvexPath(path)
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = (fill ushr 24) * alpha / 255
            strokePaint.alpha = (stroke ushr 24) * alpha / 255
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            fillPaint.colorFilter = colorFilter
            strokePaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    // ---------------------------------------------------------------- wallpaper

    /** A soft blotch: centre (fractions of the size), radius (fraction of the width), colour. */
    private class Blotch(val cx: Float, val cy: Float, val r: Float, val color: Int)

    private fun blotches(kind: Kind) = when (kind) {
        // A faint olive camouflage
        Kind.MILITARY -> listOf(
            Blotch(0.2f, 0.25f, 0.55f, 0xFF1D2116.toInt()), Blotch(0.85f, 0.45f, 0.5f, 0xFF1A1D13.toInt()),
            Blotch(0.3f, 0.7f, 0.5f, 0xFF1C2015.toInt()), Blotch(0.75f, 0.9f, 0.45f, 0xFF191C12.toInt()),
        )
        // Graphite on black
        Kind.TACTICAL -> listOf(
            Blotch(0.15f, 0.2f, 0.6f, 0xFF15171A.toInt()), Blotch(0.9f, 0.5f, 0.55f, 0xFF121416.toInt()),
            Blotch(0.3f, 0.85f, 0.55f, 0xFF141618.toInt()),
        )
        // Warm, violet and teal lights on deep blue
        else -> listOf(
            Blotch(0.1f, 0.12f, 0.75f, 0xFFC96A4A.toInt()), Blotch(0.95f, 0.42f, 0.7f, 0xFF5A4FC4.toInt()),
            Blotch(0.25f, 0.92f, 0.85f, 0xFF1C7F8F.toInt()),
        )
    }

    private fun wallpaper(context: Context, kind: Kind): Bitmap {
        val metrics = context.resources.displayMetrics
        // Half the screen is plenty for soft gradients (and light on memory)
        val w = (minOf(metrics.widthPixels, metrics.heightPixels) / 2).coerceAtLeast(360)
        val h = (maxOf(metrics.widthPixels, metrics.heightPixels) / 2).coerceAtLeast(720)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(palette(kind).ground)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        blotches(kind).forEach { b ->
            val x = w * b.cx
            val y = h * b.cy
            val radius = w * b.r
            paint.shader = RadialGradient(x, y, radius, b.color, b.color and 0x00FFFFFF, Shader.TileMode.CLAMP)
            canvas.drawCircle(x, y, radius, paint)
        }
        return bitmap
    }

    private fun backupFile(context: Context) = File(context.filesDir, "wallpaper_before_skin.png")

    private fun setWallpaper(context: Context, bitmap: Bitmap) {
        val manager = WallpaperManager.getInstance(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            manager.setBitmap(bitmap, null, false, WallpaperManager.FLAG_SYSTEM)
            manager.setBitmap(bitmap, null, false, WallpaperManager.FLAG_LOCK)
        } else manager.setBitmap(bitmap)
    }

    /**
     * Makes the wallpaper match the look chosen (home and lock screen): the look's own wallpaper,
     * or, back on no look, the wallpaper there was before (when it could be saved) or the plain
     * one of the light / dark theme. Does nothing when it already matches. File work: call it off
     * the main thread.
     */
    @Synchronized
    fun syncWallpaper(context: Context) {
        val prefs = Prefs(context)
        val kind = kind(context)
        if (prefs.skinWallpaper == kind.id) return
        try {
            if (kind == Kind.NONE) {
                val backup = backupFile(context)
                val saved = if (backup.isFile) BitmapFactory.decodeFile(backup.absolutePath) else null
                if (saved != null) {
                    setWallpaper(context, saved)
                    saved.recycle()
                } else setPlainWallpaperByTheme(context, prefs.appTheme)
                backup.delete()
            } else {
                // Coming from no look: keep the wallpaper there is, to put it back later (needs the
                // storage permission; without it the plain wallpaper comes back instead)
                if (prefs.skinWallpaper.isEmpty()) try {
                    @Suppress("MissingPermission")
                    val current = WallpaperManager.getInstance(context).drawable
                    if (current != null) backupFile(context).outputStream().use {
                        current.toBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                } catch (e: Throwable) {
                    backupFile(context).delete()
                }
                val bitmap = wallpaper(context, kind)
                setWallpaper(context, bitmap)
                bitmap.recycle()
            }
            prefs.skinWallpaper = kind.id
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }
}
