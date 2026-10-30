package app.olauncher.ui.todo

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.exifinterface.media.ExifInterface
import app.olauncher.R
import app.olauncher.data.TodoStore
import app.olauncher.helper.dpToPx
import app.olauncher.ui.UiKit
import app.olauncher.ui.media.ZoomImageView
import java.io.File

/** The photos attached to todos: copied into the app's storage, shown as a preview and full screen. */
object TodoPhotos {

    /** Copies the image at [uri] into [TodoStore.photoDir]; the new file's name, or null. Off the main thread. */
    fun copy(context: Context, uri: Uri): String? {
        val ext = when (context.contentResolver.getType(uri)) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "jpg"
        }
        val file = File(TodoStore.photoDir(context), "p${System.currentTimeMillis()}_${(1000..9999).random()}.$ext")
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { i -> file.outputStream().use { o -> i.copyTo(o) } }
            // Not an image after all: nothing to keep
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0) {
                file.delete()
                null
            } else file.name
        } catch (e: Exception) {
            e.printStackTrace()
            file.delete()
            null
        }
    }

    /** A copy of the photo [name] under a new name (a duplicated todo gets its own); "" if there's none. */
    fun duplicate(context: Context, name: String): String {
        if (name.isEmpty()) return ""
        val from = File(TodoStore.photoDir(context), name).takeIf { it.isFile } ?: return ""
        val to = File(from.parentFile, "p${System.currentTimeMillis()}_${(1000..9999).random()}.${from.extension}")
        return try {
            from.copyTo(to)
            to.name
        } catch (e: Exception) {
            ""
        }
    }

    /** Decodes [file] with its longest side about [maxSide] px, honouring EXIF rotation. Off the main thread. */
    fun decode(file: File, maxSide: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSide || bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        val degrees = when (ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (bitmap == null || degrees == 0f) bitmap
        else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, android.graphics.Matrix().apply { postRotate(degrees) }, true)
    } catch (e: Throwable) {
        // Includes OutOfMemoryError on huge images: no picture rather than a crash
        null
    }

    /** Decodes [file] in the background and sets it on [view] (if still attached). */
    fun load(view: ImageView, file: File, maxSide: Int) {
        Thread {
            val bitmap = decode(file, maxSide) ?: return@Thread
            view.post { view.setImageBitmap(bitmap) }
        }.start()
    }

    /** The photo full screen on black, with pinch and double tap zoom; back or the cross closes it. */
    fun showFull(context: Context, file: File) {
        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        val image = ZoomImageView(context)
        root.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val close = UiKit.iconButton(context, R.drawable.ic_m_close, context.getString(R.string.close), Color.WHITE) { dialog.dismiss() }
        root.addView(close, FrameLayout.LayoutParams(48.dpToPx(), 48.dpToPx(), Gravity.TOP or Gravity.END).apply {
            topMargin = 12.dpToPx()
            marginEnd = 12.dpToPx()
        })
        ViewCompat.setOnApplyWindowInsetsListener(close) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (v.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = bars.top + 12.dpToPx()
                marginEnd = bars.right + 12.dpToPx()
            }
            v.requestLayout()
            insets
        }
        dialog.setContentView(root)
        dialog.show()
        val dm = context.resources.displayMetrics
        load(image, file, maxOf(dm.widthPixels, dm.heightPixels) * 2)
    }
}
