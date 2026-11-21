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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
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

        /** The ticket for the page after the ones loaded: older videos, more results. Null at the end. */
        var next: String? = null
        var emptyPages = 0

        class Home : Screen()
        class Search(val query: String) : Screen()
        class Channel(var channel: YtChannel) : Screen()
        class Favorites : Screen()
        class Later : Screen()
    }

    /** The top of a channel's page: its picture, its name and the button to follow it. */
    private class Header(val channel: YtChannel)

    private val stack = ArrayList<Screen>()
    private val screen get() = stack.last()
    private var job: Job? = null
    private var pictures: Job? = null
    private var moreJob: Job? = null

    private lateinit var root: LinearLayout
    private lateinit var back: ImageView
    private lateinit var input: EditText
    private lateinit var star: ImageView
    private lateinit var later: ImageView
    private lateinit var eye: ImageView
    private lateinit var list: RecyclerView
    private lateinit var grid: GridLayoutManager
    private lateinit var progress: ProgressBar
    private lateinit var empty: FrameLayout
    private val adapter = Adapter()

    // What is playing, at the bottom: the way back to a video left in the background
    private lateinit var nowBar: LinearLayout
    private lateinit var nowThumb: ImageView
    private lateinit var nowTitle: TextView
    private lateinit var nowAuthor: TextView
    private lateinit var nowPlay: ImageView
    private val nowTicker = object : Runnable {
        override fun run() {
            updateNow()
            root.postDelayed(this, 1000)
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

    override fun onStop() {
        super.onStop()
        root.removeCallbacks(nowTicker)
    }

    private fun updateNow() {
        val now = YtPlayerService.now
        val exo = YtPlayerService.player
        val loaded = now != null && exo != null && exo.playbackState != Player.STATE_IDLE
        nowBar.isVisible = loaded
        if (now == null || exo == null || !loaded) return
        nowTitle.text = now.title
        nowAuthor.text = now.author
        nowAuthor.isVisible = now.author.isNotBlank()
        val thumb = "https://i.ytimg.com/vi/${now.videoId}/mqdefault.jpg"
        if (nowThumb.tag != thumb) YtImages.load(nowThumb, thumb)
        val playing = exo.playWhenReady && exo.playbackState != Player.STATE_ENDED
        nowPlay.setImageResource(if (playing) R.drawable.ic_m_pause else R.drawable.ic_m_play)
    }

    private fun toggleNow() {
        val exo = YtPlayerService.player ?: return
        if (exo.playWhenReady && exo.playbackState != Player.STATE_ENDED) exo.pause()
        else {
            if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0)
            exo.play()
        }
        updateNow()
    }

    /** Stops what is playing: the bar and the notification go. */
    private fun stopNow() {
        YtPlayerService.savePosition()
        YtPlayerService.player?.let {
            it.stop()
            it.clearMediaItems()
        }
        YtPlayerService.now = null
        updateNow()
    }

    override fun onStart() {
        super.onStart()
        root.removeCallbacks(nowTicker)
        nowTicker.run()
        // A channel followed or dropped from its page shows in the grid on the way back
        if (stack.isNotEmpty() && screen is Screen.Home && screen.items != null) show()
        // A video just watched loses its red dot and gets its mark (or goes, when the watched ones are hidden)
        else if (stack.isNotEmpty()) {
            val current = screen
            saved(current)?.let { current.items = it }
            if (current.items != null) display(current, keepPlace = true)
        }
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
        moreJob?.cancel()
        pictures?.cancel()
        val current = screen
        back.isVisible = stack.size > 1
        val query = (current as? Screen.Search)?.query.orEmpty()
        if (input.text.toString() != query) input.setText(query)
        input.clearFocus()
        star.isVisible = current is Screen.Home
        eye.isVisible = current is Screen.Search || current is Screen.Channel
        showEye()

        later.isVisible = current is Screen.Home
        saved(current)?.let {
            current.items = it
            display(current)
            return
        }
        if (current is Screen.Home) {
            val channels = YtStore.subscriptions(this)
            current.items = channels
            display(current)
            refresh(channels)
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
                        is Screen.Search -> YouTubeApi.search(current.query).also { current.next = it.next }.items
                        is Screen.Channel -> {
                            val page = YouTubeApi.channel(current.channel.id)
                            current.next = page.next
                            val (info, videos) = page
                            if (info != null) {
                                current.channel = info
                                YtStore.refresh(this@YouTubeActivity, info)
                            }
                            YtStore.update(this@YouTubeActivity, current.channel.id, videos.map { it.id })
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

    private fun display(current: Screen, keepPlace: Boolean = false) {
        progress.isVisible = false
        // The watched ones can be hidden where videos are looked for, never among the favourites
        val hide = saved(current) == null && YtStore.hideWatched(this)
        val items = current.items.orEmpty().filter { !(hide && it is YtVideo && YtStore.isWatched(this, it.id)) }
        val shown = if (current is Screen.Channel) listOf(Header(current.channel)) + items else items
        adapter.submit(shown, current is Screen.Home)
        if (!keepPlace) list.scrollToPosition(0)
        when {
            items.isNotEmpty() -> empty.isVisible = false
            current is Screen.Favorites -> showEmpty(getString(R.string.ml_yt_fav_empty), null, null)
            current is Screen.Later -> showEmpty(getString(R.string.ml_yt_later_empty), null, null)
            current is Screen.Home -> showEmpty(getString(R.string.ml_yt_empty), null, null)
            current.next != null -> empty.isVisible = false
            else -> showEmpty(getString(R.string.ml_yt_no_results), null, null)
        }
        // A list that doesn't fill the screen (the watched ones hidden) goes on by itself
        list.post { checkMore() }
    }

    /** Near the end of the list: the page after it is fetched, until YouTube has no more. */
    private fun checkMore() {
        if (stack.isEmpty() || screen.next == null || screen.items == null) return
        if (grid.findLastVisibleItemPosition() >= adapter.itemCount - 8) loadMore()
    }

    private fun loadMore() {
        val current = screen
        val next = current.next ?: return
        if (moreJob?.isActive == true) return
        moreJob = lifecycleScope.launch {
            val channel = (current as? Screen.Channel)?.channel
            val page = withContext(Dispatchers.IO) {
                runCatching { YouTubeApi.more(next, channel?.name.orEmpty(), channel?.id.orEmpty()) }.getOrNull()
            }
            if (page == null) {
                current.next = null
                return@launch
            }
            val old = current.items.orEmpty()
            val known = old.map { key(it) }.toHashSet()
            val fresh = page.items.filter { known.add(key(it)) }
            // A few pages in a row with nothing new: the end, whatever the ticket says
            current.emptyPages = if (fresh.isEmpty()) current.emptyPages + 1 else 0
            current.next = page.next.takeIf { current.emptyPages < 3 }
            current.items = old + fresh
            moreJob = null
            if (screen === current) display(current, keepPlace = true)
        }
    }

    private fun key(item: Any) = if (item is YtVideo) "v" + item.id else "c" + (item as YtChannel).id

    /** The videos of a list kept on the phone (favourites, to watch), as it is now; null on the other screens. */
    private fun saved(current: Screen): List<YtVideo>? = when (current) {
        is Screen.Favorites -> YtStore.favorites(this)
        is Screen.Later -> YtStore.later(this)
        else -> null
    }

    private fun showEye() {
        val hide = YtStore.hideWatched(this)
        eye.setImageResource(if (hide) R.drawable.ic_m_hide else R.drawable.ic_m_show)
        eye.contentDescription = getString(if (hide) R.string.ml_yt_show_watched else R.string.ml_yt_hide_watched)
    }

    private fun toggleWatchedHidden() {
        val hide = !YtStore.hideWatched(this)
        YtStore.setHideWatched(this, hide)
        showEye()
        showToast(getString(if (hide) R.string.ml_yt_watched_hidden else R.string.ml_yt_watched_shown))
        val current = screen
        if (current.items != null) display(current, keepPlace = true)
    }

    /** A long press on a video: keep it among the favourites, mark it watched or not. */
    private fun videoMenu(video: YtVideo) {
        val favorite = YtStore.isFavorite(this, video.id)
        val toWatch = YtStore.isLater(this, video.id)
        val watched = YtStore.isWatched(this, video.id)
        AlertDialog.Builder(this)
            .setTitle(video.title)
            .setItems(
                arrayOf(
                    getString(if (favorite) R.string.ml_yt_fav_remove else R.string.ml_yt_fav_add),
                    getString(if (toWatch) R.string.ml_yt_later_remove else R.string.ml_yt_later_add),
                    getString(if (watched) R.string.ml_yt_mark_unwatched else R.string.ml_yt_mark_watched)
                )
            ) { _, which ->
                when (which) {
                    0 -> YtStore.setFavorite(this, video, !favorite)
                    1 -> YtStore.setLater(this, video, !toWatch)
                    else -> {
                        YtStore.setWatched(this, video.id, !watched)
                        // Marked as watched by hand: no longer one to watch
                        if (!watched) YtStore.dropLater(this, video.id)
                    }
                }
                val current = screen
                saved(current)?.let { current.items = it }
                if (current.items != null) display(current, keepPlace = true)
            }
            .show()
    }

    private fun showEmpty(text: String, action: String?, onAction: (() -> Unit)?) {
        empty.removeAllViews()
        empty.addView(UiKit.emptyState(this, R.drawable.ic_m_video, text, action, onAction), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        empty.isVisible = true
    }

    /**
     * Reads the latest videos of the channels in the grid, one after the other, for the red count
     * of the new ones (and for the picture of a channel that came without). Each channel is read
     * again only after some minutes.
     */
    private fun refresh(channels: List<YtChannel>) {
        val ctx = this
        val stale = channels.filter { it.avatar.isBlank() || YtStore.due(ctx, it.id) }
        if (stale.isEmpty()) return
        pictures = lifecycleScope.launch {
            for (channel in stale) {
                val read = withContext(Dispatchers.IO) { runCatching { YouTubeApi.channel(channel.id) }.getOrNull() } ?: continue
                read.info?.let { YtStore.refresh(ctx, it) }
                YtStore.update(ctx, channel.id, read.videos.map { it.id })
                val current = screen
                if (current is Screen.Home) {
                    current.items = YtStore.subscriptions(ctx)
                    adapter.submit(current.items.orEmpty(), true)
                }
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

    private fun toggle(channel: YtChannel) {
        if (YtStore.isSubscribed(this, channel.id)) YtStore.unsubscribe(this, channel.id) else YtStore.subscribe(this, channel)
        adapter.notifyDataSetChanged()
    }

    /** A long press on a channel of the grid: forget its new videos, or stop following it. */
    private fun channelMenu(channel: YtChannel) {
        val fresh = YtStore.newVideos(this, channel.id)
        if (fresh.isEmpty()) {
            confirmRemove(channel)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(channel.name)
            .setItems(arrayOf(getString(R.string.ml_yt_mark_seen), getString(R.string.ml_yt_remove))) { _, which ->
                if (which == 0) {
                    YtStore.markSeen(this, channel.id, fresh)
                    adapter.notifyDataSetChanged()
                } else confirmRemove(channel)
            }
            .show()
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
        bar.addView(back)
        bar.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = 4.dpToPx()
            marginEnd = 4.dpToPx()
        })
        // On the grid: the favourites. Where videos are listed: show or hide the watched ones
        later = UiKit.iconButton(this, R.drawable.ic_m_timer, getString(R.string.ml_yt_later)) { open(Screen.Later()) }
        bar.addView(later)
        star = UiKit.iconButton(this, R.drawable.ic_m_star, getString(R.string.ml_yt_favorites)) { open(Screen.Favorites()) }
        eye = UiKit.iconButton(this, R.drawable.ic_m_show, getString(R.string.ml_yt_hide_watched)) { toggleWatchedHidden() }
        bar.addView(star)
        bar.addView(eye)
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val body = FrameLayout(this)
        val columns = (resources.configuration.screenWidthDp / 116).coerceAtLeast(3)
        grid = GridLayoutManager(this, columns).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = if (adapter.getItemViewType(position) == TILE) 1 else spanCount
            }
        }
        list = RecyclerView(this).apply {
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy > 0) checkMore()
                }
            })
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

        nowBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UiKit.cardBackground(this@YouTubeActivity, 16)
            setPadding(8.dpToPx(), 8.dpToPx(), 4.dpToPx(), 8.dpToPx())
            isVisible = false
            isClickable = true
            isFocusable = true
            setOnClickListener { startActivity(YtPlayerActivity.resumeIntent(this@YouTubeActivity)) }
        }
        nowThumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                cornerRadius = 8.dpToPx().toFloat()
                setColor(Color.BLACK)
            }
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }
        val nowTexts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dpToPx(), 0, 6.dpToPx(), 0)
        }
        nowTitle = label(14f, UiKit.primary(this), 1, bold = true)
        nowAuthor = label(12f, UiKit.secondary(this), 1)
        nowTexts.addView(nowTitle)
        nowTexts.addView(nowAuthor)
        nowPlay = UiKit.iconButton(this, R.drawable.ic_m_pause, getString(R.string.ml_player_play_pause)) { toggleNow() }
        nowBar.addView(nowThumb, LinearLayout.LayoutParams(80.dpToPx(), 45.dpToPx()))
        nowBar.addView(nowTexts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        nowBar.addView(nowPlay)
        nowBar.addView(UiKit.iconButton(this, R.drawable.ic_m_close, getString(R.string.ml_yt_stop)) { stopNow() })
        root.addView(nowBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(8.dpToPx(), 0, 8.dpToPx(), 8.dpToPx())
        })
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
        var dot: View? = null
        var seen: TextView? = null
        var bar: View? = null
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
                        // The red count of the new videos sits on the corner of the picture
                        val count = label(11f, Color.WHITE, 1, bold = true).apply {
                            gravity = Gravity.CENTER
                            minWidth = 20.dpToPx()
                            setPadding(5.dpToPx(), 0, 5.dpToPx(), 0)
                            background = GradientDrawable().apply {
                                cornerRadius = 10.dpToPx().toFloat()
                                setColor(RED)
                            }
                            isVisible = false
                        }
                        badge = count
                        val picture = FrameLayout(ctx)
                        picture.addView(image, FrameLayout.LayoutParams(76.dpToPx(), 76.dpToPx()))
                        picture.addView(count, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 20.dpToPx(), Gravity.TOP or Gravity.END))
                        box.addView(picture, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
                        val mark = View(ctx).apply {
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(RED)
                                setStroke(1.dpToPx(), Color.BLACK)
                            }
                            isVisible = false
                        }
                        dot = mark
                        thumb.addView(mark, FrameLayout.LayoutParams(12.dpToPx(), 12.dpToPx(), Gravity.TOP or Gravity.START).apply {
                            marginStart = 5.dpToPx()
                            topMargin = 5.dpToPx()
                        })
                        // How far a video left half way was played, along the bottom of its picture
                        val played = View(ctx).apply {
                            setBackgroundColor(RED)
                            isVisible = false
                        }
                        bar = played
                        thumb.addView(played, FrameLayout.LayoutParams(0, 3.dpToPx(), Gravity.BOTTOM or Gravity.START))
                        // "Watched", on the corner of a video already played
                        val seenMark = label(11f, Color.WHITE, 1).apply {
                            text = "\u2713 " + getString(R.string.ml_yt_watched)
                            background = GradientDrawable().apply {
                                cornerRadius = 5.dpToPx().toFloat()
                                setColor(0xCC000000.toInt())
                            }
                            setPadding(5.dpToPx(), 1.dpToPx(), 5.dpToPx(), 1.dpToPx())
                            isVisible = false
                        }
                        seen = seenMark
                        thumb.addView(seenMark, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
                            marginStart = 5.dpToPx()
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
                    val watched = YtStore.isWatched(ctx, item.id)
                    val favorite = YtStore.isFavorite(ctx, item.id)
                    holder.sub?.text = (if (favorite) "\u2605 " else "") + listOf(item.channel, item.info).filter { it.isNotBlank() }.joinToString(" · ")
                    holder.title.setTextColor(if (watched) UiKit.secondary(ctx) else UiKit.primary(ctx))
                    holder.image.alpha = if (watched) 0.45f else 1f
                    holder.seen?.isVisible = watched
                    holder.bar?.let { played ->
                        val part = YtStore.progress(ctx, item.id)
                        played.isVisible = part > 0f
                        played.layoutParams = played.layoutParams.apply { width = (152.dpToPx() * part).toInt() }
                    }
                    holder.itemView.setOnLongClickListener {
                        videoMenu(item)
                        true
                    }
                    holder.badge?.text = item.duration
                    holder.badge?.isVisible = item.duration.isNotBlank()
                    YtImages.load(holder.image, item.thumbnail)
                    holder.dot?.isVisible = item.channelId.isNotBlank() && item.id in YtStore.newVideos(ctx, item.channelId)
                    holder.itemView.setOnClickListener {
                        YtStore.markSeen(ctx, item.channelId, listOf(item.id))
                        startActivity(YtPlayerActivity.intent(ctx, item))
                    }
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
                    holder.badge?.let { count ->
                        val fresh = YtStore.newVideos(ctx, channel.id).size
                        count.text = if (fresh > 99) "99+" else fresh.toString()
                        count.isVisible = fresh > 0
                    }
                    holder.itemView.setOnLongClickListener {
                        if (tiles) channelMenu(channel)
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
        private const val RED = 0xFFE53935.toInt()
        private const val EXTRA_CHANNEL_ID = "channel_id"
        private const val EXTRA_CHANNEL_NAME = "channel_name"

        fun channelIntent(ctx: Context, channel: YtChannel): Intent = Intent(ctx, YouTubeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_CHANNEL_ID, channel.id)
            .putExtra(EXTRA_CHANNEL_NAME, channel.name)
    }
}
