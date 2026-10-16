package app.olauncher.helper

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.util.LruCache
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import kotlin.math.abs

/**
 * Loads app icons, optionally from an installed icon pack (ADW/Nova-style appfilter.xml).
 * Apps missing from the pack get their original icon adapted with the pack's
 * iconback / iconmask / iconupon / scale, when the pack defines them.
 *
 * All loading methods are blocking: call them off the main thread.
 */
object IconPackManager {

    data class IconPackInfo(val packageName: String, val label: String)

    private val PACK_INTENTS = listOf(
        Intent("org.adw.launcher.THEMES"),
        Intent("com.novalauncher.THEME"),
        Intent("com.gau.go.launcherex.theme"),
        Intent("android.intent.action.MAIN").addCategory("com.anddoes.launcher.THEME"),
        Intent("com.fede.launcher.THEME_ICONPACK"),
        Intent("com.teslacoilsw.launcher.THEME"),
    )

    private class LoadedPack(
        val packageName: String,
        val resources: Resources,
        val components: Map<String, String>,
        val packages: Map<String, String>,
        val backs: List<Bitmap>,
        val mask: Bitmap?,
        val upon: Bitmap?,
        val scale: Float,
    )

    private var loadedPack: LoadedPack? = null
    private var failedPack: String? = null
    private val cache = LruCache<String, Bitmap>(400)

