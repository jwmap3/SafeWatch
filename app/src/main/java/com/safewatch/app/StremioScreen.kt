package com.safewatch.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import com.safewatch.app.browser.BrowserActivity
import com.safewatch.app.browser.WatchActivity
import com.safewatch.app.ui.Ui

/**
 * The Stremio tab: Stremio's own web app, as it is, filling the tab like any other screen in edenOS, with no
 * browser around it. It keeps its place when other tabs are opened. When something is played, it plays in
 * edenOS's own player, where the cursing is muted and nudity hidden like everywhere else in the app.
 */
@SuppressLint("SetJavaScriptEnabled")
class StremioScreen(private val activity: MainActivity) {
    val view: FrameLayout = FrameLayout(activity)
    private val web = WebView(activity)
    private val loading = ProgressBar(activity)
    private var loaded = false

    init {
        view.setBackgroundColor(android.graphics.Color.parseColor("#0C0B11"))
        web.setBackgroundColor(android.graphics.Color.parseColor("#0C0B11"))
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (isStremio(url)) return false
                // Anything outside Stremio opens in the edenOS browser, and Stremio stays where it was.
                if (request.isForMainFrame && (url.scheme == "http" || url.scheme == "https")) BrowserActivity.open(activity, url.toString())
                return true
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                // Stremio's player: played in edenOS's own player instead, so the filters work on it.
                if (url.contains("#/player")) {
                    if (view.canGoBack()) view.goBack()
                    WatchActivity.open(activity, url, "Stremio")
                }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                loading.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                loading.visibility = View.GONE
            }
        }
        view.addView(web, FrameLayout.LayoutParams(-1, -1))
        view.addView(loading, FrameLayout.LayoutParams(Ui.dp(activity, 36), Ui.dp(activity, 36), android.view.Gravity.CENTER))
    }

    /** Called when the tab comes into view: Stremio is loaded the first time, and kept as it was after that. */
    fun onShown() {
        if (!loaded) {
            loaded = true
            web.loadUrl(HOME)
        }
        web.onResume()
    }

    fun onHidden() {
        if (loaded) web.onPause()
    }

    /** Back within Stremio, if there is anywhere to go back to. */
    fun back(): Boolean {
        if (!web.canGoBack()) return false
        web.goBack()
        return true
    }

    fun destroy() = web.destroy()

    private fun isStremio(url: Uri): Boolean {
        val host = url.host.orEmpty()
        return url.scheme == "about" || url.scheme == "blob" || url.scheme == "data" || host == "stremio.com" || host.endsWith(".stremio.com") ||
            host.endsWith(".strem.io") || host == "strem.io"
    }

    companion object {
        const val HOME = "https://web.stremio.com/"
    }
}
