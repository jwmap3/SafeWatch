package com.safewatch.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Loads artwork in the background, shrinks it to the size needed, and keeps recent pictures in memory. */
object Images {
    private val cache = object : LruCache<String, Bitmap>(40 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(4)
    private val ui = Handler(Looper.getMainLooper())

    /**
     * [minWidth] is the smallest width in pixels the picture is allowed to be shrunk to. [clear] keeps
     * see-through parts see-through (for icons); photos do without, to save memory.
     */
    fun load(url: String, into: ImageView, minWidth: Int = 360, clear: Boolean = false, onLoaded: (() -> Unit)? = null) {
        val key = "$minWidth:$clear:$url"
        into.tag = key
        val ready = cache.get(key)
        if (ready != null) {
            into.setImageBitmap(ready)
            onLoaded?.invoke()
            return
        }
        into.setImageDrawable(null)
        pool.execute {
            val bitmap = try { fetch(url, minWidth, clear) } catch (e: Exception) { null } ?: return@execute
            cache.put(key, bitmap)
            // The view may have been reused for another picture while this one loaded.
            ui.post {
                if (into.tag == key) {
                    into.setImageBitmap(bitmap)
                    onLoaded?.invoke()
                }
            }
        }
    }

    private fun fetch(url: String, minWidth: Int, clear: Boolean): Bitmap? {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            val bytes = connection.inputStream.use { it.readBytes() }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= minWidth) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = if (clear) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } finally {
            connection.disconnect()
        }
    }
}
