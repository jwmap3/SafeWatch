package com.safewatch.app.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.content.pm.ActivityInfo
import android.view.MotionEvent
import android.view.WindowManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.os.Message
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.content.res.ColorStateList
import android.util.Log
import android.widget.EditText
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.safewatch.app.MainActivity
import com.safewatch.app.R
import com.safewatch.app.data.Accounts
import com.safewatch.app.data.FilterLog
import com.safewatch.app.data.Prefs
import com.safewatch.app.data.Service
import com.safewatch.app.data.Services
import com.safewatch.app.data.TagStore
import com.safewatch.app.detect.NudityDetector
import com.safewatch.app.ui.SceneDialog
import com.safewatch.app.ui.Ui
import com.safewatch.core.Action
import com.safewatch.core.CaptionFormats
import com.safewatch.core.CueTagger
import com.safewatch.core.FilterEngine
import com.safewatch.core.FilterSettings
import com.safewatch.core.MediaKey
import com.safewatch.core.ProfanityMatcher
import com.safewatch.core.Strictness
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The built-in browser. Every page gets the EdenOS page script (assets/safewatch.js),
 * which mutes, skips and blurs the page's own video player. The script asks this
 * activity what to filter through [Bridge].
 *
 * Sign-ins happen on each service's own website and are kept by the browser's
 * cookie store, as in any browser. The app never reads or stores passwords.
 */
open class BrowserActivity : AppCompatActivity() {

    /**
     * False for the Browser tab. [WatchActivity] turns it on: no address bar, and once
     * a video is playing the screen turns sideways and everything but the picture goes.
     */
    protected open val watchMode: Boolean get() = false

    private lateinit var root: LinearLayout
    private lateinit var web: WebView
    private lateinit var stage: FrameLayout
    private lateinit var curtain: Curtain
    private var playerLayer: PlayerLayer? = null
    private lateinit var address: EditText
    private lateinit var chrome: List<View>

    // The Browser tab's own parts: one bar at the bottom that can be hidden, a quick-links start
    // screen, and a pill that only shows while a scene is being marked.
    private var bottomBar: View? = null
    private var barDivider: View? = null
    private var revealButton: View? = null
    private var quickLinks: QuickLinks? = null
    private var markingPill: TextView? = null
    private var barHidden = false
    private var barHiddenByChoice = false
    private var currentUrl = ""
    private val markButtons = ArrayList<TextView>()
    private var playerBar: View? = null
    private var label = ""
    private var playerView = false
    private var playingChecks = 0
    private var resumed = false
    @Volatile private var lastWidthShare = 0.0
    @Volatile private var wantsFullPicture = false

    // The app's own play, skip and scrub controls. Used where the page's controls are switched off (YouTube).
    private var ownControls = false
    private var touchCatcher: View? = null
    private var controlViews: List<View> = emptyList()
    private var playButton: ImageView? = null
    private var scrubber: SeekBar? = null
    private var timeNow: TextView? = null
    private var timeTotal: TextView? = null
    private var scrubbing = false
    @Volatile private var pendingCommand = ""
    @Volatile private var videoPositionMs = 0L
    @Volatile private var videoLengthMs = 0L
    @Volatile private var videoPaused = true
    @Volatile private var advertPlaying = false
    @Volatile private var lastStateAt = 0L

    private val ui = Handler(Looper.getMainLooper())
    private val background = Executors.newSingleThreadExecutor()
    private val version = AtomicInteger(1)

    @Volatile private var settings = FilterSettings()
    @Volatile private var matcher = ProfanityMatcher(settings)
    @Volatile private var pageKey = ""
    @Volatile private var pageTitle = ""
    @Volatile private var lastBeatAt = 0L
    @Volatile private var lastPositionMs = 0L

    private var markStartMs: Long? = null
    private var detector: NudityDetector? = null
    private var checking = false
    private var copying = false
    private var hidden = false
    private var hiddenUntil = 0L
    private var lastLiveCheckAt = 0L

    // Looking ahead: a hidden second copy of the video, and whether what it found is hiding the picture now.
    private var scout: Scout? = null
    private var scoutKey = ""
    private var scoutGaveUpOn = ""
    private var scoutDoubts = 0
    private var aheadHidden = false
    private var playingYoutube: String? = null
    private var playingFile: String? = null

    // What the language filter has to go on for the video being watched, as its page last reported.
    @Volatile private var captionKind = ""
    @Volatile private var captionLines = 0
    @Volatile private var captionKindAt = 0L
    private var captionKey = ""
    private var playingUnfilteredSince = 0L
    private var warnedNoCaptions = ""
    private val statusLabels = ArrayList<TextView>()
    private val titleViews = ArrayList<TextView>()

    // Pop-ups and redirects.
    // The video file the page is playing, if it plays a whole file, and caption files that go with it.
    @Volatile private var pageVideoSrc = ""
    @Volatile private var pageVideoTracks: List<String> = emptyList()

    // Streams' manifests the page has loaded, and every caption line with times the page script has
    // read: what a clean copy for the TV is made from.
    private class PageManifest(val kind: String, val address: String, val master: Boolean, val refusal: String?)
    private val pageManifests = ArrayList<PageManifest>()
    private val pageCues = LinkedHashMap<String, Triple<Long, Long, String>>()

