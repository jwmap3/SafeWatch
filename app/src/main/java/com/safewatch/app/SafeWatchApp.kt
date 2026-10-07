package com.safewatch.app

import android.app.Application
import com.safewatch.app.data.Prefs

class SafeWatchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.applyTheme(this)
    }
}
