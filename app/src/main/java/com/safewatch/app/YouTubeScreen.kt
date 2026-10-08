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
import com.safewatch.app.data.Prefs
import com.safewatch.app.data.Video
import com.safewatch.app.data.YouTubeData
import com.safewatch.app.ui.Ui
import java.util.concurrent.Executors

/**
 * The YouTube tab: YouTube's videos in SafeWatch's own layout. It shows the
 * latest from channels followed here, shelves for a few chosen subjects, and
 * search. Videos open on a page of their own and play in the app's player.
 */
class YouTubeScreen(private val activity: MainActivity) {

    val view: View
    private val field: EditText
    private val body: LinearLayout
    private val pool = Executors.newFixedThreadPool(3)
    private var loadNumber = 0
    private var shownFor: String? = null

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
        val state = Prefs.followedChannels(activity).keys.sorted().joinToString(",") + "|" + Prefs.youtubeTopics(activity).joinToString(",")
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

    private fun showShelves() {
        val number = ++loadNumber
        body.removeAllViews()
        val following = Prefs.followedChannels(activity)
        if (following.isNotEmpty()) {
            shelf("Following", number) { following.keys.flatMap { id -> try { YouTubeData.channelFeed(id).take(6) } catch (e: Exception) { emptyList() } }
                .sortedByDescending { it.meta } }
        }
        for (topic in Prefs.youtubeTopics(activity)) shelf(topic, number) { YouTubeData.search(topic) }
        body.addView(Ui.caption(activity,
            "Google does not allow signing in to YouTube from inside another app, so this is not your personal feed. " +
                "Follow a channel from any video's page and its newest videos appear here.").apply {
            setPadding(Ui.dp(context, 20), Ui.dp(context, 28), Ui.dp(context, 20), 0)
        })
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