    private var pageStartedAt = 0L
    private var allowOnce: String? = null
    private var blockedBar: TextView? = null
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var pageScript = ""
    private var mobileAgent = ""
    private var fixedKey: String? = null
    private var signingInTo: Service? = null
    private var sawSignInPage = false
    private var scriptAtDocumentStart = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (handOffToPlayer(intent)) {
            finish()
            return
        }
        label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        root = buildLayout()
        setContentView(root)
        Ui.fitSystemBars(this, root)
        // The player view uses the whole screen, right to the edges and around the camera cut-out.
        // Everywhere else the page keeps clear of the system bars and the keyboard.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
        } else {
            window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            if (playerView) {
                v.setPadding(0, 0, 0, 0)
            } else {
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
                )
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            }
            insets
        }

        pageScript = assets.open("safewatch.js").bufferedReader().use { it.readText() }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            // A page asking for a new window has to ask the app (see Chrome.onCreateWindow), which says no.
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        // The browser introduces itself as the phone's Chrome. WebView's own name ("; wv") makes some
        // sites, Google's sign-in among them, refuse it as an app rather than a browser.
        mobileAgent = web.settings.userAgentString.replace("; wv)", ")").replace(Regex("Version/\\d+(\\.\\d+)* "), "")
        hideAppName(web)
        FilterLog.load(applicationContext)
        web.addJavascriptInterface(Bridge(), "SafeWatchBridge")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            // Runs in every frame before the page's own scripts, so embedded players are covered too.
            WebViewCompat.addDocumentStartJavaScript(web, pageScript, setOf("*"))
            scriptAtDocumentStart = true
        }
        web.webViewClient = Client()
        web.webChromeClient = Chrome()

        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) {
            // Opened on its own, the Browser tab starts at the quick links.
            load(requestedUrl(intent) ?: if (watchMode) Prefs.lastPage(this) ?: START_PAGE else START_PAGE)
        }
        if (savedInstanceState == null) handToTvWhenReady(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (handOffToPlayer(intent)) return
        requestedUrl(intent)?.let { load(it) }
        handToTvWhenReady(intent)
    }

    /**
     * A link straight to a video file, opened from another app or typed into the browser,
     * is better watched in the player than on a bare browser page. Returns true when the
     * link was passed on.
     */
    private fun handOffToPlayer(intent: Intent): Boolean {
        if (watchMode) return false
        val url = intent.dataString ?: intent.getStringExtra(EXTRA_URL) ?: return false
        if (!DIRECT_VIDEO.containsMatchIn(url) && !YOUTUBE_PLAYER.containsMatchIn(url)) return false
        WatchActivity.open(this, url, Uri.parse(url).lastPathSegment.orEmpty())
        return true
    }

    /**
     * The page an intent asks for: one chosen inside the app, a link opened
     * from another app, or a link shared to EdenOS from another browser.
     */
    private fun requestedUrl(intent: Intent): String? {
        signingInTo = Services.byId(intent.getStringExtra(EXTRA_SIGN_IN))
        sawSignInPage = false
        intent.getStringExtra(EXTRA_URL)?.let { return it }
        if (intent.action == Intent.ACTION_VIEW) return intent.dataString
        if (intent.action == Intent.ACTION_SEND) {
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
            return Regex("https?://\\S+").find(shared)?.value ?: toUrl(shared)
        }
        return null
    }

    private fun buildLayout(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.color(context, R.color.bar))
        }

        address = EditText(this).apply {
            textSize = 15f
            maxLines = 1
            hint = "Search Google or type a website"
            gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setSelectAllOnFocus(true)
            setTextColor(Ui.color(context, R.color.text))
            setHintTextColor(Ui.color(context, R.color.text_secondary))
            background = Ui.rounded(Ui.color(context, R.color.fill), Ui.dp(context, 18).toFloat())
            setPadding(Ui.dp(context, 14), Ui.dp(context, 9), Ui.dp(context, 14), Ui.dp(context, 9))
            setOnEditorActionListener { v, _, _ ->
                go(v.text.toString())
                true
            }
            // Away from the keyboard the bar shows just the site's name; tapped, the whole address, ready to replace.
            setOnFocusChangeListener { _, focused ->
                gravity = if (focused) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER
                showAddress()
                if (focused) selectAll()
                showQuickLinks(focused || currentUrl == START_PAGE)
            }
        }

        web = WebView(this)
        stage = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(web, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        curtain = Curtain(stage)
        val stageHolder = stage

        // Whenever a video fills the screen (the player, or any page's own full-screen video in the
        // browser), the controls sit in a layer of their own over the picture, above the blur, so they
        // can always be seen: back, the title, Mark scene and Send to TV.
        val layer = PlayerLayer(this, below = { fullscreenView ?: web }, onTouched = {
            // Any touch on the picture brings the controls back for a moment.
            // (With the app's own controls up, the layer over the picture decides that itself.)
            if (touchCatcher?.visibility != View.VISIBLE) showPlayerBar()
        })
        val floating = watchControls(overPicture = true).apply { visibility = View.GONE }
        addOwnControls(layer)
        layer.addView(floating, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        playerBar = floating
        playerLayer = layer
        stage.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (playerView) layer.showOver(stage) }

        if (watchMode) {
            // A slim bar while browsing to the video.
            val bar = watchControls(overPicture = false)
            root.addView(bar)
            root.addView(stageHolder, LinearLayout.LayoutParams(-1, 0, 1f))
            chrome = listOf(bar)
            return root
        }

        // The Browser tab: the page fills the screen, with one slim bar at the bottom for back, the
        // address and a menu. The bar slides away while reading down a page and comes back on the way up.
        val links = QuickLinks(this) { url -> go(url) }.apply { visibility = View.GONE }
        quickLinks = links
        stage.addView(links, FrameLayout.LayoutParams(-1, -1))
        val pill = Ui.pill(this, MARK_END, filled = true) { onMarkTapped() }.apply { visibility = View.GONE }
        markButtons += pill
        markingPill = pill
        stage.addView(pill, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = Ui.dp(this@BrowserActivity, 16)
        })
        val reveal = FrameLayout(this).apply {
            contentDescription = "Show the bar"
            background = Ui.rounded(Color.argb(150, 30, 30, 34), Ui.dp(context, 22).toFloat())
            addView(Ui.icon(context, R.drawable.ic_up, R.color.on_accent, 22), FrameLayout.LayoutParams(Ui.dp(context, 22), Ui.dp(context, 22), Gravity.CENTER))
            visibility = View.GONE
            setOnClickListener {
                barHiddenByChoice = false
                setBarHidden(false)
            }
        }
        revealButton = reveal
        stage.addView(reveal, FrameLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, Ui.dp(this@BrowserActivity, 14), Ui.dp(this@BrowserActivity, 14))
        })
        val bottom = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Ui.color(context, R.color.bar))
            setPadding(Ui.dp(context, 4), Ui.dp(context, 6), Ui.dp(context, 4), Ui.dp(context, 6))
            addView(Ui.iconButton(context, R.drawable.ic_back, "Back") { goBack() })
            addView(address, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Ui.iconButton(context, R.drawable.ic_more, "More") { showMenu(it) })
        }
        val divider = Ui.divider(this, 0)
        bottomBar = bottom
        barDivider = divider
        root.addView(stageHolder, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(divider)
        root.addView(bottom)
        chrome = listOf(divider, bottom)
        web.setOnScrollChangeListener { _, _, y, _, oldY ->
            if (barHiddenByChoice || playerView || address.hasFocus() || !Prefs.hideBarWhileScrolling(this)) return@setOnScrollChangeListener
            if (y > oldY + 10 && y > Ui.dp(this, 80)) setBarHidden(true)
            else if (y < oldY - 10) setBarHidden(false)
        }
        return root
    }

    /** Hides or shows the browser's bottom bar. While it is hidden, a small button in the corner brings it back. */
    private fun setBarHidden(hide: Boolean) {
        val bar = bottomBar ?: return
        if (hide == barHidden || playerView) return
        barHidden = hide
        if (hide) {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(address.windowToken, 0)
            address.clearFocus()
            bar.animate().translationY(bar.height.toFloat()).setDuration(160).withEndAction {
                if (barHidden) {
                    bar.visibility = View.GONE
                    barDivider?.visibility = View.GONE
                }
            }.start()
        } else {
            bar.visibility = View.VISIBLE
            barDivider?.visibility = View.VISIBLE
            bar.translationY = bar.height.toFloat()
            bar.animate().translationY(0f).setDuration(160).start()
        }
        revealButton?.visibility = if (hide) View.VISIBLE else View.GONE
    }

    /** The address bar's text: the site's name, or, while it is being typed in, the whole address. */
    private fun showAddress() {
        if (!::address.isInitialized) return
        val url = currentUrl
        val text = when {
            url == START_PAGE || url.isEmpty() || fixedKey != null -> ""
            address.hasFocus() -> url
            else -> Uri.parse(url).host.orEmpty().removePrefix("www.").removePrefix("m.")
        }
        if (address.text.toString() != text) address.setText(text)
    }

    private fun showQuickLinks(show: Boolean) {
        val links = quickLinks ?: return
        if (show && links.visibility != View.VISIBLE) links.refresh()
        links.visibility = if (show) View.VISIBLE else View.GONE
    }

    /** Back, the title, Mark scene and Send to TV: everything the player shows besides the picture. */
    private fun watchControls(overPicture: Boolean): LinearLayout = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        val tint = if (overPicture) R.color.on_accent else R.color.text
        if (overPicture) {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.argb(190, 0, 0, 0), Color.TRANSPARENT))
            setPadding(Ui.dp(context, 8), Ui.dp(context, 6), Ui.dp(context, 12), Ui.dp(context, 26))
        } else {
            setPadding(Ui.dp(context, 4), Ui.dp(context, 4), Ui.dp(context, 8), Ui.dp(context, 4))
        }
        addView(Ui.iconButton(context, R.drawable.ic_back, "Back", tint) { goBack() })
        addView(TextView(context).apply {
            titleViews += this
            text = label
            textSize = 16f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(if (overPicture) Color.WHITE else Ui.color(context, R.color.text))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val status = TextView(context).apply {
            textSize = 12f
            maxLines = 1
            visibility = View.GONE
            setPadding(Ui.dp(context, 10), Ui.dp(context, 5), Ui.dp(context, 10), Ui.dp(context, 5))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(context, 8) }
        }
        statusLabels += status
        addView(status)
        val mark = Ui.pill(context, MARK_START, filled = false) { onMarkTapped() }
        if (overPicture) {
            mark.setTextColor(Color.WHITE)
            mark.background = Ui.rounded(Color.argb(70, 255, 255, 255), Ui.dp(context, 18).toFloat())
        }
        markButtons += mark
        addView(mark)
        addView(Ui.iconButton(context, R.drawable.ic_cast, "Send to TV", tint) { sendToTv() })
    }

    /**
     * Builds the app's own playback controls: back ten seconds, play or pause, forward ten
     * seconds, and a bar to scrub along. They sit over the picture and fade with the title bar.
     */
    private fun addOwnControls(holder: FrameLayout) {
        // A see-through layer that takes taps on the picture, so a tap shows or hides the controls
        // instead of reaching the page underneath.
        val catcher = View(this).apply {
            visibility = View.GONE
            setOnClickListener { if (controlViews.firstOrNull()?.visibility == View.VISIBLE) hidePlayerBar.run() else showPlayerBar() }
        }
        holder.addView(catcher, FrameLayout.LayoutParams(-1, -1))
        touchCatcher = catcher

        fun round(child: View, size: Int, onClick: () -> Unit) = FrameLayout(this).apply {
            background = Ui.rounded(Color.argb(110, 0, 0, 0), Ui.dp(context, size / 2).toFloat())
            addView(child, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(Ui.dp(context, size), Ui.dp(context, size)).apply {
                marginStart = Ui.dp(context, 18)
                marginEnd = Ui.dp(context, 18)
            }
            setOnClickListener { onClick() }
        }
        fun label(text: String) = TextView(this).apply {
            this.text = text
            textSize = 15f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(Color.WHITE)
        }
        val play = ImageView(this).apply {
            setImageResource(R.drawable.ic_pause)
            layoutParams = FrameLayout.LayoutParams(Ui.dp(context, 34), Ui.dp(context, 34))
        }
        playButton = play
        val middle = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            val jump = Prefs.skipSeconds(this@BrowserActivity)
            addView(round(label("−$jump"), 54) { send("skip:-$jump") }.apply { contentDescription = "Back $jump seconds" })
            addView(round(play, 72) { send(if (videoPaused) "play" else "pause") }.apply { contentDescription = "Play or pause" })
            addView(round(label("+$jump"), 54) { send("skip:$jump") }.apply { contentDescription = "Forward $jump seconds" })
        }
        holder.addView(middle, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        val accent = ColorStateList.valueOf(Ui.color(this, R.color.accent))
        val bar = SeekBar(this).apply {
            progressTintList = accent
            thumbTintList = accent
            progressBackgroundTintList = ColorStateList.valueOf(Color.argb(120, 255, 255, 255))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) timeNow?.text = Ui.time(progress * 1000L)
                }
                override fun onStartTrackingTouch(bar: SeekBar) {
                    scrubbing = true
                    ui.removeCallbacks(hidePlayerBar)
                }
                override fun onStopTrackingTouch(bar: SeekBar) {
                    scrubbing = false
                    send("seek:${bar.progress}")
                }
            })
        }
        scrubber = bar
        val now = label("0:00").apply { textSize = 13f }
        val total = label("0:00").apply { textSize = 13f }
        timeNow = now
        timeTotal = total
        val bottom = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Color.argb(190, 0, 0, 0), Color.TRANSPARENT))
            setPadding(Ui.dp(context, 18), Ui.dp(context, 26), Ui.dp(context, 18), Ui.dp(context, 12))
            addView(now)
            addView(bar, LinearLayout.LayoutParams(0, -2, 1f))
            addView(total)
        }
        holder.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        controlViews = listOf(middle, bottom)
    }

    /** A page that is nothing but a video goes straight to the player view, with the app's controls showing. */
    private fun showOwnPlayer() {
        if (watchMode) ui.post { setPlayerView(true) }
    }

    /** Passes a press on the app's controls to the page, which carries it out on its video. */
    private fun send(command: String) {
        pendingCommand = command
        showPlayerBar()
    }

    /** Keeps the app's controls in step with the video. Called a few times a second. */
    private fun refreshOwnControls() {
        if (!ownControls) return
        // During an advert the page keeps its taps, so its Skip button can be pressed.
        touchCatcher?.visibility = if (playerView && videoLengthMs > 0 && !advertPlaying) View.VISIBLE else View.GONE
        playButton?.setImageResource(if (videoPaused) R.drawable.ic_play else R.drawable.ic_pause)
        timeTotal?.text = Ui.time(videoLengthMs)
        if (!scrubbing) {
            scrubber?.max = (videoLengthMs / 1000).toInt()
            scrubber?.progress = (videoPositionMs / 1000).toInt()
            timeNow?.text = Ui.time(videoPositionMs)
        }
    }

    /**
     * Says, in the player's bar, what the language filter has to go on: captions read ahead of
     * time, captions as they appear on screen, or nothing. With nothing, cursing cannot be muted,
     * and the viewer is told so once per video.
     */
    private fun refreshLanguageStatus(now: Long) {
        if (captionKey != pageKey) {
            captionKey = pageKey
            captionKind = ""
            playingUnfilteredSince = 0
        }
        val on = settings.language != Strictness.OFF
        val playing = videoIsPlaying() && lastWidthShare >= 0.5 && !advertPlaying
        val kind = if (now - captionKindAt < 8000) captionKind else ""
        val text = when {
            !on || !(playing || playerView) -> ""
            kind == "ahead" -> "Captions read"
            kind == "screen" -> "Captions live"
            kind == "none" -> "No captions"
            else -> ""
        }
        val warning = kind == "none"
        for (label in statusLabels) {
            label.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
            if (label.text.toString() != text) {
                label.text = text
                label.setTextColor(Color.WHITE)
                label.background = Ui.rounded(if (warning) Color.argb(230, 190, 60, 40) else Color.argb(90, 255, 255, 255), Ui.dp(this, 12).toFloat())
            }
        }
        // Told once per video, after it has played a while with nothing to go on.
        if (on && playing && warning) {
            if (playingUnfilteredSince == 0L) playingUnfilteredSince = now
            if (now - playingUnfilteredSince > 15000 && warnedNoCaptions != pageKey) {
                warnedNoCaptions = pageKey
                note("no captions found after 15 s of playing; told the viewer")
                Ui.toast(this, if (Prefs.silentWithoutCaptions(this)) "No captions found for this video, so it is playing without sound"
                    else "No captions found for this video. Cursing cannot be muted here.")
                showPlayerBar()
            }
        } else if (!warning) {
            playingUnfilteredSince = 0
        }
    }

    /** A line for the phone's log and for the filter report. */
    private fun note(text: String) {
        Log.i("SafeWatch", "filter: $text on $pageKey")
        FilterLog.add("$text  [${pageKey.take(70)}]")
    }

    // ---- The player view: sideways, full screen, nothing but the picture ----

    private fun setPlayerView(on: Boolean) {
        if (playerView == on) return
        playerView = on
        wantsFullPicture = on
        ViewCompat.requestApplyInsets(root)
        chrome.forEach { it.visibility = if (on || barHidden) View.GONE else View.VISIBLE }
        if (!on) bottomBar?.translationY = 0f
        revealButton?.visibility = if (!on && barHidden) View.VISIBLE else View.GONE
        requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        WindowCompat.getInsetsController(window, root).apply {
            if (on) {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            } else {
                show(WindowInsetsCompat.Type.systemBars())
            }
        }
        if (on) {
            titleViews.forEach { it.text = label.ifEmpty { pageTitle } }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            stage.post { playerLayer?.showOver(stage) }
            showPlayerBar()
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            ui.removeCallbacks(hidePlayerBar)
            playerBar?.visibility = View.GONE
            controlViews.forEach { it.visibility = View.GONE }
            touchCatcher?.visibility = View.GONE
            playerLayer?.dismiss()
        }
    }

    private fun showPlayerBar() {
        val bar = playerBar ?: return
        bar.visibility = View.VISIBLE
        if (ownControls) controlViews.forEach { it.visibility = View.VISIBLE }
        ui.removeCallbacks(hidePlayerBar)
        ui.postDelayed(hidePlayerBar, PLAYER_BAR_MS)
    }

    private val hidePlayerBar: Runnable = Runnable {
        // While paused with the app's own controls, they stay up: there is nothing else to press play on.
        if (ownControls && videoPaused && playerView) return@Runnable
        playerBar?.visibility = View.GONE
        controlViews.forEach { it.visibility = View.GONE }
    }

    private fun setMarkLabel(text: String) {
        markButtons.forEach { it.text = text }
        // In the Browser tab, Mark scene lives in the menu; while a scene is being marked, a pill ends it.
        markingPill?.visibility = if (markStartMs != null && !playerView) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        settings = Prefs.settings(this)
        matcher = ProfanityMatcher(settings)
        searchPrefix = Prefs.searchPrefix(this)
        version.incrementAndGet()
        if (detector == null && settings.nudity != Strictness.OFF) {
            background.execute {
                val opened = NudityDetector.open(applicationContext)
                ui.post { if (isDestroyed) opened?.close() else detector = opened }
            }
        }
        ui.removeCallbacks(watch)
        ui.post(watch)
        resumed = true
        scout?.wake()
        if (playerView) stage.post { playerLayer?.showOver(stage) }
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        ui.removeCallbacks(watch)
        // The floating layers belong to this screen and must not outlive its time in front.
        hidden = false
        aheadHidden = false
        curtain.drop()
        scout?.rest()
        playerLayer?.dismiss()
        // Nothing should keep playing unfiltered behind another screen.
        web.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(m){m.pause()})", null)
        web.onPause()
        web.url?.let { if (it.startsWith("http")) Prefs.setLastPage(this, it) }
        FilterLog.save(applicationContext)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        if (!::web.isInitialized) {
            // Closed straight away after passing a video link to the player; nothing was set up.
            background.shutdown()
            super.onDestroy()
            return
        }
        scout?.stop()
        scout = null
        val open = detector
        detector = null
        background.execute { open?.close() }
        background.shutdown()
        stage.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = goBack()

    /** Back: out of a page's full-screen video first, then out of the player, then back a page. */
    private fun goBack() {
        when {
            fullscreenView != null -> web.webChromeClient?.onHideCustomView()
            watchMode && playerView -> finish()
            !watchMode && address.hasFocus() -> {
                address.clearFocus()
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(address.windowToken, 0)
            }
            web.canGoBack() -> web.goBack()
            watchMode -> finish()
            else -> MainActivity.open(this)
        }
    }

    // ---- Navigation ----

    private fun go(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        showQuickLinks(false)
        address.clearFocus()
        load(toUrl(text))
        web.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(address.windowToken, 0)
    }


    /** Whether a page should be asked for as a computer would: always if the viewer chose so, and for services that need it. */
    private fun wantsDesktop(url: String): Boolean =
        Prefs.desktopSite(this) || Services.forUrl(url)?.needsDesktopSite == true

    private fun agentFor(url: String): String {
        if (!wantsDesktop(url)) return mobileAgent
        val chromeVersion = Regex("Chrome/(\\S+)").find(mobileAgent)?.groupValues?.get(1) ?: "120.0.0.0"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeVersion Safari/537.36"
    }

    private fun forgetPageMedia() {
        synchronized(pageManifests) { pageManifests.clear() }
        synchronized(pageCues) { pageCues.clear() }
    }

    private fun load(url: String) {
        forgetPageMedia()
        val video = YOUTUBE_PLAYER.find(url)?.groupValues?.get(1)
        if (video != null) {
            // A YouTube video picked in the app plays in YouTube's embedded player, filling the screen.
            // YouTube only plays embedded when it is told which app is asking, hence the app's own address.
            web.settings.userAgentString = mobileAgent
            ownControls = watchMode
            playingYoutube = video
            playingFile = null
            val key = MediaKey.forUrl("https://www.youtube.com/watch?v=$video")
            fixedKey = key
            pageKey = key
            version.incrementAndGet()
            web.loadDataWithBaseURL(APP_ORIGIN, youtubePage(video, hiddenCopy = false), "text/html", "utf-8", null)
            showOwnPlayer()
            return
        }
        if (watchMode && DIRECT_VIDEO.containsMatchIn(url)) {
            // A plain video file gets a page with nothing on it but the video, and the app's own controls.
            ownControls = true
            playingYoutube = null
            playingFile = url
            val key = MediaKey.forUrl(url)
            fixedKey = key
            pageKey = key
            version.incrementAndGet()
            web.loadDataWithBaseURL(APP_ORIGIN, filePage(url, hiddenCopy = false), "text/html", "utf-8", null)
            showOwnPlayer()
            return
        }
        fixedKey = null
        playingYoutube = null
        playingFile = null
        ownControls = false
        web.settings.userAgentString = agentFor(url)
        web.loadUrl(url)
    }

    /** A page with nothing on it but one video file. The hidden look-ahead copy gets the same page, silent. */
    private fun filePage(url: String, hiddenCopy: Boolean): String {
        val source = url.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
        return """
            <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
            <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}video{width:100%;height:100%;object-fit:contain;background:#000}</style>
            </head><body><video src="$source" autoplay playsinline${if (hiddenCopy) " muted" else ""}></video></body></html>
        """.trimIndent()
    }

    private fun youtubePage(videoId: String, hiddenCopy: Boolean): String = """
        <!doctype html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta name="referrer" content="strict-origin-when-cross-origin">
        <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}iframe{position:fixed;top:0;left:0;width:100%;height:100%;border:0}</style>
        </head><body>
        <iframe src="https://www.youtube.com/embed/$videoId?autoplay=1&playsinline=1&cc_load_policy=1&rel=0&iv_load_policy=3&controls=${if (ownControls || hiddenCopy) 0 else 1}${if (hiddenCopy) "&mute=1" else ""}&fs=0&origin=$APP_ORIGIN"
          referrerpolicy="strict-origin-when-cross-origin"
          allow="autoplay; encrypted-media; picture-in-picture; fullscreen" allowfullscreen></iframe>
        </body></html>
    """.trimIndent()

    private fun showMenu(anchor: View) {
        val desktop = Prefs.desktopSite(this)
        PopupMenu(this, anchor).apply {
            if (web.canGoForward()) menu.add(0, 1, 0, "Forward")
            menu.add(0, 2, 1, "Reload")
            menu.add(0, 3, 2, if (markStartMs == null) "Mark scene" else "End scene")
            menu.add(0, 4, 3, "Send to TV")
            menu.add(0, 5, 4, "Quick links")
            menu.add(0, 6, 5, "Hide this bar")
            menu.add(0, 7, 6, if (desktop) "Mobile site" else "Desktop site")
            menu.add(0, 8, 7, "EdenOS home")
            menu.add(0, 9, 8, "Clear marked scenes on this page")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> web.goForward()
                    2 -> web.reload()
                    3 -> onMarkTapped()
                    4 -> sendToTv()
                    5 -> load(START_PAGE)
                    6 -> {
                        barHiddenByChoice = true
                        setBarHidden(true)
                        Ui.toast(this@BrowserActivity, "Tap the arrow in the corner to bring the bar back")
                    }
                    7 -> {
                        Prefs.setDesktopSite(this@BrowserActivity, !desktop)
                        web.url?.let { load(it) }
                    }
                    8 -> MainActivity.open(this@BrowserActivity)
                    9 -> {
                        TagStore.save(this@BrowserActivity, pageKey, pageTitle, emptyList())
                        version.incrementAndGet()
                        Ui.toast(this@BrowserActivity, "Marked scenes cleared")
                    }
                }
                true
            }
            show()
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            val scheme = request.url.scheme.orEmpty()
            if (scheme != "http" && scheme != "https") {
                // Links that try to hand over to another app (a service's own app, the Play Store)
                // are not followed: watching has to stay here for the filters to apply. If the
                // link names a web page to use instead, that page is opened.
                if (scheme == "intent") {
                    try {
                        Intent.parseUri(url, Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url")
                            ?.takeIf { it.startsWith("http") }?.let { load(it) }
                    } catch (e: Exception) {
                        // Not a well-formed link; ignore it.
                    }
                }
                return true
            }
            if (request.isForMainFrame && isUnwantedRedirect(view, request)) {
                blocked("redirect", url)
                return true
            }
            if (request.isForMainFrame) {
                fixedKey = null
                playingYoutube = null
                playingFile = null
                pageVideoSrc = ""
                pageVideoTracks = emptyList()
                forgetPageMedia()
            }
            // Moving to a site that needs the other kind of page: ask again the right way.
            if (request.isForMainFrame && agentFor(url) != view.settings.userAgentString) {
                load(url)
                return true
            }
            return false
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            pageKey = fixedKey ?: MediaKey.forUrl(url)
            version.incrementAndGet()
            noteSignIn(url)
            currentUrl = url
            showAddress()
            showQuickLinks(url == START_PAGE || address.hasFocus())
            if (markStartMs != null) {
                markStartMs = null
                setMarkLabel(MARK_START)
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            pageStartedAt = SystemClock.elapsedRealtime()
        }

        override fun onPageFinished(view: WebView, url: String) {
            // Older WebViews cannot inject at document start; add the script once the page has loaded.
            if (!scriptAtDocumentStart) view.evaluateJavascript(pageScript, null)
        }
    }

    /**
     * Follows a sign-in started from Settings: once the sign-in pages have been and gone
     * and the viewer is on the service itself, the sign-in is taken to have gone through.
     */
    private fun noteSignIn(url: String) {
        val service = signingInTo ?: return
        if (Accounts.looksLikeSignIn(url)) {
            sawSignInPage = true
        } else if (sawSignInPage && service.owns(url)) {
            Prefs.setSignInSeen(this, service.id, true)
            signingInTo = null
            Ui.toast(this, "Signed in to ${service.name}")
        }
    }

    private inner class Chrome : WebChromeClient() {
        // Errors a page reports go to the phone's log under the app's name, so a page that misbehaves can be diagnosed.
        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                Log.i("SafeWatch", "page error: ${message.message().take(300)} (${message.sourceId().take(80)}:${message.lineNumber()})")
            }
            return false
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            pageTitle = title.orEmpty()
        }

        // A page asking to open a new window. A link the viewer tapped that was merely set to open
        // in a new tab is opened here instead. Anything else is a pop-up and is refused.
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!Prefs.blockPopups(this@BrowserActivity)) {
                // Not blocking: find out where the new window was going and go there in this one.
                val asked = WebView(this@BrowserActivity)
                asked.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                        val target = request.url.toString()
                        ui.post { if (!isDestroyed && target.startsWith("http")) load(target); asked.destroy() }
                        return true
                    }
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = asked
                resultMsg.sendToTarget()
                return true
            }
            val link = tappedLink(view)
            if (isUserGesture && !inVideo() && link != null) load(link) else blocked("pop-up", link ?: "")
            return false
        }

        // Message boxes are a favourite way to trap a viewer on a page. While a video plays they are dismissed unseen.
        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean =
            dismissed(result)

        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean =
            dismissed(result)

        override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean =
            dismissed(result)

        override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            result.confirm() // never asked "are you sure you want to leave?"
            return true
        }

        private fun dismissed(result: JsResult): Boolean {
            if (!Prefs.blockPopups(this@BrowserActivity) || !inVideo()) return false
            result.cancel()
            note("blocked a message box from the page")
            return true
        }

        // Streaming sites ask for this before they will play protected video.
        override fun onPermissionRequest(request: PermissionRequest) {
            val wanted = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
            if (wanted.isNotEmpty()) request.grant(wanted.toTypedArray()) else request.deny()
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (fullscreenView != null) {
                callback.onCustomViewHidden()
                return
            }
            fullscreenView = view
            fullscreenCallback = callback
            stage.addView(view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            // A page asking for full screen gets the same player view, in either mode.
            setPlayerView(true)
        }

        override fun onHideCustomView() {
            fullscreenView?.let { stage.removeView(it) }
            fullscreenView = null
            fullscreenCallback?.onCustomViewHidden()
            fullscreenCallback = null
            if (!watchMode) setPlayerView(false)
        }
    }

    // ---- Send to TV ----

    private fun sendToTv() {
        val (source, whyNot) = cleanSource()
        val youtube = youtubeVideoId()
        Ui.sendToTv(this, source, whyNot, youtube = youtube?.let { id -> { youtubeToTv(id) } })
    }

    /** The YouTube video on this page, if there is one. */
    private fun youtubeVideoId(): String? = playingYoutube ?: YOUTUBE_VIDEO.find(web.url.orEmpty())?.groupValues?.get(1)

    /**
     * Sends this YouTube video to the TV's own YouTube app. The stretches to mute come from the captions
     * the page script has read here, so the video has to have played for a moment first.
     */
    private fun youtubeToTv(id: String) {
        val cues = synchronized(pageCues) { pageCues.values.sortedBy { it.first } }.map { (start, end, text) -> com.safewatch.core.Cue(start, end, text) }
        val mute = CueTagger.tagsFor(cues, matcher).map { it.startMs..it.endMs }.toMutableList()
        val marked = FilterEngine(TagStore.load(applicationContext, pageKey), settings).activeTags
        marked.filter { it.action == Action.MUTE }.forEach { mute += it.startMs..it.endMs }
        // The TV cannot blur, so scenes marked to blur are jumped past there.
        val skips = marked.filter { it.action == Action.SKIP || it.action == Action.BLUR }.map { it.startMs..it.endMs }
        val title = label.ifEmpty { pageTitle }.ifEmpty { "YouTube video" }
        fun go(muteAll: Boolean) {
            send("pause")
            val stretches = if (muteAll) listOf(0L..(24 * 3_600_000L)) else mute
            com.safewatch.app.tv.YouTubeTv.send(this, com.safewatch.app.tv.YouTubeTv.Video(id, title, videoPositionMs, stretches, skips))
            note("YouTube on TV: ${if (muteAll) "sound off throughout (no captions)" else "${mute.size} stretches to mute"}, ${skips.size} scenes to skip")
        }
        if (settings.language == Strictness.OFF || cues.isNotEmpty()) {
            go(muteAll = false)
            return
        }
        val strict = Prefs.silentWithoutCaptions(this)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("No captions read yet")
            .setMessage("EdenOS mutes cursing on the TV using the video's captions, and it has not read any for this video. " +
                "Let it play here for a few seconds, then send it again." +
                if (strict) "\n\nOr send it now with the TV's sound off throughout, as your \"No captions, no sound\" setting asks." else
                    "\n\nIf the video has no captions at all, nothing can be muted on the TV.")
            .setPositiveButton("OK", null)
            .setNeutralButton(if (strict) "Send without sound" else "Send unfiltered") { _, _ -> go(muteAll = strict) }
            .show()
    }

    /** True while a video opened with Play on TV is being read here before it goes to the TV. */
    private var tvHandOffSince = 0L

    /**
     * For Play on TV on a video's page: the video starts here, silently, until its captions have been read,
     * then goes to the TV's YouTube app. Gives up waiting after a while and asks.
     */
    private fun handToTvWhenReady(intent: Intent) {
        if (!intent.getBooleanExtra(EXTRA_TO_TV, false)) return
        intent.removeExtra(EXTRA_TO_TV)
        tvHandOffSince = System.currentTimeMillis()
        Ui.toast(this, "Reading the captions, then sending it to your TV…")
        ui.removeCallbacks(tvHandOff)
        ui.postDelayed(tvHandOff, 1500)
    }

    private var tvCuesSeen = -1

    private val tvHandOff = object : Runnable {
        override fun run() {
            if (tvHandOffSince == 0L || isDestroyed) return
            pendingCommand = "quiet"
            val id = youtubeVideoId() ?: run { tvHandOffSince = 0; return }
            val count = synchronized(pageCues) { pageCues.size }
            val waited = System.currentTimeMillis() - tvHandOffSince
            // Sent once the captions have arrived and stopped growing, or after 15 seconds either way.
            if ((count > 0 && count == tvCuesSeen) || waited > 15_000) {
                tvHandOffSince = 0
                tvCuesSeen = -1
                youtubeToTv(id)
                return
            }
            tvCuesSeen = count
            ui.postDelayed(this, 1000)
        }
    }

    /** What could be made into a clean copy for the TV here, or why nothing can. */
    private fun cleanSource(): Pair<com.safewatch.app.tv.CleanSource?, String?> {
        val title = label.ifEmpty { pageTitle }.ifEmpty { "Video" }
        if (playingYoutube != null) return null to "YouTube videos cannot be saved."
        val captions = pageVideoTracks + listOfNotNull(cuesFile())
        playingFile?.let { return com.safewatch.app.tv.CleanSource(title, pageKey, it, captions) to null }
        Services.forUrl(web.url)?.takeIf { it.protectedVideo }?.let { return null to "${it.name} locks its videos so they cannot be saved." }
        val src = pageVideoSrc
        val referrer = web.url.orEmpty()
        // A whole video file.
        if (src.startsWith("http") && com.safewatch.app.tv.CleanSource.streamKind(src).isEmpty() && com.safewatch.app.tv.CleanSource.isWholeFile(src)) {
            return com.safewatch.app.tv.CleanSource(title, pageKey, src, captions, referrer) to null
        }
        // A stream in pieces: the video's own manifest, or the one the page's player loaded.
        com.safewatch.app.tv.CleanSource.streamKind(src).takeIf { it.isNotEmpty() }?.let { kind ->
            return com.safewatch.app.tv.CleanSource(title, pageKey, src, captions, referrer, kind) to null
        }
        val manifest = synchronized(pageManifests) { pageManifests.lastOrNull { it.master } ?: pageManifests.lastOrNull() }
        if (manifest != null) {
            manifest.refusal?.let { return null to it }
            return com.safewatch.app.tv.CleanSource(title, pageKey, manifest.address, captions, referrer, manifest.kind) to null
        }
        if (src.isEmpty()) return null to "Start the video first, then tap Send to TV."
        return null to "The video's source could not be found on this page. Start it playing, then try again."
    }

    /** The caption lines the page script read for this video, written as a caption file for the clean copy. */
    private fun cuesFile(): String? {
        val lines = synchronized(pageCues) { pageCues.values.sortedBy { it.first } }
        if (lines.isEmpty()) return null
        fun stamp(ms: Long): String {
            val t = ms.coerceAtLeast(0)
            return String.format(java.util.Locale.US, "%02d:%02d:%02d.%03d", t / 3_600_000, t / 60_000 % 60, t / 1000 % 60, t % 1000)
        }
        val file = java.io.File(cacheDir, "clean-captions-${System.currentTimeMillis()}.vtt")
        file.writeText("WEBVTT\n\n" + lines.joinToString("\n\n") { (start, end, text) -> "${stamp(start)} --> ${stamp(end)}\n$text" } + "\n")
        return file.absolutePath
    }

    // ---- Pop-ups and redirects ----
    //
    // While a video is playing nothing may take the viewer off its page: no new windows, no message
    // boxes, no move to another site. Elsewhere a move to another site is allowed when the viewer
    // tapped a link to it, when it is part of signing in, or when it happens as a page first loads.
    // A move a page makes by itself, or on a tap that was not on a link while a video is on the
    // page, is refused, with a bar offering to go there after all.

    private fun inVideo(): Boolean = playerView || (videoIsPlaying() && lastWidthShare >= 0.3)

    /** The address of the link under the viewer's last touch, if it was on a link. */
    private fun tappedLink(view: WebView): String? {
        val hit = view.hitTestResult
        val onLink = hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE || hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
        return hit.extra?.takeIf { onLink && it.startsWith("http") }
    }

    /** The part of an address that says whose site it is: "play.hbomax.com" and "auth.hbomax.com" are both "hbomax.com". */
    private fun siteOf(url: String?): String {
        val host = (Uri.parse(url.orEmpty()).host ?: "").lowercase().removePrefix("www.")
        val parts = host.split('.')
        if (parts.size <= 2) return host
        val twoPart = parts[parts.size - 1].length == 2 && parts[parts.size - 2] in setOf("co", "com", "org", "net", "gov", "ac", "edu")
        return parts.takeLast(if (twoPart) 3 else 2).joinToString(".")
    }

    private fun isUnwantedRedirect(view: WebView, request: WebResourceRequest): Boolean {
        if (!Prefs.blockPopups(this)) return false
        val to = request.url.toString()
        if (to == allowOnce) {
            allowOnce = null
            return false
        }
        val from = view.url ?: return false
        if (!from.startsWith("http")) return false // the start page, or a page of the app's own
        val ownPlayer = from.startsWith(APP_ORIGIN)
        if (!ownPlayer && siteOf(from) == siteOf(to)) return false
        if (!ownPlayer) {
            if (request.isRedirect) return false // a further hop of a move already allowed
            if (signingInTo != null || Accounts.looksLikeSignIn(to) || Accounts.looksLikeSignIn(from)) return false
            if (Services.forUrl(to) != null) return false
        }
        if (ownPlayer || inVideo()) return true
        if (!request.hasGesture()) return SystemClock.elapsedRealtime() - pageStartedAt > 4000
        // The viewer tapped. A tap on a link goes where the link says. A tap on a page with a video on it
        // that sends the browser elsewhere is the page's doing, not the viewer's.
        val videoOnPage = SystemClock.elapsedRealtime() - lastStateAt < 1500
        return videoOnPage && tappedLink(view) == null
    }

    private fun blocked(what: String, url: String) {
        val where = Uri.parse(url).host ?: ""
        note("blocked a $what${if (where.isEmpty()) "" else " to $where"}")
        if (playerView || !url.startsWith("http")) return
        // Outside the player a bar says what happened and offers to go there after all.
        val bar = blockedBar ?: TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            background = Ui.rounded(Color.argb(235, 40, 40, 44), Ui.dp(context, 14).toFloat())
            setPadding(Ui.dp(context, 16), Ui.dp(context, 10), Ui.dp(context, 16), Ui.dp(context, 10))
            stage.addView(this, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = Ui.dp(context, 14)
            })
            blockedBar = this
        }
        bar.text = "Blocked a $what to $where. Tap to open it."
        bar.visibility = View.VISIBLE
        bar.setOnClickListener {
            bar.visibility = View.GONE
            allowOnce = url
            load(url)
        }
        ui.removeCallbacks(hideBlockedBar)
        ui.postDelayed(hideBlockedBar, 6000)
    }

    private val hideBlockedBar = Runnable { blockedBar?.visibility = View.GONE }

    // ---- What the page script can ask (called on a background thread) ----

    private inner class Bridge {
        @JavascriptInterface
        fun version(): Int = version.get()

        @JavascriptInterface
        fun config(): String {
            val tags = JSONArray()
            for (t in FilterEngine(TagStore.load(applicationContext, pageKey), settings).activeTags) {
                tags.put(JSONObject().put("s", t.startMs).put("e", t.endMs).put("a", t.action.name.lowercase()))
            }
            return JSONObject()
                .put("version", version.get())
                .put("language", settings.language != Strictness.OFF)
                .put("showCaptions", Prefs.showCaptions(applicationContext))
                .put("strict", Prefs.silentWithoutCaptions(applicationContext))
                .put("tags", tags)
                .toString()
        }

        /**
         * Given caption lines as `[[startMs, endMs, "text"], ...]`, answers with every stretch to
         * mute, as `[[startMs, endMs], ...]`.
         */
        @JavascriptInterface
        fun cueWindows(lines: String): String {
            val out = JSONArray()
            try {
                val given = JSONArray(lines)
                val cues = ArrayList<com.safewatch.core.Cue>(given.length())
                for (i in 0 until given.length()) {
                    val line = given.getJSONArray(i)
                    cues += com.safewatch.core.Cue(line.getLong(0), line.getLong(1), line.getString(2))
                }
                for (t in CueTagger.tagsFor(cues, matcher)) out.put(JSONArray().put(t.startMs).put(t.endMs))
            } catch (e: Throwable) {
                // Said in the filter report: a failure here would otherwise go unnoticed.
                this@BrowserActivity.note("could not work out when to mute: $e")
            }
            return out.toString()
        }

        /**
         * Given a whole caption file the page's player downloaded, in any of the usual formats, answers
         * with its lines as `[[startMs, endMs, "text"], ...]`. Answers `[]` for anything that is not one.
         */
        @JavascriptInterface
        fun captionCues(file: String): String {
            val out = JSONArray()
            if (!CaptionFormats.recognises(file)) return out.toString()
            val cues = try { CaptionFormats.parse(file) } catch (e: Throwable) {
                this@BrowserActivity.note("could not read a caption file: $e")
                emptyList()
            }
            for (c in cues) out.put(JSONArray().put(c.startMs).put(c.endMs).put(c.text))
            return out.toString()
        }

        /** Where the filtered words sit in a piece of text, as `[[start, end], ...]` counted in letters. */
        @JavascriptInterface
        fun spans(text: String): String {
            val out = JSONArray()
            for (m in matcher.find(text)) out.put(JSONArray().put(m.start).put(m.end))
            return out.toString()
        }

        /**
         * What the language filter has to go on for the video being watched: "ahead" (caption lines
         * with times, [lines] of them so far), "screen" (captions as they appear) or "none".
         */
        @JavascriptInterface
        fun captionState(kind: String, lines: Int) {
            if (kind != captionKind) FilterLog.add("captions for this video: $kind${if (lines > 0) " ($lines lines so far)" else ""}  [${pageKey.take(70)}]")
            captionKind = kind
            captionLines = lines
            captionKindAt = SystemClock.elapsedRealtime()
        }

        /**
         * Sent several times a second while a video is playing, with its position and how
         * much of the page's width it fills (0 for a video playing without sound, such as
         * a preview on a title's page).
         */
        @JavascriptInterface
        fun beat(positionMs: Double, widthShare: Double) {
            lastPositionMs = positionMs.toLong()
            lastWidthShare = widthShare
            lastBeatAt = SystemClock.elapsedRealtime()
        }

        /** Sent several times a second for the page's main video, playing or not, to keep the app's controls in step. */
        @JavascriptInterface
        fun state(positionMs: Double, lengthMs: Double, paused: Boolean, advert: Boolean) {
            videoPositionMs = positionMs.toLong()
            videoLengthMs = lengthMs.toLong()
            videoPaused = paused
            advertPlaying = advert
            lastStateAt = SystemClock.elapsedRealtime()
        }

        /** The latest press on the app's controls that the page has not yet carried out; empty when there is none. */
        @JavascriptInterface
        fun command(): String {
            val command = pendingCommand
            pendingCommand = ""
            return command
        }

        /** A line for the phone's log each time the filter acts, so its work can be checked afterwards. */
        @JavascriptInterface
        fun note(text: String) = this@BrowserActivity.note(text)

        /** The address of the video being watched, and of English caption files given with it (as a JSON list). */
        @JavascriptInterface
        fun source(address: String, captions: String) {
            pageVideoSrc = address
            pageVideoTracks = try {
                val list = JSONArray(captions)
                (0 until list.length()).map { list.getString(it) }.filter { it.startsWith("http") }
            } catch (e: Exception) { emptyList() }
        }

        /** A stream's manifest the page loaded: kept, with whether it may be saved, for Send to TV. */
        @JavascriptInterface
        fun manifest(kind: String, address: String, text: String) {
            if (!address.startsWith("http")) return
            val master = kind == com.safewatch.app.tv.CleanSource.DASH || com.safewatch.core.StreamInfo.hlsIsMaster(text)
            val refusal = com.safewatch.core.StreamInfo.refusal(text)
            synchronized(pageManifests) {
                pageManifests.removeAll { it.address == address }
                pageManifests += PageManifest(kind, address, master, refusal)
                while (pageManifests.size > 12) pageManifests.removeAt(0)
            }
        }

        /** Caption lines with times, as `[[startMs, endMs, "text"], ...]`, kept for a clean copy made for the TV. */
        @JavascriptInterface
        fun copyCues(lines: String) {
            try {
                val list = JSONArray(lines)
                synchronized(pageCues) {
                    for (i in 0 until list.length()) {
                        val line = list.getJSONArray(i)
                        val start = line.getLong(0)
                        val text = line.getString(2)
                        if (pageCues.size < 30_000) pageCues["$start|$text"] = Triple(start, line.getLong(1), text)
                    }
                }
            } catch (e: Exception) {
                // Not lines after all.
            }
        }

        /** True while the player view is up, so the page should let its video fill the screen. */
        @JavascriptInterface
        fun fullPicture(): Boolean = wantsFullPicture
    }

    private fun videoIsPlaying(): Boolean = SystemClock.elapsedRealtime() - lastBeatAt < 1000

    // ---- Marking a scene by hand ----

    private fun onMarkTapped() {
        if (!videoIsPlaying()) {
            Ui.toast(this, "Play a video first, then mark where the scene starts")
            return
        }
        val start = markStartMs
        if (start == null) {
            markStartMs = lastPositionMs
            setMarkLabel(MARK_END)
            return
        }
        val end = lastPositionMs
        markStartMs = null
        setMarkLabel(MARK_START)
        if (end <= start) {
            Ui.toast(this, "The end has to come after the start")
            return
        }
        SceneDialog.show(this, start, end) { tag ->
            TagStore.add(this, pageKey, pageTitle, tag)
            version.incrementAndGet()
            Ui.toast(this, "Scene saved for this page")
        }
    }

    // ---- Nudity: looking ahead, and watching live ----
    //
    // Two things decide whether the picture is hidden.
    //
    // Looking ahead. Where the picture can be read, a hidden second copy of the
    // video (Scout) plays a few seconds in front. What it finds is known before
    // the viewer gets there, so the curtain goes up before a scene starts and
    // comes down after it ends.
    //
    // Watching live. Several times a second a small copy of the picture on
    // screen is checked. This covers whatever has not been looked at ahead: the
    // first moments of a video, the moments after a jump, and pages where a
    // second copy cannot be made. It goes on at a slower pace even where the
    // look-ahead has been, as a second opinion.
    //
    // The curtain is a separate window, so the copies taken from the screen go
    // on showing the real picture underneath it. Protected streams copy as
    // black frames, so they are never detected by either.

    private val watch = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (hidden && now >= hiddenUntil) {
                hidden = false
                Log.i("SafeWatch", "filter: picture shown again on $pageKey")
            }
            refreshOwnControls()
            refreshLanguageStatus(now)
            // In watch mode, a video that plays with sound across most of the page for a moment is the feature: show it as a player.
            if (watchMode && !playerView) {
                playingChecks = if (videoIsPlaying() && lastWidthShare >= 0.6) playingChecks + 1 else 0
                if (playingChecks >= 3) setPlayerView(true)
            }
            val videoOnPage = now - lastStateAt < 1000
            val detecting = detector != null && settings.nudity != Strictness.OFF
            lookAhead(detecting, videoOnPage)
            if (!hidden && !aheadHidden && curtain.isUp) curtain.drop()
            if (detecting && (videoOnPage || videoIsPlaying() || hidden || aheadHidden)) {
                // Checked whenever the page has a video on it, playing or paused, since a paused picture is
                // still on screen. Where the look-ahead has already been, a live check now and then is enough.
                val known = scout?.ahead?.covers(videoPositionMs) == true
                val due = !checking && (!known || now - lastLiveCheckAt >= SECOND_OPINION_MS)
                if (due || hidden || aheadHidden) look(due)
            }
            ui.postDelayed(this, CHECK_EVERY_MS)
        }
    }

    /** What the hidden copy should be showing for the page as it is now; null when there is nothing it could look ahead in. */
    private fun scoutPage(): Triple<String?, String?, String>? {
        if (!Prefs.lookAhead(this) || pageKey == scoutGaveUpOn) return null
        if (videoLengthMs <= 0 || advertPlaying) return null // nothing playing yet, or a live broadcast with no "ahead"
        playingYoutube?.let { return Triple(null, youtubePage(it, hiddenCopy = true), APP_ORIGIN) }
        playingFile?.let { return Triple(null, filePage(it, hiddenCopy = true), APP_ORIGIN) }
        // Any other website: a second copy of the page itself, once its video is clearly what is being watched.
        val url = web.url ?: return null
        if (!url.startsWith("http") || Services.forUrl(url)?.protectedVideo == true) return null
        if (!Scout.canSilenceAnyPage || !(playerView || (videoIsPlaying() && lastWidthShare >= 0.6))) return null
        return Triple(url, null, url)
    }

    private fun lookAhead(detecting: Boolean, videoOnPage: Boolean) {
        var sc = scout
        if (sc != null && sc.running && (scoutKey != pageKey || !detecting || !Prefs.lookAhead(this))) {
            // A different page, or the viewer switched it off: what the hidden copy knew no longer applies.
            sc.stop()
            aheadHidden = false
        }
        if (!detecting || !videoOnPage) return
        if (sc == null || !sc.running) {
            val page = scoutPage() ?: return
            sc = scout ?: Scout(this, pageScript).also { scout = it }
            scoutKey = pageKey
            scoutDoubts = 0
            if (!sc.start(page.first, page.second, page.third, web.settings.userAgentString)) {
                scoutGaveUpOn = pageKey
                return
            }
        }
        if (sc.lostFor(SCOUT_PATIENCE_MS)) {
            Log.i("SafeWatch", "look-ahead: the hidden copy never found the video on $pageKey; watching live only")
            scoutGaveUpOn = pageKey
            sc.stop()
            aheadHidden = false
            return
        }
        sc.follow(videoPositionMs, videoLengthMs)
        detector?.let { sc.sample(it, background, Prefs.testingBlur(this)) }
        val level = sc.ahead.levelAt(videoPositionMs)
        val hide = level > 0 && settings.nudity.filters(level)
        if (hide != aheadHidden) {
            aheadHidden = hide
            Log.i("SafeWatch", if (hide) "filter: picture hidden ahead of time (level $level) at ${videoPositionMs / 1000}s on $pageKey"
                else "filter: scene over, picture shown again at ${videoPositionMs / 1000}s on $pageKey")
        }
    }

    /**
     * Takes a small copy of the picture on screen. While the picture is hidden the copy becomes the
     * curtain's moving blur. With [detect] it is also checked for nudity.
     */
    private fun look(detect: Boolean) {
        val det = detector ?: return
        if (copying || stage.width == 0 || stage.height == 0) return
        val at = IntArray(2)
        stage.getLocationInWindow(at)
        val area = Rect(at[0], at[1], at[0] + stage.width, at[1] + stage.height)
        val testing = Prefs.testingBlur(this)
        val scale = (if (testing) 640f else 320f) / maxOf(stage.width, stage.height)
        val frame = Bitmap.createBitmap(
            (stage.width * scale).toInt().coerceAtLeast(1),
            (stage.height * scale).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        copying = true
        val startedAt = SystemClock.elapsedRealtime()
        val copiedAt = videoPositionMs
        val curtainWasUp = hidden || aheadHidden
        try {
            PixelCopy.request(window, area, frame, { result ->
                copying = false
                if (result != PixelCopy.SUCCESS || background.isShutdown) {
                    if (testing) Log.i("SafeWatch", "blur test: the screen could not be read (code $result)")
                    return@request
                }
                if (isDestroyed || !resumed) return@request
                // While hidden, each new copy refreshes the curtain, so the blur moves with the video.
                if (hidden || aheadHidden) curtain.show(frame)
                if (!detect || checking) return@request
                checking = true
                lastLiveCheckAt = startedAt
                background.execute {
                    val level = try { det.maxLevel(frame, testing) } catch (e: Exception) { 0 }
                    val sketch = Scout.sketchOf(frame)
                    if (testing) {
                        // While testing, say what the detector was shown and what it made of it. "Detail" is how
                        // much neighbouring dots differ along the middle row: a real picture scores well above a
                        // blurred one, which shows whether the detector saw through the curtain.
                        val y = frame.height / 2
                        var detail = 0
                        for (x in 1 until frame.width) {
                            detail += kotlin.math.abs(((frame.getPixel(x, y) shr 8) and 0xFF) - ((frame.getPixel(x - 1, y) shr 8) and 0xFF))
                        }
                        Log.i("SafeWatch", "blur test: looked at ${frame.width}x${frame.height}, curtain ${if (curtainWasUp) "up" else "down"}, " +
                            "detail ${detail * 10 / frame.width / 10.0}, found level $level, took ${SystemClock.elapsedRealtime() - startedAt} ms")
                    }
                    ui.post {
                        checking = false
                        if (isDestroyed || !resumed) return@post
                        if (level > 0 && settings.nudity.filters(level)) {
                            if (!hidden) Log.i("SafeWatch", "filter: picture hidden (level $level) on $pageKey")
                            hidden = true
                            // Held until well after the next look can report, however long a look takes on this phone.
                            val took = SystemClock.elapsedRealtime() - startedAt
                            hiddenUntil = SystemClock.elapsedRealtime() + maxOf(HOLD_MS, took * 2 + 500)
                            curtain.show(frame)
                        }
                        doubtScout(copiedAt, sketch, testing)
                    }
                }
            }, ui)
        } catch (e: IllegalArgumentException) {
            copying = false // the window is not ready to be copied yet
        }
    }

    /**
     * A check on the look-ahead itself. Each live look at the viewer's picture is compared with what
     * the hidden copy showed at the same moment. If they are plainly different pictures several times
     * running, the hidden copy is not seeing the same video (some phones may not draw video on a hidden
     * screen, and some sites give a second visitor something else), and nothing it reported can be
     * relied on. It is stopped and live watching carries on alone.
     */
    private fun doubtScout(positionMs: Long, sketch: IntArray?, testing: Boolean) {
        val sc = scout ?: return
        if (!sc.running) return
        val agrees = sc.agreesWith(positionMs, sketch) ?: return
        if (testing) Log.i("SafeWatch", "look-ahead: at ${positionMs / 100 / 10.0}s the hidden copy and the viewer's picture ${if (agrees) "match" else "differ"}")
        scoutDoubts = if (agrees) 0 else scoutDoubts + 1
        if (scoutDoubts >= 4) {
            Log.i("SafeWatch", "look-ahead: the hidden copy is not showing what the viewer sees; stopped, watching live only")
            scoutGaveUpOn = pageKey
            sc.stop()
            aheadHidden = false
        }
    }

    companion object {
        /** Stops the browser telling every site the app's package name, as WebView otherwise may. */
        fun hideAppName(view: WebView) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                androidx.webkit.WebSettingsCompat.setRequestedWithHeaderOriginAllowList(view.settings, emptySet())
            }
        }

        const val EXTRA_URL = "url"
        const val EXTRA_SIGN_IN = "signIn"
        const val EXTRA_LABEL = "label"
        /** Set to send the YouTube video straight to the TV's YouTube app once its captions are read. */
        const val EXTRA_TO_TV = "toTv"
        private const val PLAYER_BAR_MS = 3500L
        private const val APP_ORIGIN = "https://com.safewatch.app"
        private val YOUTUBE_PLAYER = Regex("^safewatch://youtube/([A-Za-z0-9_-]{6,20})$")
        private val YOUTUBE_VIDEO = Regex("(?:youtube(?:-nocookie)?\\.com/(?:watch\\?(?:[^#]*&)?v=|shorts/|embed/|live/)|youtu\\.be/)([A-Za-z0-9_-]{11})")
        private val DIRECT_VIDEO = Regex("^https?://[^?#]+\\.(mp4|m4v|webm|mov|ogv)([?#].*)?$", RegexOption.IGNORE_CASE)
        const val START_PAGE = "file:///android_asset/start.html"
        /** Where typed words are searched; set from Settings. */
        @Volatile var searchPrefix = "https://www.google.com/search?q="
        private const val MARK_START = "Mark scene"
        private const val MARK_END = "End scene"
        private const val CHECK_EVERY_MS = 250L
        private const val HOLD_MS = 1500L
        private const val SECOND_OPINION_MS = 1500L
        private const val SCOUT_PATIENCE_MS = 25000L

        /** Shows the browser on the given page, reusing the one already open. */
        fun open(ctx: Context, urlOrSearch: String) = ctx.startActivity(
            Intent(ctx, BrowserActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(EXTRA_URL, toUrl(urlOrSearch))
        )

        /** Shows the browser where it was left. */
        fun resume(ctx: Context) = ctx.startActivity(
            Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        )

        /** True for "example.com" or a full link; false for ordinary search words. */
        fun looksLikeAddress(text: String): Boolean {
            val t = text.trim()
            return t.startsWith("http://") || t.startsWith("https://") || (!t.contains(' ') && t.contains('.'))
        }

        fun toUrl(text: String): String {
            val t = text.trim()
            return when {
                t.startsWith("http://") || t.startsWith("https://") -> t
                looksLikeAddress(t) -> "https://$t"
                else -> searchPrefix + android.net.Uri.encode(t)
            }
        }
    }
}
