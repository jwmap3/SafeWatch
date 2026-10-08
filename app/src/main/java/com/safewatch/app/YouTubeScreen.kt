package com.safewatch.app

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.safewatch.app.browser.WatchActivity
import com.safewatch.app.browser.YouTubeMirror
import com.safewatch.app.data.Prefs
import com.safewatch.app.data.Services
import com.safewatch.app.data.Video
import com.safewatch.app.data.YouTubeData
import com.safewatch.app.ui.Ui
import java.util.concurrent.Executors

/**
 * The YouTube tab: YouTube's videos in EdenOS's own layout.
 *
 * Signed in to YouTube (once, on Google's own page), it shows the viewer's own
 * YouTube: their subscriptions, their home feed and their searches, read from
 * YouTube's website in a browser nobody sees (YouTubeMirror). Not signed in, it
 * shows the latest from channels followed here and shelves for a few chosen
 * subjects. Videos open on a page of their own and play in the app's player.
 */
class YouTubeScreen(private val activity: MainActivity) {

    val view: View
    private val field: EditText
    private val body: LinearLayout
    private val pool = Executors.newFixedThreadPool(3)
    private var loadNumber = 0
    private var shownFor: String? = null
    private var mirror: YouTubeMirror? = null

    private fun mirror(): YouTubeMirror = mirror ?: YouTubeMirror(activity).also { mirror = it }

    /** Called when the app closes, to put the hidden browser away. */
    fun close() {
        mirror?.close()
        mirror = null
        pool.shutdown()
    }

    init {
        val (page, column) = Ui.page(activity, padded = false)
        view = page
        val side = Ui.dp(activity, 20)
        column.addView(Ui.largeTitle(activity, "YouTube").apply { setPadding(side, Ui.dp(context, 18), side, Ui.dp(context, 4)) })
        field = EditText(activity).apply {
            hint = "Search YouTube"
            textSize = 16f
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(Ui.color(context, R.color.text))
            setHintTextColor(Ui.color(context, R.color.text_secondary))
            background = Ui.rounded(Ui.color(context, R.color.fill), Ui.dp(context, 12).toFloat())
            setPadding(Ui.dp(context, 14), Ui.dp(context, 12), Ui.dp(context, 14), Ui.dp(context, 12))
            setOnEditorActionListener { _, _, _ ->
                search(text.toString())
                true
            }
        }
        column.addView(FrameLayout(activity).apply {
            setPadding(side, Ui.dp(context, 8), side, 0)
            addView(field)
        })
        body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        column.addView(body)
    }

    /** Called when the tab comes into view. Reloads the shelves if what they depend on has changed. */
    fun onShown() {
        val state = Prefs.followedChannels(activity).keys.sorted().joinToString(",") + "|" + Prefs.youtubeTopics(activity).joinToString(",") +
            "|" + YouTubeMirror.signedIn(activity)
        if (field.text.isEmpty() && state != shownFor) {
            shownFor = state
            showShelves()
        }
    }

    fun onHidden() {
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(field.windowToken, 0)
    }

