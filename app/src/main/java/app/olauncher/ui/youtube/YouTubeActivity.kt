package app.olauncher.ui.youtube

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.helper.dpToPx
import app.olauncher.helper.pillButton
import app.olauncher.helper.showToast
import app.olauncher.ui.UiKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A YouTube with two things in it: the search, and the grid of the channels followed. A channel
 * opens on its latest videos, a video opens in the player ([YtPlayerActivity]).
 */
class YouTubeActivity : AppCompatActivity() {

    private sealed class Screen {
        var items: List<Any>? = null

        class Home : Screen()
        class Search(val query: String) : Screen()
        class Channel(var channel: YtChannel) : Screen()
    }

    /** The top of a channel's page: its picture, its name and the button to follow it. */
    private class Header(val channel: YtChannel)

    private val stack = ArrayList<Screen>()
    private val screen get() = stack.last()
    private var job: Job? = null
    private var pictures: Job? = null

    private lateinit var root: LinearLayout
    private lateinit var back: ImageView
    private lateinit var more: ImageView
    private lateinit var input: EditText
    private lateinit var list: RecyclerView
    private lateinit var grid: GridLayoutManager
    private lateinit var progress: ProgressBar
    private lateinit var empty: FrameLayout
    private val adapter = Adapter()

    private val pickCsv = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val added = withContext(Dispatchers.IO) {
                runCatching { contentResolver.openInputStream(uri)?.use { YtStore.importCsv(this@YouTubeActivity, it) } ?: 0 }.getOrDefault(0)
            }
            showToast(if (added > 0) getString(R.string.ml_yt_imported, added) else getString(R.string.ml_yt_import_none))
            if (screen is Screen.Home) show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        buildLayout()
        setContentView(root)
        stack.add(Screen.Home())
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })
        if (!openFrom(intent)) show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFrom(intent)
    }

    override fun onStart() {
        super.onStart()
        // A channel followed or dropped from its page shows in the grid on the way back
        if (stack.isNotEmpty() && screen is Screen.Home && screen.items != null) show()
    }

    /** Opens the channel an intent names (the player's channel line). */
    private fun openFrom(intent: Intent): Boolean {
        val id = intent.getStringExtra(EXTRA_CHANNEL_ID) ?: return false
        intent.removeExtra(EXTRA_CHANNEL_ID)
        open(Screen.Channel(YtChannel(id, intent.getStringExtra(EXTRA_CHANNEL_NAME).orEmpty(), "")))
        return true
    }

    private fun open(next: Screen) {
        stack.add(next)
        show()
    }

    private fun goBack() {
        if (stack.size > 1) {
            stack.removeAt(stack.size - 1)
            show()
        } else finish()
    }

    // ---- showing a screen ----

    private fun show() {
        job?.cancel()
        pictures?.cancel()
        val current = screen
        back.isVisible = stack.size > 1
        more.isVisible = current is Screen.Home
        val query = (current as? Screen.Search)?.query.orEmpty()
        if (input.text.toString() != query) input.setText(query)
        input.clearFocus()

        if (current is Screen.Home) {
            val channels = YtStore.subscriptions(this)
            current.items = channels
            display(current)
            fillPictures(channels)
            return
        }
        current.items?.let {
            display(current)
            return
        }
        adapter.submit(if (current is Screen.Channel) listOf(Header(current.channel)) else emptyList(), false)
        empty.isVisible = false
        progress.isVisible = true
        job = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    when (current) {
                        is Screen.Search -> YouTubeApi.search(current.query)
                        is Screen.Channel -> {
                            val (info, videos) = YouTubeApi.channel(current.channel.id)
                            if (info != null) {
                                current.channel = info
                                YtStore.refresh(this@YouTubeActivity, info)
                            }
                            videos
                        }
                        else -> emptyList()
                    }
                }
            }
            progress.isVisible = false
            val items = result.getOrNull()
            if (items == null) {
                showEmpty(getString(R.string.ml_yt_error), getString(R.string.ml_yt_retry)) { show() }
                return@launch
            }
            // The same video can come twice in a search (once in a shelf, once in the list)
            val seen = HashSet<String>()
            current.items = items.filter { seen.add(if (it is YtVideo) "v" + it.id else "c" + (it as YtChannel).id) }
            if (screen === current) display(current)
        }
    }

    private fun display(current: Screen) {
        progress.isVisible = false
        val items = current.items.orEmpty()
        val shown = if (current is Screen.Channel) listOf(Header(current.channel)) + items else items
        adapter.submit(shown, current is Screen.Home)
        list.scrollToPosition(0)
        when {
            items.isNotEmpty() -> empty.isVisible = false
            current is Screen.Home -> showEmpty(getString(R.string.ml_yt_empty), getString(R.string.ml_yt_import)) { importHelp() }
            else -> showEmpty(getString(R.string.ml_yt_no_results), null, null)
        }
    }

    private fun showEmpty(text: String, action: String?, onAction: (() -> Unit)?) {
        empty.removeAllViews()
        empty.addView(UiKit.emptyState(this, R.drawable.ic_m_video, text, action, onAction), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        empty.isVisible = true
    }

    /** Channels imported from a file come without a picture: a few are fetched each time the grid shows. */
    private fun fillPictures(channels: List<YtChannel>) {
        val missing = channels.filter { it.avatar.isBlank() }.take(20)
        if (missing.isEmpty()) return
        pictures = lifecycleScope.launch {
            var found = false
            for (channel in missing) {
                val info = withContext(Dispatchers.IO) { runCatching { YouTubeApi.channel(channel.id).first }.getOrNull() } ?: continue
                YtStore.refresh(this@YouTubeActivity, info)
                found = true
            }
            val current = screen
            if (found && current is Screen.Home) {
                current.items = YtStore.subscriptions(this@YouTubeActivity)
                adapter.submit(current.items.orEmpty(), true)
            }
        }
    }

    private fun search() {
        val query = input.text.toString().trim()
        if (query.isEmpty()) return
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        // A search from a search replaces it, so that back goes to where the first one started
        if (screen is Screen.Search) stack.removeAt(stack.size - 1)
        open(Screen.Search(query))
    }

    private fun importHelp() {
        AlertDialog.Builder(this)
            .setTitle(R.string.ml_yt_import)
            .setMessage(R.string.ml_yt_import_help)
            .setPositiveButton(R.string.ml_yt_import_pick) { _, _ -> runCatching { pickCsv.launch(arrayOf("*/*")) } }
            .setNegativeButton(R.string.ml_cancel, null)
            .show()
    }

    private fun toggle(channel: YtChannel) {
        if (YtStore.isSubscribed(this, channel.id)) YtStore.unsubscribe(this, channel.id) else YtStore.subscribe(this, channel)
        adapter.notifyDataSetChanged()
    }

    private fun confirmRemove(channel: YtChannel) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.ml_yt_remove_q, channel.name))
            .setPositiveButton(R.string.ml_yt_remove) { _, _ ->
                YtStore.unsubscribe(this, channel.id)
                if (screen is Screen.Home) show()
            }
            .setNegativeButton(R.string.ml_cancel, null)
            .show()
    }

    // ---- layout ----

    private fun buildLayout() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dpToPx(), 8.dpToPx(), 8.dpToPx(), 8.dpToPx())
        }
        back = UiKit.iconButton(this, R.drawable.ic_m_arrow_back, getString(R.string.ml_cal_back)) { goBack() }
        input = EditText(this).apply {
            hint = getString(R.string.ml_yt_search_hint)
            textSize = 16f
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH) search()
                action == EditorInfo.IME_ACTION_SEARCH
            }
        }
        UiKit.styleInput(input)
        more = UiKit.iconButton(this, R.drawable.ic_m_download, getString(R.string.ml_yt_import)) { importHelp() }
        bar.addView(back)
        bar.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = 4.dpToPx()
            marginEnd = 4.dpToPx()
        })
        bar.addView(more)
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val body = FrameLayout(this)
        val columns = (resources.configuration.screenWidthDp / 116).coerceAtLeast(3)
        grid = GridLayoutManager(this, columns).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = if (adapter.getItemViewType(position) == TILE) 1 else spanCount
            }
        }
        list = RecyclerView(this).apply {
            layoutManager = grid
            adapter = this@YouTubeActivity.adapter
            clipToPadding = false
            setPadding(8.dpToPx(), 4.dpToPx(), 8.dpToPx(), 24.dpToPx())
        }
        progress = ProgressBar(this).apply { isVisible = false }
        empty = FrameLayout(this).apply { isVisible = false }
        body.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        body.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        body.addView(progress, FrameLayout.LayoutParams(48.dpToPx(), 48.dpToPx(), Gravity.CENTER))
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /** A round picture of a channel, on a disc of the card color until it is loaded. */
    private fun avatar(sizeDp: Int) = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(UiKit.card(this@YouTubeActivity))
            setStroke(1.dpToPx(), UiKit.stroke(this@YouTubeActivity))
        }
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
        }
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(sizeDp.dpToPx(), sizeDp.dpToPx())
    }

    private fun label(size: Float, color: Int, lines: Int, bold: Boolean = false) = TextView(this).apply {
        textSize = size
        setTextColor(color)
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private class Holder(view: View) : RecyclerView.ViewHolder(view) {
        lateinit var image: ImageView
        lateinit var title: TextView
        var sub: TextView? = null
        var badge: TextView? = null
        var button: FrameLayout? = null
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        private var items: List<Any> = emptyList()
        private var tiles = false

        fun submit(list: List<Any>, asTiles: Boolean) {
            items = list
            tiles = asTiles
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun getItemViewType(position: Int) = when (val item = items.getOrNull(position)) {
            is YtVideo -> VIDEO
            is Header -> HEADER
            is YtChannel -> if (tiles) TILE else CHANNEL
            else -> error("Unknown item $item")
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ctx = this@YouTubeActivity
            val primary = UiKit.primary(ctx)
            val secondary = UiKit.secondary(ctx)
            return when (viewType) {
                TILE -> {
                    val box = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(6.dpToPx(), 12.dpToPx(), 6.dpToPx(), 12.dpToPx())
                        background = UiKit.ripple(ctx)
                        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    }
                    Holder(box).apply {
                        image = avatar(76)
                        title = label(13f, primary, 2).apply {
                            gravity = Gravity.CENTER
                            setPadding(0, 8.dpToPx(), 0, 0)
                            minLines = 2
                        }
                        box.addView(image)
                        box.addView(title)
                    }
                }
                VIDEO -> {
                    val row = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(8.dpToPx(), 8.dpToPx(), 8.dpToPx(), 8.dpToPx())
                        background = UiKit.ripple(ctx)
                        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    }
                    Holder(row).apply {
                        val thumb = FrameLayout(ctx)
                        image = ImageView(ctx).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            background = GradientDrawable().apply {
                                cornerRadius = 10.dpToPx().toFloat()
                                setColor(UiKit.card(ctx))
                            }
                            outlineProvider = ViewOutlineProvider.BACKGROUND
                            clipToOutline = true
                        }
                        val time = label(11f, Color.WHITE, 1).apply {
                            background = GradientDrawable().apply {
                                cornerRadius = 5.dpToPx().toFloat()
                                setColor(0xCC000000.toInt())
                            }
                            setPadding(5.dpToPx(), 1.dpToPx(), 5.dpToPx(), 1.dpToPx())
                        }
                        thumb.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                        badge = time
                        thumb.addView(time, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
                            marginEnd = 5.dpToPx()
                            bottomMargin = 5.dpToPx()
                        })
                        val texts = LinearLayout(ctx).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(12.dpToPx(), 0, 0, 0)
                        }
                        title = label(14.5f, primary, 3)
                        val under = label(12.5f, secondary, 2).apply { setPadding(0, 4.dpToPx(), 0, 0) }
                        sub = under
                        texts.addView(title)
                        texts.addView(under)
                        row.addView(thumb, LinearLayout.LayoutParams(152.dpToPx(), 86.dpToPx()))
                        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    }
                }
                else -> {
                    // A channel in a search, or on top of its own page
                    val row = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(8.dpToPx(), 10.dpToPx(), 8.dpToPx(), 10.dpToPx())
                        if (viewType == CHANNEL) background = UiKit.ripple(ctx)
                        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    }
                    Holder(row).apply {
                        image = avatar(56)
                        val texts = LinearLayout(ctx).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(14.dpToPx(), 0, 10.dpToPx(), 0)
                        }
                        title = label(if (viewType == HEADER) 18f else 15f, primary, 2, bold = true)
                        val under = label(12.5f, secondary, 1)
                        sub = under
                        texts.addView(title)
                        texts.addView(under)
                        val slot = FrameLayout(ctx)
                        button = slot
                        row.addView(image)
                        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        row.addView(slot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 38.dpToPx()))
                    }
                }
            }
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val ctx = this@YouTubeActivity
            when (val item = items[position]) {
                is YtVideo -> {
                    holder.title.text = item.title
                    holder.sub?.text = listOf(item.channel, item.info).filter { it.isNotBlank() }.joinToString(" · ")
                    holder.badge?.text = item.duration
                    holder.badge?.isVisible = item.duration.isNotBlank()
                    YtImages.load(holder.image, item.thumbnail)
                    holder.itemView.setOnClickListener { startActivity(YtPlayerActivity.intent(ctx, item)) }
                }
                is YtChannel, is Header -> {
                    val channel = if (item is Header) item.channel else item as YtChannel
                    holder.title.text = channel.name
                    holder.sub?.text = channel.info
                    holder.sub?.isVisible = channel.info.isNotBlank()
                    YtImages.load(holder.image, channel.avatar)
                    holder.button?.let { slot ->
                        val following = YtStore.isSubscribed(ctx, channel.id)
                        slot.removeAllViews()
                        slot.addView(pillButton(ctx, getString(if (following) R.string.ml_yt_subscribed else R.string.ml_yt_subscribe), filled = !following, color = UiKit.primary(ctx)) {
                            toggle(channel)
                        }.apply { setPadding(16.dpToPx(), 0, 16.dpToPx(), 0) }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    }
                    if (item is Header) return
                    holder.itemView.setOnClickListener { open(Screen.Channel(channel)) }
                    holder.itemView.setOnLongClickListener {
                        if (tiles) confirmRemove(channel)
                        tiles
                    }
                }
            }
        }
    }

    companion object {
        private const val TILE = 0
        private const val CHANNEL = 1
        private const val VIDEO = 2
        private const val HEADER = 3
        private const val EXTRA_CHANNEL_ID = "channel_id"
        private const val EXTRA_CHANNEL_NAME = "channel_name"

        fun channelIntent(ctx: Context, channel: YtChannel): Intent = Intent(ctx, YouTubeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_CHANNEL_ID, channel.id)
            .putExtra(EXTRA_CHANNEL_NAME, channel.name)
    }
}
