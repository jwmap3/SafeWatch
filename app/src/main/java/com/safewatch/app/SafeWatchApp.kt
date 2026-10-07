package com.safewatch.app

import android.app.Application
import android.net.http.HttpResponseCache
import com.safewatch.app.data.Prefs
import java.io.File

class SafeWatchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.applyTheme(this)
        // Keeps downloaded artwork on the phone so it is not fetched again on every opening.
        try {
            HttpResponseCache.install(File(cacheDir, "http"), 64L * 1024 * 1024)
        } catch (e: Exception) {
            // The app works without it, only slower.
        }
    }
}
