package app.olauncher.ui.media

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.helper.MediaThumbnails
import app.olauncher.helper.MenuEntry
import app.olauncher.helper.dpToPx
import app.olauncher.helper.showConfirmDialog
import app.olauncher.helper.showMenuDialog
import app.olauncher.helper.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Built-in photo and video viewer: swipe between the media of a folder (newest first), pinch and
 * double-tap zoom on photos ([ZoomImageView]), video playback with controls. A tap shows or hides
 * the top bar (share, edit, delete, details). Alternative to the gallery app, see Prefs.builtInViewer.
 */
class MediaViewerActivity : AppCompatActivity() {

    private var media: List<File> = emptyList()
    private lateinit var pager: RecyclerView
    private lateinit var topBar: LinearLayout
    private lateinit var title: TextView
    private lateinit var counter: TextView
    private var chromeVisible = true
    private var current = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val start = File(intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return })
        val dir = start.parentFile ?: run { finish(); return }
        media = dir.listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") && MediaThumbnails.isMedia(MediaThumbnails.mimeOf(it.name)) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
            .ifEmpty { listOf(start) }
        current = media.indexOfFirst { it.absolutePath == start.absolutePath }.coerceAtLeast(0)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        setContentView(buildLayout())
        pager.scrollToPosition(current)
        updateChrome()
    }

    private fun buildLayout(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        pager = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MediaViewerActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = PagesAdapter()
            overScrollMode = View.OVER_SCROLL_NEVER
            setHasFixedSize(true)
            // Keep neighbours ready so swipes don't wait for decoding
            setItemViewCacheSize(2)
        }
        PagerSnapHelper().attachToRecyclerView(pager)
        pager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                val lm = rv.layoutManager as LinearLayoutManager
                val pos = lm.findFirstCompletelyVisibleItemPosition().takeIf { it >= 0 } ?: return
                if (pos != current) {
                    // Pause the video of the page we left
                    (lm.findViewByPosition(current) as? FrameLayout)?.let { stopVideo(it) }
                    current = pos
                    updateTitle()
                }
            }
        })
        root.addView(pager, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x99000000.toInt())
            setPadding(12.dpToPx(), 8.dpToPx(), 8.dpToPx(), 8.dpToPx())
        }
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(12.dpToPx(), bars.top + 8.dpToPx(), 8.dpToPx(), 8.dpToPx())
            insets
        }
        topBar.addView(button("←", 26f) { finish() })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        counter = TextView(this).apply {
            setTextColor(0xB3FFFFFF.toInt())
            textSize = 12f
        }
        texts.addView(title)
        texts.addView(counter)
        topBar.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 8.dpToPx() })
        topBar.addView(button("⤴", 22f) { share() })
        topBar.addView(button("✎", 22f) { edit() })
        topBar.addView(button("🗑", 20f) { delete() })
        topBar.addView(button("ⓘ", 22f) { details() })
        root.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        updateTitle()
        return root
    }

    private fun button(glyph: String, size: Float, onClick: () -> Unit) = TextView(this).apply {
        text = glyph
        textSize = size
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(10.dpToPx(), 4.dpToPx(), 10.dpToPx(), 4.dpToPx())
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun updateTitle() {
        val file = media.getOrNull(current) ?: return
        title.text = file.name
        counter.text = "${current + 1} / ${media.size}"
    }

    private fun toggleChrome() {
        chromeVisible = !chromeVisible
        updateChrome()
    }

    private fun updateChrome() {
        topBar.animate().alpha(if (chromeVisible) 1f else 0f).setDuration(150).withEndAction {
            topBar.isVisible = chromeVisible
        }.start()
        if (chromeVisible) topBar.isVisible = true
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (chromeVisible) controller.show(WindowInsetsCompat.Type.systemBars()) else controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    // ---------------------------------------------------------------- actions

    private fun currentFile() = media.getOrNull(current)

    private fun uriFor(file: File): Uri = MediaThumbnails.mediaUris(this, file.parentFile ?: file)[file.absolutePath]
        ?: FileProvider.getUriForFile(this, "$packageName.files", file)

    private fun share() {
        val file = currentFile() ?: return
        val intent = Intent(Intent.ACTION_SEND).setType(MediaThumbnails.mimeOf(file.name) ?: "*/*")
            .putExtra(Intent.EXTRA_STREAM, uriFor(file)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, file.name))
    }

    private fun edit() {
        val file = currentFile() ?: return
        if (MediaThumbnails.isVideo(file)) VideoEditActivity.open(this, file) else PhotoEditActivity.open(this, file)
    }

    private fun delete() {
        val file = currentFile() ?: return
        showConfirmDialog(getString(R.string.ml_explorer_delete), getString(R.string.ml_explorer_delete_confirm, file.name), getString(R.string.ml_explorer_delete)) {
            if (!file.delete()) {
                showToast(getString(R.string.ml_explorer_failed, getString(R.string.ml_explorer_delete)))
                return@showConfirmDialog
            }
            MediaScannerConnection.scanFile(applicationContext, arrayOf(file.absolutePath), null, null)
            media = media.filterNot { it.absolutePath == file.absolutePath }
            if (media.isEmpty()) {
                finish()
                return@showConfirmDialog
            }
            current = current.coerceAtMost(media.lastIndex)
            pager.adapter?.notifyDataSetChanged()
            pager.scrollToPosition(current)
            updateTitle()
        }
    }

    private fun details() {
        val file = currentFile() ?: return
        lifecycleScope.launch {
            val lines = withContext(Dispatchers.IO) { detailsOf(file) }
            showMenuDialog(file.name, lines.map { MenuEntry(it) {} })
        }
    }

    private fun detailsOf(file: File): List<String> {
        val lines = mutableListOf(
            getString(R.string.ml_viewer_size, Formatter.formatShortFileSize(this, file.length())),
            getString(R.string.ml_viewer_date, DateFormat.getMediumDateFormat(this).format(file.lastModified()) + " " + DateFormat.getTimeFormat(this).format(file.lastModified())),
            getString(R.string.ml_viewer_path, file.absolutePath),
        )
        try {
            if (MediaThumbnails.isVideo(file)) {
                val r = android.media.MediaMetadataRetriever()
                r.setDataSource(file.absolutePath)
                val w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                val ms = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                r.release()
                lines.add(0, getString(R.string.ml_viewer_dimensions, "$w × $h"))
                lines.add(1, getString(R.string.ml_viewer_duration, formatDuration(ms)))
            } else {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, o)
                lines.add(0, getString(R.string.ml_viewer_dimensions, "${o.outWidth} × ${o.outHeight}"))
                val exif = ExifInterface(file.absolutePath)
                exif.getAttribute(ExifInterface.TAG_MODEL)?.let { lines.add(1, getString(R.string.ml_viewer_camera, it)) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return lines
    }

    // ---------------------------------------------------------------- pages

    private fun stopVideo(page: FrameLayout) {
        (page.getChildAt(0) as? VideoView)?.let {
            it.stopPlayback()
            page.getChildAt(1)?.isVisible = true
        }
    }

    override fun onPause() {
        super.onPause()
        (pager.layoutManager?.findViewByPosition(current) as? FrameLayout)?.let { stopVideo(it) }
    }

    private var firstResume = true

    override fun onResume() {
        super.onResume()
        if (firstResume) {
            firstResume = false
            return
        }
        // Back from an editor: list the folder again (a saved copy appears next to the original)
        val shown = media.getOrNull(current) ?: return
        val dir = shown.parentFile ?: return
        val fresh = dir.listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") && MediaThumbnails.isMedia(MediaThumbnails.mimeOf(it.name)) }
            ?.sortedByDescending { it.lastModified() }.orEmpty()
        if (fresh.map { it.absolutePath } == media.map { it.absolutePath }) return
        media = fresh.ifEmpty { listOf(shown) }
        current = media.indexOfFirst { it.absolutePath == shown.absolutePath }.coerceAtLeast(0)
        pager.adapter?.notifyDataSetChanged()
        pager.scrollToPosition(current)
        updateTitle()
    }

    private inner class PagesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = media.size
        override fun getItemViewType(position: Int) = if (MediaThumbnails.isVideo(media[position])) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val page = FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            if (viewType == 1) {
                page.addView(VideoView(parent.context), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
                // Poster with a play button until playback starts
                val poster = FrameLayout(parent.context)
                poster.addView(ZoomImageView(parent.context).apply { onSingleTap = { toggleChrome() } },
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                poster.addView(TextView(parent.context).apply {
                    text = "▶"
                    textSize = 40f
                    setTextColor(Color.WHITE)
                    setShadowLayer(12f, 0f, 0f, Color.BLACK)
                    isClickable = true
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                page.addView(poster, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            } else {
                page.addView(ZoomImageView(parent.context).apply { onSingleTap = { toggleChrome() } },
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            return object : RecyclerView.ViewHolder(page) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val file = media[position]
            val page = holder.itemView as FrameLayout
            if (getItemViewType(position) == 1) {
                val video = page.getChildAt(0) as VideoView
                val poster = page.getChildAt(1) as FrameLayout
                val image = poster.getChildAt(0) as ZoomImageView
                val play = poster.getChildAt(1) as TextView
                poster.isVisible = true
                image.setImageBitmap(null)
                MediaThumbnails.load(lifecycleScope, image, file, 1024)
                val controller = MediaController(this@MediaViewerActivity).apply { setAnchorView(video) }
                video.setMediaController(controller)
                video.setOnCompletionListener { poster.isVisible = true }
                video.setOnErrorListener { _, _, _ ->
                    showToast(getString(R.string.ml_search_cannot_open))
                    poster.isVisible = true
                    true
                }
                play.setOnClickListener {
                    poster.isVisible = false
                    video.setVideoPath(file.absolutePath)
                    video.start()
                }
            } else {
                val image = page.getChildAt(0) as ZoomImageView
                image.setImageBitmap(null)
                image.tag = file.absolutePath
                lifecycleScope.launch {
                    val bitmap = withContext(Dispatchers.IO) { decodeForScreen(file) } ?: return@launch
                    if (image.tag == file.absolutePath) image.setImageBitmap(bitmap)
                }
            }
        }
    }

    /** Decodes the photo at up to about twice the screen size (enough for zooming), honouring EXIF rotation. */
    private fun decodeForScreen(file: File): Bitmap? = try {
        val dm = resources.displayMetrics
        val maxSide = maxOf(dm.widthPixels, dm.heightPixels) * 2
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSide || bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val orientation = ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) bitmap
        else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, android.graphics.Matrix().apply { postRotate(degrees) }, true)
    } catch (e: Throwable) {
        null
    }

    companion object {
        private const val EXTRA_PATH = "path"

        fun formatDuration(ms: Long): String {
            val s = ms / 1000
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }

        fun open(context: Context, file: File) = context.startActivity(
            Intent(context, MediaViewerActivity::class.java).putExtra(EXTRA_PATH, file.absolutePath)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