    fun installedPacks(context: Context): List<IconPackInfo> {
        val pm = context.packageManager
        val found = linkedMapOf<String, IconPackInfo>()
        for (intent in PACK_INTENTS) {
            val list = try {
                pm.queryIntentActivities(intent, PackageManager.GET_META_DATA)
            } catch (e: Exception) {
                emptyList()
            }
            for (ri in list) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (found.containsKey(pkg)) continue
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (e: Exception) {
                    pkg
                }
                found[pkg] = IconPackInfo(pkg, label)
            }
        }
        return found.values.sortedBy { it.label.lowercase() }
    }

    @Synchronized
    fun clearCache() {
        cache.evictAll()
        loadedPack = null
        failedPack = null
    }

    /** Returns the icon as a bitmap of [sizePx] x [sizePx], or null when nothing can be loaded. */
    fun getIcon(
        context: Context,
        packageName: String,
        activityClassName: String?,
        user: UserHandle,
        iconPackPackage: String,
        sizePx: Int,
    ): Bitmap? {
        val key = "$iconPackPackage|$packageName/$activityClassName|$user|$sizePx"
        synchronized(this) { cache.get(key) }?.let { return it }

        val bitmap = try {
            loadCustom(context, CustomIcons.get(context, packageName, activityClassName, user), sizePx)
                ?: if (FolderShortcuts.isFolder(packageName)) FolderShortcuts.icon(context, sizePx)
                else loadDefault(context, packageName, activityClassName, user, iconPackPackage, sizePx)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
        if (bitmap != null) synchronized(this) { cache.put(key, bitmap) }
        return bitmap
    }

    private fun loadDefault(
        context: Context,
        packageName: String,
        activityClassName: String?,
        user: UserHandle,
        iconPackPackage: String,
        sizePx: Int,
    ): Bitmap? {
        val bitmap = try {
            val pack = if (iconPackPackage.isBlank()) null else getPack(context, iconPackPackage)
            loadFromPack(pack, packageName, activityClassName, sizePx)
                ?: loadOriginal(context, packageName, activityClassName, user)?.let { original ->
                    if (pack != null) adapt(pack, original, packageName, sizePx)
                    else original.toBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
                }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
        return bitmap
    }

    /** Custom icon chosen by the user: "pack:<pack package>:<drawable>" or "file:<absolute path>". */
    private fun loadCustom(context: Context, spec: String?, sizePx: Int): Bitmap? {
        spec ?: return null
        return try {
            when {
                spec.startsWith(CustomIcons.PACK_PREFIX) -> {
                    val rest = spec.removePrefix(CustomIcons.PACK_PREFIX)
                    loadPackDrawable(context, rest.substringBefore(":"), rest.substringAfter(":"), sizePx)
                }

                spec.startsWith(CustomIcons.FILE_PREFIX) ->
                    android.graphics.BitmapFactory.decodeFile(spec.removePrefix(CustomIcons.FILE_PREFIX))
                        ?.let { Bitmap.createScaledBitmap(it, sizePx, sizePx, true) }

                else -> null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** Loads one drawable of an icon pack by name. Blocking. */
    @SuppressLint("DiscouragedApi")
    fun loadPackDrawable(context: Context, packPackage: String, drawableName: String, sizePx: Int): Bitmap? {
        return try {
            val res = context.packageManager.getResourcesForApplication(packPackage)
            val id = res.getIdentifier(drawableName, "drawable", packPackage)
            if (id == 0) null
            else ResourcesCompat.getDrawable(res, id, null)?.toBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * All icon names offered by a pack: its drawable.xml when present (the list packs publish for
     * manual picking), otherwise every drawable referenced by appfilter.xml.
     */
    @SuppressLint("DiscouragedApi")
    fun packIconNames(context: Context, packPackage: String): List<String> {
        val names = linkedSetOf<String>()
        try {
            val res = context.packageManager.getResourcesForApplication(packPackage)
            val xmlId = res.getIdentifier("drawable", "xml", packPackage)
            val parser: XmlPullParser? = if (xmlId != 0) res.getXml(xmlId) else try {
                XmlPullParserFactory.newInstance().newPullParser()
                    .apply { setInput(res.assets.open("drawable.xml"), "utf-8") }
            } catch (e: Exception) {
                null
            }
            if (parser != null) {
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && parser.name == "item")
                        parser.getAttributeValue(null, "drawable")?.takeIf { it.isNotBlank() }?.let { names.add(it) }
                    event = parser.next()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (names.isEmpty()) {
            getPack(context, packPackage)?.let { pack ->
                names.addAll(pack.components.values)
                names.addAll(pack.packages.values)
            }
        }
        return names.sorted()
    }

    fun toDrawable(context: Context, bitmap: Bitmap, sizePx: Int): Drawable =
        BitmapDrawable(context.resources, bitmap).apply { setBounds(0, 0, sizePx, sizePx) }

    @Synchronized
    private fun getPack(context: Context, packPackage: String): LoadedPack? {
        loadedPack?.let { if (it.packageName == packPackage) return it }
        if (failedPack == packPackage) return null
        val pack = try {
            parsePack(context, packPackage)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
        loadedPack = pack
        failedPack = if (pack == null) packPackage else null
        return pack
    }

    @SuppressLint("DiscouragedApi")
    private fun parsePack(context: Context, packPackage: String): LoadedPack? {
        val res = context.packageManager.getResourcesForApplication(packPackage)
        val components = HashMap<String, String>()
        val packages = HashMap<String, String>()
        val backNames = mutableListOf<String>()
        var maskName: String? = null
        var uponName: String? = null
        var scale = 1f

        val parser: XmlPullParser = run {
            val xmlId = res.getIdentifier("appfilter", "xml", packPackage)
            if (xmlId != 0) res.getXml(xmlId)
            else {
                val input = res.assets.open("appfilter.xml")
                XmlPullParserFactory.newInstance().newPullParser().apply { setInput(input, "utf-8") }
            }
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "item" -> {
                        val component = parser.getAttributeValue(null, "component")
                        val drawable = parser.getAttributeValue(null, "drawable")
                        if (component != null && !drawable.isNullOrBlank()) {
                            val inner = component.substringAfter("{", "").substringBefore("}", "")
                            if (inner.isNotBlank()) {
                                val pkg = inner.substringBefore("/")
                                val cls = inner.substringAfter("/", "")
                                if (cls.isNotBlank()) {
                                    // Some packs write ".Main" relative class names
                                    val fullCls = if (cls.startsWith(".")) pkg + cls else cls
                                    components.putIfAbsent("$pkg/$fullCls", drawable)
                                }
                                packages.putIfAbsent(pkg, drawable)
                            }
                        }
                    }

                    "iconback" -> for (i in 0 until parser.attributeCount) {
                        if (parser.getAttributeName(i).startsWith("img")) backNames.add(parser.getAttributeValue(i))
                    }

                    "iconmask" -> maskName = parser.getAttributeValue(null, "img1") ?: maskName
                    "iconupon" -> uponName = parser.getAttributeValue(null, "img1") ?: uponName
                    "scale" -> scale = parser.getAttributeValue(null, "factor")?.toFloatOrNull() ?: scale
                }
            }
            event = parser.next()
        }

        fun bitmapNamed(name: String?): Bitmap? {
            if (name.isNullOrBlank()) return null
            val id = res.getIdentifier(name, "drawable", packPackage)
            if (id == 0) return null
            return try {
                ResourcesCompat.getDrawable(res, id, null)?.toBitmap(config = Bitmap.Config.ARGB_8888)
            } catch (e: Exception) {
                null
            }
        }

        return LoadedPack(
            packageName = packPackage,
            resources = res,
            components = components,
            packages = packages,
            backs = backNames.mapNotNull { bitmapNamed(it) },
            mask = bitmapNamed(maskName),
            upon = bitmapNamed(uponName),
            scale = scale,
        )
    }

    @SuppressLint("DiscouragedApi")
    private fun loadFromPack(pack: LoadedPack?, packageName: String, activityClassName: String?, sizePx: Int): Bitmap? {
        pack ?: return null
        val name = activityClassName?.let { pack.components["$packageName/$it"] }
            ?: pack.packages[packageName]
            ?: return null
        val id = pack.resources.getIdentifier(name, "drawable", pack.packageName)
        if (id == 0) return null
        return ResourcesCompat.getDrawable(pack.resources, id, null)
            ?.toBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    }

    private fun loadOriginal(context: Context, packageName: String, activityClassName: String?, user: UserHandle): Drawable? {
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val activities = try {
            launcherApps.getActivityList(packageName, user)
        } catch (e: Exception) {
            emptyList()
        }
        val info = activities.firstOrNull { it.componentName.className == activityClassName }
            ?: activities.firstOrNull()
        if (info != null) {
            return if (user == android.os.Process.myUserHandle()) info.getIcon(0) else info.getBadgedIcon(0)
        }
        return try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (e: Exception) {
            null
        }
    }

    /** Classic ADW algorithm: scaled icon, cut by the mask, back drawn behind, upon drawn on top. */
    private fun adapt(pack: LoadedPack, original: Drawable, packageName: String, sizePx: Int): Bitmap {
        if (pack.backs.isEmpty() && pack.mask == null && pack.upon == null)
            return original.toBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)

        val result = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val full = Rect(0, 0, sizePx, sizePx)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val inner = (sizePx * pack.scale).toInt().coerceIn(1, sizePx)
        val offset = (sizePx - inner) / 2
        val icon = original.toBitmap(inner, inner, Bitmap.Config.ARGB_8888)
        canvas.drawBitmap(icon, null, Rect(offset, offset, offset + inner, offset + inner), paint)

        pack.mask?.let {
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            canvas.drawBitmap(it, null, full, paint)
        }
        if (pack.backs.isNotEmpty()) {
            val back = pack.backs[abs(packageName.hashCode()) % pack.backs.size]
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OVER)
            canvas.drawBitmap(back, null, full, paint)
        }
        pack.upon?.let {
            paint.xfermode = null
            canvas.drawBitmap(it, null, full, paint)
        }
        return result
    }
}