    /** Runs a search from elsewhere in the app, such as the main Search tab. */
    fun search(query: String) {
        val q = query.trim()
        field.setText(q)
        field.clearFocus()
        onHidden()
        if (q.isEmpty()) {
            shownFor = null
            onShown()
            return
        }
        val number = ++loadNumber
        body.removeAllViews()
        body.addView(note("Searching…"))
        if (YouTubeMirror.signedIn(activity)) {
            // Searched as the viewer, on YouTube itself, so the results are the ones YouTube would show them.
            collect(YouTubeMirror.search(q), number, until = { it.size >= 8 }) { found ->
                body.removeAllViews()
                if (found.isEmpty()) {
                    body.addView(note("No videos found."))
                    return@collect
                }
                body.addView(Ui.pill(activity, "Clear search", filled = false) { search("") }.apply {
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(Ui.dp(context, 20), Ui.dp(context, 14), 0, 0) }
                })
                found.take(30).forEach { body.addView(Ui.videoRow(activity, it) { VideoActivity.open(activity, it) }) }
            }
            return
        }
        pool.execute {
            val found = try { YouTubeData.search(q) } catch (e: Exception) { null }
            activity.runOnUiThread {
                if (activity.isDestroyed || number != loadNumber) return@runOnUiThread
                body.removeAllViews()
                when {
                    found == null -> body.addView(note("YouTube could not be reached."))
                    found.isEmpty() -> body.addView(note("No videos found."))
                    else -> {
                        body.addView(Ui.pill(activity, "Clear search", filled = false) { search("") }.apply {
                            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(Ui.dp(context, 20), Ui.dp(context, 14), 0, 0) }
                        })
                        found.take(25).forEach { body.addView(Ui.videoRow(activity, it) { VideoActivity.open(activity, it) }) }
                    }
                }
            }
        }
    }

    /**
     * Opens a YouTube address in the hidden browser and collects the videos it is given, until [until]
     * is satisfied or a little time has passed. [show] gets them, once, on the main thread.
     */
    private fun collect(url: String, number: Int, until: (List<Video>) -> Boolean, show: (List<Video>) -> Unit) {
        val answers = ArrayList<YouTubeMirror.Answer>()
        var done = false
        fun finish() {
            if (done || activity.isDestroyed || number != loadNumber) return
            done = true
            show(YouTubeData.videosIn(answers.map { it.json }))
        }
        val opened = mirror().open(url) { answer ->
            answers += answer
            if (until(YouTubeData.videosIn(answers.map { it.json }))) finish()
        }
        if (!opened) { finish(); return }
        body.postDelayed({ finish() }, 12000)
    }

    private fun showShelves() {
        val number = ++loadNumber
        body.removeAllViews()
        if (YouTubeMirror.signedIn(activity)) {
            showOwnYouTube(number)
            return
        }
        body.addView(Ui.card(activity).apply {
            addView(Ui.row(activity, "Sign in to YouTube", "Your own feed") {
                Services.byId("youtube")?.let { WatchActivity.signIn(activity, it) }
            })
        }.let { card -> FrameLayout(activity).apply { setPadding(Ui.dp(context, 20), Ui.dp(context, 16), Ui.dp(context, 20), 0); addView(card) } })
        body.addView(Ui.caption(activity,
            "Sign in on Google's own page and this tab shows your own YouTube: your home feed, your subscriptions and " +
                "your searches. The sign-in stays on this phone, the way a browser keeps it; EdenOS never sees your password.").apply {
            setPadding(Ui.dp(context, 20), Ui.dp(context, 8), Ui.dp(context, 20), 0)
        })
        val following = Prefs.followedChannels(activity)
        if (following.isNotEmpty()) {
            shelf("Following", number) { following.keys.flatMap { id -> try { YouTubeData.channelFeed(id).take(6) } catch (e: Exception) { emptyList() } }
                .sortedByDescending { it.meta } }
        }
        for (topic in Prefs.youtubeTopics(activity)) shelf(topic, number) { YouTubeData.search(topic) }
        body.addView(Ui.caption(activity,
            "Follow a channel from any video's page and its newest videos appear here.").apply {
            setPadding(Ui.dp(context, 20), Ui.dp(context, 28), Ui.dp(context, 20), 0)
        })
    }

    /** The viewer's own YouTube: subscriptions first, then their home feed, which grows as they scroll to its end. */
    private fun showOwnYouTube(number: Int) {
        val subscriptionsTitle = Ui.shelfTitle(activity, "Subscriptions")
        val strip = LinearLayout(activity).apply { setPadding(Ui.dp(context, 20), 0, Ui.dp(context, 8), 0) }
        repeat(3) { strip.addView(Ui.videoCard(activity, null) {}) }
        val scroller = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
        }
        val feed = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        body.addView(subscriptionsTitle)
        body.addView(scroller)
        body.addView(Ui.shelfTitle(activity, "For you"))
        body.addView(feed)
        feed.addView(note("Loading your feed…"))
        // One hidden browser, so one page at a time: the home feed, then subscriptions.
        collect(YouTubeMirror.HOME, number, until = { it.size >= 16 }) { videos ->
            feed.removeAllViews()
            if (videos.isEmpty()) {
                feed.addView(note("Your feed could not be loaded. If you have signed out of YouTube, sign in again from Settings > Your accounts."))
            } else {
                showFeed(feed, videos, number)
            }
            collect(YouTubeMirror.SUBSCRIPTIONS, number, until = { it.size >= 12 }) { subs ->
                strip.removeAllViews()
                if (subs.isEmpty()) {
                    subscriptionsTitle.visibility = View.GONE
                    scroller.visibility = View.GONE
                } else {
                    subs.take(20).forEach { video -> strip.addView(Ui.videoCard(activity, video) { VideoActivity.open(activity, video) }) }
                }
                // The hidden browser is left on the home feed, ready to load more of it.
                mirrorFeedMore = { onMore -> collectMore(YouTubeMirror.HOME, number, onMore) }
            }
        }
    }

    private var mirrorFeedMore: ((onMore: (List<Video>) -> Unit) -> Unit)? = null

    private fun showFeed(feed: LinearLayout, videos: List<Video>, number: Int) {
        val shown = HashSet<String>()
        fun add(list: List<Video>) {
            for (video in list) if (shown.add(video.id)) feed.addView(Ui.videoRow(activity, video) { VideoActivity.open(activity, video) })
        }
        add(videos)
        lateinit var moreButton: View
        moreButton = FrameLayout(activity).apply {
            setPadding(Ui.dp(context, 20), Ui.dp(context, 8), Ui.dp(context, 20), Ui.dp(context, 8))
            addView(Ui.actionButton(context, "Show more", filled = false) {
                val loader = mirrorFeedMore ?: return@actionButton
                feed.removeView(moreButton)
                feed.addView(note("Loading more…").also { it.tag = "loading" })
                loader { more ->
                    feed.findViewWithTag<View>("loading")?.let { feed.removeView(it) }
                    if (number != loadNumber) return@loader
                    add(more)
                    feed.addView(moreButton)
                }
            })
        }
        feed.addView(moreButton)
    }

    /** Reopens [url] and scrolls it to its end, collecting what YouTube adds. */
    private fun collectMore(url: String, number: Int, onMore: (List<Video>) -> Unit) {
        val answers = ArrayList<YouTubeMirror.Answer>()
        var done = false
        val target = mirror()
        fun finish() {
            if (done || activity.isDestroyed) return
            done = true
            onMore(YouTubeData.videosIn(answers.map { it.json }))
        }
        var scrolls = 0
        val scroll = object : Runnable {
            override fun run() {
                if (done || scrolls++ > 6) return
                target.more()
                body.postDelayed(this, 1500)
            }
        }
        target.open(url) { answer ->
            answers += answer
            if (answer.kind == "initial") body.postDelayed(scroll, 800)
            if (answer.kind == "browse") finish()
        }
        body.postDelayed({ finish() }, 14000)
    }

    /** Adds a titled row of videos that fills in when its list has loaded, and disappears if it cannot be loaded. */
    private fun shelf(name: String, number: Int, load: () -> List<Video>) {
        val title = Ui.shelfTitle(activity, name)
        val strip = LinearLayout(activity).apply { setPadding(Ui.dp(context, 20), 0, Ui.dp(context, 8), 0) }
        repeat(3) { strip.addView(Ui.videoCard(activity, null) {}) }
        val scroller = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
        }
        body.addView(title)
        body.addView(scroller)
        pool.execute {
            val videos = try { load() } catch (e: Exception) { emptyList() }
            activity.runOnUiThread {
                if (activity.isDestroyed || number != loadNumber) return@runOnUiThread
                strip.removeAllViews()
                if (videos.isEmpty()) {
                    title.visibility = View.GONE
                    scroller.visibility = View.GONE
                } else {
                    videos.take(15).forEach { video -> strip.addView(Ui.videoCard(activity, video) { VideoActivity.open(activity, video) }) }
                }
            }
        }
    }

    private fun note(text: String) = Ui.caption(activity, text).apply { setPadding(Ui.dp(context, 20), Ui.dp(context, 24), Ui.dp(context, 20), 0) }
}
