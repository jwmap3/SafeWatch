package com.safewatch.app.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Presentation
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.safewatch.app.data.Services
import com.safewatch.app.data.Accounts
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors

/**
 * The viewer's own YouTube, read from YouTube's website in a browser nobody sees.
 *
 * Signed in once (on Google's own page, in the app's browser), the app's browser
 * holds the viewer's YouTube sign-in like any browser does. This opens YouTube's
 * website with that sign-in on a screen that exists only in memory, and hands the
 * app what the website is given to draw: the viewer's home feed, subscriptions,
 * search results, what to watch next and comments. The app shows those in its own
 * layout. Videos are never played here (their data is refused, and pictures are not
 * loaded); they play in the app's player.
 *
 * Everything here is called on the main thread, and answers arrive on it.
 */
class YouTubeMirror(private val activity: Activity) {

    /** One piece of what YouTube's website was given: [kind] is "initial", "browse", "next" or "search". */
    class Answer(val kind: String, val json: JSONObject)

    private val ui = Handler(Looper.getMainLooper())
    private val parse = Executors.newSingleThreadExecutor()
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var screen: Presentation? = null
    private var web: WebView? = null
    private var frames: HandlerThread? = null
    private var listener: ((Answer) -> Unit)? = null
    private var visit = 0

    /** Opens a YouTube address. [onAnswer] receives each piece of what the page is given until the next [open] or [close]. */
    fun open(url: String, onAnswer: (Answer) -> Unit): Boolean {
        val view = web ?: start() ?: return false
        visit++
        listener = onAnswer
        view.loadUrl(url)
        return true
    }

    /** Scrolls the hidden page to its end, so YouTube loads more of the list. */
    fun more() {
        web?.evaluateJavascript("window.__mirrorMore && window.__mirrorMore()", null)
    }

    /** Scrolls the hidden page to the comments, so YouTube loads them. */
    fun comments() {
        web?.evaluateJavascript("window.__mirrorComments && window.__mirrorComments()", null)
    }

    fun close() {
        listener = null
        val closing = web
        web = null
        try { screen?.dismiss() } catch (e: Exception) { /* already gone */ }
        screen = null
        closing?.destroy()
        display?.release()
        display = null
        reader?.close()
        reader = null
        frames?.quitSafely()
        frames = null
        parse.shutdown()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun start(): WebView? {
        if (parse.isShutdown) return null
        return try {
            val thread = HandlerThread("SafeWatch YouTube").apply { start() }
            // The hidden screen draws into this; its pictures are thrown away as they come.
            val newReader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2)
            newReader.setOnImageAvailableListener({ r -> try { r.acquireLatestImage()?.close() } catch (e: Exception) { /* none */ } }, Handler(thread.looper))
            val newDisplay = activity.getSystemService(DisplayManager::class.java).createVirtualDisplay(
                "SafeWatch YouTube", WIDTH, HEIGHT, 160, newReader.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            )
            val newScreen = Presentation(activity, newDisplay.display)
            val view = WebView(newScreen.context)
            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                blockNetworkImage = true
                mediaPlaybackRequiresUserGesture = true
                userAgentString = DESKTOP_AGENT
                setSupportMultipleWindows(true) // and no window is ever given
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO)) WebViewCompat.setAudioMuted(view, true)
            BrowserActivity.hideAppName(view)
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
            view.addJavascriptInterface(Bridge(), "MirrorBridge")
            val script = activity.assets.open("mirror.js").bufferedReader().use { it.readText() }
            val atStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
            if (atStart) WebViewCompat.addDocumentStartJavaScript(view, script, setOf("https://www.youtube.com"))
            view.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean =
                    request.url.host?.endsWith("youtube.com") != true // stays on YouTube

                override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? {
                    // The videos themselves are never downloaded here.
                    val host = request.url.host.orEmpty()
                    if (host.endsWith("googlevideo.com") || request.url.path.orEmpty().startsWith("/api/stats/")) {
                        return WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    }
                    return null
                }

                override fun onPageFinished(v: WebView, url: String) {
                    if (!atStart) v.evaluateJavascript(script, null)
                }
            }
            newScreen.setContentView(view)
            newScreen.show()
            frames = thread
            reader = newReader
            display = newDisplay
            screen = newScreen
            web = view
            view
        } catch (e: Exception) {
            Log.i("SafeWatch", "YouTube mirror could not start: $e")
            null
        }
    }

    /** What the hidden page hands over. Called on the page's own thread. */
    private inner class Bridge {
        @JavascriptInterface
        fun answer(kind: String, text: String) {
            val forVisit = visit
            if (parse.isShutdown) return
            parse.execute {
                val json = try { JSONObject(text) } catch (e: Exception) { return@execute }
                ui.post { if (forVisit == visit) listener?.invoke(Answer(kind, json)) }
            }
        }
    }

    companion object {
        private const val WIDTH = 1280
        private const val HEIGHT = 2400
        private const val DESKTOP_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        const val HOME = "https://www.youtube.com/"
        const val SUBSCRIPTIONS = "https://www.youtube.com/feed/subscriptions"

        fun search(query: String) = "https://www.youtube.com/results?search_query=" + android.net.Uri.encode(query)
        fun watch(videoId: String) = "https://www.youtube.com/watch?v=$videoId"

        /** Whether the app's browser holds a YouTube sign-in. */
        fun signedIn(activity: Activity): Boolean {
            val youtube = Services.byId("youtube") ?: return false
            return Accounts.isSignedIn(activity, youtube)
        }
    }
}
