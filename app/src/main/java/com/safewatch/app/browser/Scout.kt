package com.safewatch.app.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Presentation
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.safewatch.app.detect.NudityDetector
import com.safewatch.core.LookAhead
import java.io.File
import java.util.TreeMap
import java.util.concurrent.ExecutorService
import kotlin.math.abs

/**
 * Looks ahead in the video being watched.
 *
 * A second copy of the same video plays, silent and unseen, on a screen
 * that exists only in memory, a few seconds in front of the copy the viewer
 * is watching. Its pictures are checked as they go by and the findings are
 * kept in [ahead], so by the time the viewer reaches a scene the player
 * already knows to hide it, and knows where it ends.
 *
 * It works wherever the app can read the picture: YouTube, video files and
 * ordinary websites. The paid streaming services scramble their picture, so
 * a second copy of those would show nothing, and none is started.
 *
 * Everything here is called on the main thread unless it says otherwise.
 */
class Scout(private val activity: Activity, private val pageScript: String) {

    /** What has been found so far, by position in the video. */
    val ahead = LookAhead()

    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var screen: Presentation? = null
    private var web: WebView? = null
    private var frames: HandlerThread? = null
    private val ui = Handler(Looper.getMainLooper())

    // The newest picture from the hidden screen, and when it arrived.
    private val pictureLock = Any()
    private var picture: Image? = null
    private var pictureAt = 0L
    private var pictures = 0

    // Where the hidden copy has got to, as its page last reported (from the page's own thread).
    @Volatile private var position = 0L
    @Volatile private var length = 0L
    @Volatile private var paused = true
    @Volatile private var steady = false
    @Volatile private var advert = false
    @Volatile private var stateAt = 0L
    @Volatile private var pending = ""
    @Volatile private var wantedLength = 0L

    private var rate = 1.0
    private var lastSeekAt = 0L
    private var lastRateAt = 0L
    private var sampling = false
    private var lastSampled = -1L
    private var typicalLookMs = 300L
    private var savedTestPicture = false
    private var startedAt = 0L
    private var saidFollowing = false

    // How bright each checked picture was, to compare with what the viewer's own copy shows.
    private val light = TreeMap<Long, Int>()

    val running: Boolean get() = web != null

    /** How far in front of the viewer the hidden copy tries to stay: longer on a phone that checks slowly. */
    val leadMs: Long get() = (typicalLookMs * 2 + 4000).coerceIn(6000, 30000)

