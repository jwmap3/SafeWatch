package com.safewatch.app.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
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
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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
class BrowserActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var stage: FrameLayout
    private lateinit var cover: View
    private lateinit var address: EditText
    private lateinit var markButton: TextView
    private lateinit var chrome: List<View>

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
    private var signingInTo: Service? = null
    private var sawSignInPage = false
    private var scriptAtDocumentStart = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = buildLayout()
        setContentView(root)
        Ui.fitSystemBars(this, root)

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
        requestedUrl(intent)?.let { load(it) }
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

    private fun buildLayout(): View {
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
        cover = View(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        stage = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(web, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        // The cover sits beside the stage, not inside it, so it stays sharp while the stage is blurred.
        val stageHolder = FrameLayout(this).apply {
            addView(stage, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(cover, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        markButton = Ui.pill(this, MARK_START, filled = false) { onMarkTapped() }
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
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(watch)
        // Nothing should keep playing unfiltered behind another screen.
        web.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(m){m.pause()})", null)
        web.onPause()
        web.url?.let { if (it.startsWith("http")) Prefs.setLastPage(this, it) }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
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
            web.canGoBack() -> web.goBack()
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
        web.settings.userAgentString = agentFor(url)
        web.loadUrl(url)
    }

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
            // Moving to a site that needs the other kind of page: ask again the right way.
            if (request.isForMainFrame && agentFor(url) != view.settings.userAgentString) {
                load(url)
                return true
            }
            return false
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            pageKey = MediaKey.forUrl(url)
            version.incrementAndGet()
            noteSignIn(url)
            if (!address.hasFocus()) address.setText(if (url == START_PAGE) "" else url)
            if (markStartMs != null) {
                markStartMs = null
                markButton.text = MARK_START
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
            chrome.forEach { it.visibility = View.GONE }
        }

        override fun onHideCustomView() {
            fullscreenView?.let { stage.removeView(it) }
            fullscreenView = null
            fullscreenCallback?.onCustomViewHidden()
            fullscreenCallback = null
            chrome.forEach { it.visibility = View.VISIBLE }
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

        /** Sent several times a second while a video is playing, with its position. */
        @JavascriptInterface
        fun beat(positionMs: Double) {
            lastPositionMs = positionMs.toLong()
            lastBeatAt = SystemClock.elapsedRealtime()
        }
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
            markButton.text = MARK_END
            return
        }
        val end = lastPositionMs
        markStartMs = null
        markButton.text = MARK_START
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
    // Several times a second, while a video plays, a small copy of the browser
    // area is checked. A hit blurs the whole area for HOLD_MS. The copy is taken
    // of what is on screen, so a blurred picture cannot be checked: when the
    // hold ends the blur lifts and the next check follows at once, which can let
    // a brief glimpse through if the scene is still going. Protected streams
    // copy as black frames, so they are never detected here.

    private val watch = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (hidden && now >= hiddenUntil) setHidden(false)
            if (!hidden && !checking && detector != null && settings.nudity != Strictness.OFF && videoIsPlaying()) check()
            ui.postDelayed(this, CHECK_EVERY_MS)
        }
    }

    private fun setHidden(on: Boolean) {
        hidden = on
        Ui.setHidden(stage, cover, on)
    }

    private fun check() {
        val det = detector ?: return
        if (stage.width == 0 || stage.height == 0) return
        val at = IntArray(2)
        stage.getLocationInWindow(at)
        val area = Rect(at[0], at[1], at[0] + stage.width, at[1] + stage.height)
        val scale = 320f / maxOf(stage.width, stage.height)
        val frame = Bitmap.createBitmap(
            (stage.width * scale).toInt().coerceAtLeast(1),
            (stage.height * scale).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        checking = true
        try {
            PixelCopy.request(window, area, frame, { result ->
                if (result != PixelCopy.SUCCESS || background.isShutdown) {
                    checking = false
                    return@request
                }
                background.execute {
                    val level = try { det.maxLevel(frame) } catch (e: Exception) { 0 }
                    ui.post {
                        checking = false
                        if (level > 0 && settings.nudity.filters(level)) {
                            hiddenUntil = SystemClock.elapsedRealtime() + HOLD_MS
                            setHidden(true)
                        }
                    }
                }
            }, ui)
        } catch (e: IllegalArgumentException) {
            checking = false // the window is not ready to be copied yet
        }
    }

    companion object {
        const val EXTRA_URL = "url"
        private const val EXTRA_SIGN_IN = "signIn"
        const val START_PAGE = "file:///android_asset/start.html"
        const val WEB_SEARCH = "https://duckduckgo.com/?q="
        private const val MARK_START = "Mark scene"
        private const val MARK_END = "End scene"
        private const val CHECK_EVERY_MS = 250L
        private const val HOLD_MS = 4000L

        /** Shows the browser on the given page, reusing the one already open. */
        fun open(ctx: Context, urlOrSearch: String) = ctx.startActivity(
            Intent(ctx, BrowserActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(EXTRA_URL, toUrl(urlOrSearch))
        )

        /** Opens a service's sign-in page. The sign-in is remembered by the browser from then on. */
        fun signIn(ctx: Context, service: Service) = ctx.startActivity(
            Intent(ctx, BrowserActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(EXTRA_URL, service.signInUrl)
                .putExtra(EXTRA_SIGN_IN, service.id)
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
