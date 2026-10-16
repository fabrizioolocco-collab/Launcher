package app.olauncher.ui

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.helper.MediaThumbnails
import app.olauncher.helper.dpToPx
import app.olauncher.helper.showToast
import java.io.File

/**
 * Grid of the photos and videos of an album folder (newest first), opened from the home search.
 * Tapping one opens it in the gallery (Fossify when installed), which lets you swipe through the
 * rest of the folder. Fossify can't be asked to open the album grid itself.
 */
class AlbumActivity : AppCompatActivity() {

    private lateinit var dir: File
    private var media: List<File> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dir = File(intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return })
        media = dir.listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") && isMedia(mimeOf(it.name)) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        setContentView(buildLayout())
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top + 12.dpToPx(), 0, 0)
            insets
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20.dpToPx(), 0, 16.dpToPx(), 8.dpToPx())
        }
        header.addView(TextView(this, null, 0, R.style.TextLarge).apply {
            text = "←"
            textSize = 26f
            setPadding(0, 4.dpToPx(), 16.dpToPx(), 4.dpToPx())
            isClickable = true
            setOnClickListener { finish() }
        })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this, null, 0, R.style.TextLarge).apply {
            text = dir.name
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        texts.addView(TextView(this, null, 0, R.style.TextSmall).apply {
            text = getString(R.string.ml_album_count, media.size)
            alpha = 0.6f
        })
        header.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(this, null, 0, R.style.TextLarge).apply {
            text = "📂"
            textSize = 20f
            contentDescription = getString(R.string.ml_search_open_folder)
            setPadding(16.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
            isClickable = true
            setOnClickListener { FileExplorerActivity.openLocal(this@AlbumActivity, dir) }
        })
        root.addView(header)
        val grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@AlbumActivity, COLUMNS)
            adapter = MediaAdapter()
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(2.dpToPx(), 0, 2.dpToPx(), 24.dpToPx())
        }
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    // ---------------------------------------------------------------- media

    private fun mimeOf(name: String) =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())

    private fun isMedia(mime: String?) = mime != null && (mime.startsWith("image/") || mime.startsWith("video/"))

    /** Opens a photo or video in the gallery (Fossify first), which shows the rest of the folder too. */
    private fun open(file: File) {
        val mime = mimeOf(file.name) ?: "image/*"
        var uri: Uri? = null
        try {
            @Suppress("DEPRECATION")
            contentResolver.query(
                MediaStore.Files.getContentUri("external"), arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DATA} = ?", arrayOf(file.absolutePath), null,
            )?.use { c -> if (c.moveToFirst()) uri = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), c.getLong(0)) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        val target = uri ?: FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(target, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        for (gallery in GALLERIES) {
            try {
                startActivity(Intent(intent).setPackage(gallery))
                return
            } catch (e: Exception) {
                // not installed: try the next one
            }
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            showToast(getString(R.string.ml_search_cannot_open))
        }
    }

    private inner class MediaAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = media.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val side = parent.width.takeIf { it > 0 }?.let { (it - parent.paddingLeft - parent.paddingRight) / COLUMNS }
                ?: (resources.displayMetrics.widthPixels / COLUMNS)
            val cell = FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(side, side)
                setPadding(2.dpToPx(), 2.dpToPx(), 2.dpToPx(), 2.dpToPx())
                isClickable = true
            }
            cell.addView(ImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0x22888888)
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) =
                        outline.setRoundRect(0, 0, view.width, view.height, 10.dpToPx().toFloat())
                }
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            cell.addView(TextView(parent.context).apply {
                text = "▶"
                textSize = 22f
                setTextColor(0xFFFFFFFF.toInt())
                setShadowLayer(6f, 0f, 0f, 0xAA000000.toInt())
                isVisible = false
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            return object : RecyclerView.ViewHolder(cell) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val file = media[position]
            val cell = holder.itemView as FrameLayout
            val image = cell.getChildAt(0) as ImageView
            val play = cell.getChildAt(1) as TextView
            play.isVisible = mimeOf(file.name)?.startsWith("video/") == true
            cell.setOnClickListener { open(file) }
            MediaThumbnails.load(lifecycleScope, image, file, cell.layoutParams.width.coerceAtLeast(64))
        }
    }

    companion object {
        private const val EXTRA_PATH = "path"
        private const val COLUMNS = 3
        private val GALLERIES = listOf("org.fossify.gallery", "com.simplemobiletools.gallery.pro")

        fun open(context: Context, dir: File) = context.startActivity(
            Intent(context, AlbumActivity::class.java).putExtra(EXTRA_PATH, dir.absolutePath)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
