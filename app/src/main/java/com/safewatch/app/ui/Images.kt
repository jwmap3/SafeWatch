package com.safewatch.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.net.URL
import java.util.concurrent.Executors

/** Loads poster images in the background and keeps recent ones in memory. */
object Images {
    private val cache = LruCache<String, Bitmap>(80)
    private val pool = Executors.newFixedThreadPool(3)
    private val ui = Handler(Looper.getMainLooper())

    fun load(url: String, into: ImageView) {
        into.tag = url
        val ready = cache.get(url)
        if (ready != null) {
            into.setImageBitmap(ready)
            return
        }
        into.setImageDrawable(null)
        pool.execute {
            val bitmap = try {
                URL(url).openStream().use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) {
                null
            } ?: return@execute
            cache.put(url, bitmap)
            // The view may have been reused for another poster while this one loaded.
            ui.post { if (into.tag == url) into.setImageBitmap(bitmap) }
        }
    }
}