    /**
     * Starts the hidden copy on [url], or on the page [html] when the app wrote the page itself.
     * Returns false when this phone cannot provide the hidden screen.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun start(url: String?, html: String?, baseUrl: String, agent: String): Boolean {
        stop()
        try {
            val thread = HandlerThread("SafeWatch look-ahead").apply { start() }
            val newReader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 3)
            newReader.setOnImageAvailableListener({ source ->
                // Always take the newest picture, so the hidden screen never backs up with old ones.
                val next = try { source.acquireLatestImage() } catch (e: Exception) { null } ?: return@setOnImageAvailableListener
                synchronized(pictureLock) {
                    picture?.close()
                    picture = next
                    pictureAt = SystemClock.elapsedRealtime()
                    pictures++
                }
            }, Handler(thread.looper))
            val manager = activity.getSystemService(DisplayManager::class.java)
            val newDisplay = manager.createVirtualDisplay(
                "SafeWatch look-ahead", WIDTH, HEIGHT, 160, newReader.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            )
            val newScreen = Presentation(activity, newDisplay.display)
            val newWeb = WebView(newScreen.context)
            newWeb.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                useWideViewPort = true
                loadWithOverviewMode = true
                userAgentString = agent
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO)) WebViewCompat.setAudioMuted(newWeb, true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(newWeb, true)
            newWeb.addJavascriptInterface(Bridge(), "SafeWatchBridge")
            val atStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
            if (atStart) WebViewCompat.addDocumentStartJavaScript(newWeb, pageScript, setOf("*"))
            newWeb.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url.scheme.orEmpty()
                    return scheme != "http" && scheme != "https" // never leaves for another app
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (!atStart) view.evaluateJavascript(pageScript, null)
                }
            }
            newScreen.setContentView(newWeb)
            newScreen.show()
            if (html != null) newWeb.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null) else newWeb.loadUrl(url.orEmpty())

            frames = thread
            reader = newReader
            display = newDisplay
            screen = newScreen
            web = newWeb
            startedAt = SystemClock.elapsedRealtime()
            Log.i(TAG, "look-ahead: a hidden copy of the video has been started")
            return true
        } catch (e: Exception) {
            Log.i(TAG, "look-ahead: this phone could not provide a hidden screen ($e)")
            stop()
            return false
        }
    }

    fun stop() {
        val closing = web
        web = null
        try { screen?.dismiss() } catch (e: Exception) { /* already gone */ }
        screen = null
        closing?.let {
            it.stopLoading()
            it.loadUrl("about:blank")
            it.destroy()
        }
        display?.release()
        display = null
        reader?.close()
        reader = null
        frames?.quitSafely()
        frames = null
        synchronized(pictureLock) {
            picture = null // closed with the reader
            pictures = 0
        }
        ahead.clear()
        light.clear()
        stateAt = 0
        lastSampled = -1
        pending = ""
        rate = 1.0
        savedTestPicture = false
        saidFollowing = false
    }

    /** Stops the hidden copy playing while the app is not in front. What it found is kept. */
    fun rest() {
        web?.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(m){m.pause()})", null)
        web?.onPause()
    }

    fun wake() {
        web?.onResume()
    }

    /** True when the hidden copy has been running for [ms] without ever finding the video. */
    fun lostFor(ms: Long): Boolean = running && stateAt == 0L && SystemClock.elapsedRealtime() - startedAt > ms

    private fun usable(now: Long): Boolean =
        now - stateAt < 700 && !advert && (wantedLength <= 0 || length <= 0 || abs(wantedLength - length) < LENGTH_SLACK_MS)

    /**
     * Keeps the hidden copy the right distance in front of the viewer, who is at
     * [mainPositionMs] in a video [mainLengthMs] long. Called a few times a second.
     */
    fun follow(mainPositionMs: Long, mainLengthMs: Long) {
        if (!running) return
        wantedLength = mainLengthMs
        val now = SystemClock.elapsedRealtime()
        if (now - stateAt > 1500) return // its page has not found the video yet
        if (!usable(now)) {
            // An advert, or some other video: let it play through until the right one comes up.
            if (paused && pending.isEmpty()) pending = "play"
            return
        }
        if (!saidFollowing) {
            saidFollowing = true
            Log.i(TAG, "look-ahead: the hidden copy found the same video and is moving ahead of the viewer")
        }
        val lead = leadMs
        val gap = position - mainPositionMs
        val quick = typicalLookMs < 700
        if (now - lastSeekAt > 2500 && (gap < (if (quick) 300 else lead / 3) || gap > lead * 2 + 4000)) {
            // Out of place (at the start, or after the viewer jumped): go to just in front of the viewer.
            // A quick phone starts close and catches the lead up at speed, so nothing in between goes unseen.
            val target = mainPositionMs + (if (quick) 1200 else lead)
            if (mainLengthMs <= 0 || target < mainLengthMs - 1000) {
                pending = "seek:${target / 1000.0}"
                lastSeekAt = now
                return
            }
        }
        val wantedRate = if (quick && gap < lead) FAST else 1.0
        if (wantedRate != rate && now - lastRateAt > 600 && pending.isEmpty()) {
            pending = "rate:$wantedRate"
            rate = wantedRate
            lastRateAt = now
            return
        }
        if (pending.isNotEmpty()) return
        if (!paused && gap > lead + 2500) pending = "pause"
        else if (paused && gap < lead + 500) pending = "play"
    }

    /**
     * Checks the hidden copy's newest picture, unless a check is already going on. [testing] counts
     * faces as something to hide, which is how the blur is tried out without anything explicit.
     */
    fun sample(detector: NudityDetector, worker: ExecutorService, testing: Boolean) {
        if (sampling || !running || worker.isShutdown) return
        val now = SystemClock.elapsedRealtime()
        if (!usable(now) || !steady || now - lastSeekAt < 900) return
        var arrived = 0L
        val frame: Bitmap = synchronized(pictureLock) {
            val image = picture ?: return
            arrived = pictureAt
            try { toBitmap(image) } catch (e: Exception) { return }
        }
        // Pictures stop arriving while the video is paused, and the last one still shows what is there.
        // A playing video with no new pictures means the hidden screen is not being drawn: say nothing.
        if (!paused && now - arrived > 3000) return
        if (arrived < lastSeekAt + 400) return // drawn before the last jump landed
        val at = if (paused) position else position + ((arrived - stateAt).coerceIn(-1000, 1000) * rate).toLong()
        if (at / 100 == lastSampled / 100) return
        lastSampled = at
        sampling = true
        val copyAt = position
        worker.execute {
            val began = SystemClock.elapsedRealtime()
            // Normally a small copy is enough. Faces are smaller than bodies, so the test uses the full picture.
            val shown = if (testing) frame else Bitmap.createScaledBitmap(frame, WIDTH / 2, HEIGHT / 2, true)
            val level = try { detector.maxLevel(shown, testing) } catch (e: Exception) { -1 }
            val took = SystemClock.elapsedRealtime() - began
            val brightness = brightnessOf(frame)
            if (testing && !savedTestPicture && brightness > 12) {
                // Kept once per test, in the app's private scratch space, so the test can show what the hidden copy saw.
                savedTestPicture = true
                try {
                    File(activity.cacheDir, "look-ahead-test.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 90, it) }
                } catch (e: Exception) { /* only a record for the test */ }
            }
            ui.post {
                sampling = false
                if (!running || level < 0) return@post
                typicalLookMs = (typicalLookMs * 3 + took) / 4
                ahead.maxGapMs = maxOf(3000L, (typicalLookMs * 2.5 * rate).toLong())
                ahead.add(at, level)
                light[at] = brightness
                while (light.size > 600) light.pollFirstEntry()
                if (testing || level > 0) {
                    Log.i(TAG, "look-ahead: checked ${at / 100 / 10.0}s (hidden copy at ${copyAt / 100 / 10.0}s), " +
                        "brightness $brightness, found level $level, took $took ms")
                }
            }
        }
    }

    /**
     * Compares what the viewer's copy shows at [positionMs] (its [brightness], 0 to 255) with what the
     * hidden copy showed there. Answers false when the viewer has a picture and the hidden copy had
     * only black, which means the hidden copy cannot be believed; null when there is nothing to compare.
     */
    fun agreesWith(positionMs: Long, brightness: Int): Boolean? {
        val before = light.floorEntry(positionMs)
        val after = light.ceilingEntry(positionMs)
        val nearest = listOfNotNull(before, after).minByOrNull { abs(it.key - positionMs) } ?: return null
        if (abs(nearest.key - positionMs) > 1500) return null
        if (brightness < 30) return null // a dark scene proves nothing either way
        return nearest.value >= 6
    }

    private fun toBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val rowDots = plane.rowStride / plane.pixelStride
        val whole = Bitmap.createBitmap(rowDots, image.height, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        whole.copyPixelsFromBuffer(plane.buffer)
        return if (rowDots == image.width) whole else Bitmap.createBitmap(whole, 0, 0, image.width, image.height)
    }

    /** What the hidden copy's page can ask. Called on that page's own thread. */
    private inner class Bridge {
        @JavascriptInterface fun scout(): Boolean = true
        @JavascriptInterface fun version(): Int = 1
        @JavascriptInterface fun config(): String = "{\"version\":1,\"language\":false,\"tags\":[]}"
        @JavascriptInterface fun fullPicture(): Boolean = false
        @JavascriptInterface fun beat(positionMs: Double, widthShare: Double) {}

        /** How long the video the viewer is watching is, so the page can pick the same one out. */
        @JavascriptInterface fun wanted(): Double = wantedLength.toDouble()

        @JavascriptInterface
        fun state(positionMs: Double, lengthMs: Double, isPaused: Boolean, isAdvert: Boolean) {
            position = positionMs.toLong()
            length = lengthMs.toLong()
            paused = isPaused
            advert = isAdvert
            stateAt = SystemClock.elapsedRealtime()
        }

        /** False while the video is jumping or waiting for data, when its picture and position may not match. */
        @JavascriptInterface fun steady(ok: Boolean) { steady = ok }

        @JavascriptInterface
        fun command(): String {
            val command = pending
            pending = ""
            return command
        }

        @JavascriptInterface
        fun note(text: String) {
            Log.i(TAG, "look-ahead: hidden copy says: $text")
        }
    }

    companion object {
        private const val TAG = "SafeWatch"
        private const val WIDTH = 640
        private const val HEIGHT = 360
        private const val FAST = 3.0
        private const val LENGTH_SLACK_MS = 2500L

        /** True where a second copy of any website can be kept silent from its first moment. */
        val canSilenceAnyPage: Boolean get() = WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO)

        /** Average brightness of a picture, 0 (black) to 255, from a scattering of its dots. */
        fun brightnessOf(frame: Bitmap): Int {
            var sum = 0
            var count = 0
            val stepX = maxOf(1, frame.width / 24)
            val stepY = maxOf(1, frame.height / 14)
            var y = stepY / 2
            while (y < frame.height) {
                var x = stepX / 2
                while (x < frame.width) {
                    val p = frame.getPixel(x, y)
                    sum += (((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)) / 3
                    count++
                    x += stepX
                }
                y += stepY
            }
            return if (count == 0) 0 else sum / count
        }
    }
}
