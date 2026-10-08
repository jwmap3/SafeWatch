package com.safewatch.app

import android.app.Application
import android.net.http.HttpResponseCache
import com.safewatch.app.data.Prefs
import com.safewatch.app.detect.ModelSetup
import com.safewatch.core.Strictness
import java.io.File

class SafeWatchApp : Application() {
    companion object {
        const val CRASH_FILE = "last-crash.txt"

        /** Shows the error the app last closed on, once, with a way to copy it. */
        fun showLastCrash(activity: android.app.Activity) {
            val file = File(activity.filesDir, CRASH_FILE)
            if (!file.exists()) return
            val text = try { file.readText() } catch (e: Exception) { "" }
            file.delete()
            if (text.isEmpty()) return
            androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle("edenOS closed on an error")
                .setMessage("Copy this and send it on, so it can be fixed:\n\n" + text.take(3000))
                .setPositiveButton("Copy") { _, _ ->
                    activity.getSystemService(android.content.ClipboardManager::class.java)
                        ?.setPrimaryClip(android.content.ClipData.newPlainText("edenOS error", text))
                    com.safewatch.app.ui.Ui.toast(activity, "Copied")
                }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // If the app ever closes on an error, the error is kept, to show and copy the next time it opens.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                File(filesDir, CRASH_FILE).writeText("edenOS ${packageManager.getPackageInfo(packageName, 0).versionName} on Android " +
                    "${android.os.Build.VERSION.RELEASE} (${android.os.Build.MODEL}), thread ${thread.name}:\n" + android.util.Log.getStackTraceString(error))
            } catch (e: Throwable) { /* nothing more can be done */ }
            previous?.uncaughtException(thread, error)
        }
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
