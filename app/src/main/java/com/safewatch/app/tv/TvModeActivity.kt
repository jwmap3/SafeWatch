package com.safewatch.app.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Display
import android.view.FocusFinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.safewatch.app.R
import com.safewatch.app.browser.BrowserActivity
import com.safewatch.app.browser.WatchActivity
import com.safewatch.app.data.Prefs
import com.safewatch.app.data.Services
import com.safewatch.app.data.Video
import com.safewatch.app.data.YouTubeData
import com.safewatch.app.ui.Images
import com.safewatch.app.ui.Sounds
import com.safewatch.app.ui.Ui

/**
 * TV Mode's home: EdenOS's own TV screen, shown on the TV, with the phone as its remote. Pick a service,
 * a YouTube video or a quick link and it opens on the TV, filtered as everywhere else in EdenOS.
 */
class TvModeActivity : AppCompatActivity() {
    private var stage: TvStage? = null
    private var home: TvHome? = null
    private var stopWatching: (() -> Unit)? = null
    private lateinit var holder: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TvMode.active = true
        holder = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0B0F0D")) }
        setContentView(holder)
        Ui.fitSystemBars(this, holder)
    }

    override fun onStart() {
        super.onStart()
        stopWatching = TvMode.watch(this) { show(it) }
        show(TvMode.display(this))
    }

    override fun onStop() {
        stopWatching?.invoke()
        stopWatching = null
        // While a page or player is on the TV, it has the TV to itself.
        closeStage()
        super.onStop()
    }

    private fun closeStage() {
        stage?.let { if (it.isShowing) it.dismiss() }
        stage = null
        home = null
    }

    /** Puts the TV home on the TV, or, with no TV connected, says how to connect one. */
    private fun show(display: Display?) {
        if (isFinishing) return
        if (display == null) {
            closeStage()
            holder.removeAllViews()
            holder.addView(connectHelp(), FrameLayout.LayoutParams(-1, -1))
            return
        }
        if (stage?.display?.displayId == display.displayId && stage?.isShowing == true) return
        closeStage()
        val tv = TvStage(this, display)
        val view = TvHome(tv.context, accent = Ui.color(this, R.color.accent)) { open(it) }
        tv.show()
        tv.root.addView(view, FrameLayout.LayoutParams(-1, -1))
        tv.setOnDismissListener { if (stage === tv) { stage = null; home = null } }
        stage = tv
        home = view
        holder.removeAllViews()
        holder.addView(RemotePad(this, homeTarget, display.name) { exit() }, FrameLayout.LayoutParams(-1, -1))
    }

    private fun connectHelp(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val pad = Ui.dp(context, 28)
        setPadding(pad, Ui.dp(context, 60), pad, pad)
        addView(Ui.logo(context, 96))
        addView(TextView(context).apply {
            text = "Connect to your TV"
            textSize = 26f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(context, 22), 0, Ui.dp(context, 10))
        })
        addView(TextView(context).apply {
            text = "Swipe down twice from the top of the screen, tap Smart View (or Screen cast), and choose your TV. " +
                "EdenOS appears on the TV as soon as it is connected, and this phone becomes the remote.\n\n" +
                "Roku, Samsung and LG TVs all take Smart View. A USB-C to HDMI cable works too."
            textSize = 16f
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
            setTextColor(Color.argb(200, 255, 255, 255))
        })
        addView(Ui.actionButton(context, "Open Smart View", iconRes = R.drawable.ic_cast) { Ui.openScreenCasting(this@TvModeActivity) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(context, 28) })
        addView(Ui.actionButton(context, "Leave TV Mode", filled = false) { exit() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(context, 12) })
    }

    private fun exit() {
        TvMode.active = false
        closeStage()
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (home?.back() == true) return
        exit()
    }

    /** Opens what was picked on the TV home. Pages and players go to the TV by themselves while TV Mode is on. */
    private fun open(choice: TvHome.Choice) {
        Sounds.play(this, Sounds.OPEN)
        when (choice) {
            is TvHome.Choice.Page -> BrowserActivity.open(this, choice.url)
            is TvHome.Choice.Service -> WatchActivity.open(this, choice.url, choice.name)
            is TvHome.Choice.YouTube -> WatchActivity.open(this, WatchActivity.youtube(choice.video.id), choice.video.title)
            is TvHome.Choice.Search -> type()
        }
    }

    /** Asks the phone for words to look for, on YouTube or the web. */
    private fun type() {
        val field = EditText(this).apply {
            hint = "Search YouTube or the web"
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        val box = FrameLayout(this).apply {
            setPadding(Ui.dp(context, 22), Ui.dp(context, 8), Ui.dp(context, 22), 0)
            addView(field)
        }
        AlertDialog.Builder(this)
            .setTitle("Search")
            .setView(box)
            .setPositiveButton("YouTube") { _, _ -> field.text.toString().trim().takeIf { it.isNotEmpty() }?.let { home?.searchYouTube(it) } }
            .setNeutralButton("The web") { _, _ -> field.text.toString().trim().takeIf { it.isNotEmpty() }?.let { BrowserActivity.open(this, it) } }
            .setNegativeButton("Cancel", null)
            .show()
        field.requestFocus()
    }

    private val homeTarget = object : TvTarget {
        override val prefersPointer = false
        override fun arrow(keyCode: Int) { home?.move(keyCode) }
        override fun ok() { home?.choose() }
        override fun back() { if (home?.back() != true) Ui.toast(this@TvModeActivity, "This is the TV home. Leave with Exit TV Mode.") }
        override fun home() { home?.toTop() }
        override fun type() = this@TvModeActivity.type()
        override fun media(command: String) {}
        override fun pointer(dx: Float, dy: Float) { stage?.movePointer(dx, dy) }
        override fun click() { home?.clickAt(stage?.pointerX ?: 0f, stage?.pointerY ?: 0f) }
        override fun scroll(dy: Float) { home?.scrollBy(dy) }
    }

    companion object {
        fun open(activity: Activity) = activity.startActivity(Intent(activity, TvModeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }
}

/**
 * What the TV shows in TV Mode: the EdenOS mark and the time, then rows of large tiles (the viewer's
 * services, YouTube, quick links). The phone's remote moves a highlight from tile to tile.
 */
class TvHome(context: Context, private val accent: Int, private val onChoose: (Choice) -> Unit) : FrameLayout(context) {
    sealed class Choice {
        data class Page(val url: String) : Choice()
        data class Service(val name: String, val url: String) : Choice()
        data class YouTube(val video: Video) : Choice()
        object Search : Choice()
    }

    private val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false; isFocusable = false }
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var youtubeRow: LinearLayout? = null
    private var youtubeTitle: TextView? = null
    private var firstTile: View? = null

    init {
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#12261A"), Color.parseColor("#05090A")))
        val side = dp(56)
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(side, dp(34), side, dp(10))
            addView(Ui.logo(context, 64))
            addView(TextView(context).apply {
                text = "EdenOS"
                textSize = 34f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.WHITE)
                setPadding(dp(16), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextClock(context).apply {
                textSize = 30f
                setTextColor(Color.argb(220, 255, 255, 255))
            })
        }, LayoutParams(-1, dp(130), Gravity.TOP))
        scroll.addView(rows, ViewGroup.LayoutParams(-1, -2))
        addView(scroll, LayoutParams(-1, -1).apply { topMargin = dp(130) })
        addView(TextView(context).apply {
            text = "Use your phone as the remote: swipe to move, tap the middle to choose"
            textSize = 15f
            setTextColor(Color.argb(140, 255, 255, 255))
            setPadding(side, 0, side, dp(18))
        }, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))

        val services = Services.connected(context)
        if (services.isNotEmpty()) row("Your services", services.map { s ->
            tile(s.name, wide = false, letter = s.name) { onChoose(Choice.Service(s.name, s.homeUrl)) }
        })
        val searchTile = tile("Search YouTube", wide = true, letter = "⌕") { onChoose(Choice.Search) }
        youtubeRow = row("YouTube", listOf(searchTile))
        youtubeTitle = (youtubeRow?.parent?.parent as? LinearLayout)?.getChildAt(0) as? TextView
        row("Quick links", Prefs.quickLinks(context).map { (name, url) ->
            tile(name, wide = false, letter = name, icon = android.net.Uri.parse(url).host?.let { "https://www.google.com/s2/favicons?domain=$it&sz=128" }) {
                onChoose(Choice.Page(url))
            }
        })
        post { firstTile?.requestFocus() }
        val topic = Prefs.youtubeTopics(context).firstOrNull() ?: "popular videos"
        loadYouTube(topic, "YouTube · $topic")
    }

    /** Shows YouTube's results for [query] in the YouTube row. */
    fun searchYouTube(query: String) = loadYouTube(query, "YouTube · $query", focus = true)

    private fun loadYouTube(query: String, title: String, focus: Boolean = false) {
        youtubeTitle?.text = "$title…"
        Thread {
            val found = try { YouTubeData.search(query).take(14) } catch (e: Exception) { emptyList() }
            post {
                val row = youtubeRow ?: return@post
                youtubeTitle?.text = if (found.isEmpty()) "YouTube · nothing found" else title
                while (row.childCount > 1) row.removeViewAt(1)
                found.forEach { v -> row.addView(videoTile(v)) }
                if (focus) row.getChildAt(1)?.requestFocus()
            }
        }.start()
    }

    private fun row(title: String, tiles: List<View>): LinearLayout {
        val line = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(46), dp(14), dp(46), dp(14))
            clipChildren = false
            clipToPadding = false
        }
        tiles.forEach { line.addView(it) }
        if (firstTile == null) firstTile = tiles.firstOrNull()
        rows.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            addView(TextView(context).apply {
                text = title
                textSize = 22f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.WHITE)
                setPadding(dp(56), dp(16), dp(56), 0)
            })
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                isFocusable = false
                clipChildren = false
                addView(line)
            })
        })
        return line
    }

    /** A large tile: a letter or the site's icon, and a name. */
    private fun tile(name: String, wide: Boolean, letter: String, icon: String? = null, onClick: () -> Unit): View = card(if (wide) 300 else 210, 150, onClick).apply {
        contentDescription = name
        val mark = TextView(context).apply {
            text = letter.take(1).uppercase()
            textSize = 44f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setTextColor(accent)
        }
        addView(mark, LayoutParams(-1, -1).apply { bottomMargin = dp(34) })
        if (icon != null) {
            val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            addView(image, LayoutParams(dp(64), dp(64), Gravity.CENTER).apply { bottomMargin = dp(34) })
            Images.load(icon, image, minWidth = 64, clear = true) { mark.visibility = View.INVISIBLE }
        }
        addView(label(name), LayoutParams(-1, -2, Gravity.BOTTOM))
    }

    /** A YouTube video: its picture with its title underneath. */
    private fun videoTile(v: Video): View = card(320, 250, { onChoose(Choice.YouTube(v)) }).apply {
        contentDescription = v.title
        val picture = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#1F2A24"))
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            background = Ui.rounded(Color.parseColor("#1F2A24"), dp(14).toFloat())
        }
        addView(picture, LayoutParams(-1, dp(180), Gravity.TOP))
        Images.load(v.thumbnail, picture, minWidth = 320)
        addView(label(v.title).apply { maxLines = 2; textSize = 15f; gravity = Gravity.START; setPadding(dp(12), 0, dp(12), dp(10)) },
            LayoutParams(-1, -2, Gravity.BOTTOM))
    }

    private fun label(text: String) = TextView(context).apply {
        this.text = text
        textSize = 17f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(dp(10), 0, dp(10), dp(14))
    }

    /** A rounded, focusable card that grows and lights up when the highlight is on it. */
    private fun card(widthDp: Int, heightDp: Int, onClick: () -> Unit) = FrameLayout(context).apply {
        val normal = Ui.rounded(Color.parseColor("#17211C"), dp(18).toFloat())
        val lit = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(Color.parseColor("#22302A"))
            setStroke(dp(4), accent)
        }
        background = normal
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(dp(widthDp), dp(heightDp)).apply { marginEnd = dp(22) }
        setOnClickListener { onClick() }
        setOnFocusChangeListener { v, focused ->
            v.background = if (focused) lit else normal
            v.animate().scaleX(if (focused) 1.08f else 1f).scaleY(if (focused) 1.08f else 1f).setDuration(140).start()
            v.elevation = if (focused) dp(12).toFloat() else 0f
        }
    }

    // ---- Worked from the phone ----

    /** Moves the highlight to the next tile in the arrow's direction. */
    fun move(keyCode: Int) {
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
            else -> View.FOCUS_RIGHT
        }
        val current = findFocus() ?: run { firstTile?.requestFocus(); return }
        val next = FocusFinder.getInstance().findNextFocus(this, current, direction) ?: return
        next.requestFocus(direction)
        Sounds.play(context, Sounds.TAP)
    }

    fun choose() {
        (findFocus() ?: firstTile)?.performClick()
    }

    fun back(): Boolean {
        if (scroll.scrollY > 0) { toTop(); return true }
        return false
    }

    fun toTop() {
        scroll.smoothScrollTo(0, 0)
        firstTile?.requestFocus()
    }

    fun scrollBy(dy: Float) = scroll.smoothScrollBy(0, dy.toInt())

    /** A click from the phone's pointer, at a place on the TV. */
    fun clickAt(x: Float, y: Float) {
        val hit = IntArray(2)
        fun find(group: ViewGroup): View? {
            for (i in group.childCount - 1 downTo 0) {
                val child = group.getChildAt(i)
                if (child.visibility != View.VISIBLE) continue
                child.getLocationOnScreen(hit)
                val origin = IntArray(2).also { getLocationOnScreen(it) }
                val left = hit[0] - origin[0]
                val top = hit[1] - origin[1]
                if (x >= left && x < left + child.width * child.scaleX && y >= top && y < top + child.height * child.scaleY) {
                    if (child.isClickable && child.hasOnClickListeners()) return child
                    if (child is ViewGroup) find(child)?.let { return it }
                }
            }
            return null
        }
        find(this)?.let { it.requestFocus(); it.performClick() }
    }

    private fun dp(v: Int) = Ui.dp(context, v)
}
