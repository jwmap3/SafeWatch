package com.safewatch.app

import android.app.Application
import android.net.http.HttpResponseCache
import com.safewatch.app.data.Prefs
import com.safewatch.app.detect.ModelSetup
import com.safewatch.core.Strictness
import java.io.File

class SafeWatchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.applyTheme(this)
        com.safewatch.app.browser.BrowserActivity.searchPrefix = Prefs.searchPrefix(this)
        // Keeps downloaded artwork on the phone so it is not fetched again on every opening.
        try {
            HttpResponseCache.install(File(cacheDir, "http"), 64L * 1024 * 1024)
        } catch (e: Exception) {
            // The app works without it, only slower.
        }
        if (Prefs.settings(this).nudity != Strictness.OFF) ModelSetup.ensure(this)
    }
}
