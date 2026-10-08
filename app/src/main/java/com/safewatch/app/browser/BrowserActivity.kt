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
import com.safewatch.app.data.Prefs
import com.safewatch.app.data.Service
import com.safewatch.app.data.Services
import com.safewatch.app.data.TagStore
import com.safewatch.app.detect.NudityDetector
import com.safewatch.app.ui.SceneDialog
import com.safewatch.app.ui.Ui
import com.safewatch.core.Action
import com.safewatch.core.Cue
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
 * The built-in browser. Every page gets the SafeWatch script (assets/safewatch.js),
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
    private var hidden = false
    private var hiddenUntil = 0L
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
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        mobileAgent = web.settings.userAgentString
        web.addJavascriptInterface(Bridge(), "SafeWatchBridge")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            // Runs in every frame before the page's own scripts, so embedded players are covered too.
            WebViewCompat.addDocumentStartJavaScript(web, pageScript, setOf("*"))
            scriptAtDocumentStart = true
        }
        web.webViewClient = Client()
        web.webChromeClient = Chrome()

        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) {
            load(requestedUrl(intent) ?: Prefs.lastPage(this) ?: START_PAGE)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (handOffToPlayer(intent)) return
        requestedUrl(intent)?.let { load(it) }
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
     * from another app, or a link shared to SafeWatch from another browser.
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
            hint = "Search or enter a website"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setSelectAllOnFocus(true)
            setTextColor(Ui.color(context, R.color.text))
            setHintTextColor(Ui.color(context, R.color.text_secondary))
            background = Ui.rounded(Ui.color(context, R.color.fill), Ui.dp(context, 10).toFloat())
            setPadding(Ui.dp(context, 12), Ui.dp(context, 8), Ui.dp(context, 12), Ui.dp(context, 8))
            setOnEditorActionListener { v, _, _ ->
                go(v.text.toString())
                true
            }
        }
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(context, 4), Ui.dp(context, 6), Ui.dp(context, 4), Ui.dp(context, 6))
            addView(Ui.iconButton(context, R.drawable.ic_home, "Home") { MainActivity.open(context) })
            addView(address, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Ui.iconButton(context, R.drawable.ic_more, "More") { showMenu(it) })
        }

        web = WebView(this)
        stage = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(web, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        curtain = Curtain(stage)
        val stageHolder = stage

        if (watchMode) {
            // A slim bar while browsing to the video. Once it plays, the controls move to a layer
            // of their own over the picture, above the blur, so they can always be seen.
            val bar = watchControls(overPicture = false)
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
            root.addView(bar)
            root.addView(stageHolder, LinearLayout.LayoutParams(-1, 0, 1f))
            chrome = listOf(bar)
            // The layer follows the picture's place on screen, through turning the phone and the bars coming and going.
            stage.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (playerView) layer.showOver(stage) }
            return root
        }

        val markButton = Ui.pill(this, MARK_START, filled = false) { onMarkTapped() }
        markButtons += markButton
        val bottom = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(context, 4), Ui.dp(context, 4), Ui.dp(context, 4), Ui.dp(context, 4))
            addView(Ui.iconButton(context, R.drawable.ic_back, "Back") { if (web.canGoBack()) web.goBack() })
            addView(Ui.iconButton(context, R.drawable.ic_forward, "Forward") { if (web.canGoForward()) web.goForward() })
            addView(Ui.spacer(context))
            addView(markButton)
            addView(Ui.spacer(context))
            addView(Ui.iconButton(context, R.drawable.ic_tv, "Send to TV") { Ui.sendToTv(this@BrowserActivity) })
            addView(Ui.iconButton(context, R.drawable.ic_filters, "Filters") { MainActivity.open(context, MainActivity.TAB_FILTERS) })
        }

        root.addView(top)
        root.addView(Ui.divider(this, 0))
        root.addView(stageHolder, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(Ui.divider(this, 0))
        root.addView(bottom)
        chrome = listOf(top, bottom)
        return root
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
        addView(Ui.iconButton(context, R.drawable.ic_back, "Back", tint) { finish() })
        addView(TextView(context).apply {
            text = label
            textSize = 16f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(if (overPicture) Color.WHITE else Ui.color(context, R.color.text))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val mark = Ui.pill(context, MARK_START, filled = false) { onMarkTapped() }
        if (overPicture) {
            mark.setTextColor(Color.WHITE)
            mark.background = Ui.rounded(Color.argb(70, 255, 255, 255), Ui.dp(context, 18).toFloat())
        }
        markButtons += mark
        addView(mark)
        addView(Ui.iconButton(context, R.drawable.ic_tv, "Send to TV", tint) { Ui.sendToTv(this@BrowserActivity) })
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
            addView(round(label("−10"), 54) { send("skip:-10") }.apply { contentDescription = "Back 10 seconds" })
            addView(round(play, 72) { send(if (videoPaused) "play" else "pause") }.apply { contentDescription = "Play or pause" })
            addView(round(label("+10"), 54) { send("skip:10") }.apply { contentDescription = "Forward 10 seconds" })
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

    // ---- The player view: sideways, full screen, nothing but the picture ----

    private fun setPlayerView(on: Boolean) {
        if (playerView == on) return
        playerView = on
        wantsFullPicture = on
        ViewCompat.requestApplyInsets(root)
        chrome.forEach { it.visibility = if (on) View.GONE else View.VISIBLE }
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

    private fun setMarkLabel(text: String) = markButtons.forEach { it.text = text }

    override fun onResume() {
        super.onResume()
        web.onResume()
        settings = Prefs.settings(this)
        matcher = ProfanityMatcher(settings)
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
        if (playerView) stage.post { playerLayer?.showOver(stage) }
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        ui.removeCallbacks(watch)
        // The floating layers belong to this screen and must not outlive its time in front.
        hidden = false
        curtain.drop()
        playerLayer?.dismiss()
        // Nothing should keep playing unfiltered behind another screen.
        web.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(m){m.pause()})", null)
        web.onPause()
        web.url?.let { if (it.startsWith("http")) Prefs.setLastPage(this, it) }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        if (!::web.isInitialized) {
            // Closed straight away after passing a video link to the player; nothing was set up.
            background.shutdown()
            super.onDestroy()
            return
        }
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
    override fun onBackPressed() {
        when {
            fullscreenView != null -> web.webChromeClient?.onHideCustomView()
            watchMode && playerView -> finish()
            web.canGoBack() -> web.goBack()
            watchMode -> finish()
            else -> MainActivity.open(this)
        }
    }

    // ---- Navigation ----

    private fun go(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
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

    private fun load(url: String) {
        val video = YOUTUBE_PLAYER.find(url)?.groupValues?.get(1)
        if (video != null) {
            // A YouTube video picked in the app plays in YouTube's embedded player, filling the screen.
            // YouTube only plays embedded when it is told which app is asking, hence the app's own address.
            web.settings.userAgentString = mobileAgent
            ownControls = watchMode
            val key = MediaKey.forUrl("https://www.youtube.com/watch?v=$video")
            fixedKey = key
            pageKey = key
            version.incrementAndGet()
            web.loadDataWithBaseURL(APP_ORIGIN, youtubePage(video), "text/html", "utf-8", null)
            showOwnPlayer()
            return
        }
        if (watchMode && DIRECT_VIDEO.containsMatchIn(url)) {
            // A plain video file gets a page with nothing on it but the video, and the app's own controls.
            ownControls = true
            val key = MediaKey.forUrl(url)
            fixedKey = key
            pageKey = key
            version.incrementAndGet()
            val source = url.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
            web.loadDataWithBaseURL(APP_ORIGIN, """
                <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
                <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}video{width:100%;height:100%;object-fit:contain;background:#000}</style>
                </head><body><video src="$source" autoplay playsinline></video></body></html>
            """.trimIndent(), "text/html", "utf-8", null)
            showOwnPlayer()
            return
        }
        fixedKey = null
        ownControls = false
        web.settings.userAgentString = agentFor(url)
        web.loadUrl(url)
    }

    private fun youtubePage(videoId: String): String = """
        <!doctype html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta name="referrer" content="strict-origin-when-cross-origin">
        <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}iframe{position:fixed;top:0;left:0;width:100%;height:100%;border:0}</style>
        </head><body>
        <iframe src="https://www.youtube.com/embed/$videoId?autoplay=1&playsinline=1&cc_load_policy=1&rel=0&iv_load_policy=3&controls=${if (ownControls) 0 else 1}&fs=0&origin=$APP_ORIGIN"
          referrerpolicy="strict-origin-when-cross-origin"
          allow="autoplay; encrypted-media; picture-in-picture; fullscreen" allowfullscreen></iframe>
        </body></html>
    """.trimIndent()

    private fun showMenu(anchor: View) {
        val desktop = Prefs.desktopSite(this)
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Reload")
            menu.add(0, 2, 1, if (desktop) "Mobile site" else "Desktop site")
            menu.add(0, 3, 2, "Send to TV")
            menu.add(0, 4, 3, "Clear marked scenes on this page")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> web.reload()
                    2 -> {
                        Prefs.setDesktopSite(this@BrowserActivity, !desktop)
                        web.url?.let { load(it) }
                    }
                    3 -> Ui.sendToTv(this@BrowserActivity)
                    4 -> {
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
            if (request.isForMainFrame) fixedKey = null
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
            if (!address.hasFocus()) address.setText(if (url == START_PAGE || fixedKey != null) "" else url)
            if (markStartMs != null) {
                markStartMs = null
                setMarkLabel(MARK_START)
            }
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
                .put("tags", tags)
                .toString()
        }

        /** Mute ranges for one caption line, as `[[startMs, endMs], ...]`. */
        @JavascriptInterface
        fun muteWindows(startMs: Double, endMs: Double, text: String): String {
            val out = JSONArray()
            for (t in CueTagger.tagsFor(Cue(startMs.toLong(), endMs.toLong(), text), matcher)) {
                out.put(JSONArray().put(t.startMs).put(t.endMs))
            }
            return out.toString()
        }

        @JavascriptInterface
        fun profane(text: String): Boolean = matcher.containsProfanity(text)

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
        fun note(text: String) {
            Log.i("SafeWatch", "filter: $text on $pageKey")
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

    // ---- Live nudity detection ----
    //
    // Several times a second, while a video plays, a small copy of the picture
    // is taken from the app's main window and checked. A hit puts the curtain
    // up. The curtain is a separate window, so the copies go on showing the
    // real picture underneath: checking continues behind the curtain, and it
    // stays up until HOLD_MS after the last hit, which is to say for as long as
    // the scene lasts. Protected streams copy as black frames, so they are
    // never detected here.

    private val watch = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (hidden && now >= hiddenUntil) {
                hidden = false
                curtain.drop()
                Log.i("SafeWatch", "filter: picture shown again on $pageKey")
            }
            refreshOwnControls()
            // In watch mode, a video that plays with sound across most of the page for a moment is the feature: show it as a player.
            if (watchMode && !playerView) {
                playingChecks = if (videoIsPlaying() && lastWidthShare >= 0.6) playingChecks + 1 else 0
                if (playingChecks >= 3) setPlayerView(true)
            }
            // Checked whenever the page has a video on it, playing or paused, since a paused picture is
            // still on screen, and for as long as the curtain is up.
            val videoOnPage = now - lastStateAt < 1000
            if (!checking && detector != null && settings.nudity != Strictness.OFF && (videoOnPage || videoIsPlaying() || hidden)) check()
            ui.postDelayed(this, CHECK_EVERY_MS)
        }
    }

    private fun check() {
        val det = detector ?: return
        if (stage.width == 0 || stage.height == 0) return
        val at = IntArray(2)
        stage.getLocationInWindow(at)
        val area = Rect(at[0], at[1], at[0] + stage.width, at[1] + stage.height)
        val scale = (if (Prefs.testingBlur(this)) 640f else 320f) / maxOf(stage.width, stage.height)
        val frame = Bitmap.createBitmap(
            (stage.width * scale).toInt().coerceAtLeast(1),
            (stage.height * scale).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        checking = true
        val testing = Prefs.testingBlur(this)
        val startedAt = SystemClock.elapsedRealtime()
        val curtainWasUp = hidden
        try {
            PixelCopy.request(window, area, frame, { result ->
                if (result != PixelCopy.SUCCESS || background.isShutdown) {
                    if (testing) Log.i("SafeWatch", "blur test: the screen could not be read (code $result)")
                    checking = false
                    return@request
                }
                background.execute {
                    val level = try { det.maxLevel(frame, testing) } catch (e: Exception) { 0 }
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
                        }
                        // While hidden, each new copy refreshes the curtain, so the blur moves with the video.
                        if (hidden) curtain.show(frame)
                    }
                }
            }, ui)
        } catch (e: IllegalArgumentException) {
            checking = false // the window is not ready to be copied yet
        }
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_SIGN_IN = "signIn"
        const val EXTRA_LABEL = "label"
        private const val PLAYER_BAR_MS = 3500L
        private const val APP_ORIGIN = "https://com.safewatch.app"
        private val YOUTUBE_PLAYER = Regex("^safewatch://youtube/([A-Za-z0-9_-]{6,20})$")
        private val DIRECT_VIDEO = Regex("^https?://[^?#]+\\.(mp4|m4v|webm|mov|ogv)([?#].*)?$", RegexOption.IGNORE_CASE)
        const val START_PAGE = "file:///android_asset/start.html"
        const val WEB_SEARCH = "https://duckduckgo.com/?q="
        private const val MARK_START = "Mark scene"
        private const val MARK_END = "End scene"
        private const val CHECK_EVERY_MS = 250L
        private const val HOLD_MS = 1500L

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
                else -> WEB_SEARCH + android.net.Uri.encode(t)
            }
        }
    }
}
